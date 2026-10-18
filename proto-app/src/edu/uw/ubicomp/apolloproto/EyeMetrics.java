package edu.uw.ubicomp.apolloproto;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Turns per-frame face landmarks into eye metrics: a gaze position, blinks, and "gaze shifts".
 * Plain Java with no Android types, so it can be unit-tested on recorded landmark CSVs.
 *
 * Gaze is the iris centre relative to the eye corners, in units of eye width: x runs 0 (left corner in the
 * image) to 1 (right corner); y is the offset from the corner-to-corner midline, positive downward. Both eyes
 * are averaged. This is a relative signal, not a screen position: there is no per-user calibration.
 *
 * A gaze shift is the gaze moving more than SHIFT_THRESHOLD within shiftWindowMs while the eyes are open.
 * At 30 fps a saccade (20-200 ms) spans only a few frames, so this counts shifts; it can't measure their speed.
 */
final class EyeMetrics {
    // MediaPipe Face Landmarker indices (478-point mesh with irises)
    static final int R_CORNER_A = 33, R_CORNER_B = 133, R_IRIS = 468;
    static final int L_CORNER_A = 362, L_CORNER_B = 263, L_IRIS = 473;
    static final int[] EYE_POINTS = {33, 133, 159, 145, 160, 144, 158, 153, 362, 263, 386, 374, 387, 373, 385, 380};
    static final int[] IRIS_POINTS = {468, 469, 470, 471, 472, 473, 474, 475, 476, 477};

    // Starting values; tune on recordings
    static final double BLINK_CLOSE = 0.5, BLINK_OPEN = 0.3;   // blendshape eyeBlink score, with hysteresis
    static final double SHIFT_THRESHOLD = 0.10;                // eye widths
    static final long RATE_WINDOW_MS = 60_000;
    static final double SMOOTHING = 0.5;                       // EMA weight of the newest frame

    /** The shift window must span at least ~2 frames: 100 ms at 30 fps, 150 ms at 15 fps. */
    private final long shiftWindowMs, shiftRefractoryMs;

    EyeMetrics() { this(30); }

    EyeMetrics(double fps) {
        shiftWindowMs = Math.max(100, Math.round(2.2 * 1000 / fps));
        shiftRefractoryMs = shiftWindowMs + 50;
    }

    double gazeX = Double.NaN, gazeY = Double.NaN;
    boolean eyesClosed;
    int blinkCount, shiftCount;
    boolean blinkEvent, shiftEvent;                            // true only on the frame the event fires

    private final ArrayDeque<double[]> history = new ArrayDeque<>();   // {t, x, y}
    private final ArrayDeque<Long> blinkTimes = new ArrayDeque<>(), shiftTimes = new ArrayDeque<>();
    private long firstT = -1, lastShiftT = Long.MIN_VALUE / 2;

    /** xs/ys are landmark positions in pixels; blinkScore is the larger eyeBlink blendshape, or NaN. */
    void update(long t, float[] xs, float[] ys, double blinkScore) {
        if (firstT < 0) firstT = t;
        blinkEvent = shiftEvent = false;

        if (!Double.isNaN(blinkScore)) {
            if (!eyesClosed && blinkScore > BLINK_CLOSE) {
                eyesClosed = true;
            } else if (eyesClosed && blinkScore < BLINK_OPEN) {
                eyesClosed = false;
                blinkCount++;
                blinkEvent = true;
                blinkTimes.add(t);
            }
        }
        if (eyesClosed) {          // iris landmarks are guesses while the lids are shut
            history.clear();
            return;
        }

        double[] r = eye(xs, ys, R_CORNER_A, R_CORNER_B, R_IRIS);
        double[] l = eye(xs, ys, L_CORNER_A, L_CORNER_B, L_IRIS);
        double x = (r[0] + l[0]) / 2, y = (r[1] + l[1]) / 2;
        if (Double.isNaN(gazeX)) {
            gazeX = x;
            gazeY = y;
        } else {
            gazeX += SMOOTHING * (x - gazeX);
            gazeY += SMOOTHING * (y - gazeY);
        }

        history.add(new double[]{t, gazeX, gazeY});
        while (history.size() > 1 && t - history.peekFirst()[0] > shiftWindowMs) history.pollFirst();
        double[] old = history.peekFirst();
        if (old != null && t - old[0] >= shiftWindowMs / 2
                && Math.hypot(gazeX - old[1], gazeY - old[2]) > SHIFT_THRESHOLD
                && t - lastShiftT > shiftRefractoryMs) {
            shiftCount++;
            shiftEvent = true;
            shiftTimes.add(t);
            lastShiftT = t;
            history.clear();
            history.add(new double[]{t, gazeX, gazeY});
        }
    }

    /** No face this frame: drop the shift history so reacquiring a face doesn't count as a shift. */
    void noFace() {
        blinkEvent = shiftEvent = false;
        history.clear();
    }

    double blinksPerMinute(long now) { return perMinute(blinkTimes, now); }

    double shiftsPerMinute(long now) { return perMinute(shiftTimes, now); }

    private double perMinute(ArrayDeque<Long> times, long now) {
        for (Iterator<Long> it = times.iterator(); it.hasNext(); ) {
            if (now - it.next() > RATE_WINDOW_MS) it.remove();
            else break;
        }
        long span = Math.min(RATE_WINDOW_MS, Math.max(now - firstT, 1));
        return firstT < 0 ? 0 : times.size() * 60_000.0 / span;
    }

    /** Iris position for one eye, in eye widths: {x along the corner line from the left corner, y below it}. */
    private static double[] eye(float[] xs, float[] ys, int a, int b, int iris) {
        int lo = xs[a] <= xs[b] ? a : b, hi = lo == a ? b : a;
        double ex = xs[hi] - xs[lo], ey = ys[hi] - ys[lo];
        double w = Math.hypot(ex, ey);
        if (w < 1e-6) return new double[]{Double.NaN, Double.NaN};
        double ix = xs[iris] - xs[lo], iy = ys[iris] - ys[lo];
        // project onto the corner line (x) and its perpendicular (y), so head roll doesn't leak into gaze
        return new double[]{(ix * ex + iy * ey) / (w * w), (-ix * ey + iy * ex) / (w * w)};
    }
}
