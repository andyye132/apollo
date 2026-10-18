# EyeTrack prototype

Front camera → MediaPipe Face Landmarker → blink rate and gaze-shift rate. This is a standalone Gradle project, kept outside the design doc's module layout so it doesn't pre-empt Phase 0. `EyeMetrics.java` has no Android types, so it can move into `:sensing:vision` unchanged.

| | |
| --- | --- |
| Status (2026-10-17) | **Foreground version works on the Pixel 7.** Not yet run on the Pixel 4. The passive (background) version is specified below, not built |
| Stack | Java, Views, Gradle 9.7.1, AGP 9.4.1, CameraX 1.6.2, MediaPipe tasks-vision 1.0.0 (all at least two weeks old when chosen), compileSdk/targetSdk 36, minSdk 30 |
| Model | `face_landmarker.task` (float16, Apache 2.0, 3.7 MB), downloaded by `fetch_model.sh` into `app/src/main/assets/`. Not committed |

## Build and run

```bash
cd prototypes/eyetrack
bash fetch_model.sh                     # once
./gradlew assembleDebug                 # Windows cmd: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant edu.uw.ubicomp.apollo.eyetrack android.permission.CAMERA
adb shell am start -n edu.uw.ubicomp.apollo.eyetrack/.MainActivity
```

**Setup:**

- **JDK:** use Android Studio's bundled one. On Windows, `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`. On a Mac, `/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- **`local.properties`:** needs `sdk.dir=...`. Android Studio writes it; it's gitignored.
- **First build:** it downloads the Android 36 platform automatically.

**Per-frame log:** `/sdcard/Android/data/edu.uw.ubicomp.apollo.eyetrack/files/eyetrack_<epoch_ms>.csv`. Columns:

- `t_ms` and `face`
- `gaze_x`, `gaze_y`
- `blink_score`, `closed`
- `blink_event`, `shift_event`

Pull it with `adb pull`. In Git Bash, prefix adb commands that take `/sdcard/...` paths with `MSYS_NO_PATHCONV=1`.

## What it measures (and what it can't)

- **Gaze:** the iris centre (landmarks 468 and 473) relative to the eye corners (33/133 and 362/263), in eye widths.
  - `x` runs 0 → 1 across the eye. `y` is the offset from the corner line.
  - It's projected on the corner line, so head roll doesn't leak in, and averaged across both eyes.
  - **It's relative only: there's no screen-point calibration.**
- **Blink:** MediaPipe's `eyeBlinkLeft/Right` blendshape (the larger of the two). It closes above 0.5 and opens below 0.3, and a blink counts on reopening.
- **Gaze shift:** the smoothed gaze moves more than 0.10 eye widths within 100 ms, with a 150 ms refractory period. History resets on a blink or a lost face.
- **Can't measure saccade speed.** The front camera is 30 fps at most (Pixel 7: AE ranges [15,15], [15,24], [15,30], [30,30]; no high-speed modes). A saccade is 20–200 ms, so it spans only 1–6 frames. We count shifts; we don't time them.

**First run (Pixel 7, 32 s):** 28.9 fps, a face in 96% of frames, gaze x 0.26–0.73, 5 blinks, 4 shifts.

## Known issues, in order of priority

1. **Looking down reads as half a blink.** The blink score sat at 0.2–0.5 for several seconds while looking down: lowered lids look half-closed to MediaPipe. **Fix:** detect a blink as a fast rise above a rolling baseline, for example the score exceeding the 2 s rolling median by 0.3 and then falling back within 400 ms, instead of fixed thresholds.
2. **The thresholds are guesses.** Tune them with a cued recording:
   - Add a mode that shows "look left / right / up / down / blink" prompts at known times.
   - Log the cue times in the CSV.
   - Report hits, misses and false alarms for blinks and shifts.
