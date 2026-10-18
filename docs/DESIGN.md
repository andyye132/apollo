# Apollo design doc

First-pass plan as of 2026-10-17. These are starting points that will shift once we build, but the first steps in each section should be right.

`docs/DESIGN.md` is the source of truth and `docs/design-doc.html` is generated from it. Edit the Markdown, then run `python3 docs/build.py`.

## Design

### What we're building

Apollo is an Android app that takes health measurements with the phone's own sensors, lets people journal how they feel, and has a Claude agent give feedback grounded in the actual readings.

- **Must work:** FeverPhone core temperature and fingertip heart rate.
- **Comes along cheaply:** steps, floors climbed, stair steps.
- **Stretch:** lung function from audio. Vision: an eye-tracking prototype (blink rate, gaze shifts) works in the foreground on the Pixel 7, and a passive version is specified (see Vision: eye tracking, below). Facial heart rate remains deferred.
- **Constraint: offline first, local only.** Every measurement, the history, trends and journal work with no network. Heart rate, temperature, steps and floors are stored only in the on-device database. There is no cloud database, account or sync. The one network call is the chatbot, which sends only what its tools return (see Data provenance and privacy, below).

### v1 scope

| Feature | Method | Paper | Ships in |
| --- | --- | --- | --- |
| Core temperature | Thermistor warming curve during 90 s of forehead contact | FeverPhone | v1 |
| Heart rate | Fingertip on rear camera with torch, 20 s | Yan 2017 | v1 |
| Steps | Hardware step counter | Platform API | v1 |
| Floors and stair steps | Barometer altitude change plus step detector | Liu 2014 | v1 |
| Journal | Free text, mood 1–5, tags | None | v1 |
| Agent feedback | Claude reads readings and journal through tools | RageSense (trigger design) | v1 |
| SpO2 | Red vs green PPG ratio, labelled experimental | Nemcova 2020 | v2 |
| HRV | Accelerometer on the belly, 30 s | EHB 2017 | v2 |
| Absolute elevation | Barometer plus a reference pressure | Liu 2014 | v2 |
| Lung function | Microphone during exhale or sustained "Aaah" | SpiroSmart, Saleheen | Stretch |
| Eye tracking | Front camera, MediaPipe face landmarks: blink rate and gaze-shift rate; passive via a camera foreground service | None | Stretch: foreground prototype works (Pixel 7), passive version specified |

### Phones

| Phone | Status | Use it for |
| --- | --- | --- |
| Pixel 4 (Android 13) | Have it. The no-root thermistor route is verified: 19 sensors at about 0.002 °C resolution | Main dev phone now |
| Pixel 7 (Android 15) | Have it. Mic measured linear on `VOICE_RECOGNITION`; front camera max 30 fps | Lung tests, heart rate, motion. Still run `tools/probe_phone.sh` and the forehead test |
| Pixel 6 | Don't have one. It's the phone FeverPhone was validated on | Buy 1–2 if FeverPhone accuracy matters more than demo speed |
| Any teammate's Android | Varies | UI, journal, agent, steps, heart rate |

Set `minSdk = 33` so the Pixel 4 works, and `targetSdk = compileSdk = 36`. Every Pixel since the Pixel 3 has a barometer. The FeverPhone code is per-phone-model because thermistor names and positions differ, and so does calibration.

### Tech stack

| Layer | Choice | Why |
| --- | --- | --- |
| Language | Kotlin, JDK 17 toolchain | Every sensor API we need is Android-native |
| UI | Jetpack Compose with our own theme over Material 3 | Spring animations and shared-element transitions give the smooth, iOS-like feel |
| State | ViewModel + `StateFlow`, one-way data flow | Standard; previews take plain state |
| Navigation | Navigation Compose | Standard |
| Dependency injection | Hilt (KSP) | Google's default, and well documented |
| Storage | Room (KSP) for records; DataStore for settings; raw session files in `filesDir` | Local-only, queryable, testable |
| Background | Foreground services for passive tracking; WorkManager for deferred jobs | The only reliable way to sense in the background |
| Camera | CameraX with Camera2 interop | Torch, frame-rate range, AE/AWB lock |
| Motion | `SensorManager` | Step counter, step detector, pressure, accelerometer |
| Thermistors | `Debug.dumpService("thermalservice")` with DUMP granted over ADB | Verified on the Pixel 4 with no root |
| Audio (stretch) | `AudioRecord` with the `VOICE_RECOGNITION` source at 48 kHz, effects forced off | Neither Pixel supports `UNPROCESSED`; `VOICE_RECOGNITION` measured linear on both (`tools/mictest/`) |
| On-device ML | Plain Kotlin for linear models and exported random forests; LiteRT if a neural net arrives; MediaPipe Tasks for face landmarks | Small, inspectable models first |
| Charts | Vico (Compose) | Native Compose charts |
| Health platform | Health Connect, optional write-only | Interop with other apps, but other apps can read it, so it's opt-in |
| LLM | Claude Opus 5.5 (`claude-opus-5-5`) through a stateless proxy that holds the key | Best feedback quality; the key never ships in the APK |
| Networking | OkHttp + kotlinx.serialization | Small and well known |
| Analysis | Python (pandas, scikit-learn) in `analysis/` | Fit models on recorded CSVs, export parameters as JSON for the app |

**Decided: native Kotlin + Jetpack Compose** (re-checked 2026-10-17). Almost all of the app's hard parts are sensor code that only exists as native Android API:

- `Debug.dumpService` for the thermistors
- Camera2 interop for the torch and the AE/AWB lock
- `SensorManager` for the barometer and step sensors
- foreground service types

Flutter or React Native would need a hand-written native bridge for each of those, which is most of the app, and a second language on top. Compose also gives the stateless-screen-plus-preview workflow that lets UI work happen without a phone (see Prototype UI without a device, in Dev setup).

Hilt stays: each module declares its own `@Module`, so there is no central DI file for everyone to edit.

### Architecture

```text
┌──────────────────────────────── phone ─────────────────────────────────┐
│                                                                        │
│  Sensors ──▶ SensorSource (Live | Replay) ──▶ Measurement modules       │
│  thermistors, camera,                         feverphone, ppg,         │
│  barometer, steps, mic                        floors, steps            │
│                                                   │ Reading + quality  │
│                                                   ▼                    │
│  Compose UI ◀── ViewModels ◀── Repositories ◀── Room DB                 │
│                                    ▲            readings, sessions,    │
│                                    │            journal, feedback,     │
│                                    │            calibration            │
│                       tools run on the phone                           │
│                                    │                                   │
│  Red-flag rules (plain code) ──▶ Agent loop ──────────────────────────┼──▶ Proxy ──▶ Claude API
│                                                                        │    holds key,
└────────────────────────────────────────────────────────────────────────┘    stores nothing
```

Three rules hold this together:

