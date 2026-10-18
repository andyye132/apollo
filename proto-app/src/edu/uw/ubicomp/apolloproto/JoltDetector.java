package edu.uw.ubicomp.apolloproto;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Spots a jolt during a heart-rate recording, deliberately leniently: only movement big enough to
 * spoil the pulse restarts the test.
 *  - The phone itself jolts: rotation above 1 rad/s (57°/s) or acceleration above 2.5 m/s². Holding a
 *    phone still stays far below both.
 *  - The finger shifts on the lens: the finger's brightness (last 0.33 s) moves more than 10% from the
 *    previous 3 s. On Pixel 4 recordings (Oct 2026), clean runs stayed under ~4% and a slipped finger
 *    reached 19%.
 * The first 0.5 s (motion) and 1.5 s (brightness) are skipped: the voice prompt and the exposure lock
 * land there.
 */
final class JoltDetector {
    static final double GYRO_RAD_S = 1.0, ACCEL_M_S2 = 2.5, BRIGHTNESS_JUMP = 0.10;
    static final double MOTION_WARMUP_S = 0.5, BRIGHTNESS_WARMUP_S = 1.5;

    private final ArrayDeque<double[]> recent = new ArrayDeque<>();   // {t, brightness}
    private double startT = Double.NaN;
    String reason;

    void reset() {
        recent.clear();
        startT = Double.NaN;
        reason = null;
    }

    /**
     * One recorded frame: time (s), the finger's brightness, and the largest phone rotation (rad/s) and
     * acceleration without gravity (m/s²) since the previous frame (NaN when there's no sensor).
     */
    boolean frame(double t, double brightness, double gyro, double accel) {
        if (Double.isNaN(startT)) startT = t;
        double since = t - startT;
        if (since >= MOTION_WARMUP_S) {
            if (gyro > GYRO_RAD_S) return fire(String.format(java.util.Locale.US, "phone rotated %.1f rad/s", gyro));
            if (accel > ACCEL_M_S2) return fire(String.format(java.util.Locale.US, "phone shook %.1f m/s²", accel));
        }
        if (Double.isNaN(brightness)) return false;
        recent.addLast(new double[]{t, brightness});
        while (t - recent.peekFirst()[0] > 3.33) recent.removeFirst();
        if (since < BRIGHTNESS_WARMUP_S) return false;
        double cur = 0;
        int nc = 0;
        double[] ref = new double[recent.size()];
        int nr = 0;
        for (double[] p : recent) {
            if (t - p[0] <= 0.33) {
                cur += p[1];
                nc++;
            } else {
                ref[nr++] = p[1];
            }
        }
        if (nc == 0 || nr < 15) return false;
        Arrays.sort(ref, 0, nr);
        double base = ref[nr / 2], jump = Math.abs(cur / nc - base) / base;
        return jump > BRIGHTNESS_JUMP && fire(String.format(java.util.Locale.US, "finger brightness jumped %.0f%%", 100 * jump));
    }

    private boolean fire(String why) {
        reason = why;
        return true;
    }
}
