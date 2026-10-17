# Apollo design doc

First-pass plan as of 2026-10-17. These are starting points that will shift once we build, but the first steps in each section should be right.

`docs/DESIGN.md` is the source of truth and `docs/design-doc.html` is generated from it. Edit the Markdown, then run `python3 docs/build.py`.

## Design

### What we're building

Apollo is an Android app that takes health measurements with the phone's own sensors, lets people journal how they feel, and has a Claude agent give feedback grounded in the actual readings.

- **Must work:** FeverPhone core temperature and fingertip heart rate.
- **Comes along cheaply:** steps, floors climbed, stair steps.
- **Stretch:** lung function from audio, background eye tracking.
- **Constraint:** all data stays on the phone. The one exception is the agent call, which sends only what the agent's tools return.

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
| Eye tracking | Front camera in a foreground service, face landmarks | None | Stretch |

### Phones

| Phone | Status | Use it for |
| --- | --- | --- |
| Pixel 4 (Android 13) | Have it. The no-root thermistor route is verified: 19 sensors at about 0.002 °C resolution | Main dev phone now |
| Pixel 7 | Have it, not yet probed | Run `tools/probe_phone.sh` and the forehead test next |
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
| Audio (stretch) | `AudioRecord` with `UNPROCESSED` source | Avoids AGC and noise suppression |
| On-device ML | Plain Kotlin for linear models and exported random forests; LiteRT if a neural net arrives; MediaPipe Tasks for face landmarks | Small, inspectable models first |
| Charts | Vico (Compose) | Native Compose charts |
| Health platform | Health Connect, optional write-only | Interop with other apps, but other apps can read it, so it's opt-in |
| LLM | Claude Opus 5.5 (`claude-opus-5-5`) through a stateless proxy that holds the key | Best feedback quality; the key never ships in the APK |
| Networking | OkHttp + kotlinx.serialization | Small and well known |
| Analysis | Python (pandas, scikit-learn) in `analysis/` | Fit models on recorded CSVs, export parameters as JSON for the app |

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

### Code layout

Start with one `app` module organised by package, and split into Gradle modules when build times hurt.

```text
apollo/
├── app/src/main/java/edu/uw/ubicomp/apollo/
│   ├── ui/            screens, navigation, theme, components
│   ├── data/          Room entities, DAOs, repositories, export
│   ├── sensing/
│   │   ├── core/      SensorSource, Measurement, Reading, quality
│   │   ├── thermal/   ThermistorReader, FeverPhone pipeline
│   │   ├── ppg/       camera capture, HR pipeline
│   │   ├── motion/    steps, floors, stairs (barometer)
│   │   └── replay/    CSV-backed fake sources
│   ├── agent/         Claude client, tools, red-flag rules, triggers
│   └── service/       foreground services
├── backend/           stateless Claude proxy (not part of the APK)
├── analysis/          Python notebooks and scripts that fit models
├── data/              recorded sessions (CSV) used for replay and tests
├── docs/              this doc
├── probe-app/         existing FeverProbe logger
└── tools/             existing adb/test scripts
```

### Data model

Room tables. Every row carries the phone model, because calibration is per model.

| Table | Key fields | Notes |
| --- | --- | --- |
| `reading` | metric, value, unit, quality 0–1, method, startedAt, endedAt, sessionId, deviceModel | One number shown to the user, e.g. `core_temp_c`, `heart_rate_bpm`, `floors` |
| `session` | kind, startedAt, endedAt, protocolVersion, rawPath, referenceValue, referenceDevice, notes | Raw data lives in a file; `referenceValue` holds a thermometer or oximeter reading when the user has one |
| `journal_entry` | createdAt, text, mood (1–5), tags | Free text plus light structure |
| `feedback` | createdAt, trigger, cardJson, model, promptVersion | Every agent output, for review |
| `calibration` | deviceModel, metric, paramsJson, nSamples, fittedAt | Model coefficients per phone model |

The `referenceValue` field matters. If we ask "have a thermometer? enter its reading" after each measurement from day one, every use of the app produces calibration data.

### Measurement contract

Each measurement module implements one interface. Passive collectors (steps, floors) write readings on their own schedule instead.

```kotlin
interface Measurement {
    val id: String                          // "feverphone", "ppg_finger"
    val prepChecks: List<PrepCheck>         // charging, airplane mode, finger on lens...
    fun run(): Flow<MeasurementState>       // Prep -> Countdown -> Capturing(progress, live) -> Done | Failed
}

data class MeasurementResult(
    val readings: List<Reading>,
    val rawFile: File,                      // every sample, for replay and re-analysis
    val quality: Float,                     // 0..1
    val warnings: List<String>,             // "moved during capture", "room too warm"
)
```

### The agent

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

### Privacy and data

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

An exponential fit to pa-therm gave a heat-transfer constant k ≈ 0.21 /min, with equilibrium at about 34.6 °C. That is a physics feature the paper never used.

#### Android implementation