1. **Every sensor sits behind a `SensorSource` interface with a live and a replay implementation.** Replay reads CSVs recorded on real phones, like the ones in `data/`. That lets us build UI on the emulator, write unit tests and show previews without a Pixel in hand.
2. **Every measurement produces a `Reading` with a quality score.** Below the threshold, the UI says "retake" instead of showing a number, and the agent never sees it as a valid value.
3. **The agent's tools execute on the phone.** The proxy only relays requests. Only the tool results the agent asks for leave the device.
4. **Features never import each other.** Each feature module depends only on `:core`. A feature that needs something from another feature gets it through an interface in `:core`. The Gradle module boundaries make the compiler enforce this, so parallel work (and each person's coding agent) can't quietly couple two features.

### Code layout and ownership

Use Gradle modules from day one, not one `app` module split by package. Four people working in parallel matters more than build times: a module boundary is a wall the compiler enforces, while a package boundary is only a convention.

```text
apollo/
├── app/                  :app              UI shell, theme, navigation, generic measurement-flow screen, DI wiring
├── core/                 :core             shared interfaces + models (below), pure-Kotlin signal helpers. No Android UI
├── storage/              :storage          Room database, DAOs, HealthRepository implementation, export
├── sensing/thermal/      :sensing:thermal  ThermistorReader, FeverPhone pipeline, PhoneThermalMonitor, replay source
├── sensing/ppg/          :sensing:ppg      camera capture, heart-rate pipeline, replay source
├── sensing/motion/       :sensing:motion   steps, floors, PassiveTrackingService, replay source
├── agent/                :agent            chatbot client, tools, red-flag rules, triggers
├── backend/              stateless Claude proxy (not part of the APK)
├── analysis/             Python scripts that fit models
├── data/                 recorded sessions (CSV), used for replay and tests
├── docs/                 this doc
├── probe-app/            existing FeverProbe logger (retire once :sensing:thermal works)
├── prototypes/eyetrack/  standalone eye-tracking prototype; becomes :sensing:vision
└── tools/                existing adb/test scripts
```

Each module uses the package `edu.uw.ubicomp.apollo.<module>`, for example `edu.uw.ubicomp.apollo.sensing.thermal`.

**Dependencies:**

- `:app` depends on everything.
- `:storage`, `:agent` and every `:sensing:*` module depend only on `:core`.
- `:agent` reads data through the `HealthRepository` interface in `:core`, never the Room classes.

| Role | Owns | Phone needed |
| --- | --- | --- |
| UI | `:app`, the root Gradle files, `gradle/libs.versions.toml` | None: emulator + replay |
| Temperature | `:sensing:thermal` | Pixel 4 |
| Heart rate + motion | `:sensing:ppg`, `:sensing:motion` | Any phone with a torch and a barometer; the Pixel 7 is fine |
| Data + agent | `:core` (as merge reviewer), `:storage`, `:agent`, `backend/` | None |

The data + agent owner writes `:core` in the first hour, before anyone branches off (see Phase 0 in the Roadmap).

**Files everyone is tempted to touch, and the rule for each:**

| File | Rule |
| --- | --- |
| `core/**` | Changes go through a PR that every owner whose module uses the changed type approves. Add, don't rename. |
| `gradle/libs.versions.toml`, `settings.gradle.kts` | Owned by UI. Others may add a line in their own PR, but say so in the team channel |
| `AndroidManifest.xml` | Each module declares its own permissions and services in its own manifest, and Gradle merges them. Nobody edits another module's manifest |
| Room `AppDatabase` | Owned by data + agent, in `:storage`. Features don't add tables: they return a `MeasurementResult`, and raw samples go in files |
| Navigation graph | Owned by UI. The Measure screen lists every `Measurement` it gets through Hilt (`@IntoSet`), so adding a measurement needs no navigation change |
| `CLAUDE.md` at the root | States these rules, so every coding agent reads them. Each module gets a short `CLAUDE.md` with its owner and scope |

### Data model

Room tables. Every row carries the phone model, because calibration is per model.

| Table | Key fields | Notes |
| --- | --- | --- |
| `reading` | metric, value, unit, quality 0–1, method, startedAt, endedAt, sessionId, deviceModel | One number shown to the user, e.g. `core_temp_c`, `heart_rate_bpm`, `floors` |
| `session` | kind, startedAt, endedAt, protocolVersion, rawPath, referenceValue, referenceDevice, notes | Raw data lives in a file; `referenceValue` holds a thermometer or oximeter reading when the user has one |
| `journal_entry` | createdAt, text, mood (1–5), tags | Free text plus light structure |
| `feedback` | createdAt, trigger, cardJson, model, promptVersion | Every agent output, for review |
| `chat_message` | conversationId, createdAt, role, text, toolCallsJson | Chatbot history, kept on the phone and resent as context on each call |
| `calibration` | deviceModel, metric, powerMode, paramsJson, nSamples, fittedAt | Model coefficients per phone model and power mode (`unplugged` or `trickle`; see Measurement order and phone heat) |

The `referenceValue` field matters. If we ask "have a thermometer? enter its reading" after each measurement from day one, every use of the app produces calibration data.

### Shared interfaces (`:core`)

Everything in this section lives in `:core`, and these are the only types modules share. Write it first, merge it, then branch. Below is the agreed v1 contract.

- **A guided test** such as FeverPhone or fingertip heart rate implements `Measurement`. The app's generic flow screen runs it and saves its result, so feature modules write no navigation code and no database code.
- **A passive collector** such as steps or floors writes `Reading`s through `HealthRepository` on its own schedule instead.

```kotlin
package edu.uw.ubicomp.apollo.core

// ---- Sensors --------------------------------------------------------------------------------

/** A stream of raw samples from one sensor. Every source has a Live and a Replay implementation. */
interface SensorSource<T> {
    val id: String                                   // "thermistors", "camera_red", "pressure"
    suspend fun availability(): Availability
    fun samples(): Flow<T>                           // cold: collecting starts the sensor, cancelling stops it
}

sealed interface Availability {
    data object Ready : Availability
    data class NeedsPermission(val permissions: List<String>) : Availability
    data class NeedsSetup(val instructions: String) : Availability       // "adb shell pm grant ... DUMP"
    data class Unsupported(val reason: String) : Availability            // no barometer, no torch, not a Pixel
}

// ---- Guided measurements --------------------------------------------------------------------

interface Measurement {
    val id: String                                   // "feverphone", "ppg_finger"
    val title: String
    val estimatedSeconds: Int
    val heatsPhone: Boolean                          // true for ppg (torch + camera)
    val heatSensitive: Boolean                       // true for feverphone
    suspend fun availability(): Availability
    fun prepChecks(): List<PrepCheck>
    fun run(): Flow<MeasurementState>                // cancelling the collector aborts the test
}

data class PrepCheck(val id: String, val label: String, val evaluate: suspend () -> CheckResult)

sealed interface CheckResult {
    data object Pass : CheckResult
    data class Warn(val message: String) : CheckResult                   // user may continue
    data class Block(val message: String, val retryInSeconds: Int? = null) : CheckResult
}

sealed interface MeasurementState {
    data class Instruction(val text: String, val secondsLeft: Int?) : MeasurementState    // "Hold the corners", countdown
    data class Capturing(val progress: Float, val live: LiveSignal?) : MeasurementState
    data class Done(val result: MeasurementResult) : MeasurementState
    data class Failed(val reason: String, val retryable: Boolean) : MeasurementState
}

data class LiveSignal(val label: String, val value: Double?, val unit: String, val trace: FloatArray? = null)

data class MeasurementResult(
    val measurementId: String,
    val readings: List<Reading>,                     // includes low-quality ones; the UI and agent filter on quality
    val rawFile: File,                               // every sample, for replay and re-analysis
    val quality: Float,                              // 0..1
    val warnings: List<String>,                      // "moved during capture", "phone was still warming"
    val startedAt: Long,                             // epoch ms, UTC
    val endedAt: Long,
)

// ---- Data -----------------------------------------------------------------------------------

data class Reading(
    val metric: String,                              // one of Metrics.*
    val value: Double,
    val unit: String,
    val quality: Float,
    val method: String,                              // "feverphone.dump.v1", "ppg.finger.v1"
    val startedAt: Long,
    val endedAt: Long,
    val deviceModel: String,                         // Build.MODEL
    val id: Long = 0,                                // set by storage
)

object Metrics {
    const val CORE_TEMP_C = "core_temp_c"
    const val HEART_RATE_BPM = "heart_rate_bpm"
    const val STEPS = "steps"
    const val FLOORS = "floors"
}

/** The only way into stored data, for the UI, the agent and passive collectors. Implemented in :storage. */
interface HealthRepository {
    suspend fun saveResult(result: MeasurementResult): Long             // returns the session id
    suspend fun saveReading(reading: Reading)
    fun readings(metric: String, from: Long, to: Long): Flow<List<Reading>>
    suspend fun latest(metric: String): Reading?
    suspend fun baseline(metric: String): Baseline?                     // personal median + spread, last 14 days
    suspend fun lastRun(measurementId: String): Long?                   // epoch ms of the last finished session
    suspend fun saveJournal(entry: JournalEntry): Long
    fun journal(from: Long, to: Long): Flow<List<JournalEntry>>
}

data class Baseline(val median: Double, val spread: Double, val n: Int)
data class JournalEntry(val createdAt: Long, val text: String, val mood: Int?, val tags: List<String>, val id: Long = 0)

// ---- Phone heat (see "Measurement order and phone heat") ------------------------------------

/** Published by :sensing:thermal; read by the app and by FeverPhone's prep checks. */
interface PhoneThermalMonitor {
    val state: StateFlow<PhoneThermalState>
}

data class PhoneThermalState(
    val verdict: ThermalVerdict,
    val phoneTempC: Double?,                         // reference thermistor (quiet-therm on the Pixel 4)
    val driftCPerMin: Double?,                       // slope over the last 120 s
    val minutesSinceHeatingMeasurement: Double?,     // since the last heatsPhone measurement ended
    val powerMode: PowerMode,
    val minutesSincePowerChange: Double?,            // since the cable was last plugged in or pulled out
    val estimatedSecondsUntilReady: Int?,
)

enum class ThermalVerdict { READY, SETTLING, TOO_WARM, UNSUPPORTED }

/** Read from BatteryManager. Each mode FeverPhone supports has its own calibration. */
enum class PowerMode { UNPLUGGED, TRICKLE /* plugged in at 100% */, CHARGING /* plugged in below 100%: not supported */ }
```

**What other modules provide**, all through Hilt:

- Each `:sensing:*` module binds its measurements with `@Binds @IntoSet Measurement`.
- `:storage` binds `HealthRepository`.
- `:sensing:thermal` binds `PhoneThermalMonitor`. On phones without thermistor access it binds a stub that always says `UNSUPPORTED`.
- Each module also binds its replay sources behind a developer-settings switch.

**Conventions:**

- **Time:** times are epoch milliseconds (UTC) at the edges and sensor nanoseconds inside pipelines.
- **Units:** units are SI-ish and named in the field (`celsius`, `bpm`, `hPa`).

### Measurement order and phone heat

The heart-rate test runs the torch and camera for about 20 s, which warms the phone. FeverPhone measures how fast the phone warms against the forehead, so a phone that is already warm, or still cooling, gives a bad temperature. Two mechanisms handle this.

**1. Ask before a heating measurement.**

When the user starts a measurement with `heatsPhone = true` (heart rate), the app first checks for any available `heatSensitive` measurement (temperature) that hasn't run in this check-up. If there is one, it asks:

> "Taking your temperature too? Do it first. The camera light warms the phone and would throw off the reading."

The options are **Temperature first** and **Just heart rate**. The rule is generic, driven by the two flags, so there is no FeverPhone-specific code in the heart-rate module.

Where this lives in the UI (a dialog, a combined "check-up" flow, or ordering on the Measure screen) is **to be decided**. The rule and the flags are fixed now.

**2. Track the phone's own temperature continuously.**

`PhoneThermalMonitor` reads the thermistors once a second while the app is in the foreground. It is not a background service. Starting at app launch means there is already a few minutes of history by the time the user opens the temperature test. The monitor also sees the torch heat from a heart-rate test directly, so the heart-rate module doesn't need to report anything.

| Verdict | Rule (starting values, tune on Pixel 4 recordings) | FeverPhone prep check |
| --- | --- | --- |
| `READY` | \|drift\| ≤ 0.1 °C/min over the last 120 s, ≥ 10 min since a `heatsPhone` measurement, and phone temp < 32 °C | Pass |
| `SETTLING` | Drift above the limit, or a heating measurement too recent | Block, with "about N min" from the drift rate or the timer |
| `TOO_WARM` | Phone temp ≥ 32 °C. Forehead skin is about 34–35 °C, which leaves too little gradient | Block, "let the phone cool" |
| `UNSUPPORTED` | No DUMP grant, or not a calibrated phone model | FeverPhone hidden or marked unavailable |

Our first forehead run shows why this matters: after unplugging, the Pixel 4 was still cooling at about −0.5 °C/min two to three minutes later.

The monitor's state at the start of each capture is saved with the session, in the raw file header. The FeverPhone quality score also uses it, so a reading taken while settling is marked low quality, not hidden.

**Charging: two supported power modes, each with its own calibration.**

FeverPhone measures how fast the phone warms, so the phone's own heat has to be steady. A change in power state (plugging in or unplugging) breaks that for several minutes. Being plugged in or not is fine either way, as long as it's steady. Because the phone's internal heat differs between the two states, each one needs its own calibration.

| Power mode | Detected when | Notes |
| --- | --- | --- |
| `UNPLUGGED` | Not plugged in | How real users will measure. The phone cools toward room temperature after unplugging; the monitor waits for that to settle. This is the default |
| `TRICKLE` | Plugged in at 100% | The paper's protocol. Steady charger heat, but there's a cable at the forehead |
| `CHARGING` | Plugged in below 100% | Not supported: active charging heats the phone a lot, and unevenly. The prep check blocks with "unplug, or wait until full" |

- **The user picks the mode by plugging in or not,** so the app needs no setting. FeverPhone uses the calibration that matches the detected mode.
- **Fallback:** if there's no calibration for that mode on this phone model yet, the app says so and points to the other mode.
- **Settling:** a power change within the last 10 minutes counts as `SETTLING` (starting value, tune it).
- **Provenance:** the power mode is saved with every session and reading `method`, for example `feverphone.dump.unplugged.v1`.

**To measure on the Pixel 4:** after unplugging, log the thermistors with no test until the drift flattens. That gives the real settling time. Then collect calibration runs in both modes. If one mode settles far faster, or calibrates much better, make it the one the UI recommends.

### The agent

The same tool loop serves two surfaces:

- **Feedback cards,** triggered as below.
- **A chatbot** the user can ask questions such as "how has my resting heart rate looked this week?".

Where the chatbot sits in the UI (its own tab, a sheet from Today, or both) is to be decided. It needs a network connection. Offline, it says so, and every other screen keeps working.

- **Model:** Claude Opus 5.5 with adaptive thinking. Revisit Sonnet or Haiku for cost after comparing outputs on real entries.
- **Tools, all executed on the phone:**
  - `get_readings(metric, from, to)`
  - `get_journal(from, to)`
  - `get_baseline(metric)`
  - `submit_feedback(card)`
- **Output:** the agent finishes by calling `submit_feedback` with a strict schema. Fields:
  - summary
  - readings cited (by id)
  - change from baseline
  - one suggestion
  - confidence
  - escalate flag
- **When it runs:**
  - after each journal entry
  - when a reading moves away from the person's own baseline (borrowed from RageSense, below)
  - with a cooldown of at most one unprompted card per 6 hours
- **Failure:** the call is non-blocking. If it fails, show the readings with a fixed default message.
- **Red-flag rules run in plain code before the agent and override it.** Example rules to review with a clinician before shipping:

| Rule | Trigger | App shows |
| --- | --- | --- |
| Fever | Core temperature estimate at or above 38.0 °C, with quality OK | Fever notice and "confirm with a thermometer" |
| High fever | 39.4 °C or above | Contact a clinician |
| Resting heart rate | Above 120 or below 40 bpm at rest | Re-measure; contact a clinician if it repeats |
| Journal keywords | Chest pain, can't breathe, self-harm | Emergency resources (911, 988 in the US), whatever the agent says |

The agent cites readings and never diagnoses. Every FeverPhone value is labelled as a research estimate until gate 2 passes (see Roadmap).

### Data provenance and privacy

**Decision: everything is stored on the phone, and we build no cloud database.** The app is offline first. Only the chatbot needs a network connection.

| Data | Stored in | Leaves the phone? |
| --- | --- | --- |
| Heart rate, temperature, steps, floors (`reading`) | Room, on the phone | Only as chatbot or agent tool results, and only the rows a tool call asks for |
| Raw sessions (every sample) | CSV files in `filesDir` | Only if the user exports them |
| Journal entries | Room | Only as tool results |
| Chat history (`chat_message`) | Room | Resent to the model as conversation context on each chat call |
| Calibration and settings | Room, DataStore | Never |

What is out of scope:

- no cloud database
- no accounts or login
- no sync between phones
- no analytics or crash-reporting SDKs
- no server-side copy of anything

The proxy (`backend/`) is a relay that holds the API key. It stores and logs no request bodies.

**Provenance:** every reading records its `method` (algorithm and version), `deviceModel`, `quality` and `sessionId`. The session records the raw file and any reference value. So any number shown to the user, or cited by the agent, traces back to the raw samples and the code version that produced it.

**What works offline:** all measurements, history, trends, the journal and the red-flag rules. **What needs the network:** the chatbot and the feedback cards. Their failure path is the fixed fallback message.

- **On-device storage:** one Room database in app-private storage, encrypted at rest by Android.
- **Turn off backup:** set `android:allowBackup="false"` and exclusion rules. Otherwise Android uploads app data to the user's Google Drive by default.
- **Health Connect is opt-in.** It is on-device too, but other apps can read it once they have permission.
- **Export:** a ZIP of CSVs through the share sheet. There's no server copy, so a lost phone loses the data.
- **The agent call is the only thing that leaves the phone.** The proxy stores nothing and logs no bodies.
- **Volunteers need IRB approval.** Before we collect data from anyone outside the team, check with the lab. FeverPhone used UW IRB STUDY00008912.

### UI and design system

| Screen | Contents |
| --- | --- |
| Today | Latest reading per metric with its quality chip, today's agent card, steps and floors |
| Measure | One card per measurement: what it needs, how long it takes, last result |
| Measurement flow | Checklist → countdown → live capture with progress → result → optional reference reading → saved |
| Journal | Entries list and a quick-entry composer (text, mood, tags) |
| Trends | One chart per metric against the personal baseline band |
| Settings | Permissions, export, delete all data, developer tools (replay sources, raw logs) |

Use bottom navigation for Today, Measure, Journal and Trends, with Settings in the top bar. To get the iOS feel:

- Use `spring()` on every transition, shared-element transitions from card to detail, and a haptic tick on start, finish and save.
- Use our own type scale with large numerals for readings, and collapsing large titles. Support dark mode from day one.
- Show quality as a chip: "Good signal", or "Weak signal, retake". Never show a low-quality number big.
- Judge smoothness only on release builds with R8 and a Baseline Profile, because debug Compose builds stutter.

### Vision: eye tracking

**Status (2026-10-17):**

- **Foreground prototype working on the Pixel 7:** `prototypes/eyetrack/`, about 30 fps, measuring blink rate and gaze-shift rate.
- **Passive (background) version:** specified in `prototypes/eyetrack/README.md`, not built yet.
- **Pixel 4:** not tested yet.

The README is the implementation guide. Read it before writing vision code.

**What it measures:**

- **Gaze:** the iris position between the eye corners (MediaPipe Face Landmarker, 478 points with irises). It's relative and uncalibrated.
- **Blinks:** MediaPipe's eye-blink blendshapes.
- **Gaze shifts:** the gaze moving more than 0.1 eye widths within 100 ms.

**What it can't do:**

- **Saccade speed:** the front camera is 30 fps at most, and a saccade lasts 20–200 ms.
- **A screen gaze point:** there's no calibration.
- **Overwhelm detection:** nothing in `Papers/` validates an eye-movement measure of overwhelm, so present the numbers as exploratory attention signals against the person's own baseline, not a diagnosis.

**Passive mode rules** (details in the README):

- **Service:** it's a camera foreground service started from a visible screen, with a persistent notification and Android's green camera dot. No reboot or background start is possible.
- **Battery:** it runs at 15 fps and pauses with the screen off.
- **Camera conflicts:** it yields to other camera apps and resumes afterwards.
- **Storage:** it writes per-minute summary readings. No images are ever stored or sent.
- **Heat:** it warms the phone, so it counts as `heatsPhone` and pauses around FeverPhone readings. This matters most on the Pixel 4, which is also the temperature phone.

**Moving into the app:** `EyeMetrics` has no Android types, so it moves into `:sensing:vision` as is. The service becomes the module's passive collector, writing `Reading`s through `HealthRepository`:

- `blink_rate_per_min`
- `gaze_shift_rate_per_min`
- face coverage, used as the quality score

**Known issues:** looking down reads as half a blink, and the thresholds are untuned. The README has the fixes and a cued-recording test plan.

### Conventions

- **Pure Kotlin signal code.** Every algorithm is a plain function from samples to features to value, with no Android types, so it unit-tests on the JVM against CSVs in `data/`.
- **Tag methods.** Each algorithm change bumps `method` (`ppg.finger.v2`), so old and new readings stay distinguishable.
- **Record before you tune.** Capture raw sessions first, then iterate on the pipeline offline in `analysis/` or in JVM tests.
- **Git:** one branch per feature, and pull requests reviewed by one teammate.

## Papers

Eleven papers, in `Papers/`. None publishes code or data, so every feature is a reimplementation. FeverPhone and SpiroSmart come from our own lab (UW Ubicomp), so ask those authors first.

| Paper | Tier | Measures | Our plan |
| --- | --- | --- | --- |
| FeverPhone | Core v1 | Core temperature | Reimplement on the no-root thermistor route; rooted Pixel 6 for full fidelity |
| Yan 2017 | Core v1 | Heart rate | Write our own fingertip PPG; use the paper as the accuracy bar |
| Liu 2014 | Core v1 | Elevation, floors | Relative altitude + Kalman filter for floors; absolute elevation later |
| RageSense | Design input | Emotion while gaming, plus LLM intervention | Copy its trigger and fallback design for our agent |
| Hafez 2020 | Design input | Barometer side channel | Filter tap, speaker and door transients before floor detection |
| Nemcova 2020 | Experimental | HR, SpO2, BP | Get the full text; SpO2 labelled experimental; never ship BP |
| EHB 2017 | Experimental | HRV from accelerometer | Optional 30 s HRV demo |
| SpiroSmart | Stretch | FEV1, FVC, PEF | Ask the lab for code and data first |
| Saleheen 2020 | Stretch | FEV1/FVC from a vowel | Trend screen only, if at all |
| SpiroSonic | Stretch | FEV1, FVC via ultrasound | Skip for now; check the speaker can play 17–22.5 kHz |
| Magical Touch | Reference | Raw capacitive images | Don't implement; shows why raw touch needs vendor access |

### FeverPhone

- **Paper:** Breda, Springston, Mariakakis, Patel. "FeverPhone: Accessible Core-Body Temperature Sensing for Fever Monitoring Using Commodity Smartphones." IMWUT 7(1), 2023. doi 10.1145/3580850
- **Tier:** Core v1
- **Measures:** Core body temperature (°C), regressed against an oral thermometer. Fever means 37.5 °C or above.
- **Sensors:** All internal thermistors (root in the paper), plus raw touchscreen capacitance every 5 s (userdebug kernel)
- **Validated on:** Pixel 6 (lab and clinic). Lab-only checks on a second Pixel 6, a Pixel 3 and a Huawei P20
- **Headline result:** MAE 0.229 °C, sensitivity 0.813, specificity 0.904, N = 37 ED patients (16 febrile)
- **Code / data:** None released. Same lab, so ask.
- **PDF:** `Papers/FEVERPHONE.pdf`

#### How it works

Press the screen to the forehead for 90 s. Heat flows from skin into the phone, and the thermistors warm at a rate that depends on how hot the skin is and how well the two are coupled. The paper fits a line, T(t) = m·t + T0, as a short-window approximation of Newton's law of cooling. It builds four features:

| Feature | Definition |
| --- | --- |
| ROT, rate of temperature change | Slope m of the linear fit over the first 90 s of contact. The main signal |
| IT, initial temperature | T0, the thermistor temperature before contact |
| CA, contact area | Average the capacitance frames, keep cells above 25% of the averaged maximum, count them |
| CL, contact location | Vertical position of that region's weighted centroid. Sets the distance from skin to thermistor |

A random forest maps the four features straight to oral temperature. It never computes skin temperature or the heat-transfer constant explicitly, because CA and CL stand in for it.

- **Model type** (paper, 90 s, leave-one-participant-out): random forest MAE 0.229 °C; linear 0.320; quadratic 0.367; always-the-median dummy 0.452.
- **Window length:** 30 s 0.417 → 60 s 0.324 → 90 s 0.229 → 120 s 0.240. Longer windows break the linear approximation.
- **Ablation:** all four features 0.229; without IT 0.251; ROT + IT 0.311; ROT alone 0.309. The touch features carry most of the gain.
- **Lab rig:** a sous-vide heater in a bag of water against the screen, with water at 36.4–39.2 °C in about 0.25 °C steps and the phone starting at 21.1–28.3 °C, 41 samples.
  - ROT alone gave MAE 0.786 °C.
  - A linear model on all four features gave 0.495 °C.
- **Anomaly variant** for when there's no fever data:
  - Train IT, CA and CL → ROT on afebrile people only.
  - Flag a fever when the measured ROT departs from the prediction (threshold −0.001).
  - Sensitivity 1.00, specificity 0.713.
- **Cross-device:** a Pixel 6 model on an uncalibrated Pixel 3 gave 8.76 °C MAE. Adding 6–10 samples from that phone and retraining brought it to 0.825 °C. Calibrate per phone model.

#### Our measurements so far

On the Pixel 4, with the DUMP permission granted over ADB and no root, our probe app (`probe-app/`) read 19 sensors once a second.

| Signal | Change over 90 s on the forehead | Verdict |
| --- | --- | --- |
| pa-therm | +1.72 °C | Strongest, but sits by the radio amplifier, so keep airplane mode on |
| quiet-therm | +1.40 °C (+1.09 °C/min vs −0.54 in the hand) | Use |
| sdm-therm-monitor | +1.35 °C | Use; it also drives the public thermal headroom |
| Thermal headroom ×30 (public API) | +0.69 °C, lags | Too smoothed for a slope |
| Battery temperature (public API) | +0.1 °C, in 0.1 °C steps | Useless here |
| Touch events (`MotionEvent`) | 36 events in the first 2.6 s, then nothing | Doesn't see a forehead, so CA and CL need another source |

An exponential fit to pa-therm first gave k ≈ 0.21 /min and an equilibrium near 34.6 °C, but the fit is unstable over 90 s. On the same run, the equilibrium came out 34.5 °C when the fit started 10 s after contact, 46.6 °C from 5 s, and 94 °C from 0 s. So k has to be calibrated, not fitted (next section).

#### k: what it is, how to calibrate it, how to verify it

**What k is.** Newton's law of heating gives dT/dt = k·(T_skin − T). At contact, a thermistor warms at slope = k·(T_skin − T0), where T0 is its temperature just before contact. k (per minute) lumps together:

- contact conductance and contact area (how firmly the screen is pressed)
- the thermistor's distance from the glass
- the phone's heat capacity

So k differs per sensor, per phone model and per protocol. The paper never computes it: the random forest learns the same thing from CA and CL.

**Why one curve can't give k.** With τ = 1/k around 4–5 min, 90 s of contact is nearly a straight line. The curvature that would reveal k is about 15% of the rise and is swamped by sensor lag and drift.

**Calibrate instead.** This is now in the prototype: forehead report → "Calibrate", plus `tools/fever_calibrate.py`.

1. Press the screen against something of known temperature. The best is the sous-vide water bag. On a person, use a forehead (skin) IR thermometer reading, not an oral one.
2. For each sensor, k_i = slope_i / (T_ref − T0_i). Store the median over at least 3 runs, per phone model, power mode and protocol.
3. Every later run then gives T_skin,i = T0_i + slope_i / k_i. Report the median across sensors; their spread is a quality flag.
4. The slope skips the first 10 s, because the thermistors lag the glass.

**Where the error comes from.** If k is off by a fraction δ, the estimate is off by about δ·(T_skin − T0). The phone is typically 6 °C below skin, so 10% → 0.6 °C. On synthetic runs (`fever_calibrate.py --self-test`), leave-one-out error is:

| Contact between runs | Leave-one-out error |
| --- | --- |
| identical | 0.01 °C (the method itself is exact) |
| ±3% firmness | 0.13 °C |
| ±10% firmness | 0.39 °C |

The spread across sensors does not catch this, because firmness changes every sensor's k together. The paper fixes it with contact area from raw capacitance, which stock Android doesn't expose: the Pixel 4 drops the forehead touch about 2.5 s after contact.

**Our fix: a table protocol.** The phone lies screen-up on a table and the forehead rests on it, elbows down:

- Head weight gives the same pressure every run.
- The hand's heat is out of the picture.
- The pre-hold drift is the phone untouched.

The app offers both protocols and calibrates each separately.

**Real data says drift control comes first.** On our two Pixel 4 recordings, pre-contact drift was +0.52 and −0.54 °C/min, which is 25–100% of the contact slope. Taken at face value, the k values they imply differ 3×. Neither run would pass the readiness gate (|drift| ≤ 0.1 °C/min).

**Calibrating on skin (prototype: "Calibrate temperature" on the home screen).**

1. With the phone on the table until it reads READY, measure a patch of skin with a thermometer (inner forearm, or the forehead).
2. Tap Start: 10 s to pick the phone up, then exactly 90 s with the screen pressed on that patch, with spoken cues.
3. Type the reading.

Design choices:

- **Drift baseline:** the screen records from the moment it opens, and drift is fitted over the 40 s before contact. A 10 s window is too short: on our Pixel 4 recordings, 10 s drift estimates scatter by 0.2–0.5 °C/min, as large as the signal.
- **Fixed 90 s contact:** a linear slope over a longer contact is lower, so k only holds for the window length it was calibrated on.
- **Built-in check:** each new run is first predicted from the k saved so far, an honest out-of-sample test, and only then added.
- **Storage:** k is stored per power mode and site (arm, or each forehead protocol). Forehead tests fall back to the arm's k with an "offset possible" warning.
- **Offline analysis:** `tools/fever_calibrate.py data/apollo/results.jsonl` gives leave-one-out errors per site.

Use the thermometer's surface/object mode: the normal mode of a forehead IR thermometer shows an estimated body temperature (~37 °C), not skin (~33–35 °C).

#### First field results (Oct 17, 2026, Pixel 4, 3 people)

Three calibrations (AN, AY, MH; forehead, hand-held, body thermometer mapped to skin) and one forehead test.

**The forehead test read 94.9 °F against a 98.3 °F thermometer.** Two procedure bugs caused it:

1. The drift baseline included the 10 s pickup, where the hand warms the phone by up to +2.6 °C/min. That over-subtracted, until some sensors' rates went negative.
2. The forehead test had a 60 s hand pre-hold whose heat was subtracted, while calibration kept the hand's heat in k.

The phone also came out of a fridge at 24.4 °C and wasn't settled. Its in-hand warming (+0.9 °C/min) was subtracted from a 1.6 °C/min contact rise, leaving the "0.7 °C/min" shown.

**Fixes:**
- The forehead test now follows the calibration procedure exactly: untouched baseline while the screen is open → Start → 10 s pickup → 90 s contact.
- Drift is fitted only on the untouched phone, so the hand's heat during contact stays in k.
- The estimate uses only the two front thermistors, `pa-therm` and `chg-therm`.
- All calibrations were recomputed (`RECALIBRATE=1 proto-app/check.sh data/apollo/results.jsonl`) and pushed to the phone.

**What the data says now:**

| Choice | Leave-one-person-out error (core) |
| --- | --- |
| `pa-therm` / `pa`+`chg`, drift fixed | 0.7–0.8 °C |
| all four thermistors | 1.2–1.9 °C |
| old drift window | 1.0–1.6 °C |

- **People differ:** k varies about 25–30% between people (AN 0.21, AY 0.13, MH 0.15 /min on `pa-therm`). That's the contact and pressure variation FeverPhone corrects with touchscreen contact area, which stock Android doesn't expose.
- **Not enough data for a fitted model:** three people can't support a regression beyond this. Per-person k only had one old-procedure test to check against (−0.85 °C), which is inconclusive.
- **Next data:** at least 3 runs per person with the fixed procedure (within-person repeatability), the table protocol vs hand-held (does it shrink the 25–30%?), and runs at normal room-settled start temperatures (never a fridge). Each run's in-app "check" line reports its out-of-sample error.

**Table protocol dropped (Oct 17).** One run had the forehead resting on the phone lying on a table. It rose only 0.44 °C/min, because no hand warmed the phone and the table drew heat from its back. The phone was also drifting +0.4 °C/min on its own, so the forehead's share was unmeasurable. The app is now hand-held only.

Other changes:
- The drift baseline now comes from the thermal monitor's 2-minute history, so a quick Start still has one.
- The report leads with the body temperature in °C and °F, or "--" with the reason: no baseline, the phone drifting by itself, or not calibrated.
- Plugged in at 100% shares the unplugged calibration.

#### Calibration session protocol (Oct 17, 2026)

**Why:** four hand-held runs suggest the warming has a part that doesn't depend on the forehead (the hand, plus the phone's own screen/voice/CPU heat): warming ≈ k·(skin − phone) + c. Fitting two of AN's runs gave k ≈ 0.10/min and c ≈ +0.8 °C/min, against c ≈ +0.2 for AY and MH. A single k absorbed c, which is why k "jumped" (0.21 → 0.32 for the same person). That's a hypothesis from four runs; the controls below test it.

