package edu.uw.ubicomp.apolloproto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Locale;

/**
 * Passive eye tracking (the All day measurement): a camera foreground service. It has to be started while the app is
 * visible (Android refuses camera services started from the background), shows a persistent notification, and the
 * system shows the green camera-in-use dot. It runs the front camera at a fixed ~15 fps, pauses while the screen is
 * off, yields to any app that takes the camera and reopens it when it is free, and writes one summary row per minute.
 * No image is ever stored or sent: each frame is turned into landmarks and dropped.
 *
 * Ported from prototypes/eyetrack (CameraX) to Camera2 so the prototype needs no AndroidX.
 */
public class EyeTrackingService extends Service {
    static final String ACTION_STOP = "edu.uw.ubicomp.apolloproto.EYE_STOP";
    private static final String TAG = "EyeTrackingService";
    // Default importance (silent), not low: on Android 14 a low notification hides in the collapsed "Silent" section
    // with no status-bar icon, and testers couldn't find it.
    private static final String CHANNEL = "eye_tracking_visible";
    private static final int NOTIFICATION_ID = 7;
    private static final int TARGET_FPS = 15;
    private static final int MIN_FACE_SECONDS = 20;            // a minute with less face time than this is not rated
    private static final double ELEVATED_MULTIPLIER = 1.5;     // shift rate vs this session's own recent average
    private static final int MIN_BASELINE_MINUTES = 3, BASELINE_WINDOW_MINUTES = 10;
    private static final double HIGH_BLINK_RATE = 28;          // blinks per minute; resting is roughly 15 to 20

    private HandlerThread thread;
    private Handler cam;                                       // every camera and analysis call runs here
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraManager manager;
    private String cameraId;
    private int sensorOrientation;
    private Range<Integer> fpsRange;
    private Size size;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean opening;
    private volatile boolean screenOn = true, stopped;
    private FaceTracker tracker;
    private EyeMetrics metrics;
    private PrintWriter summary, frameLog;                     // frameLog: per-frame gaze, first FRAME_LOG_S of a session
    private long frameRows;
    private static final int FRAME_LOG_S = 600;
    private int[] pixels;
    private String status = "";

