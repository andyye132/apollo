package edu.uw.ubicomp.apollo.eyetrack;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.List;

/**
 * MediaPipe Face Landmarker in VIDEO mode: one synchronous call per frame on the caller's thread.
 * Shared by the live preview (MainActivity) and passive tracking (EyeTrackingService).
 */
final class FaceTracker implements AutoCloseable {
    /** Landmarks in pixels of the upright frame, or xs == null when no face was found. */
    static final class Frame {
        final Bitmap image;
        final float[] xs, ys;
        final double blink;    // larger of eyeBlinkLeft/Right, NaN if unavailable

        Frame(Bitmap image, float[] xs, float[] ys, double blink) {
            this.image = image;
            this.xs = xs;
            this.ys = ys;
            this.blink = blink;
        }

        boolean hasFace() { return xs != null && xs.length > EyeMetrics.L_IRIS; }
    }

    private final FaceLandmarker landmarker;
    private long lastT = Long.MIN_VALUE;
    /** Wall time of the last detectForVideo call, for profiling. */
    volatile double lastDetectMs;

    FaceTracker(Context context) { this(context, Delegate.CPU); }

    FaceTracker(Context context, Delegate delegate) {
        landmarker = FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath("face_landmarker.task")
                        .setDelegate(delegate)
                        .build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .build());
    }

    /** Rotates a camera frame upright and mirrors it (selfie view). Closes the ImageProxy. */
    static Bitmap upright(ImageProxy image) {
        Bitmap raw = image.toBitmap();
        Matrix m = new Matrix();
        m.postRotate(image.getImageInfo().getRotationDegrees());
        m.postScale(-1, 1);
        image.close();
        return Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
    }

    Frame process(Bitmap upright, long tMs) {
        long t = Math.max(tMs, lastT + 1);   // VIDEO mode needs strictly increasing timestamps
        lastT = t;
        long t0 = System.nanoTime();
        FaceLandmarkerResult result = landmarker.detectForVideo(new BitmapImageBuilder(upright).build(), t);
        lastDetectMs = (System.nanoTime() - t0) / 1e6;
        if (result.faceLandmarks().isEmpty()) return new Frame(upright, null, null, Double.NaN);
        int w = upright.getWidth(), h = upright.getHeight();
        List<NormalizedLandmark> lm = result.faceLandmarks().get(0);
        float[] xs = new float[lm.size()], ys = new float[lm.size()];
        for (int i = 0; i < lm.size(); i++) {
            xs[i] = lm.get(i).x() * w;
            ys[i] = lm.get(i).y() * h;
        }
        double blink = Double.NaN;
        if (result.faceBlendshapes().isPresent() && !result.faceBlendshapes().get().isEmpty()) {
            double l = 0, r = 0;
            for (Category c : result.faceBlendshapes().get().get(0)) {
                if (c.categoryName().equals("eyeBlinkLeft")) l = c.score();
                else if (c.categoryName().equals("eyeBlinkRight")) r = c.score();
            }
            blink = Math.max(l, r);
        }
        return new Frame(upright, xs, ys, blink);
    }

    /**
     * The front camera's lowest fixed frame rate at or above minFps: [15,15] on the Pixel 5 and Pixel 7.
     * Falls back to the range with the lowest upper bound, or null if the camera reports none.
     */
    @androidx.annotation.OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    static Range<Integer> passiveFpsRange(ProcessCameraProvider provider, int minFps) {
        for (CameraInfo info : provider.getAvailableCameraInfos()) {
            if (info.getLensFacing() != CameraSelector.LENS_FACING_FRONT) continue;
            Range<Integer>[] ranges = Camera2CameraInfo.from(info)
                    .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) return null;
            Range<Integer> fixed = null, lowest = null;
            for (Range<Integer> r : ranges) {
                if (r.getLower().equals(r.getUpper()) && r.getUpper() >= minFps
                        && (fixed == null || r.getUpper() < fixed.getUpper())) fixed = r;
                if (lowest == null || r.getUpper() < lowest.getUpper()) lowest = r;
            }
            return fixed != null ? fixed : lowest;
        }
        return null;
    }

    @Override
    public void close() { landmarker.close(); }
}
