package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.List;

/** MediaPipe Face Landmarker in VIDEO mode: one synchronous call per frame on the caller's thread. */
final class FaceTracker implements AutoCloseable {
    /** Landmarks in pixels of the upright frame, or xs == null when no face was found. */
    static final class Frame {
        final float[] xs, ys;
        final double blink;    // larger of eyeBlinkLeft/Right, NaN if unavailable

        Frame(float[] xs, float[] ys, double blink) {
            this.xs = xs;
            this.ys = ys;
            this.blink = blink;
        }

        boolean hasFace() {
            return xs != null && xs.length > EyeMetrics.L_IRIS;
        }
    }

    private final FaceLandmarker landmarker;
    private long lastT = Long.MIN_VALUE;

    /** The model is bundled as an asset; fetch_eyetrack_libs.sh downloads it. Throws if it is missing. */
    FaceTracker(Context context) {
        landmarker = FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .build());
    }

    Frame process(Bitmap upright, long tMs) {
        long t = Math.max(tMs, lastT + 1);   // VIDEO mode needs strictly increasing timestamps
        lastT = t;
        FaceLandmarkerResult result = landmarker.detectForVideo(new BitmapImageBuilder(upright).build(), t);
        if (result.faceLandmarks().isEmpty()) return new Frame(null, null, Double.NaN);
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
        return new Frame(xs, ys, blink);
    }

    @Override
    public void close() {
        landmarker.close();
    }
}