    // The current one-minute bucket (camera thread)
    private long minuteStart, frames, faceMs, lastFrameT = -1, pausedMs, pausedSince = -1;
    private int minuteBlinks0, minuteShifts0;
    private final ArrayDeque<Double> recentShiftRates = new ArrayDeque<>();

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            cam.post(screenOn ? EyeTrackingService.this::openCamera : EyeTrackingService.this::pauseForScreen);
        }
    };

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            if (id.equals(cameraId)) cam.post(EyeTrackingService.this::openCamera);   // the other app let go
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Store.prefs(this).edit().putBoolean(EyeState.PREF_WANTED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (EyeState.running) return START_NOT_STICKY;

        // Foreground first, within the 5 s limit, before touching the camera.
        try {
            startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (RuntimeException e) {   // not allowed from the background, or the camera permission is off
            Log.e(TAG, "could not start the camera foreground service", e);
            EyeState.status = "Could not start: open Apollo and try again";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!EyeState.hasCamera(this)) {
            EyeState.status = "Camera permission is off";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            tracker = new FaceTracker(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "face model", e);
            EyeState.status = "Model missing: run fetch_eyetrack_libs.sh";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!pickCamera()) {
            EyeState.status = "No front camera";
            stopSelf();
            return START_NOT_STICKY;
        }

        EyeState.running = true;
        EyeState.startedAtMs = System.currentTimeMillis();
        EyeState.blinks = EyeState.shifts = 0;
        EyeState.blinkRate = EyeState.shiftRate = Double.NaN;
        EyeState.read = "";
        metrics = new EyeMetrics(TARGET_FPS);
        // The baseline carries over between sessions: the last rated minutes from the past week (EyeLog).
        recentShiftRates.clear();
        for (double v : EyeLog.recentShiftRates(EyeLog.load(Store.dir(this)), BASELINE_WINDOW_MINUTES,
                System.currentTimeMillis() - 7 * 86_400_000L)) recentShiftRates.addLast(v);
        openSummary();
        minuteStart = System.currentTimeMillis();

        thread = new HandlerThread("eye-tracking");
        thread.start();
        cam = new Handler(thread.getLooper());
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        screenOn = getSystemService(PowerManager.class).isInteractive();
        manager.registerAvailabilityCallback(availability, cam);
        setStatus(screenOn ? "Starting" : "Paused: screen off");
        if (screenOn) cam.post(this::openCamera);
        return START_NOT_STICKY;   // a sticky restart would come from the background, which Android refuses
    }

    // ---- Camera ---------------------------------------------------------------------------------

    /** The front camera, a ~480x360 YUV size, and the lowest fixed frame rate at or above TARGET_FPS. */
    private boolean pickCamera() {
        try {
            manager = getSystemService(CameraManager.class);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_FRONT || map == null) continue;
                Size best = null;
                for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                    boolean fourThree = s.getWidth() * 3 == s.getHeight() * 4;
                    if (fourThree && s.getWidth() >= 480 && (best == null || s.getWidth() < best.getWidth())) best = s;
                }
                if (best == null) continue;
                cameraId = id;
                size = best;
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                Range<Integer> fixed = null, lowest = null;
                if (ranges != null) {
                    for (Range<Integer> r : ranges) {
                        if (r.getLower().equals(r.getUpper()) && r.getUpper() >= TARGET_FPS
                                && (fixed == null || r.getUpper() < fixed.getUpper())) fixed = r;
                        if (lowest == null || r.getUpper() < lowest.getUpper()) lowest = r;
                    }
                }
                fpsRange = fixed != null ? fixed : lowest;
                Log.i(TAG, "front camera " + id + " " + size + " fps " + fpsRange + " sensor " + sensorOrientation);
                return true;
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "camera lookup", e);
        }
        return false;
    }

    private void openCamera() {
        if (stopped || !screenOn || device != null || opening) return;
        if (!EyeState.hasCamera(this)) {
            setStatus("Camera permission is off");
            return;
        }
        opening = true;
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    opening = false;
                    if (stopped || !screenOn) {
                        d.close();
                        return;
                    }
                    device = d;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {   // another app took the camera
                    opening = false;
                    d.close();
                    closeCamera();
                    setStatus("Paused: camera in use by another app");
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    opening = false;
                    d.close();
                    closeCamera();
                    boolean busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE;
                    setStatus(busy ? "Paused: camera in use by another app" : "Camera error " + error);
                    if (!busy) cam.postDelayed(EyeTrackingService.this::openCamera, 5000);
                }
            }, cam);
        } catch (CameraAccessException | SecurityException e) {
            opening = false;
            setStatus("Camera error: " + e.getMessage());
        }
    }

    private void startSession() {
        try {
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, cam);
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            CaptureRequest request = b.build();
            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (stopped || device == null) {
                        s.close();
                        return;
                    }
                    session = s;
                    try {
                        s.setRepeatingRequest(request, null, cam);
                        setStatus("Tracking");
                    } catch (CameraAccessException | IllegalStateException e) {
                        setStatus("Camera error: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    setStatus("Camera error: could not configure");
                    closeCamera();
                }
            }, cam);
        } catch (CameraAccessException | IllegalStateException e) {
            setStatus("Camera error: " + e.getMessage());
            closeCamera();
        }
    }

    private void pauseForScreen() {
        closeCamera();
        setStatus("Paused: screen off");
    }

    private void closeCamera() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    // ---- Analysis -------------------------------------------------------------------------------

    private void onImage(ImageReader r) {
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null || stopped) return;
            analyze(upright(image));
        } catch (IllegalStateException e) {
            // the reader closed under us (screen off or another app took the camera)
        } finally {
            if (image != null) image.close();
        }
    }

    /** YUV to ARGB, rotated upright and mirrored (selfie view) in one pass. */
    private Bitmap upright(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        boolean quarter = sensorOrientation % 180 != 0;
        int dw = quarter ? h : w, dh = quarter ? w : h;
        if (pixels == null || pixels.length != dw * dh) pixels = new int[dw * dh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int yy = yb.get(y * yRow + x) & 0xFF;
                int uvi = (y >> 1) * uvRow + (x >> 1) * uvPix;
                int u = (ub.get(uvi) & 0xFF) - 128, v = (vb.get(uvi) & 0xFF) - 128;
                int rr = clamp(yy + ((359 * v) >> 8)), gg = clamp(yy - ((88 * u + 183 * v) >> 8)), bb = clamp(yy + ((454 * u) >> 8));
                int dx, dy;
                switch (sensorOrientation) {
                    case 90: dx = h - 1 - y; dy = x; break;
                    case 180: dx = w - 1 - x; dy = h - 1 - y; break;
                    case 270: dx = y; dy = w - 1 - x; break;
                    default: dx = x; dy = y;
                }
                pixels[dy * dw + (dw - 1 - dx)] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        }
        return Bitmap.createBitmap(pixels, dw, dh, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private void analyze(Bitmap upright) {
        long t = SystemClock.uptimeMillis();
        FaceTracker.Frame f = tracker.process(upright, t);
        upright.recycle();
        if (lastFrameT > 0 && f.hasFace()) faceMs += Math.min(t - lastFrameT, 500);   // cap gaps so pauses don't count
        lastFrameT = t;
        frames++;
        if (f.hasFace()) metrics.update(t, f.xs, f.ys, f.blink);
        else metrics.noFace();
        EyeState.blinks = metrics.blinkCount;
        EyeState.shifts = metrics.shiftCount;
        if (frameLog != null && frameRows++ < FRAME_LOG_S * TARGET_FPS) {   // raw material for checking the detector
            frameLog.println(String.format(Locale.US, "%d,%d,%.4f,%.4f,%.3f,%d,%d,%.4f", System.currentTimeMillis(),
                    f.hasFace() ? 1 : 0, metrics.gazeX, metrics.gazeY, f.blink, metrics.shiftEvent ? 1 : 0, metrics.blinkEvent ? 1 : 0,
                    metrics.threshold()));
        }
        long now = System.currentTimeMillis();
        if (now - minuteStart >= 60_000) writeMinute(now);
    }

    // ---- Status, summaries, notification --------------------------------------------------------

    private void setStatus(String s) {
        cam.post(() -> {
            long now = SystemClock.elapsedRealtime();
            boolean paused = !s.equals("Tracking");
            if (paused && pausedSince < 0) pausedSince = now;
            if (!paused && pausedSince >= 0) {
                pausedMs += now - pausedSince;
                pausedSince = -1;
            }
        });
        if (s.equals(status)) return;
        status = s;
        EyeState.status = s;
        main.post(() -> {
            if (!stopped) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(s));
        });
    }

    private void openSummary() {
        try {
            long now = System.currentTimeMillis();
            File file = new File(Store.dir(this), EyeLog.PREFIX + now + ".csv");
            summary = new PrintWriter(new FileWriter(file));
            summary.println("minute_start_ms,minute_end_ms,frames,face_s,paused_s,blinks,gaze_shifts,blinks_per_face_min,shifts_per_face_min,read,shift_threshold,jitter");
            summary.flush();
            frameLog = new PrintWriter(new java.io.BufferedWriter(new FileWriter(new File(Store.dir(this), "eyetrack_frames_" + now + ".csv"))));
            frameLog.println("epoch_ms,face,gaze_x,gaze_y,blink_score,shift,blink,shift_threshold");
            frameRows = 0;
        } catch (IOException e) {
            Log.e(TAG, "could not open the summary log", e);
        }
    }

    /** One row per minute. Rates are per minute of face time, so time away from the phone doesn't dilute them. */
    private void writeMinute(long now) {
        long paused = pausedMs + (pausedSince >= 0 ? SystemClock.elapsedRealtime() - pausedSince : 0);
        int blinks = metrics.blinkCount - minuteBlinks0, shifts = metrics.shiftCount - minuteShifts0;
        double faceS = faceMs / 1000.0;
        double blinkRate = faceS > 1 ? blinks * 60 / faceS : Double.NaN, shiftRate = faceS > 1 ? shifts * 60 / faceS : Double.NaN;

        String read;
        if (faceS < MIN_FACE_SECONDS) {
            read = "Not enough face time";
        } else {
            Double baseline = recentShiftRates.size() >= MIN_BASELINE_MINUTES ? average(recentShiftRates) : null;
            recentShiftRates.addLast(shiftRate);
            while (recentShiftRates.size() > BASELINE_WINDOW_MINUTES) recentShiftRates.removeFirst();
            read = baseline == null ? "Learning your baseline"
                    : shiftRate > baseline * ELEVATED_MULTIPLIER || blinkRate >= HIGH_BLINK_RATE ? "Restless" : "Settled";
            EyeState.blinkRate = blinkRate;
            EyeState.shiftRate = shiftRate;
        }
        EyeState.faceSecondsLastMinute = faceS;
        EyeState.read = read;

        if (summary != null) {
            summary.println(String.format(Locale.US, "%d,%d,%d,%.1f,%.1f,%d,%d,%.1f,%.1f,%s,%.4f,%.4f", minuteStart, now, frames, faceS,
                    paused / 1000.0, blinks, shifts, blinkRate, shiftRate, read, metrics.threshold(), metrics.jitter()));
            summary.flush();
        }
        if (frameLog != null) frameLog.flush();
        EyeState.threshold = metrics.threshold();
        EyeState.jitter = metrics.jitter();
        minuteStart = now;
        frames = faceMs = pausedMs = 0;
        if (pausedSince >= 0) pausedSince = SystemClock.elapsedRealtime();
        minuteBlinks0 = metrics.blinkCount;
        minuteShifts0 = metrics.shiftCount;
    }

    private static double average(ArrayDeque<Double> v) {
        double s = 0;
        for (double d : v) s += d;
        return s / v.size();
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Eye tracking", NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, EyeTrackingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Eye tracking: " + text)
                .setContentText("Counting blinks and eye movement. Nothing leaves this phone.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", stop).build())
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public void onDestroy() {
        stopped = true;
        if (EyeState.running) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            manager.unregisterAvailabilityCallback(availability);
            if (thread != null) {
                cam.post(this::closeCamera);
                thread.quitSafely();
                try {
                    thread.join(1500);
                } catch (InterruptedException ignored) {
                }
            }
            writeMinute(System.currentTimeMillis());   // the partial last minute
            if (summary != null) summary.close();
            if (frameLog != null) frameLog.close();
            if (tracker != null) tracker.close();
        }
        EyeState.running = false;
        EyeState.status = "Off";
        super.onDestroy();
    }
}