**App support (installed):**
- **Readiness from live readings:** lying still 60 s (gyroscope median < 0.015 rad/s), front and reference thermistors flat (≤ 0.1 °C/min), ≤ 30 °C, and not charging. This replaces the fixed 10-minute timers.
- **Baseline:** the drift comes only from time the phone lay still before Start. Raw files log `_still` and `_gyro` rows.
- **Matched screen heat:** intro screens stay on at the test's 20% brightness, so the baseline sees the same screen heat as the test.
- **Run types** (Calibrate screen): Forehead (calibration), Hand only (control: same grip, held in the air, screen facing away), Phone only (control: untouched on the table). Controls are saved as `type: control` and never used for k.
- **Forehead report:** always gives a number once calibrated, labelled "Unreliable: …" when conditions weren't met.

**Procedure:**
- **Before each run:** unplugged, out of case, phone ≤ 30 °C, lying flat until "Ready", and a body-mode thermometer reading right before Start. Put your name on every run.
- **Per person:** forehead → hand only → forehead, with about 10 minutes of settling between runs. Add one phone-only run per session.

**Analysis:** `python3 tools/fever_models.py data/apollo/results.jsonl` compares, each run predicted from the others:
- "always the average"
- k
- k + c
- a FeverPhone-style formula (rate + start temperature, no k)
- rate alone

