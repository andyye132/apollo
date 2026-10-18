package edu.uw.ubicomp.apollo.eyetrack;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.hardware.camera2.CaptureRequest;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import com.google.mediapipe.tasks.core.Delegate;

import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Passive eye tracking: a camera foreground service. It must be started while the app is visible (Android
 * refuses camera foreground services started from the background), shows a persistent notification, and the
 * system shows the camera-in-use indicator. It runs the front camera at a fixed ~15 fps, pauses while the screen
 * is off, yields to other apps that take the camera, and writes one summary row per minute. No images are stored.
 */
public class EyeTrackingService extends LifecycleService {
    static final String ACTION_STOP = "edu.uw.ubicomp.apollo.eyetrack.STOP";
    /** Optional start extra: "CPU" (default) or "GPU", for profiling the MediaPipe delegate. */
    static final String EXTRA_DELEGATE = "delegate";
    private static final String TAG = "EyeTrackingService";
    // DEFAULT importance (silent), not LOW: on Android 14 a LOW notification sits collapsed in the "Silent" section with
    // no status-bar icon, and testers on a Pixel 5 couldn't find it. The id changed because a channel's importance is
    // fixed once created.
    private static final String CHANNEL = "eye_tracking_visible";
    private static final int NOTIFICATION_ID = 1;
    private static final int TARGET_FPS = 15;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private Camera camera;
    private FaceTracker tracker;
    private EyeMetrics metrics;
    private PrintWriter summary;
    private boolean screenOn = true, cameraOpen;
    private volatile String status = "starting";

    // Current one-minute bucket (analysis thread, except pausedMs which is touched on the main thread)
    private long minuteStart, frames, faceMs, lastFrameT = -1;
    private int minuteBlinks0, minuteShifts0;
    private long pausedMs, pausedSince = -1;   // guarded by this

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (screenOn) bindCamera();
            else unbindCamera();
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (PassiveState.running) return START_NOT_STICKY;

