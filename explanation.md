# How Apollo works

Here, we provide explanations of the physics and CS principles which drive each of the features we provide.

Each section covers three things: the physics that makes the measurement possible, how the phone's hardware senses it, and the computation that turns raw samples into a number. Design decisions and code live in [docs/DESIGN.md](docs/DESIGN.md).

| Feature | Physical principle | Sensor | Core computation |
| --- | --- | --- | --- |
| Temperature (FeverPhone) | Heat conduction (Newton's law of cooling) | Internal thermistors | Line fit of warming rate, calibrated regression |
| Phone-heat readiness | Thermal settling of a first-order system | Internal thermistors | Drift slope over a sliding window |
| Heart rate | Light absorption by blood (Beer–Lambert law) | Rear camera + torch | Band-pass filter, FFT peak |
| Steps | Inertia of a proof mass | Accelerometer (step sensor hub) | Peak detection on a low-power chip |
| Floors climbed | Air pressure falls with height (hydrostatics) | Barometer | Pressure → altitude, Kalman filter, step gating |
| HRV (experimental) | Recoil from each heartbeat (ballistocardiography) | Accelerometer | Template matching, beat intervals |
| SpO2 (experimental) | Oxygenated and deoxygenated blood absorb colours differently | Rear camera + torch | Ratio of ratios |
| Health assistant | — | — | Tool-using LLM, deterministic safety rules, change-point detection |

---

## Temperature (FeverPhone)

### The physics: heat flows from hot to cold

When the phone's glass screen is pressed against your forehead, heat conducts from your skin (about 34–35 °C) into the cooler phone (about 25–28 °C). Newton's law of cooling says the rate of heat flow is proportional to the temperature difference:

```text
dT_phone/dt = k · (T_skin − T_phone)
```

- **T_skin** is what we want to know.
- **T_phone** is what the thermistors read.
- **k** is a coupling constant: how well heat gets from skin to sensor. It depends on contact area, pressure, where the thermistor sits under the glass, and the phone's heat capacity.

Solving that equation gives an exponential approach to equilibrium:

```text
T_phone(t) = T_skin − (T_skin − T0) · e^(−k·t)
```

Over a short window (90 s), the exponential is close to a straight line, so the paper fits `T(t) = m·t + T0`. The slope `m` is approximately `k · (T_skin − T0)`. A hotter forehead gives a steeper slope. That is the whole signal.

**Why each feature matters:**

- **Slope (rate of change).** It carries the skin temperature.
- **Starting temperature T0.** The slope depends on the gap `T_skin − T0`, so the same forehead gives a steeper slope on a colder phone. The model needs T0 to undo that.
- **Contact area and location.** These set `k`. More contact, or contact closer to the thermistor, also steepens the slope. The paper reads them from raw touchscreen capacitance. Stock Android doesn't expose that, so we fix the location by protocol (an on-screen placement guide) instead.

**Why 90 s:** shorter windows are noisy, since there is less rise compared with sensor noise. Longer windows bend away from a straight line as the phone approaches equilibrium. The paper measured 90 s as the best trade-off.

**Our own data:** fitting the full exponential to `pa-therm` from our Pixel 4 test gave `k ≈ 0.21 /min` and an equilibrium near 34.6 °C. That's a plausible forehead skin temperature, recovered from first principles. The paper never used this physics feature.

### The sensors: thermistors

A thermistor is a resistor whose resistance changes steeply with temperature. Phones contain a dozen or more, near the battery, the charger, the radio amplifier, the cameras and the processor, so the power-management system can throttle before anything overheats. A circuit converts each resistance to a voltage, an analog-to-digital converter samples it, and Android's thermal HAL (hardware abstraction layer) publishes the result.

Apps normally only see the coarse battery temperature, in 0.1 °C steps, which is too coarse for a 90 s slope. With the developer-only `DUMP` permission, our app reads the full thermal-HAL report: 19 sensors on the Pixel 4 at about 0.002 °C resolution.

### Skin to core temperature

Fever is defined by **core** temperature, and the forehead skin is cooler than the core and affected by the room. The relationship is roughly stable: the forehead sits over the temporal artery and well-perfused tissue. But it isn't a simple formula, so the paper learns it from data against an oral thermometer.

### The computation

1. Sample every thermistor at 1 Hz.
2. Measure the phone's own drift during a 60 s hand pre-hold, then subtract it from the contact slope. The phone is never perfectly still thermally.
3. Fit a least-squares line to each thermistor over the 90 s of contact.
4. Map the features to temperature with a calibrated model. We start with linear regression. The paper used a random forest, which needs more data and can't extrapolate beyond its training labels.

Calibration is **per phone model and per power mode**. Glass thickness, thermistor placement and internal heat all change the slope a given forehead produces. A Pixel 6 model was off by 8.8 °C on a Pixel 3.

---

## Phone-heat readiness

The phone's own components are heat sources too. The processor, the charger, the radio amplifier, and the camera torch during a heart-rate test all produce heat. When one of them turns on or off, the phone's temperature drifts toward a new equilibrium. It behaves like a first-order system, settling as `e^(−t/τ)` with a time constant of minutes.

FeverPhone measures a slope, so any drift adds directly to the reading. In our first test, the phone was still cooling at about −0.5 °C/min two to three minutes after it was unplugged. The forehead had to work against that.

So the app keeps watching:

- **Drift:** a least-squares slope over the last 120 s. Ready when it is at most 0.1 °C/min.
- **Recent heating:** a heart-rate test in the last 10 minutes blocks the reading. For the same reason, the app asks to measure temperature before heart rate.
- **Absolute temperature:** above about 32 °C, the phone is too close to skin temperature, the gradient (and so the signal) is too small.
- **Power mode:**
  - Unplugged and plugged in at 100% are each steady, and each has its own calibration.
  - Actively charging is not steady, so it's blocked.

---

## Heart rate (fingertip camera)

### The physics: blood absorbs light

The Beer–Lambert law says light passing through a material is attenuated exponentially with the path length and the concentration of the absorber:

```text
I = I0 · e^(−ε · c · d)
```

Here `I0` is the light going in, `ε` is how strongly the material absorbs, `c` is its concentration, and `d` is the path length.

In a fingertip the main absorber is haemoglobin in the blood. With every heartbeat, a pressure wave pushes extra blood into the fingertip's arteries and capillaries, so `d` (the effective blood path) briefly grows and less light comes out. This is photoplethysmography (PPG), the same principle as a clinical pulse oximeter clip.

The detected light has two parts:

- **A large steady part (DC):** tissue, bone, venous blood.
- **A small pulsing part (AC):** arterial blood, around 1% of the total. The heart rate is the frequency of this AC part.

Red light penetrates tissue well, so with the torch on, the finger glows red, and the red channel carries the strongest pulse.

### The sensor: a camera as a light meter

We don't use the image, only its average brightness. Averaging thousands of pixels per frame cuts random sensor noise by roughly the square root of the pixel count, which is what makes a 1% signal visible.

A few camera behaviours matter:

- **Auto-exposure and auto-white-balance must be locked.** They are feedback controllers that adjust the image to keep brightness constant, which would partly cancel the very variation we're measuring.
- **Frame timing is irregular** on Android, so every frame keeps its hardware timestamp, and we resample onto an even grid before filtering.
- **30 fps is plenty.** By the Nyquist theorem, sampling at 30 Hz captures frequencies up to 15 Hz. Heart rates are 0.7–4 Hz (42–240 bpm).
- **Pressing too hard** squeezes blood out of the capillaries, so the pulse disappears.

### The computation

1. **Average the red channel** per frame.
2. **Resample** onto an even 30 Hz grid.
3. **Detrend:** remove slow drift from finger movement and torch heating.
4. **Band-pass filter** at 0.7–4 Hz, zero-phase so peaks don't shift in time.
5. **Find the frequency** with an FFT and take the strongest peak. A 20 s window gives 0.05 Hz (3 bpm) frequency bins, so we interpolate around the peak.
6. **Cross-check** against the median time between detected peaks.
7. **Score quality** from the following. Low quality shows "retake" instead of a number.
   - how much the spectral peak stands out
   - whether the two estimates agree
   - accelerometer motion

---

## Steps

An accelerometer is a microscopic mass on silicon springs (MEMS, micro-electro-mechanical systems). When the phone accelerates, inertia makes the mass lag behind, and the change in capacitance between the mass and fixed plates measures the displacement.

Walking produces a periodic vertical acceleration of about 1–2 Hz, one peak per footstep. Modern phones run step detection on a low-power sensor hub, a small separate processor. That is why counting continues while the app is closed and the main processor sleeps, at almost no battery cost.

- **Step detector:** an event per step, used for floors (below).
- **Step counter:** a running total since boot. It resets when the phone reboots, so we store the change between readings rather than the raw total.

---

## Floors climbed

### The physics: air gets thinner with height

Air pressure at any height is the weight of the air above it. Going up, there's less air overhead:

```text
dP = −ρ · g · dh
```

Near sea level this is about **0.12 hPa per metre**, so one 3 m floor is about 0.36 hPa, a 0.04% change.

The standard-atmosphere formula converts pressure to altitude:

```text
h = 44330 · (1 − (P / P0)^(1/5.255))   metres
```

Here `P0` is sea-level pressure. It changes with the weather, and we don't know it. But we only need **changes** in height, and over a minute of stair climbing `P0` barely moves, so it cancels out.

### The sensor: a MEMS barometer

A thin silicon membrane is sealed over a vacuum cavity. Outside pressure bends the membrane, and piezoresistors (resistors that change value when strained) measure the bend. The sensor resolves about 0.01 hPa, roughly 10 cm of height. Its absolute accuracy is poor (±1 hPa or more), which is another reason to use only relative changes.

### The computation

1. **Median filter.** This removes brief pressure spikes from doors, HVAC, wind, and in sealed phones like Pixels, even screen taps and the speaker (the Hafez 2020 paper).
2. **Kalman filter** with state [height, vertical speed]. It blends a motion model with noisy measurements, weighting each by its expected noise. The result is a smooth height trace that still responds quickly to real climbs.
3. **Floor rule:** a sustained height change of 2.5 m or more in under 60 s counts as a floor.
4. **Stairs or elevator:** an elevator changes height without steps, so the step detector tells them apart.
5. **Weather:** the weather moves pressure a few tenths of a hPa per hour, a few metres of fake height. So only short windows count.

---

## HRV from the accelerometer (experimental)

Each heartbeat ejects about 70 ml of blood upward into the aorta. By Newton's third law, the body recoils the other way. This is ballistocardiography (BCG). Lying still with the phone on your belly, the accelerometer picks up this recoil as a tiny repeating waveform, one per beat.

1. **Band-pass** 1–25 Hz. This removes breathing (about 0.3 Hz) and keeps the sharp recoil.
2. **Template matching.** Take a 400 ms snippet around a clear beat, then cross-correlate it along the signal: slide it over and measure similarity at each position. The correlation peaks mark each beat.
3. **Beat-to-beat (J–J) intervals** give heart-rate variability:
   - **SDNN:** the standard deviation of the intervals.
   - **RMSSD:** the root mean square of successive differences. It reflects fast, parasympathetic ("rest and digest") control, and drops under stress.

At 100 Hz sampling, each beat time is only known to 10 ms, so the sampling rate directly limits HRV precision.

---

## SpO2 (experimental)

Oxygenated and deoxygenated haemoglobin absorb red and infrared light differently. A pulse oximeter compares the pulsing (AC) part relative to the steady (DC) part at two wavelengths:

```text
R = (AC_red / DC_red) / (AC_ir / DC_ir)        SpO2 ≈ a − b·R
```

Dividing AC by DC cancels skin tone, finger thickness and light brightness, leaving mostly the blood's oxygen-dependent colour.

Phones have no infrared light, so we use the camera's red and green channels as two stand-in "wavelengths". The white torch is broadband, and camera colour filters overlap, so the two channels are far less separated than real oximeter LEDs. That's why this stays labelled experimental: the constants `a` and `b` need fitting against a real oximeter, per phone.

---

## The health assistant (CS principles)

- **Tool use:**
  - The language model never sees the database. It asks for specific data through tools (`get_readings`, `get_journal`, `get_baseline`) that run on the phone.
  - Only the tool results leave the device.
  - It must finish with a structured `submit_feedback` call, so the app gets fields it can validate, not free text.
- **Deterministic safety first.**
  - Red-flag rules run as plain code before the model, and override it. Examples: temperature at or above 38 °C, resting heart rate above 120 or below 40, emergency keywords in a journal entry.
  - The model can explain, but it can't talk the app out of a safety notice.
- **Change-point detection (CUSUM).** To decide when to speak up unprompted, the app keeps a running sum of deviations from the person's own 14-day baseline. It fires when the sum crosses a threshold. A cumulative sum catches a small sustained shift that no single day would reveal, and ignores one-off noise.
- **Quality gating.** Every reading carries a 0–1 quality score. Low-quality readings are stored, but never shown big, and never treated as valid by the agent. A wrong number presented confidently is worse than no number.
- **Provenance.** Every reading records the algorithm version (`method`), phone model and session. Any number the assistant cites traces back to the raw samples that produced it.

---

## Stretch: lung function from sound

- **SpiroSmart (forced exhale):**
  - The sound of a hard exhale at the phone is turbulent airflow at the lips.
  - Sound pressure relates to flow (flow ∝ √pressure, from Bernoulli's principle), after undoing how sound spreads between the mouth and the microphone.
  - Features of the loudness envelope then estimate peak flow and exhaled volume, using a model trained against a spirometer.
- **Sustained vowel ("Aaaa…"):**
  - How long and how steadily you can sustain a vowel on one breath reflects lung capacity and airway obstruction.
  - Audio features (duration, energy decay, spectral features) feed a regression trained against spirometry.
- **Microphone processing must be off.** Phones normally apply automatic gain control and noise suppression, which would flatten exactly the loudness changes these methods rely on. Both methods need the microphone's unprocessed audio source.