1. **Prep checks:**
   - Charging is on (`BatteryManager.isCharging`); the paper used a full charge on a trickle.
   - Airplane mode is on (read `Settings.Global.AIRPLANE_MODE_ON` and ask the user to turn it on).
   - The app sets window brightness to 20%.
   - At least 10 minutes have passed since the last reading.
   - Ask whether the room is roughly 18–24 °C.
2. **60 s hand pre-hold** with a countdown, holding the corners in "camera grip". This settles the phone and measures drift and IT.
3. **90 s forehead contact,** landscape, screen on (`FLAG_KEEP_SCREEN_ON`):
   - Read every thermistor at 1 Hz with `ThermistorReader` (see Android build).
   - Log touch events anyway.
   - Speak or vibrate the start and end cues.
4. **Features per thermistor:** contact slope minus pre-contact drift, IT, and the exponential-fit k and equilibrium where the curve allows.
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

- [ ] Charge the Pixel 4 to 100% and run `tools/forehead_test_app.sh` three more times, with an oral thermometer reading each time
- [ ] Run the same test on the Pixel 7
- [ ] Email the authors for code, data and their thermistor choice
- [ ] Build the water-bag rig (sous-vide stick, sealed bag, cardboard box with a forehead-sized hole)
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

1. **Capture:** `AudioRecord` with `MediaRecorder.AudioSource.UNPROCESSED`. Check `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` first. Use 48 kHz.
2. **Coaching UI:** full inhale, then blast it out. Show a live flow bar like their ball-in-cylinder.
3. **Features:** compute them on the phone.
4. **Model:** train in `analysis/` on paired data from a spirometer.

#### First steps

- [ ] Ask the SpiroSmart authors in the lab for code and data
- [ ] Borrow a portable spirometer before collecting anything

#### Watch out for

- **Needs a quiet room.** Inhalation is inaudible.
- **FVC is least accurate:** only about the first 3 s of the exhale are audible.
- **Mic responses differ per phone,** and the paper had only one phone.

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

1. `AudioRecord` at 16 kHz or higher, `UNPROCESSED` source, with loudness normalisation as in the paper.
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

- **Capture:** `AudioTrack` and `AudioRecord` at 48 kHz on the `UNPROCESSED` source.
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

Don't build it. The relevance is that it hits the same wall as FeverPhone's contact features: public touch APIs give only finger ellipses. Raw capacitance images need vendor firmware, or on Pixels root or a debug kernel. If we root a Pixel 6 for FeverPhone, the same raw frames would serve both.

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
    <uses-permission android:name="android.permission.RECORD_AUDIO" />              <!-- lung papers, later -->
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

```kotlin
@Entity(indices = [Index("metric", "startedAt")])
data class Reading(
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

@Entity
data class Session(
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

@Entity
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val text: String,
    val mood: Int?,                // 1..5
    val tags: String,              // comma-separated
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
# macOS on Apple Silicon: use arm64-v8a images (Windows/Intel: x86_64)
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
Phase 0  Scaffold            app shell, theme, Room, replay sources, ThermistorReader port
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

- [ ] Create the Android Studio project with the stack and layout above
- [ ] Theme, bottom navigation and empty screens for Today, Measure, Journal and Trends, all with previews
- [ ] Room entities and DAOs for reading, session, journal_entry, feedback and calibration
- [ ] Port `ThermistorReader`, plus a replay source over `data/forehead_app_20261016_162855.csv`
- [ ] FeverPhone measurement flow end to end on the Pixel 4: prep checks → pre-hold → 90 s → raw CSV saved → reference reading entered
- [ ] Heart-rate capture writing raw CSVs, plus 10 sessions next to a reference device
- [ ] Barometer and step logging on a stair walk and an elevator ride
- [ ] Stateless proxy deployed, and a single agent call returning a feedback card from fake data

### Open decisions

- [ ] Can the agent call leave the phone? The fully on-device alternative, Gemini Nano, needs a Pixel 9 or newer and is a much smaller model
- [ ] What should eye tracking measure: blink rate, drowsiness or attention? That sets frame rate and battery cost
- [ ] Buy and root a Pixel 6 for full-fidelity FeverPhone, or stay on the no-root route?
- [ ] IRB: which data collection needs approval before we involve people outside the team?
- [ ] Is the app called Apollo, after the repo?

### Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Temperature models don't transfer between phones | A Pixel 6 model gave 8.76 °C MAE on an uncalibrated Pixel 3 | Calibrate per phone model; standardise lab phones |
| No fever data | The paper had 16 febrile patients; we have none | Water-bag rig, anomaly variant, ask the authors, IRB study later |
| Narrow validation conditions | FeverPhone: 18.3–23.6 °C ambient, no high fevers. Yan: light-to-medium skin only | Show the limits in the app; test darker skin tones |
| The LLM gives medical advice | Health feedback can cause harm | Red-flag rules in code first; cite readings; never diagnose |
| Medical-device rules | "Detects fever" is a diagnostic claim | Frame as research or wellness until validated |
| Phone sleeps mid-measurement | Lost data in our first forehead test | Measurements run in a foreground activity with the screen kept on |

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
