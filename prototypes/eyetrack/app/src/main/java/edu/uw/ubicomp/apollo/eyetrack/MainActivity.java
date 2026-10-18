package edu.uw.ubicomp.apollo.eyetrack;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live preview: front camera -> FaceTracker -> EyeMetrics, drawn on screen and logged one row per frame to
 * /sdcard/Android/data/edu.uw.ubicomp.apollo.eyetrack/files/eyetrack_<stamp>.csv.
 * The button hands the camera to EyeTrackingService for passive tracking (the camera is exclusive, so the
 * preview stops while the service runs) and shows the service's progress instead.
 */
public class MainActivity extends ComponentActivity {
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final EyeMetrics metrics = new EyeMetrics();
    private OverlayView overlay;
    private Button button;
    private FaceTracker tracker;
    private ProcessCameraProvider provider;
    private PrintWriter log;
    private long lastFrameT = -1;
    private double fps;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        overlay = new OverlayView(this);
        button = new Button(this);
        button.setOnClickListener(v -> togglePassive());
        FrameLayout root = new FrameLayout(this);
        root.addView(overlay);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(32, 0, 0, 220);
        root.addView(button, lp);
        setContentView(root);

        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);   // without it the service still runs, but its notification is hidden
        }
        if (needed.isEmpty()) start();
        else requestPermissions(needed.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start();
        else overlay.message("Camera permission denied");
    }

    private void start() {
        try {
            tracker = new FaceTracker(this);
        } catch (RuntimeException e) {
            overlay.message("Could not load face_landmarker.task:", String.valueOf(e.getMessage()),
                    "Run prototypes/eyetrack/fetch_model.sh");
            return;
        }
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(this).get();
                refresh();
            } catch (Exception e) {
                overlay.message("Camera error:", String.valueOf(e.getMessage()));
            }
        }, getMainExecutor());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
    }

    /** Preview when the service isn't running; the service's status when it is. */
    private void refresh() {
        ui.removeCallbacksAndMessages(null);   // one polling loop at a time
        if (provider == null) return;
        if (PassiveState.running) {
            provider.unbindAll();
            button.setText("Stop passive tracking");
            showPassive();
        } else {
            button.setText("Start passive tracking");
            bindPreview();
        }
    }

    private void bindPreview() {
        if (provider == null || PassiveState.running) return;
        if (log == null) openLog();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, image -> onFrame(tracker.process(FaceTracker.upright(image), SystemClock.uptimeMillis())));
        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
    }

    private void togglePassive() {
        if (PassiveState.running) {
            startService(new Intent(this, EyeTrackingService.class).setAction(EyeTrackingService.ACTION_STOP));
            ui.postDelayed(this::refresh, 500);
        } else {
            if (provider != null) provider.unbindAll();   // the camera is exclusive: release it before the service opens it
            // Profiling: `am start ... --es delegate GPU` launches the app so the button starts the service on the GPU
            ContextCompat.startForegroundService(this, new Intent(this, EyeTrackingService.class)
                    .putExtra(EyeTrackingService.EXTRA_DELEGATE, getIntent().getStringExtra(EyeTrackingService.EXTRA_DELEGATE)));
            button.setText("Stop passive tracking");
            ui.removeCallbacksAndMessages(null);
            ui.postDelayed(this::showPassive, 300);
        }
    }

    /** Polls PassiveState once a second while the service runs. */
    private void showPassive() {
        if (!PassiveState.running) {
            overlay.message("Passive tracking: " + PassiveState.status);
            ui.postDelayed(this::refresh, 1000);
            return;
        }
        long mins = (System.currentTimeMillis() - PassiveState.startedAtMs) / 60_000;
        double faceMin = PassiveState.faceSeconds / 60;
        overlay.message(
                "PASSIVE: " + PassiveState.status,
                String.format(Locale.US, "running %d min, face seen %.1f min", mins, faceMin),
                String.format(Locale.US, "%.0f fps", PassiveState.fps),
                String.format(Locale.US, "blinks %d  (%.0f / face-min)", PassiveState.blinks, faceMin > 0.05 ? PassiveState.blinks / faceMin : 0),
                String.format(Locale.US, "gaze shifts %d  (%.0f / face-min)", PassiveState.shifts, faceMin > 0.05 ? PassiveState.shifts / faceMin : 0),
                "Switch apps freely; stop here or from the notification.",
                "Per-minute log: " + new File(PassiveState.logPath).getName());
        ui.postDelayed(this::showPassive, 1000);
    }

    /** Analysis thread, preview mode only. */
    private void onFrame(FaceTracker.Frame f) {
        long t = SystemClock.uptimeMillis();
        if (lastFrameT > 0) fps += 0.1 * (1000.0 / Math.max(1, t - lastFrameT) - fps);
        lastFrameT = t;
        if (f.hasFace()) metrics.update(t, f.xs, f.ys, f.blink);
        else metrics.noFace();
        writeLog(t, f.hasFace(), f.blink);
        overlay.show(f.image, f.hasFace() ? f.xs : null, f.hasFace() ? f.ys : null, new String[]{
                String.format(Locale.US, "%.0f fps   %dx%d   %s", fps, f.image.getWidth(), f.image.getHeight(), f.hasFace() ? "face" : "NO FACE"),
                String.format(Locale.US, "gaze x %.2f  y %+.2f   %s", metrics.gazeX, metrics.gazeY, metrics.eyesClosed ? "CLOSED" : "open"),
                String.format(Locale.US, "blinks %d   (%.0f / min)", metrics.blinkCount, metrics.blinksPerMinute(t)),
                String.format(Locale.US, "gaze shifts %d   (%.0f / min)", metrics.shiftCount, metrics.shiftsPerMinute(t)),
        }, metrics.gazeX, metrics.gazeY, metrics.eyesClosed);
    }

    private void openLog() {
        try {
            File f = new File(getExternalFilesDir(null), "eyetrack_" + System.currentTimeMillis() + ".csv");
            log = new PrintWriter(new FileWriter(f));
            log.println("t_ms,face,gaze_x,gaze_y,blink_score,closed,blink_event,shift_event");
        } catch (IOException e) {
            overlay.message("Could not open log:", String.valueOf(e.getMessage()));
        }
    }

    private void writeLog(long t, boolean face, double blink) {
        if (log == null) return;
        log.println(String.format(Locale.US, "%d,%d,%.4f,%.4f,%.3f,%d,%d,%d", t, face ? 1 : 0, metrics.gazeX, metrics.gazeY,
                blink, metrics.eyesClosed ? 1 : 0, metrics.blinkEvent ? 1 : 0, metrics.shiftEvent ? 1 : 0));
        if (metrics.blinkEvent || metrics.shiftEvent || t % 1000 < 40) log.flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        analysisExecutor.shutdown();
        if (tracker != null) tracker.close();
        if (log != null) log.close();
    }
}