        // Foreground first, within the 5 s limit, before touching the camera.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (RuntimeException e) {   // SecurityException / ForegroundServiceStartNotAllowedException
            Log.e(TAG, "could not start camera foreground service", e);
            PassiveState.status = "could not start: " + e.getClass().getSimpleName();
            stopSelf();
            return START_NOT_STICKY;
        }

        PassiveState.running = true;
        PassiveState.startedAtMs = System.currentTimeMillis();
        PassiveState.blinks = PassiveState.shifts = 0;
        PassiveState.faceSeconds = PassiveState.pausedSeconds = 0;
        metrics = new EyeMetrics(TARGET_FPS);
        try {
            Delegate delegate = intent != null && "GPU".equalsIgnoreCase(intent.getStringExtra(EXTRA_DELEGATE)) ? Delegate.GPU : Delegate.CPU;
            tracker = new FaceTracker(this, delegate);
            Log.i(TAG, "MediaPipe delegate " + delegate);
        } catch (RuntimeException e) {
            PassiveState.status = "model missing: run fetch_model.sh";
            stopSelf();
            return START_NOT_STICKY;
        }
        openSummary();
        minuteStart = System.currentTimeMillis();

        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        screenOn = getSystemService(PowerManager.class).isInteractive();

        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(this).get();
                analysis = buildAnalysis();
                if (screenOn) bindCamera();
                else unbindCamera();
            } catch (Exception e) {
                setStatus("camera error: " + e.getMessage());
            }
        }, getMainExecutor());
        return START_NOT_STICKY;   // a sticky restart would come from the background, which Android refuses
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private ImageAnalysis buildAnalysis() {
        ImageAnalysis.Builder b = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        // 480x360, not the preview's 640x480: in the background the service ran at ~9 fps on a
                        // Pixel 5 (likely scheduled on slower cores), below the ~12 fps blinks need
                        .setResolutionStrategy(new ResolutionStrategy(new Size(480, 360),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setTargetRotation(Surface.ROTATION_0)   // no display in a service; frames are rotated by rotationDegrees
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
        Range<Integer> fps = FaceTracker.passiveFpsRange(provider, TARGET_FPS);
        if (fps != null) {
            new Camera2Interop.Extender<>(b).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            Log.i(TAG, "front camera fps range " + fps);
        }
        ImageAnalysis a = b.build();
        a.setAnalyzer(analysisExecutor, image -> {
            long camT = image.getImageInfo().getTimestamp();   // sensor timestamp, ns
            long t0 = System.nanoTime();
            Bitmap upright = FaceTracker.upright(image);
            profile(camT, (System.nanoTime() - t0) / 1e6);
            analyze(upright);
        });
        return a;
    }

    private void bindCamera() {
        if (provider == null || analysis == null || !screenOn) return;
        try {
            if (!provider.isBound(analysis)) {
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
                camera.getCameraInfo().getCameraState().removeObservers(this);   // rebinding after screen-off reuses the camera
                camera.getCameraInfo().getCameraState().observe(this, this::onCameraState);
            }
        } catch (RuntimeException e) {
            setStatus("camera error: " + e.getMessage());
        }
    }

    private void unbindCamera() {
        if (provider != null && analysis != null) provider.unbind(analysis);
        cameraOpen = false;
        setStatus(screenOn ? "paused" : "paused: screen off");
    }

    private void onCameraState(CameraState state) {
        CameraState.StateError err = state.getError();
        cameraOpen = state.getType() == CameraState.Type.OPEN;
        if (err != null && (err.getCode() == CameraState.ERROR_CAMERA_IN_USE || err.getCode() == CameraState.ERROR_MAX_CAMERAS_IN_USE)) {
            setStatus("paused: camera in use by another app");   // CameraX reopens by itself once it's free
        } else if (err != null) {
            setStatus("camera error " + err.getCode());
        } else if (cameraOpen) {
            setStatus("tracking");
        } else if (state.getType() == CameraState.Type.PENDING_OPEN) {
            setStatus("waiting for camera");
        }
        Log.i(TAG, "camera state " + state.getType() + (err != null ? " error " + err.getCode() : ""));
    }

    /** Main thread. Tracks paused time and keeps the notification and PassiveState in sync. */
    private void setStatus(String s) {
        synchronized (this) {   // pausedMs/pausedSince are also reset by writeMinute on the analysis thread
            long now = SystemClock.elapsedRealtime();
            boolean paused = !s.equals("tracking");
            if (paused && pausedSince < 0) pausedSince = now;
            if (!paused && pausedSince >= 0) {
                pausedMs += now - pausedSince;
                pausedSince = -1;
            }
        }
        if (s.equals(status)) return;
        status = s;
        PassiveState.status = s;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(s));
    }

    // Profiling (analysis thread): every 5 s, log how fast frames arrive and where the time goes.
    private long profStart = -1, profFrames, profFace, lastCamT = -1;
    private double profConvMs, profDetectMs, profDetectFaceMs, profCamGapMs;

    private void profile(long camT, double convMs) {
        if (lastCamT > 0) profCamGapMs += (camT - lastCamT) / 1e6;
        lastCamT = camT;
        profConvMs += convMs;
    }

    private void profileDetect(boolean face) {
        long now = SystemClock.uptimeMillis();
        if (profStart < 0) profStart = now;
        profFrames++;
        profDetectMs += tracker.lastDetectMs;
        if (face) {
            profFace++;
            profDetectFaceMs += tracker.lastDetectMs;
        }
        if (now - profStart >= 5000 && profFrames > 1) {
            Log.i(TAG, String.format(Locale.US,
                    "profile: %.1f fps processed, camera gap %.0f ms (%.1f fps between delivered frames), convert %.1f ms, "
                            + "detect %.1f ms (with face %.1f ms, %d/%d frames with face)",
                    profFrames * 1000.0 / (now - profStart), profCamGapMs / (profFrames - 1), 1000.0 * (profFrames - 1) / profCamGapMs,
                    profConvMs / profFrames, profDetectMs / profFrames, profFace > 0 ? profDetectFaceMs / profFace : Double.NaN, profFace, profFrames));
            profStart = now;
            profFrames = profFace = 0;
            profConvMs = profDetectMs = profDetectFaceMs = profCamGapMs = 0;
        }
    }

    /** Analysis thread. */
    private void analyze(Bitmap upright) {
        long t = SystemClock.uptimeMillis();
        FaceTracker.Frame f = tracker.process(upright, t);
        profileDetect(f.hasFace());
        if (lastFrameT > 0) {
            long dt = Math.min(t - lastFrameT, 500);   // cap gaps (pauses) so they don't count as face time
            if (f.hasFace()) {
                faceMs += dt;
                PassiveState.faceSeconds += dt / 1000.0;
            }
            PassiveState.fps += 0.05 * (1000.0 / Math.max(1, t - lastFrameT) - PassiveState.fps);
        }
        lastFrameT = t;
        frames++;
        if (f.hasFace()) metrics.update(t, f.xs, f.ys, f.blink);
        else metrics.noFace();
        PassiveState.blinks = metrics.blinkCount;
        PassiveState.shifts = metrics.shiftCount;
        long now = System.currentTimeMillis();
        if (now - minuteStart >= 60_000) writeMinute(now);
    }

    private void openSummary() {
        try {
            File file = new File(getExternalFilesDir(null), "eyetrack_passive_" + System.currentTimeMillis() + ".csv");
            summary = new PrintWriter(new FileWriter(file));
            summary.println("minute_start_ms,minute_end_ms,frames,face_s,paused_s,blinks,gaze_shifts,blinks_per_face_min,shifts_per_face_min,status");
            summary.flush();
            PassiveState.logPath = file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "could not open summary log", e);
        }
    }

    /** One row per minute. Rates are per minute of face time, so time away from the phone doesn't dilute them. */
    private synchronized void writeMinute(long now) {
        long paused = pausedMs + (pausedSince >= 0 ? SystemClock.elapsedRealtime() - pausedSince : 0);
        int blinks = metrics.blinkCount - minuteBlinks0, shifts = metrics.shiftCount - minuteShifts0;
        double faceS = faceMs / 1000.0;
        if (summary != null) {
            summary.println(String.format(Locale.US, "%d,%d,%d,%.1f,%.1f,%d,%d,%.1f,%.1f,%s", minuteStart, now, frames, faceS,
                    paused / 1000.0, blinks, shifts, faceS > 1 ? blinks * 60 / faceS : Double.NaN,
                    faceS > 1 ? shifts * 60 / faceS : Double.NaN, status));
            summary.flush();
        }
        PassiveState.pausedSeconds += paused / 1000.0;
        minuteStart = now;
        frames = faceMs = 0;
        pausedMs = 0;
        if (pausedSince >= 0) pausedSince = SystemClock.elapsedRealtime();
        minuteBlinks0 = metrics.blinkCount;
        minuteShifts0 = metrics.shiftCount;
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
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Eye tracking: " + text)
                .setContentText("Counting blinks and eye movement. Nothing leaves this phone.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    @Override
    public void onDestroy() {
        if (PassiveState.running) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            if (provider != null) provider.unbindAll();
            analysisExecutor.shutdown();
            try {
                analysisExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            writeMinute(System.currentTimeMillis());   // partial last minute
            if (summary != null) summary.close();
            if (tracker != null) tracker.close();
        }
        PassiveState.running = false;
        PassiveState.status = "stopped";
        super.onDestroy();
    }
}
