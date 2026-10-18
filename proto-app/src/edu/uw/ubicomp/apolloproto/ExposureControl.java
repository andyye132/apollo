package edu.uw.ubicomp.apolloproto;

import java.util.Arrays;

/**
 * Manual exposure for the fingertip, checked ~5x a second from the 6x4 tile means: darken while the
 * finger's red is clipped (V > 222), brighten while it's dim (red < 130), lock after 3 checks in range
 * or 4 s. After locking it keeps watching, and goes back to adjusting if the finger stays clipped for
 * 3 checks: the finger often settles (pressure, position) after the lock and gets brighter.
 *
 * Tiles blown out to white count as clipped. At too long an exposure the torch-lit finger saturates,
 * its chroma collapses (V -> 128) and it stops looking like skin; judging only skin-coloured tiles let
 * a Pixel 4 run lock at 11 ms with 23 of 24 tiles clipped (Oct 2026).
 */
final class ExposureControl {
    static final double V_CLIP = 222, RED_DIM = 130, Y_BLOWN = 235;

    long exp;
    final long lo, hi;
    boolean adjusting;
    private long sinceNs;
    private int steady, clipped;

    ExposureControl(long lo, long hi) {
        this.lo = lo;
        this.hi = hi;
    }

    /** A torch-lit fingertip: clearly red (V well above neutral) and not black. */
    static boolean isSkin(double y, double v) {
        return v - 128 > 15 && y > 15;
    }

    void start(long exposureNs, long ns) {
        exp = exposureNs;
        adjusting = true;
        sinceNs = ns;
        steady = 0;
        clipped = 0;
    }

    /** One check. Returns true when `exp` changed and must be applied to the camera. */
    boolean check(double[] ty, double[] tv, long ns) {
        int n = 0;
        double[] v = new double[ty.length], red = new double[ty.length];
        for (int k = 0; k < ty.length; k++) {
            boolean blown = ty[k] > Y_BLOWN;
            if (!isSkin(ty[k], tv[k]) && !blown) continue;   // not under the finger
            v[n] = blown ? 255 : tv[k];
            red[n] = ty[k] + 1.402 * (tv[k] - 128);
            n++;
        }
        if (n == 0) return false;
        Arrays.sort(v, 0, n);
        Arrays.sort(red, 0, n);
        boolean clippedNow = v[n / 2] > V_CLIP;
        if (!adjusting) {
            clipped = clippedNow ? clipped + 1 : 0;
            if (clipped < 3 || exp <= lo) return false;
            adjusting = true;   // settled brighter after the lock: tune again
            sinceNs = ns;
            steady = 0;
            clipped = 0;
        }
        if (clippedNow && exp > lo) {
            exp = Math.max(lo, (long) (exp * 0.6));
            steady = 0;
            return true;
        }
        if (red[n / 2] < RED_DIM && exp < hi) {
            exp = Math.min(hi, (long) (exp * 1.5));
            steady = 0;
            return true;
        }
        if (++steady >= 3 || ns - sinceNs > 4_000_000_000L) adjusting = false;
        return false;
    }
}