3. **Vertical gaze is weak.** The iris moves less vertically, and the lids cover it. Treat `y` as unreliable until it's measured.
4. **The APK is 67 MB** because MediaPipe ships native libraries for every ABI. Add `ndk { abiFilters += "arm64-v8a" }` (both Pixels are arm64) if size matters.

## Next: passive tracking as a camera foreground service (specified, not built)

**Goal:** keep counting blinks and gaze shifts while the user is in other apps, the way video-call apps keep the camera. On Android the only allowed way is a **foreground service of type `camera`**.

### Android rules (non-negotiable)

- **It must be started while our app is visible,** for example from a "Start passive tracking" button.
  - Starting a camera foreground service from the background (boot, alarms, WorkManager) throws, since Android 11 made camera a while-in-use permission. Catch `SecurityException` / `ForegroundServiceStartNotAllowedException` and show the error.
  - It does **not** restart after a reboot or after being killed. That's expected.
- **The notification is required:** a persistent one for the whole session. Android also shows the green camera-in-use dot. Neither can be hidden; don't try.
- **The camera is exclusive.** If the user opens Camera, a video call or another camera app, that app takes the camera. The service must pause and resume afterwards.
- **No images are ever stored or sent.** Only landmarks-derived numbers, and only on the phone (see Data provenance in `docs/DESIGN.md`).

### Manifest

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />   <!-- required from API 34; harmless on 33 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />          <!-- runtime permission from API 33 -->

<service
    android:name=".EyeTrackingService"
    android:foregroundServiceType="camera"
    android:exported="false" />
