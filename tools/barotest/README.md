# BaroTest: blow strength from the barometer

A forced blow at the screen from 2–3 cm flexes the glass of a sealed (IP68) phone and briefly raises the pressure inside it. The phone's barometer sees that as a pulse. **The pulse is repeatable for one person but doesn't give peak flow in L/min across people**, so the feature built on it is **blow strength against your own baseline**.

| | |
| --- | --- |
| Logger | `BaroLogActivity` records `Sensor.TYPE_PRESSURE` at the fastest rate into `<name>.csv` (`t_ns,hpa`), and shows the live change in Pa |
| Build | `tools/barotest/build.sh --install` (no Gradle; Git Bash on Windows) |
| Record | `adb shell am start -n edu.uw.ubicomp.barotest/.BaroLogActivity --ei seconds 60 --es name cal_P1`, then pull `/sdcard/Android/data/edu.uw.ubicomp.barotest/files/cal_P1.csv` **after** the run ends. The file is buffered, so a mid-run pull is truncated |
| Analyse | `python3 analyze_baro.py events <csv>...` and `python3 analyze_baro.py fit <meter.csv> <cal_*.csv>...` |
| Data | `tools/barotest/out/` is gitignored. It holds health data from volunteers: participant IDs only, never commit it |

## What we measured (2026-10-17, Pixel 5)

**Sensor:** Bosch BMP380. Android delivers at most **25 Hz** (`maxRate=25.00Hz`). Noise is about **1.6 Pa** sample to sample, and drift is a few Pa per minute.

| Test | Result |
| --- | --- |
| Hard finger press on the screen | **+1,232 Pa** for as long as the press lasts. Touching or holding the phone swamps everything, so it must lie flat and untouched |
| Blows from ~10 cm, phone flat | 7–12 Pa, one at 22 Pa. Soft, medium and maximal overlapped. **Too far** |
| Blows from 2–3 cm at the screen centre (one person) | Soft ~11 Pa, medium ~16 Pa, **maximal ~55 Pa**: a clear dose response, 25–40× the noise |
| Each blow | Followed by a −17 to −24 Pa rebound (the glass springing back). Ignore it; use the positive peak |
| Calibration, 4 people × 4 maximal blows, finger-width spacer | See below |

**Calibration against a peak-flow meter** (meter value = mean of each person's top 3 of 8 meter blows):

| | Meter | Barometer peaks | Top-3 mean | Spread |
| --- | --- | --- | --- | --- |
| P1 | 538 L/min | 74, 81, 85, 90 Pa | 85 Pa | 7% |
| P2 | 460 L/min | 33, 42, 42, 46 Pa (first 4 of the run, by team decision; a touch at 32 s) | 44 Pa | 12% |
| P3 | 337 L/min | 9, 14, 14, 19 Pa | 16 Pa | 26% |
| P4 | 283 L/min | 31, 33, 43, 62 Pa | 46 Pa | 52% |

- **Fit:** linear r = 0.72, leave-one-out MAE **115 L/min**. Square-root fit: r = 0.68, 130 L/min. **That's not usable for absolute peak flow.**
- **P1–P3 rank correctly, but P4 (the lowest meter reading) reads like P2.**
- **Likely cause: mouth opening.** The jet's push on the glass goes as **flow² ÷ opening area**, so a smaller or varying opening reads high. A mouthpiece would fix the area, but the team chose not to add components.
- **What works is within-person repeatability:** P1's peaks vary ±7%, about what the meter's ±5% flow spread predicts once squared. **That is the basis for the feature.**
- **Caveat: four people, one session, one phone.** Repeatability across days is not yet shown.

## Feature spec: "Blow strength" (for `:sensing:motion` or its own module)

**What it is:** a guided 30–45 s test that reports blow strength in Pa and **how it compares with your own baseline**. It is not a lung-function measure. Never show L/min or claim peak flow, and the assistant treats it as a trend signal, not a diagnosis.

**Protocol the UI coaches:**

1. Phone **flat on a table, screen up**, no case pressing on the screen, **hands off**.
2. **Spacer:** one finger laid flat on the screen; put your lips just above it, then lift the finger before blowing. That gives 2–3 cm. The app shows a target circle at the screen centre.
3. **Round "O" mouth, about the size of a finger.** Keep it the same every time, since opening area is the biggest confound.
4. Full breath in, then a maximal blast. **3–5 blows, 8–10 s apart**, with a countdown and a "blow now" cue. The app shows each blow's result as it lands.

**Prep checks** (`PrepCheck`):

- **Flat and still:** gravity on the accelerometer's z axis, at least 9.6 m/s², with low variance over 2 s.
- **Barometer present.**
- **Phone model is sealed:** Pixel 4, 5 and 7 are all IP68. Warn on unknown models.

**Signal pipeline** (pure Kotlin, port of `analyze_baro.py`):

1. Register `TYPE_PRESSURE` at `SENSOR_DELAY_FASTEST` and keep the event timestamps. Expect about 25 Hz.
2. **Baseline:** 4 s running median. **Deviation** = pressure − baseline, in Pa.
3. **Event:** deviation above 6 Pa (about 4× noise). Merge samples less than 0.5 s apart. The **feature** is the event's positive peak; the area under it is logged too, since it was even steadier for P1 (±3%).
4. **Reject touches:** an event above 500 Pa, or lasting more than 1.5 s, is a touch or press. Show "don't touch the phone" and discard it.
5. **Session value:** mean of the **top 3** peaks. Use no absolute floor: a weak-lunged person's maximal blow can be small.
6. **Quality:** fewer than 3 events → retake. Spread of the top 3 below 15% → good; 15–30% → OK; above 30% → "inconsistent, retake".

**Output:**

- `Reading(metric = "blow_strength_pa", unit = "Pa", method = "baro.blow.v1", quality, deviceModel)`.
- **Baseline:** median of the person's last 5 good sessions **on the same phone model**. Absolute Pa depend on the phone's glass and barometer, so values don't compare across models.
- **Before 3 sessions exist,** show "building your baseline".
- **After that,** show today as a percentage of baseline, with the person's own normal band. The design doc's CUSUM trigger can watch it like any other daily metric.

**Not yet verified:**

- **Pixel 4 and Pixel 7:** check the barometer rate and noise (`dumpsys sensorservice | grep -A2 Pressure`) and repeat the dose-response test.
- **Day-to-day repeatability:** the same person on 3+ days.
- **Whether a screen protector or case changes the response.**