With healthy people all near 36.8 °C, "average" wins by default. Showing that a model tracks temperature needs runs spanning more than 1 °C, for example a warm water bag measured with a probe thermometer.

#### Skin to core

The phone measures skin temperature. A fever reading needs core temperature, and the mapping is weak at the forehead.

**Dataset:** the FDA "Facial and oral temperature data from a large set of human subject volunteers" (Wang et al., PhysioNet 2023, CC0, https://physionet.org/content/face-oral-temp-data/). It has:

- 1,020 subjects (111 with oral temperature at or above 37.5 °C)
- IR-camera temperatures of 26 facial regions, including forehead centre `T_FHCC`
- oral temperature in two modes
- room temperature and humidity

Our 5-fold cross-validated fits on it (oral monitor mode):

| Predictor | MAE | Fever AUC |
| --- | --- | --- |
| Always the median | 0.323 °C | – |
| Forehead centre | 0.315 °C | 0.83 |
| Forehead + room temperature | 0.312 °C | 0.83 |
| Inner eye corner + room temperature | 0.241 °C | 0.97 |

Forehead skin correlates only 0.51 with oral temperature (the inner eye corner: 0.78). Caveats:

- These are non-contact IR-camera readings, so contact skin temperature may behave differently.
- FeverPhone's 0.229 °C came from a population with 16 of 37 febrile, against a 0.452 °C median baseline.

No open dataset with contact skin plus core temperature (zero-heat-flux or GI pill) turned up.

**Model in the prototype (`SkinToCore.java`, fitted by `tools/skin_to_core.py`):**

- oral = 23.495 + 0.4214·skin − 0.0430·room, with room defaulting to the dataset median of 24 °C. A room reading 2 °C off moves the estimate by only 0.09 °C.
- **Accuracy:** mean error 0.31 °C (cross-validated; always guessing the median gives 0.32), fever AUC 0.83.
- **Fever flag:** estimates bunch toward 37 like any regression's. A flag at an estimate of 37.24 °C catches 70% of fevers with 10% false alarms; the plain 37.5 cutoff catches 33%.
- **Shrinks skin errors:** the 0.42 slope turns a 0.3 °C skin error into a 0.13 °C core error.
- **Rejected:** a heat-balance form (core = skin + K·(skin − room)) fitted K ≈ 0 and did worse (0.45 °C), and adding `T_offset` made every model worse.

Replace this model with one fitted on paired phone + oral data once we have it.

**Verification protocol.** Keep each step's raw files and list them in a `runs.csv` (`raw_file,ref_c,kind`) for `fever_calibrate.py`.

1. **Bench sweep:** water bag at 34, 35, 36, 37, 38 °C, 3 runs each, phone settled (READY) every time. Pass: leave-one-out MAE under 0.3 °C, and the error doesn't trend with temperature.
2. **Repeatability:** 5 runs at one temperature. Pass: k varies by less than 10% (CV) per sensor.
3. **Sensitivity:** the estimate rises monotonically from 34 to 38 °C, with slope 1 ± 0.15.
4. **Negative control:** hold the phone in the air for 90 s (`kind=nocontact`). The rate should be about 0, and the "temperature" should equal the phone's own.
5. **Protocol A/B:** repeat steps 1–2 for hand-held and table, and keep the one with the lower k CV.
6. **People:** a forehead IR reading right before each run is the skin reference. An oral reading after it feeds a later skin→core model, which FeverPhone built from 37 people. Compare against the IR reading; don't claim core temperature until that model exists.

#### Android implementation

1. **Prep checks:**
   - `PhoneThermalMonitor` says `READY`. It blocks while the phone is still settling, too warm, or a heart-rate test ran in the last 10 minutes (see Measurement order and phone heat, in Design).
   - Power mode is `UNPLUGGED` or `TRICKLE` (plugged in at 100%), with a calibration for that mode on this phone. `CHARGING` blocks the test (see Measurement order and phone heat, in Design).
   - Airplane mode is on (read `Settings.Global.AIRPLANE_MODE_ON` and ask the user to turn it on).
   - The app sets window brightness to 20%.
   - At least 10 minutes have passed since the last temperature reading.
   - Ask whether the room is roughly 18–24 °C.
2. **60 s hand pre-hold** with a countdown, holding the corners in "camera grip". This settles the phone and measures drift and IT.
3. **90 s forehead contact,** landscape, screen on (`FLAG_KEEP_SCREEN_ON`):
   - Read every thermistor at 1 Hz with `ThermistorReader` (see Android build).
   - Log touch events anyway.
   - Speak or vibrate the start and end cues.
4. **Features per thermistor:** contact slope (from contact + 10 s) minus pre-contact drift, IT just before contact, and the calibrated k and skin estimate T0 + slope/k (see "k" above).
5. **Contact area and location without raw touch,** in order of effort:
   - (a) An on-screen placement guide, so location is fixed by protocol.
   - (b) The pattern of response across thermistors as a location proxy.
   - (c) Root a Pixel 6 and read the touch driver's raw frames.
6. **Model:**
   - Start with linear regression on our own paired data.
   - Move to a random forest once there are at least 40 samples.
   - Train in `analysis/`, export to JSON, evaluate in Kotlin.
   - Keep one calibration row per phone model.
7. **Output:** the value, a quality score and a "research estimate" label.

#### First steps

- [ ] Log the Pixel 4 unplugged and untouched until the thermistor drift flattens, to measure the real settling time
- [ ] Run `tools/forehead_test_app.sh` three more times in each power mode (unplugged after settling, and plugged in at 100%), with an oral thermometer reading each time
- [ ] Run the same test on the Pixel 7
- [ ] Email the authors for code, data and their thermistor choice
- [ ] Build the water-bag rig (sous-vide stick, sealed bag, cardboard box with a forehead-sized hole), then run the verification protocol above
- [ ] A/B the hand-held and table protocols on k repeatability
- [ ] Port `ProbeActivity`'s reader into `sensing/thermal/ThermistorReader.kt` with a replay source over `data/forehead_app_*.csv`

#### Watch out for

- **Narrow validated range:** the paper covered only 18.3–23.6 °C ambient air, and excluded high fevers above 38.6 °C because of sweaty skin.
- **The random forest can't extrapolate.** It never outputs values beyond its training labels, which is another reason to add physics features.
- **Confounds:** unlocking the screen or plugging in the charger each add 0.5–1 °C. So does the hand.
- **Calibration data, not code, is the bottleneck.** Fever claims need an IRB study with febrile patients.

### Yan 2017: fingertip and facial PPG

- **Paper:** Yan, Chan, Li, To, Lai, Tse, Poh, Poh. "Resting and Postexercise Heart Rate Detection From Fingertip and Facial Photoplethysmography Using a Smartphone Camera: A Validation Study." JMIR mHealth uHealth 5(3), 2017. doi 10.2196/mhealth.7275
- **Tier:** Core v1
- **Measures:** Heart rate (bpm) from a 20 s recording
- **Sensors:** Rear camera with LED (fingertip); front camera (face, about 30 cm away)
- **Validated on:** iPhone 6S running Cardiio, a closed commercial app
- **Headline result:** Fingertip RMSE 1.03 bpm at rest, 2.15 after moderate and 2.01 after vigorous exercise; N = 40 against 12-lead ECG
- **Code / data:** None (commercial app; two authors co-founded Cardiio)
- **PDF:** `Papers/Heart Rate Detection From Fingertip and Facial Photoplethysmography.pdf`

#### How it works

Blood volume in the fingertip pulses with each heartbeat, which changes how much torch light the finger absorbs. The camera sees that as a small oscillation in the red channel. The paper doesn't describe its signal processing, because it's proprietary, so we use a standard pipeline. The paper is our accuracy bar and protocol template.

| Condition | Fingertip RMSE (bpm) | Face RMSE (bpm) |
| --- | --- | --- |
| Rest | 1.03 | 1.02 |
| After moderate exercise | 2.15 | 3.68 |
| After vigorous exercise | 2.01 | 3.84 |

Both pass ANSI/AAMI EC13 (RMSE ≤ 5 bpm or 10%). The face falls behind after exercise because of flushing and breathing motion.

#### Android implementation

1. **CameraX setup:**
   - `ImageAnalysis` with RGBA output.
   - Torch on, and a 30 fps target range through Camera2 interop.
   - Lock AE and AWB once a finger is detected (see Android build).
2. **Per frame:** take the mean red value over the centre region, and record the frame timestamp.
   - Reject frames that aren't red-saturated (no finger), or that are near-black (pressing too hard).
3. **Resample** to an even 30 Hz using the timestamps, then detrend.
4. **Filter:** band-pass 0.7–4 Hz (42–240 bpm), a zero-phase Butterworth.
5. **Heart rate:** over the 20 s window, take the FFT peak. Cross-check it against the median peak-to-peak interval.
6. **Quality score:** spectral peak prominence, agreement between the FFT and peak-interval estimates, and accelerometer motion. Below the threshold, show "retake".
7. **Protocol from the paper:** sit at rest for 5 minutes, take 3 readings, and average them.

#### First steps

- [ ] Prototype capture that writes `t_ns, red_mean` to CSV, and record 10 sessions alongside a chest strap or pulse oximeter
- [ ] Write the pipeline as a pure Kotlin function and test it on those CSVs
- [ ] Include darker skin tones. Yan's sample covered only von Luschan 14–27, light to medium

#### Watch out for

- **Finger pressure:** pressing hard blanches the finger and kills the signal.
- **The torch heats the finger and the phone.** Keep captures short.
- **Irregular frame timing:** always resample using frame timestamps, never by frame index.
- **The paper's sample was narrow:** young, healthy and seated, under standardised lighting (median 199 lux), with no arrhythmias.

### Liu 2014: elevation from barometers

- **Paper:** Liu, Hossain, Iwai, Ito, Tobe, Sezaki, Matekenya. "Beyond Horizontal Location Context: Measuring Elevation Using Smartphone's Barometer." UbiComp '14 Adjunct. doi 10.1145/2638728.2641670
- **Tier:** Core v1
- **Measures:** Absolute elevation (m) and relative height between floors
- **Sensors:** Barometer at 20 Hz, GPS outdoors, weather-station and crowd reference pressures
- **Validated on:** Galaxy S3, Galaxy S4, Nexus 4
- **Headline result:** Outdoors, 75% of errors under 3.21 m and 90% under 4.52 m. Indoors, 0.86 m mean floor-height error
- **Code / data:** None
- **PDF:** `Papers/ELEVATION_BAROMETER.pdf`

#### How it works

Air pressure drops about 0.12 hPa per metre near sea level, so one 3 m floor is roughly 0.36 hPa. The paper's pipeline:

1. Calibrate each phone's fixed bias at a known point.
2. Drop burst errors with a threshold filter, then low-pass.
3. Convert pressure to altitude with the standard-atmosphere formula relative to a reference pressure, from a weather station, a DEM point or another phone.
4. Bridge gaps between reference updates with a short (under 30 minutes) damped-trend forecast.
5. Run an adaptive Kalman filter with state [height, vertical speed].

The paper has no floor-counting or elevator logic; it only compares heights to floor maps.

#### Android implementation

1. Use `Sensor.TYPE_PRESSURE`, which needs no permission. Register at `SENSOR_DELAY_GAME` or faster and keep the event timestamps.
2. Get relative altitude from `SensorManager.getAltitude(PRESSURE_STANDARD_ATMOSPHERE, hPa)` minus a rolling baseline. Only the differences matter.
3. Apply a median filter, then a 1-D Kalman filter on [h, v].
4. **Floors** (our heuristic, to tune):
   - A sustained change of 2.5 m or more over under 60 s while the step detector fires is a floor climbed or descended.
   - The same change with few or no steps is an elevator.
5. **Stair steps:** count step-detector events only while the filtered altitude is changing.
6. Write `FloorsClimbedRecord` and `ElevationGainedRecord` to Health Connect if the user opts in.
7. **Absolute elevation (v2):** a reference sea-level pressure from a weather API, or from GPS altitude outdoors.

#### First steps

- [ ] Log raw pressure and step-detector events on a 10-floor stair walk and an elevator ride, on the Pixel 4 and Pixel 7
- [ ] Fit the Kalman noise settings offline on those logs, then port them

#### Watch out for

- **Weather drift:** pressure moves a few tenths of a hPa per hour, which is a few metres of fake altitude. Only short windows count.
- **Buildings:** HVAC, doors and wind cause pressure jumps (see Hafez 2020).
- **Unreliable units:** cheap barometers vary by unit, and the paper rejected one noisy phone outright.

### RageSense

- **Paper:** Liu, Zheng, Ren, Guo, Zhang, She, Bai, Jin, Gao. "RageSense: Leveraging Acoustic Sensing and LLM-Based Intervention for Emotion Regulation in Mobile Gaming." CHI '26. doi 10.1145/3772318.3791076
- **Tier:** Design input
- **Measures:** Continuous valence and arousal (−1 to 1) every 2 s, from 5 s windows
- **Sensors:** Speaker + 2 mics, FMCW chirps at 18–22 kHz (10 ms, 48 kHz) bouncing off the face; IMU to pause sensing while the player taps
- **Validated on:** Vivo X90, Redmi K60, OnePlus Ace3 (plus SNR checks on Huawei Mate 30, Samsung S9)
- **Headline result:** CCC 0.6115 valence, 0.6215 arousal, N = 20. A 3-week field study with N = 53 found LLM interventions beat random and canned ones
- **Code / data:** None; the LLM isn't named
- **PDF:** `Papers/RageSense.pdf`

#### How it works

The sensing uses a ResNet-18, then a sparse transformer, then an LSTM on echo spectrograms. It has about 25M parameters, runs with PyTorch Mobile, and isn't worth reproducing for a health app. The **intervention design** is the part we want:

1. Smooth valence and arousal with an EMA.
2. Set dynamic thresholds from the last 60 s: mean ± k·σ, seeded with values from labelled anger instances. k decays from 1.2 to 0.8 over the session.
3. Run CUSUM change-point detection on the smoothed signal.
4. Fire only if a change point AND both thresholds trip.
5. Send the cloud LLM 5 s of history plus 5 screenshots with names and chat pixelated.
6. The message must be under 25 characters, empathetic and specific to the moment.
7. If the call fails, show a default message. The game is never blocked.

**What users said:**

- **Formative study (N = 24):**
  - Failure modes: bad timing (17/24), context mismatch (14/24), patronising tone (10/24).
  - Expert players hated being treated as novices.
- **Field study:** personalised LLM messages scored 4.5/5 for experience, against 3.1 for canned messages at the same moments and 2.9 for random timing.

#### On a stock phone (prototype: Calm sensing)

`CalmSenseActivity` in `proto-app/` runs the sensing and trigger halves on the Pixel 4. It is untested on the device so far.

- **Sonar (`Sonar.java`):**
  - AudioTrack loops an 18–22 kHz chirp: 512 samples (10.7 ms) at 48 kHz, with raised-cosine edges.
  - AudioRecord reads the `VOICE_RECOGNITION` source. CDD 7.8.3 requires near-ultrasound response there when the phone reports it, and the Pixel 4 reports mic and speaker support.
  - A circular, analytic cross-correlation gives the echo profile. The direct path is found automatically.
  - Consecutive profiles are differenced (the paper's step) over 3.6–61 cm. A 1 mm moving echo gives 14× the energy of a still one in the known-answer test.
- **IMU gating:** sensing pauses when short-time motion energy is above mean + 0.25·SD, the paper's threshold.
- **Trigger (`JitaTrigger.java`):** follows the paper:
  - EMA, a 60 s dynamic threshold with k decaying 1.2→0.8, and CUSUM
  - plus a cooldown and a 30 s warm-up
  - Tested: silent on a steady baseline; fires once, within a second, on a sustained rise.
- **Substitutions:** the emotion network (25M parameters, 20 players) isn't public. So the trigger runs on a movement index (log echo-change energy), and messages come from a fixed library of short lines (the paper's fallback), not an LLM. This is agitation sensing, not emotion recognition.
- **To verify on the phone:**
  - The heat map shows a bright band at face distance when you nod.
  - The index rises when you fidget and settles when you're still.
  - Taps are gated out.
  - Check the speaker is inaudible to younger listeners: 18 kHz is audible to some.

#### Android implementation (for our agent)

- **Trigger on change:**
  - Run CUSUM on daily metrics (resting HR, temperature, sleep and mood if we add them) against a 14-day personal baseline.
  - Fire after a journal entry, or when a change point crosses k·σ.
- **Add what the paper lacks:**
  - a cooldown
  - structured output (`submit_feedback` with a strict schema)
  - versioned prompts stored with each card
  - our red-flag rules
- **Copy its data minimisation.** Compute everything on the phone, and send only what the agent's tools return.
- **Copy its tone lessons.** Keep it short and specific to this person's numbers, never generic advice, never patronising.

#### First steps

- [ ] Write 30 scripted scenarios (readings + journal) as an agent test set, including red-flag cases
- [ ] Implement the trigger as a pure function over a list of daily values and unit-test it

#### Watch out for

- **No safety layer:** RageSense had none, and health feedback needs one.
- **No lasting effect:** the effect didn't last after 3 weeks (r = −0.13 between session order and intervention count). Don't promise behaviour change.

### Hafez 2020: barometers can hear

- **Paper:** Hafez, Nahid, Khabbazian. "Barometers Can Hear, and Sense Finger Taps." arXiv:2008.03872, 2020
- **Tier:** Design input
- **Measures:** Whether a speaker is playing, finger taps, and tap position, from pressure transients inside water-sealed phones
- **Sensors:** Barometer; Android delivered 25 Hz on their Galaxy S10+ (ST LPS22HH)
- **Validated on:** Galaxy S10 Plus (IP68), Galaxy S6 Edge (not sealed)
- **Headline result:** Speaker detection 92–96%; tap detection 100%; tap position 41% on a 3×3 grid (chance 11%); the unsealed phone only 67%
- **Code / data:** None
- **PDF:** `Papers/BAROMETERS.pdf`

#### How it works

In a sealed phone, a tap or speaker output briefly squeezes the internal air, which shows up as a 0.1–0.4 hPa blip. The paper standardises each 2 s record, smooths with a Savitzky–Golay filter (order 2, frame 5), and classifies with an SVM.

#### Android implementation (for our floors pipeline)

- **Expect these artifacts.** Pixel 4 and Pixel 7 are both IP68, so taps, speaker output and door slams will appear in barometer data.
- **Filter them out.** Median-filter and require a sustained change (seconds, not samples) before counting a floor.
- **Privacy:** reading the barometer needs no permission. It's another reason to keep raw sensor logs on the phone.

#### First steps

- [ ] While logging pressure on a desk, tap the screen and play audio, then check the floor detector ignores both

#### Watch out for

- **One phone model, 3 participants.** Pixel barometers may behave differently.

### Nemcova 2020: HR, SpO2 and BP from a smartphone

- **Paper:** Nemcova, Jordanova, Varecka, Smisek, Marsanova, Smital, Vitek. "Monitoring of heart rate, blood oxygen saturation, and blood pressure using a smartphone." Biomedical Signal Processing and Control, 2020. doi 10.1016/j.bspc.2020.101928
- **Tier:** Experimental
- **Measures:** HR (bpm), SpO2 (%), systolic and diastolic BP (mmHg)
- **Sensors:** Rear camera + LED at 30 fps (finger); microphone for heart sounds, which give BP through pulse transit time
- **Validated on:** Honor 7 Lite, iPhone SE, Lenovo Vibe S1 for training; 13 phone models in total
- **Headline result:** Test MAE: HR 1.4 bpm, SpO2 1.1%, BP 5.1 / 7.5 mmHg. BP is from only 15 records
- **Code / data:** None stated
- **PDF:** `Papers/HRBLOOD.pdf` (paywalled preview only: no methods, formulas or tables)

#### How it works

From the preview:

- HR and SpO2 come from camera PPG, using the red and green channels as stand-ins for two wavelengths.
- BP uses the delay between the heart sound (mic) and the pulse arriving at the finger (camera).
- Their app gives live feedback on finger placement quality.

The SpO2 constants, filter settings and BP regression are all in the missing methods section.

#### Android implementation

1. Reuse the heart-rate capture exactly. SpO2 just needs the green channel recorded too.
2. **SpO2:** use the standard ratio of ratios, R = (AC_red/DC_red) / (AC_green/DC_green), and fit SpO2 = a − b·R against a clip-on pulse oximeter. This generic method is not from the paper.
3. Label it "experimental, not a medical value" in the UI.
4. **BP: don't ship.** 15 records and an unknown calibration aren't enough.

#### First steps

- [ ] Get the full text through UW Libraries or from the authors
- [ ] Record red and green means alongside a pulse oximeter in every heart-rate validation session, to get SpO2 data for free

#### Watch out for

- **Camera SpO2 is unreliable** on phones with auto-processing, and across skin tones.
- **Healthy volunteers rarely go below 95%,** so a fit on them proves little.

### EHB 2017: HRV from the accelerometer

- **Paper:** "Ultra-Short-Term Heart Rate Variability Analysis on Accelerometric Signals from Mobile Phone." IEEE EHB 2017 (blinded submission, no authors in our copy)
- **Tier:** Experimental
- **Measures:** Beat-to-beat (JJ) intervals, then SDNN and RMSSD (ms), from 30 s of data
- **Sensors:** Accelerometer at 100 Hz, phone lying on the belly near the navel, top toward the head
- **Validated on:** iPhone 6s
- **Headline result:** JJ vs ECG RR r² = 0.985 at rest, 0.984 under mental stress; limits of agreement ±32–34 ms; N = 6, lying supine
- **Code / data:** None
- **PDF:** `Papers/EHB2017_blind_USVsmartphone_DEF.docx`

#### How it works

The heartbeat's mechanical recoil (ballistocardiography) shows up as small accelerations. The pipeline:

1. **Band-pass, 4th-order Butterworth:** front-to-back axis 5–25 Hz, head-to-foot axis 1–25 Hz.
2. **Template:** a 400 ms window around the largest peak in the first 10 s.
3. Cross-correlate the template with the signal. Around each correlation peak, a search window sized from recent beat lengths picks the J peak.
4. Compute JJ intervals per axis. Keep the axis whose series deviates least from a 5th-order polynomial fit.
5. Compute SDNN and RMSSD over the central 30 s.

#### Android implementation

1. **Capture:** `TYPE_ACCELEROMETER` at `samplingPeriodUs = 10_000` or faster. Resample to 100 Hz using event timestamps.
2. **Axes:** with the phone screen-up on the belly, z is front-to-back and y is head-to-foot.
3. **Replace the paper's manual cleanup with automatic rejection:** drop JJ intervals more than 25% from the median, and keep only 0.3–2.0 s (our choice).
4. **Quality:** template correlation height and JJ regularity.

#### Watch out for

- **Manual correction:** the authors fixed missed and extra beats by hand before computing HRV, so the automatic accuracy is unknown.
- **Narrow setup:** 6 people, lying on a rigid surface, not talking. Treat it as a demo, not a feature.

### SpiroSmart

- **Paper:** Larson, Goel, Borriello, Heltshe, Rosenfeld, Patel. "SpiroSmart: Using a Microphone to Measure Lung Function on a Mobile Phone." UbiComp '12
- **Tier:** Stretch
- **Measures:** FVC, FEV1 (L), PEF (L/s), FEV1/FVC, and the flow-volume curve
- **Sensors:** Microphone at 32 kHz; the user blows hard at the phone held at arm's length, no mouthpiece needed
- **Validated on:** iPhone 4S
- **Headline result:** Mean error about 5.1% against a clinical spirometer (4.6% with personal data); N = 52
- **Code / data:** None released. Same lab (UW Ubicomp), so ask.
- **PDF:** `Papers/spirosmart.pdf`

#### How it works

The sound of a forced exhale encodes airflow. The pipeline:

1. **Undo sound propagation to the lips:** an inverse radiation model, with distance estimated from height.
2. **Convert pressure to flow** (flow ∝ √pressure).
3. **Extract flow proxies from three versions of the signal:**
   - the Hilbert envelope
   - spectrogram resonances (30 ms frames, 50% overlap)
   - LPC gain at orders 2–32
4. Smooth with a Savitzky–Golay filter (order 3, window 11).
5. **Regress** PEF from the maximum, FVC from the integral, and FEV1 from the first-second integral. The model is bagged decision trees (100 trees) in an ensemble across folds.
6. A segment runs from 1 s before to 6 s after the loudest point.

#### Android implementation

1. **Capture:** `AudioRecord` with `MediaRecorder.AudioSource.VOICE_RECOGNITION` at 48 kHz, mono, bottom mic.
   - Create `AutomaticGainControl`, `NoiseSuppressor` and `AcousticEchoCanceler` on the session if available, and call `setEnabled(false)` on each.
   - **Don't use `UNPROCESSED`:** neither Pixel supports it.
   - **Don't use `MIC`:** it has a hidden expander on the Pixel 4.
   - **Never use `VOICE_COMMUNICATION`:** it has a noise gate and adaptive gain.
   - See the mic linearity results below.
2. **Coaching UI:** full inhale, then blast it out. Show a live flow bar like their ball-in-cylinder.
3. **Features:** compute them on the phone.
4. **Model, hackathon version:** peak expiratory flow only, from the peak of the flow envelope, with a straight-line fit per phone against a peak-flow meter.
   - Peak-flow meters cost about $20 at a pharmacy, much cheaper than a spirometer.
   - FEV1 and FVC need a spirometer, so leave them out for now.
5. **Model, full version:** train in `analysis/` on paired data from a spirometer.

#### Mic linearity results (2026-10-17)

`tools/mictest/` plays a 1 kHz tone in 6 dB steps (−36 → −6 → −36 dBFS) while the phone records it. A linear source records 6 dB per step, the same up and down. The full table is in `tools/mictest/README.md`.

| Source | Pixel 4 | Pixel 7 |
| --- | --- | --- |
| `VOICE_RECOGNITION` | ✅ slope 0.992, max deviation 0.27 dB | ✅ slope 0.975, max deviation 0.71 dB |
| `MIC` | ❌ expander pushes quiet sounds down | ✅ slope 0.997 |
| `VOICE_COMMUNICATION` | ❌ noise gate below about −24 dBFS | ❌ adaptive gain |

Neither phone exposes automatic gain control as an effect, and Android's framework pre-processing (AEC, NS) applies only to `voice_communication`. The `MIC` expander on the Pixel 4 lives in the Qualcomm DSP and is invisible in every config file, so only a measurement shows it.

#### Peak-flow attempt (2026-10-17): negative result

Four people did 8 peak-flow-meter blows each (top-3 means 538, 460, 337, 283 L/min) and 3–4 blows at the Pixel 4. The phone was upright, about 1 m away, and the blow was aimed over its top edge. The tools are `tools/mictest/blow.sh` and `analyze_blow.py`; the recordings stay local in the gitignored `tools/mictest/out/`.

- **Wind was the first problem.** At the short wire distance, every blow clipped. The low-frequency energy below 300 Hz is wind buffeting the phone, not sound. Above about 3 kHz the wind has died out, so the 8–20 kHz airflow hiss is unaffected by it.
- **The hiss feature doesn't separate people.** P1–P3 read within 1.2 dB of each other despite meter readings of 337–538 L/min. Per-person spread was ±2–6 dB per blow. Leave-one-out error was about 300 L/min, which is useless.
- **13 candidate features didn't fix it.** Between-person spread divided by within-person spread was at most 1.7. The spectral centroid ranked all four people correctly, but with between/within at 0.4, so treat it as chance (1 in 24 for a perfect 4-person ranking) and as a hypothesis only.
- **Conclusion:** at a distance and without fixed geometry, aim and lip shape vary more than airflow does. Fixed geometry (a tube, blowing through the meter while the phone listens) would be the next test. The team chose not to add components, so peak flow is dropped. Respiratory candidates without extra hardware are breathing rate from the accelerometer (phone on the chest) or from the heart-rate test, and a sustained-vowel duration.

**Gotcha:** the first run showed heavy compression on every source. It came from the laptop's audio enhancements (Windows/Realtek), not the phone. Turn enhancements off before running the test.

#### First steps

- [x] Check that a mic source without adaptive gain exists: `VOICE_RECOGNITION`, on both Pixels
- [ ] Clipping check: record several hard blows at arm's length and look at the peak level (the linearity test stopped at −6 dBFS played)
- [ ] Peak-flow calibration on the Pixel 7: 3 blows per person into the meter and 3 at the phone, then fit a line from the envelope peak to the meter's L/min
- [ ] Ask the SpiroSmart authors in the lab for code and data
- [ ] Borrow a portable spirometer before attempting FEV1 or FVC

#### Watch out for

- **Needs a quiet room.** Inhalation is inaudible.
- **FVC is least accurate:** only about the first 3 s of the exhale are audible.
- **Mic responses differ per phone,** and the paper had only one phone. Absolute level differs between phones, so calibrate each one.
- **Hidden DSP processing differs per phone and per source.** Run `tools/mictest/run_linearity.sh` on any new phone before trusting its envelope.

### Saleheen 2020: lung function from a vowel

- **Paper:** Saleheen, Ahmed, Rahman, Nemati, Nathan, Vatanparvar, Blackstock, Kuang. "Lung Function Estimation from a Monosyllabic Voice Segment Captured Using Smartphones." MobileHCI '20
- **Tier:** Stretch
- **Measures:** FEV1/FVC ratio (%), plus an obstruction yes/no (below 0.7)
- **Sensors:** Microphone; the user says "Aaaa…" as long as possible
- **Validated on:** Galaxy Note 8
- **Headline result:** FEV1/FVC MAE 7.4 points (lab, N = 131) and 11.35 (clinic, N = 70); obstruction F1 about 0.80
- **Code / data:** None
- **PDF:** `Papers/Lung_function_vowel.pdf`

#### How it works

1. **Find the vowel:** 200 ms frames with 5 statistics each, a random forest (100 trees) classifying vowel vs not, then DBSCAN to merge frames into segments.
2. **Features on the segment:** 310 in total.
   - envelope area and duration
   - 34 frame-level audio features × 4 statistics (MFCC, chroma, spectral and energy)
   - left-half vs right-half statistics
   - shimmer and jitter
3. Reduce with PCA to 20 components, then an MLP regressor.

#### Android implementation

1. `AudioRecord` at 16 kHz or higher on the `VOICE_RECOGNITION` source with effects off (see SpiroSmart), with loudness normalisation as in the paper.
2. Port the feature extraction, which is a reimplementation job (MFCC, chroma, pitch for jitter and shimmer). Train in `analysis/`.

#### Watch out for

- **Large error:** 7–11 points of error on a ratio near 70–80 is clinically wide. At best a trend screen.
- **Needs training data:** patients with spirometry, which we don't have.

### SpiroSonic

- **Paper:** Song, Yang, Yang, Chen, Forno, Chen, Gao. "SpiroSonic: Monitoring Human Lung Function via Acoustic Sensing on Commodity Smartphones." MobiCom '20
- **Tier:** Stretch
- **Measures:** PEF, FEV1, FVC, FEV1/FVC as % of predicted, from chest-wall motion
- **Sensors:** Speaker plays 12 tones at 17–22.5 kHz (0.5 kHz apart); the bottom mic tracks the phase of the chest echo at 48 kHz, 5–20 cm from the chest
- **Validated on:** Galaxy S8, OnePlus 7 Pro, Mi Mix 2
- **Headline result:** Under 3% error on 5 healthy adults. In a clinic (83 children with asthma or CF), patient-level FEV1 error 12.52%, FVC 9.06%; only 66% of tests were valid
- **Code / data:** None
- **PDF:** `Papers/Spirosonic.pdf`

#### How it works

1. **Phase to distance:** the echo's phase gives chest displacement, Δd = −c·Δφ / (4πf).
2. **Clean up hand motion:** fit arcs on the I/Q plane, recentre them, and subtract a ramp for body motion.
3. **Find the exhale.**
4. **Features:** peak chest speed (→ PEF), displacement at 1 s (→ FEV1) and maximum displacement (→ FVC).
5. **Model:** these features plus age, sex, height and race go into a small neural network (12-10-8). It outputs % predicted against GLI-2012.

#### Android implementation

Not for v1. If we try it, first sweep 17–22.5 kHz through a Pixel speaker and record it to see whether the hardware can do it. Then:

- **Capture:** `AudioTrack` and `AudioRecord` at 48 kHz on the `VOICE_RECOGNITION` source (`UNPROCESSED` isn't supported on our Pixels). Linearity is checked only at 1 kHz so far, so check its response at 17–22.5 kHz too.
- **GLI-2012:** implement the predicted-value equations, and collect demographics.

#### Watch out for

- **Fragile:** sensitive to body motion, thick sweaters, and how close the phone is held.
- **Phone-specific:** speaker and mic responses above 17 kHz vary by phone, and the paper didn't test Pixels.

### Magical Touch

- **Paper:** Guo, Gong, Zhang, Zhang. "Magical Touch: Transforming Raw Capacitive Streams into Expressive Hand-Touchscreen Interaction." CHI EA '26. doi 10.1145/3772363.3799129
- **Tier:** Reference
- **Measures:** Nothing physical. Whole-hand contact shape and intensity drive a physics game
- **Sensors:** Raw 78 × 52 capacitive frames at about 100 Hz, through custom firmware from the Surface team
- **Validated on:** Surface Laptop Studio 2
- **Headline result:** Demo paper, no user study
- **Code / data:** None
- **PDF:** `Papers/Magicaltouch.pdf`

#### How it works

The raw capacitance frame becomes a smoothed "gravel" density field that balls bounce off. Pressing harder makes a denser field and a sharper bounce.

#### Android implementation

It hits the same wall as FeverPhone's contact features: public touch APIs give only finger ellipses. Raw capacitance images need vendor firmware, or on Pixels root or a debug kernel. If we root a Pixel 6 for FeverPhone, the same raw frames would serve both.

**Stock-phone approximation (prototype: Ball rescue).** `TouchLabActivity` / `TouchWorld.java` rebuild the paper's field from the touch ellipses:

- Each pointer's major/minor axis, orientation and pressure is painted into a 52 × 78 grid.
- The grid is Gaussian-smoothed (3 box blurs) and dilated, as in the paper.
- Physics follows the paper: gravity, linear drag, repulsion along the field gradient, and restitution that rises with density. Tested: a firm touch rebounds a ball 125 px against 77 px for a light one.
- The game has a basket, stars, and a finite lifespan for balls in free fall.

Limits of the approximation:

- Touches are ellipses, not hand shapes.
- The Pixel 4 under-reports contact size, so touches are painted at least 8 mm wide.
- Palm rejection may cancel flat-hand contact.

## Android build

### Create the project

1. In Android Studio, create a new project from the **Empty Activity** (Compose) template.
   - Package: `edu.uw.ubicomp.apollo`
   - Min SDK: API 33
   - Build config: Kotlin DSL with a version catalog
2. Set `compileSdk` and `targetSdk` to 36, and the JVM toolchain to 17.
3. Add KSP, Hilt and kotlinx.serialization plugins.
4. Add the dependencies below. Take the versions Android Studio suggests rather than copying numbers from anywhere.

```toml
# gradle/libs.versions.toml [libraries]. Add a version.ref to each from Android Studio's suggestions;
# Compose libraries take theirs from the BOM.
androidx-compose-bom          = { module = "androidx.compose:compose-bom" }
androidx-material3            = { module = "androidx.compose.material3:material3" }
androidx-activity-compose     = { module = "androidx.activity:activity-compose" }
androidx-lifecycle-compose    = { module = "androidx.lifecycle:lifecycle-runtime-compose" }
androidx-lifecycle-viewmodel  = { module = "androidx.lifecycle:lifecycle-viewmodel-compose" }
androidx-navigation-compose   = { module = "androidx.navigation:navigation-compose" }
androidx-room-runtime         = { module = "androidx.room:room-runtime" }
androidx-room-ktx             = { module = "androidx.room:room-ktx" }
androidx-room-compiler        = { module = "androidx.room:room-compiler" }        # ksp
androidx-datastore            = { module = "androidx.datastore:datastore-preferences" }
androidx-work                 = { module = "androidx.work:work-runtime-ktx" }
androidx-camera-camera2       = { module = "androidx.camera:camera-camera2" }
androidx-camera-lifecycle     = { module = "androidx.camera:camera-lifecycle" }
androidx-camera-view          = { module = "androidx.camera:camera-view" }
hilt-android                  = { module = "com.google.dagger:hilt-android" }
hilt-compiler                 = { module = "com.google.dagger:hilt-compiler" }   # ksp
androidx-hilt-navigation      = { module = "androidx.hilt:hilt-navigation-compose" }
health-connect                = { module = "androidx.health.connect:connect-client" }
vico-compose-m3               = { module = "com.patrykandpatrick.vico:compose-m3" }
okhttp                        = { module = "com.squareup.okhttp3:okhttp" }
kotlinx-serialization-json    = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json" }
mediapipe-tasks-vision        = { module = "com.google.mediapipe:tasks-vision" }  # eye tracking, later
junit                         = { module = "junit:junit" }
kotlinx-coroutines-test       = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test" }
```

### Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />  <!-- eye tracking, later -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />              <!-- lung tests: VOICE_RECOGNITION source -->
    <!-- Development permission, granted once per phone:
         adb shell pm grant edu.uw.ubicomp.apollo android.permission.DUMP -->
    <uses-permission android:name="android.permission.DUMP"
        tools:ignore="ProtectedPermissions" />

    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        ...>
        <service
            android:name=".service.PassiveTrackingService"
            android:foregroundServiceType="health"
            android:exported="false" />
    </application>
</manifest>
```

```xml
<!-- res/xml/data_extraction_rules.xml: keep everything off Google Drive and device transfer -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

### Thermistor reader (FeverPhone)

This is a port of `probe-app/.../ProbeActivity.java`, which is verified on the Pixel 4.

```kotlin
data class ThermistorSample(val epochMs: Long, val name: String, val type: Int, val celsius: Float)

/** Reads every temperature the thermal HAL reports. Needs DUMP granted over adb (see Manifest). */
class ThermistorReader(private val context: Context) {
    private val pattern = Regex("""mValue=([-0-9.eE]+|NaN), mType=(-?\d+), mName=([^,]+),""")

    fun read(): List<ThermistorSample> {
        val dump = File(context.cacheDir, "thermalservice.txt")
        FileOutputStream(dump).use { Debug.dumpService("thermalservice", it.fd, null) }
        val now = System.currentTimeMillis()
        val out = mutableListOf<ThermistorSample>()
        var inHal = false
        dump.forEachLine { line ->
            when {
                line.startsWith("Permission Denial") -> throw SecurityException("DUMP not granted")
                line.startsWith("Current temperatures from HAL") -> inHal = true
                inHal && !line.startsWith(" ") && !line.startsWith("\t") -> inHal = false
                inHal -> pattern.find(line)?.let { m ->
                    out += ThermistorSample(now, m.groupValues[3], m.groupValues[2].toInt(), m.groupValues[1].toFloat())
                }
            }
        }
        return out
    }
}
```

Call it once a second from a coroutine on `Dispatchers.IO` while the measurement screen is open with `FLAG_KEEP_SCREEN_ON`. Don't use a background service for this; the forehead test failed when the phone slept.

### Camera PPG capture (heart rate)

```kotlin
@OptIn(ExperimentalCamera2Interop::class)
fun buildPpgAnalysis(): ImageAnalysis {
    val builder = ImageAnalysis.Builder()
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    Camera2Interop.Extender(builder)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
    return builder.build()
}

/** Mean red over the centre half of the frame, with the sensor timestamp. */
class RedMeanAnalyzer(private val onSample: (tNanos: Long, red: Double) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        var sum = 0L
        var n = 0
        for (y in image.height / 4 until image.height * 3 / 4 step 4) {
            for (x in image.width / 4 until image.width * 3 / 4 step 4) {
                sum += buf.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF  // R of RGBA
                n++
            }
        }
        onSample(image.imageInfo.timestamp, sum.toDouble() / n)
        image.close()
    }
}

// After binding: torch on, then lock exposure and white balance once a finger is detected.
camera.cameraControl.enableTorch(true)
Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
    CaptureRequestOptions.Builder()
        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        .build()
)
```

The signal pipeline (resample, detrend, band-pass, FFT) lives in a pure Kotlin function, `estimateHeartRate(t: LongArray, red: DoubleArray): HrEstimate`, unit-tested on recorded CSVs.

### Barometer, steps and floors

```kotlin
sealed interface MotionSample {
    data class Pressure(val tNanos: Long, val hPa: Float) : MotionSample
    data class Step(val tNanos: Long) : MotionSample
    data class StepTotal(val tNanos: Long, val total: Long) : MotionSample
}

class MotionCollector(context: Context, private val sink: (MotionSample) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> sink(MotionSample.Pressure(e.timestamp, e.values[0]))
            Sensor.TYPE_STEP_DETECTOR -> sink(MotionSample.Step(e.timestamp))
            Sensor.TYPE_STEP_COUNTER -> sink(MotionSample.StepTotal(e.timestamp, e.values[0].toLong()))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
```

Step sensors need the `ACTIVITY_RECOGNITION` runtime permission; the barometer needs none. Floor detection (median filter → Kalman → sustained-change rule) is a pure function over the event list.

### Passive tracking service

Steps come from the hardware counter and need no service. Floors need the barometer running, so they get a foreground service with a visible notification.

```kotlin
class PassiveTrackingService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_apollo)
            .setContentTitle("Counting floors")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0,
        )
        collector.start()
        return START_STICKY
    }
}
```

Start it from a visible screen, for example a "Count floors" toggle. Android refuses camera foreground services started from the background, and eye tracking will use the same pattern with the `camera` type.

### Room entities

These live in `:storage` and stay private to it. Other modules see the plain `Reading` and `JournalEntry` from `:core`, and `:storage` maps between the two.

```kotlin
@Entity(tableName = "reading", indices = [Index("metric", "startedAt")])
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metric: String,            // "core_temp_c", "heart_rate_bpm", "floors", "steps"
    val value: Double,
    val unit: String,
    val quality: Float,            // 0..1
    val method: String,            // "feverphone.dump.v1", "ppg.finger.v1"
    val startedAt: Long,
    val endedAt: Long,
    val sessionId: Long?,
    val deviceModel: String,       // Build.MODEL
)

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val protocolVersion: Int,
    val rawPath: String,           // CSV in filesDir
    val referenceValue: Double?,   // thermometer / oximeter reading, if entered
    val referenceDevice: String?,
    val notes: String?,
)

@Entity(tableName = "journal_entry")
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val text: String,
    val mood: Int?,                // 1..5
    val tags: String,              // comma-separated; :core's JournalEntry uses List<String>
)
```

### Agent loop

The proxy relays the Messages API and holds the key. The loop and the tools run on the phone.

```kotlin
suspend fun runAgent(trigger: Trigger, repo: HealthRepository): FeedbackCard {
    val flags = RedFlags.check(repo)                       // plain code, always first
    val messages = mutableListOf(Message.user(trigger.describe()))
    repeat(MAX_TURNS) {
        val response = proxy.createMessage(
            model = "claude-opus-5-5",
            system = SYSTEM_PROMPT,                        // versioned; stored with the card
            tools = AGENT_TOOLS,                           // get_readings, get_journal, get_baseline, submit_feedback
            messages = messages,
        ) ?: return FeedbackCard.fallback(flags)           // network failure: never block the user
        val calls = response.toolUses()
        calls.firstOrNull { it.name == "submit_feedback" }?.let { return it.toCard().withFlags(flags) }
        if (calls.isEmpty()) return FeedbackCard.fallback(flags)
        messages += response.asAssistantMessage()
        messages += Message.toolResults(calls.map { it.id to AgentTools.execute(it, repo) })
    }
    return FeedbackCard.fallback(flags)
}
```

```python
# backend/proxy.py: stateless relay. Holds the API key, stores and logs nothing.
import os

from anthropic import AsyncAnthropic
from fastapi import FastAPI, HTTPException, Request

app = FastAPI()
client = AsyncAnthropic()  # reads ANTHROPIC_API_KEY
ALLOWED_MODELS = {"claude-opus-5-5"}


@app.post("/v1/messages")
async def messages(req: Request):
    if req.headers.get("x-apollo-token") != os.environ["APOLLO_TOKEN"]:
        raise HTTPException(401, "bad app token")
    body = await req.json()
    if body.get("model") not in ALLOWED_MODELS:
        raise HTTPException(400, "model not allowed")
    body["max_tokens"] = min(int(body.get("max_tokens", 4096)), 8192)
    msg = await client.messages.create(**body)
    return msg.model_dump()
```

### Testing with recorded data

- **Unit tests (JVM):** each pipeline function is tested against CSVs in `data/`. For example, the Pixel 4 forehead run must produce a positive corrected slope on quiet-therm.
- **Replay sources:** `ReplayThermistorSource`, `ReplayPpgSource` and `ReplayMotionSource` stream a CSV at real time or faster. Developer settings can switch any module to replay, which is how the emulator and previews get realistic data.
- **Agent tests:** 30 scripted scenarios run against the real API (cost: a few dollars per full run). Check the red-flag behaviour, that cited readings exist, and that there's no diagnosis language.

## Dev setup

### Install

1. Install the latest stable Android Studio. It bundles a JDK and the SDK manager.
2. In **Settings → Languages & Frameworks → Android SDK → SDK Tools**, install:
   - Android SDK Command-line Tools
   - Android Emulator
   - Android SDK Platform-Tools
3. Clone the repo and open the project folder in Android Studio.

### Mac and Windows

The team is on both, and that is fine: Android Studio, Gradle, the emulator and adb all run on both. These are the things that actually break between the two, and the fix for each. Set them up in Phase 0, before the first feature branch.

| Problem | Fix |
| --- | --- |
| **Line endings.** Windows Git converts files to CRLF, which breaks `gradlew` and every `.sh` script on Mac, and turns whole files into diffs | Commit a `.gitattributes` (below). Then nobody's `core.autocrlf` setting matters |
| **Debug signing keys differ per laptop.** An APK built on one laptop can't install over one built on another; the phone demands an uninstall, and that **deletes the Room data and the DUMP grant** | Commit one shared `keystore/debug.keystore` (debug only, never a release key), and point `signingConfigs.debug` at it in `app/build.gradle.kts` |
| **SDK path.** `local.properties` holds `sdk.dir`, which differs per machine (`~/Library/Android/sdk` vs `%LOCALAPPDATA%\Android\Sdk`) | Never commit `local.properties`; Android Studio writes it. Scripts read `ANDROID_HOME` |
| **JDK.** Different installed JDKs give different build errors | Use Android Studio's bundled JDK, with the Gradle toolchain at 17. Don't install your own |
| **Gradle wrapper.** | Mac runs `./gradlew`, Windows runs `gradlew.bat`. Commit both, and `gradle/wrapper/` |
| **Shell scripts in `tools/`** use bash, and macOS's `say` for spoken cues | On Windows, run them in Git Bash. Move the spoken cues into the app itself (Android `TextToSpeech`); the FeverPhone flow needs them anyway. Until then, the scripts already print each cue, and on Windows the `say` call just fails harmlessly. `probe-app/build.sh` defaults to the Mac SDK path, so set `ANDROID_HOME` on Windows |
| **Python** is `python3` on Mac and `python` / `py` on Windows | Use a venv with `analysis/requirements.txt`. Docs say `python3`; Windows users substitute `py` |
| **Emulator images.** Apple Silicon needs `arm64-v8a`, Windows/Intel needs `x86_64` | AVDs are per machine and never committed. Everyone makes their own (see Emulator) |
| **Long paths on Windows.** Gradle build folders exceed 260 characters | Run `git config --global core.longpaths true`, and keep the clone near the drive root |
| **adb on Windows** sometimes doesn't see a Pixel | Install **Google USB Driver** from the SDK Manager |
| **One Pixel 4, several laptops** | Use wireless debugging: *Developer options → Wireless debugging*, then `adb pair` and `adb connect` from any laptop on the same Wi-Fi. Any OS can deploy to the sensor phone without swapping cables |

```gitattributes
# .gitattributes: the repo stores LF; Windows tools that need CRLF get it
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.ps1 text eol=crlf
*.png binary
*.jpg binary
*.pdf binary
*.docx binary
*.jar binary
*.keystore binary
```

Commit `.gitattributes` first. Then, if files already show as changed, run `git add --renormalize .` once.

### Run on a real Pixel

1. On the phone, open **Settings → About phone** and tap **Build number** 7 times. Then turn on **Developer options → USB debugging**.
2. Plug in and check with `adb devices`, then accept the prompt on the phone.
3. Run from Android Studio, then grant the thermistor permission once per install:

```bash
adb shell pm grant edu.uw.ubicomp.apollo android.permission.DUMP
```

4. The existing tools still work: `probe-app/build.sh --install` builds the FeverProbe logger, `tools/forehead_test_app.sh` runs the spoken-cue forehead test, and `python3 tools/analyze_forehead.py data/<file>.csv` analyses a run.

### Emulator

Good for UI, navigation, journal, agent, storage and anything fed by a replay source. Every teammate can run one locally for free.

```bash
# macOS on Apple Silicon: use arm64-v8a images. Windows/Intel: replace arm64-v8a with x86_64,
# and in Git Bash use SDK="$LOCALAPPDATA/Android/Sdk" (or just use Device Manager in Android Studio).
SDK=~/Library/Android/sdk
$SDK/cmdline-tools/latest/bin/sdkmanager "system-images;android-35;google_apis_playstore;arm64-v8a"
$SDK/cmdline-tools/latest/bin/avdmanager create avd -n Pixel7_API35 \
  -k "system-images;android-35;google_apis_playstore;arm64-v8a" -d pixel_7
$SDK/emulator/emulator -avd Pixel7_API35
```

Or use **Device Manager → Create Virtual Device → Pixel 7** in Android Studio.

- **Windows:** turn on **Windows Hypervisor Platform** in *Turn Windows features on or off*, reboot, and run `emulator -accel-check`. The older AEHD driver stops being supported at the end of 2026.
- **Hardware:** the emulator wants about 16 GB of RAM.

| Feature | Emulator | Real Pixel |
| --- | --- | --- |
| Compose UI, navigation, journal, agent | Yes | Yes |
| Room, export, settings | Yes | Yes |
| Barometer | Simulated: *Extended controls → Virtual sensors → Pressure* | Yes |
| Accelerometer | Simulated (rotate and move controls) | Yes |
| Steps | Use the replay source | Yes |
| FeverPhone thermistors | No; use the replay source over `data/forehead_app_*.csv` | Yes, with DUMP |
| Camera PPG | No (a virtual scene can't pulse); use the replay source | Yes |

### Prototype UI without a device

- **`@Preview`:** renders a composable in the IDE.
  - Use `@PreviewLightDark` and `@PreviewScreenSizes` to cover themes and sizes.
  - **Interactive mode** lets you click and see animations.
  - **Run Preview** pushes one preview to a device.
- **Previews can't create ViewModels or touch files and the network.** Write each screen as a stateful wrapper around a stateless `FooScreen(state, onAction)`, and preview the stateless one with sample state.
- **Live Edit** pushes function-body edits to a running emulator or phone (API 30+) without a rebuild.

### Share a clickable build

| Option | What teammates get | Cost | Notes |
| --- | --- | --- | --- |
| Figma prototype | Clickable mockups in a browser | Free tier | Best before code exists |
| Appetize.io | Our APK running in a browser tab, via a link | Free: 30 min per month, 3-minute sessions | Upload the APK from `./gradlew assembleDebug`; fine for quick demos |
| Android Device Streaming | A real Pixel 9-series phone streamed into Android Studio | Monthly free minutes on a Firebase Spark project | Needs Android Studio and a Firebase project; real sensors, but you can't touch the hardware |
| scrcpy | A physical phone mirrored and controlled from a laptop | Free | One person per phone; screen-share it in a call |
| Sideload the APK | The real app on their own Android phone | Free | Thermistors need the DUMP grant from a computer |

Avoid **Firebase Studio** (formerly Project IDX): it stopped accepting new workspaces on 2026-06-22 and shuts down on 2027-03-22. **GitHub Codespaces** can't run the Android emulator (no KVM).

### Recommended workflow

1. Build screens as stateless composables with previews.
2. Run on your own emulator against replay sources.
3. Run sensor work on the Pixel 4 or Pixel 7.
4. For demos, use Appetize for a quick link, or scrcpy plus a screen share for live sensor demos.

## Roadmap

### Phases and gates

```text
Phase 0  Scaffold            .gitattributes, shared debug keystore, root + module CLAUDE.md, Gradle modules,
   │                         :core interfaces merged, then: app shell, theme, Room, replay sources,
   │                         ThermistorReader port, PhoneThermalMonitor
   │
Phase 1  Sensing             FeverPhone data collection + linear model · fingertip HR · steps and floors
   │     ── Gate 1  Heart rate: RMSE ≤ 5 bpm (or 10%) vs chest strap or oximeter, ≥10 people, rest and post-exercise
   │     ── Gate 2  FeverPhone: MAE ≤ 0.5 °C on the water-bag rig (paper 0.495), then on volunteers vs oral thermometer
   │     ── Gate 3  Floors: ≥ 95% of floors on a 10-floor stair walk, 0 floors counted in an elevator
   │
Phase 2  Agent               journal, triggers, red-flag rules, feedback cards
   │     ── Gate 4  Agent: 30 scripted scenarios, 0 missed red flags, 0 diagnoses, every cited reading exists
   │
Phase 3  Stretch             SpO2 (experimental) · HRV demo · eye tracking · lung function
```

Each gate is a measurement against a reference device, not a code milestone. If gate 2 fails on the no-root route, move FeverPhone to a rooted Pixel 6 before going further. Gates 1 and 2 use the paper bars (Yan's ANSI/AAMI EC13 target and FeverPhone's lab MAE). Gates 3 and 4 are our own targets.

### First two weeks

- [ ] Commit `.gitattributes`, the shared debug keystore and the root `CLAUDE.md` (see Mac and Windows, in Dev setup)
- [ ] Create the Android Studio project with the stack and Gradle modules above
- [ ] Write and merge `:core` (Shared interfaces, in Design) before anyone else branches
- [ ] Theme, bottom navigation and empty screens for Today, Measure, Journal and Trends, all with previews
- [ ] Room entities and DAOs for reading, session, journal_entry, feedback and calibration
- [ ] Port `ThermistorReader`, plus a replay source over `data/forehead_app_20261016_162855.csv`
- [ ] FeverPhone measurement flow end to end on the Pixel 4: prep checks → pre-hold → 90 s → raw CSV saved → reference reading entered
- [ ] Heart-rate capture writing raw CSVs, plus 10 sessions next to a reference device
- [ ] Barometer and step logging on a stair walk and an elevator ride
- [ ] Stateless proxy deployed, and a single agent call returning a feedback card from fake data

### Open decisions

- [ ] Can the agent call leave the phone? The fully on-device alternative, Gemini Nano, needs a Pixel 9 or newer and is a much smaller model
- [x] What should eye tracking measure? Blink rate and gaze-shift rate, at 15 fps when passive (`prototypes/eyetrack/README.md`)
- [ ] Eye tracking: where does it appear in the UI, and is passive mode worth the battery and heat? Measure 30 min on both Pixels first
- [ ] Buy and root a Pixel 6 for full-fidelity FeverPhone, or stay on the no-root route?
- [ ] IRB: which data collection needs approval before we involve people outside the team?
- [ ] Is the app called Apollo, after the repo?
- [ ] Where do the "temperature first?" prompt and the phone-heat status appear in the UI: a dialog, a combined check-up flow, or the Measure screen?
- [ ] Where does the chatbot live: its own tab, or a sheet from Today?
- [ ] API key for the hackathon demo: deploy the proxy, or keep the key in `local.properties` (gitignored) and build it into team-only APKs? The second is faster, but anyone with the APK can extract the key, so switch to the proxy before the APK leaves the team

### Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Temperature models don't transfer between phones | A Pixel 6 model gave 8.76 °C MAE on an uncalibrated Pixel 3 | Calibrate per phone model; standardise lab phones |
| No fever data | The paper had 16 febrile patients; we have none | Water-bag rig, anomaly variant, ask the authors, IRB study later |
| Narrow validation conditions | FeverPhone: 18.3–23.6 °C ambient, no high fevers. Yan: light-to-medium skin only | Show the limits in the app; test darker skin tones |
| The LLM gives medical advice | Health feedback can cause harm | Red-flag rules in code first; cite readings; never diagnose |
| Medical-device rules | "Detects fever" is a diagnostic claim | Frame as research or wellness until validated |
| Phone sleeps mid-measurement | Lost data in our first forehead test | Measurements run in a foreground activity with the screen kept on |
| Heart-rate test heats the phone before a temperature test | Torch and camera warm the thermistors, so FeverPhone reads high or noisy | Ask "temperature first?"; `PhoneThermalMonitor` blocks until the phone settles |
| Reinstalling from another laptop wipes the phone | A signing-key mismatch forces an uninstall, losing Room data and the DUMP grant | Shared debug keystore in the repo |
| Parallel agents edit the same files | Merge conflicts, and features coupling to each other | Gradle modules, `:core`-only dependencies, file ownership in `CLAUDE.md` |

### Sources

- Papers: the eleven PDFs in `Papers/`, summarised above.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Auto Backup](https://developer.android.com/identity/data/autobackup)
- [ML Kit GenAI (Gemini Nano) device list](https://developers.google.com/ml-kit/genai)
- [Emulator acceleration](https://developer.android.com/studio/run/emulator-acceleration), [extended controls](https://developer.android.com/studio/run/emulator-extended-controls) and [managing AVDs](https://developer.android.com/studio/run/managing-avds)
- [Compose previews](https://developer.android.com/develop/ui/compose/tooling/previews) and [Live Edit](https://developer.android.com/develop/ui/compose/tooling/iterative-development)
- [Android Device Streaming](https://developer.android.com/studio/run/android-device-streaming)
- [Firebase Studio sunset notice](https://firebase.google.com/docs/studio)
- [Appetize pricing](https://appetize.io/pricing)
- [scrcpy](https://github.com/Genymobile/scrcpy)