```

### Service design

- **Class:** `EyeTrackingService extends androidx.lifecycle.LifecycleService`. Add `androidx.lifecycle:lifecycle-service`, and pick a version at least two weeks old. CameraX binds to the service as its `LifecycleOwner`: `provider.bindToLifecycle(service, DEFAULT_FRONT_CAMERA, analysis)`.
- **Start:** the activity calls `ContextCompat.startForegroundService(...)`.
  - In `onStartCommand`, call `ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)` immediately, within the 5 s limit, **before** binding the camera.
  - Return `START_NOT_STICKY`, since a sticky restart would come from the background and fail.
- **Notification:**
  - Channel `eye_tracking` with low importance.
  - `setOngoing(true)`.
  - Text such as "Tracking blinks and eye movement. Camera stays on this phone".
  - A **Stop** action that sends `ACTION_STOP` to the service; tapping the notification opens the app.
  - Request `POST_NOTIFICATIONS` before starting. Without it the service still runs, but the notification is hidden in the drawer.
- **Rotation:** a service has no display, so set `ImageAnalysis.Builder().setTargetRotation(Surface.ROTATION_0)` and rotate with `imageInfo.rotationDegrees`, as the prototype already does. If the phone turns sideways, the frame stays correct; MediaPipe copes with a rotated face, within limits.
- **Frame rate: 15 fps,** half the prototype's CPU cost. Set it through `Camera2Interop.Extender(builder).setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, Range(15, 15))`.
  - Both Pixels need checking for support (see the cross-phone notes below).
  - Below about 12 fps, short blinks (about 100 ms) get missed, so don't go lower for blink rate.
- **Thresholds scale with frame rate.** At 15 fps a frame is 67 ms, so set `SHIFT_WINDOW_MS ≥ 2 frames` (about 150 ms). Make the `EyeMetrics` constants constructor parameters, not statics.
- **Resolution:** 320×240 or 480×360 is enough for iris landmarks at arm's length. Check that iris jitter doesn't rise on the recorded CSVs.
- **MediaPipe:** `RunningMode.VIDEO` with synchronous `detectForVideo` on the analysis thread is simpler than `LIVE_STREAM` at 15 fps. Keep `Delegate.CPU`; GPU delegates behave differently between the two Pixels' chips.
- **Losing the camera:**
  - Observe `camera.getCameraInfo().getCameraState()`.
  - On `ERROR_CAMERA_IN_USE` or `ERROR_MAX_CAMERAS_IN_USE`, mark the session paused and update the notification ("Paused: camera in use").
  - CameraX reopens on its own when the camera is free (`PENDING_OPEN` → `OPEN`). Log each pause and resume with its time.
- **Screen off:**
  - Register `ACTION_SCREEN_OFF` and `ACTION_SCREEN_ON` dynamically.
  - Unbind the analysis use case when the screen is off and rebind when it's on. With the screen off there's no face to see, and this is the biggest battery saving.
- **Output:**
  - Write **one summary row per minute**: blinks, shifts, seconds with a face, seconds paused.
  - Write per-frame rows only behind a developer setting.
  - In the real app these become `Reading`s through `HealthRepository` (`blink_rate_per_min`, `gaze_shift_rate_per_min`, plus face coverage as quality). A minute with less than about 20 s of face is low quality.
- **UI link:**
  - Prototype: a static `EyeTrackingState` holder that the activity polls.
  - Real app: the `:core` interfaces, with the service writing `Reading`s and the UI reading `HealthRepository`.

### Heat: don't break FeverPhone

Continuous camera and MediaPipe use warms the phone, and the **Pixel 4 is the FeverPhone phone**.

- **Mark eye tracking `heatsPhone = true`** in the sense of the design doc's "Measurement order and phone heat" section.
- **Pause around temperature readings:** while a temperature measurement is preparing or running, the service pauses its camera.
- **Prep check:** FeverPhone's prep check treats "passive eye tracking ran in the last 10 minutes" like a recent heart-rate test.
- **Prototype stand-in:** until the real app exists, simply don't run passive tracking on the Pixel 4 before a forehead test.

### Pixel 4 vs Pixel 7

| | Pixel 4 | Pixel 7 |
| --- | --- | --- |
| Android / API | 13 / 33 | 15 / 35 |
| `FOREGROUND_SERVICE_CAMERA` permission | Doesn't exist yet. Declaring it is harmless; the `camera` service type is still required | Required (API 34+) |
| Edge-to-edge | Not enforced, so system-bar insets may be zero (the overlay code handles both) | Enforced at targetSdk 35+; the overlay pads for the bars |
| Front camera | One RGB front camera plus face-unlock IR hardware that apps can't see. Check that `DEFAULT_FRONT_CAMERA` picks the RGB one | One front camera; AE ranges listed above, 30 fps max |
| CPU | Snapdragon 855, slower. Check that MediaPipe CPU keeps up at 15 fps | Tensor G2; 29–30 fps at 480×640 measured |
| Other duties | FeverPhone phone: heat conflict (above) | Lung tests, heart rate, motion |
| Status | **Not tested yet** | Foreground prototype works |

**Before calling it done, run this on both phones:**

1. `adb shell dumpsys media.camera | grep -A3 "aeAvailableTargetFpsRanges"`: check that `[15,15]` or `[15,30]` exists for the front camera, and pick the fps range from that.
2. Run the foreground prototype for 60 s. In the CSV, check fps, face percentage, and that the gaze range responds to looking left and right.
3. Start passive mode, then switch to another app. Check that the notification is up, the green dot shows, and per-minute rows keep coming.
4. Open the Camera app. The notification should say paused, and tracking should resume after closing Camera.
5. Turn the screen off. Tracking should stop and restart when the screen comes back on.
6. Tap Stop in the notification. The service should end and the dot should go.
7. `adb shell dumpsys activity services edu.uw.ubicomp.apollo.eyetrack` should list the service as foreground with type camera.
8. Battery: note the battery percentage and phone temperature (`dumpsys battery`, `dumpsys thermalservice`) over 30 minutes of passive tracking, and record the numbers here.

### Clean up test apps when you plug a phone in

```bash
adb uninstall edu.uw.ubicomp.mictest       # mic linearity / blow recorder (Pixel 4 and Pixel 7)
adb uninstall edu.uw.ubicomp.apollo.eyetrack   # only when done with this prototype
```
