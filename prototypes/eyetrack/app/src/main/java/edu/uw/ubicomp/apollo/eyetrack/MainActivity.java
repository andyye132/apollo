package edu.uw.ubicomp.apollo.eyetrack;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.WindowManager;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapExtractor;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Front camera -> MediaPipe Face Landmarker (iris landmarks + blendshapes) -> EyeMetrics, drawn live and logged
 * one row per frame to /sdcard/Android/data/edu.uw.ubicomp.apollo.eyetrack/files/eyetrack_<stamp>.csv.
 */
public class MainActivity extends ComponentActivity {
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final EyeMetrics metrics = new EyeMetrics();
    private OverlayView overlay;
    private FaceLandmarker landmarker;
    private PrintWriter log;
    private long lastFrameT = -1;
    private double fps;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        overlay = new OverlayView(this);
        setContentView(overlay);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else overlay.message("Camera permission denied");
    }

    private void start() {
        try {
            landmarker = FaceLandmarker.createFromOptions(this, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                            .setModelAssetPath("face_landmarker.task")
                            .setDelegate(Delegate.CPU)
                            .build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setResultListener(this::onResult)
                    .setErrorListener(e -> overlay.message("MediaPipe error:", String.valueOf(e.getMessage())))
                    .build());
        } catch (RuntimeException e) {
            overlay.message("Could not load face_landmarker.task:", String.valueOf(e.getMessage()),
                    "Run prototypes/eyetrack/fetch_model.sh");
            return;
        }
        openLog();

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                .build())
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
            } catch (Exception e) {
                overlay.message("Camera error:", String.valueOf(e.getMessage()));
            }
        }, getMainExecutor());
    }

    /** Camera thread: rotate upright and mirror (selfie view), then hand to MediaPipe. */
    private void analyze(ImageProxy image) {
        Bitmap raw = image.toBitmap();
        Matrix m = new Matrix();
        m.postRotate(image.getImageInfo().getRotationDegrees());
        m.postScale(-1, 1);
        image.close();
        Bitmap upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
        landmarker.detectAsync(new BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis());
    }

    /** MediaPipe thread: one result per processed frame, with the frame it came from. */
    private void onResult(FaceLandmarkerResult result, MPImage input) {
        long t = result.timestampMs();
        if (lastFrameT > 0) fps += 0.1 * (1000.0 / Math.max(1, t - lastFrameT) - fps);
        lastFrameT = t;
        Bitmap frame = BitmapExtractor.extract(input);
        int w = frame.getWidth(), h = frame.getHeight();

        float[] xs = null, ys = null;
        double blink = Double.NaN;
        if (!result.faceLandmarks().isEmpty()) {
            List<NormalizedLandmark> lm = result.faceLandmarks().get(0);
            xs = new float[lm.size()];
            ys = new float[lm.size()];
            for (int i = 0; i < lm.size(); i++) {
                xs[i] = lm.get(i).x() * w;
                ys[i] = lm.get(i).y() * h;
            }
            if (result.faceBlendshapes().isPresent() && !result.faceBlendshapes().get().isEmpty()) {
                double l = 0, r = 0;
                for (Category c : result.faceBlendshapes().get().get(0)) {
                    if (c.categoryName().equals("eyeBlinkLeft")) l = c.score();
                    else if (c.categoryName().equals("eyeBlinkRight")) r = c.score();
                }
                blink = Math.max(l, r);
            }
        }
        boolean face = xs != null && xs.length > EyeMetrics.L_IRIS;
        if (face) metrics.update(t, xs, ys, blink);
        else metrics.noFace();

        writeLog(t, face, blink);
        overlay.show(frame, face ? xs : null, face ? ys : null, new String[]{
                String.format(Locale.US, "%.0f fps   %dx%d   %s", fps, w, h, face ? "face" : "NO FACE"),
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
        analysisExecutor.shutdown();
        if (landmarker != null) landmarker.close();
        if (log != null) log.close();
    }
}
