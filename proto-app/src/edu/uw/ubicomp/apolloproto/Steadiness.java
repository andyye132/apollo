package edu.uw.ubicomp.apolloproto;

import java.util.List;

/**
 * When is the phone lying still, and is its temperature steady? Used for readiness ("is it safe to
 * measure?") and to pick the drift baseline before a temperature test.
 *
 * Still = the gyroscope's median rotation over the last second is under 0.015 rad/s. A phone on a table
 * reads ~0.002; held in the hand it read 0.03 at its stillest (10th percentile) and 0.075 typically
 * (Pixel 4 heart-rate tests, Oct 2026). Brief gaps (a tap on Start) under 3 s don't break a stretch.
 */
final class Steadiness {
    static final double STILL_GYRO_RAD_S = 0.015;
    static final double MAX_GAP_S = 3, BASELINE_S = 40, MIN_BASELINE_S = 20;

    private Steadiness() {
    }

    static boolean still(double gyroMedian) {
        return !Double.isNaN(gyroMedian) && gyroMedian < STILL_GYRO_RAD_S;
    }

    /** {from, to} of the most recent still stretch ending at or before `endT`; NaNs when there's none. */
    static double[] lastStill(double[] t, boolean[] still, double endT) {
        double to = Double.NaN, from = Double.NaN, lastStillT = Double.NaN;
        for (int i = t.length - 1; i >= 0; i--) {
            if (t[i] > endT) continue;
            if (still[i]) {
                if (Double.isNaN(to)) to = t[i];
                from = t[i];
                lastStillT = t[i];
            } else if (!Double.isNaN(to) && lastStillT - t[i] > MAX_GAP_S) {
                break;   // a real interruption, not a tap
            }
        }
        return new double[]{from, to};
    }

    /** The drift window for a test: the last BASELINE_S seconds of the still stretch before `startT`, or NaNs when under MIN_BASELINE_S. */
    static double[] baseline(double[] t, boolean[] still, double startT) {
        double[] w = lastStill(t, still, startT);
        if (Double.isNaN(w[0]) || w[1] - w[0] < MIN_BASELINE_S) return new double[]{Double.NaN, Double.NaN};
        return new double[]{Math.max(w[0], w[1] - BASELINE_S), w[1] + 0.5};
    }

    /** Seconds the phone has been still up to its newest sample (0 when it isn't still now). */
    static double stillFor(double[] t, boolean[] still) {
        if (t.length == 0 || !still[t.length - 1]) return 0;
        double[] w = lastStill(t, still, t[t.length - 1]);
        return Double.isNaN(w[0]) ? 0 : w[1] - w[0];
    }

    /** Largest |slope| (°C/min) among the series over [from, to], NaN when none has 10+ points there. */
    static double maxDrift(List<double[][]> series, double from, double to) {
        double worst = Double.NaN;
        for (double[][] s : series) {
            int n = 0;
            for (double x : s[0]) if (x >= from && x <= to) n++;
            if (n < 10) continue;
            double[] tt = new double[n], yy = new double[n];
            int j = 0;
            for (int i = 0; i < s[0].length; i++) {
                if (s[0][i] >= from && s[0][i] <= to) {
                    tt[j] = s[0][i];
                    yy[j++] = s[1][i];
                }
            }
            double slope = Dsp.lineFit(tt, yy)[0] * 60;
            if (Double.isNaN(worst) || Math.abs(slope) > Math.abs(worst)) worst = slope;
        }
        return worst;
    }
}
