package edu.uw.ubicomp.apolloproto;

import java.util.ArrayDeque;

/**
 * RageSense's just-in-time intervention trigger (Sec. 4.2.1, Algorithm 1), for one arousal-like signal:
 *   1. EMA-smooth the incoming value.
 *   2. Keep the last 60 s; the dynamic threshold is mean + k * SD of that window, with k decaying
 *      linearly from 1.2 to 0.8 over the session (the paper's schedule). Mean and SD are of the raw
 *      values: the EMA is autocorrelated and ~2.4x narrower, which made thresholds on it fire on noise.
 *   3. One-sided CUSUM change-point detection on the raw values (slack 0.5 SD, decision 5 SD).
 *   4. Fire only when a change point is detected AND the smoothed value is above the threshold.
 * Added (not in the paper): a cooldown between interventions, and a warm-up before the first one.
 */
final class JitaTrigger {
    private final double alpha, windowS, sessionS, cooldownS, warmupS;
    private final ArrayDeque<double[]> window = new ArrayDeque<>();   // {t, raw value}
    private double ema = Double.NaN, cusum, startT = Double.NaN, lastFire = Double.NEGATIVE_INFINITY;
    double threshold = Double.NaN, mean = Double.NaN, sd = Double.NaN;   // last computed, for display

    JitaTrigger() {
        this(0.3, 60, 20 * 60, 45, 30);
    }

    JitaTrigger(double alpha, double windowS, double sessionS, double cooldownS, double warmupS) {
        this.alpha = alpha;
        this.windowS = windowS;
        this.sessionS = sessionS;
        this.cooldownS = cooldownS;
        this.warmupS = warmupS;
    }

    double ema() {
        return ema;
    }

    /** Feed one value at time t (seconds). Returns true when an intervention should fire. */
    boolean update(double t, double x) {
        if (Double.isNaN(startT)) startT = t;
        ema = Double.isNaN(ema) ? x : alpha * x + (1 - alpha) * ema;
        window.addLast(new double[]{t, x});
        while (t - window.peekFirst()[0] > windowS) window.removeFirst();
        if (t - startT < warmupS) return false;

        double s = 0, s2 = 0;
        for (double[] w : window) {
            s += w[1];
            s2 += w[1] * w[1];
        }
        int n = window.size();
        mean = s / n;
        sd = Math.sqrt(Math.max(1e-12, s2 / n - mean * mean));
        double k = 1.2 - 0.4 * Math.min(1, (t - startT) / sessionS);
        threshold = mean + k * sd;
        // One-sided CUSUM with slack 0.5 SD and decision interval 5 SD (standard choices).
        cusum = Math.max(0, cusum + (x - mean - 0.5 * sd));
        boolean change = cusum > 5 * sd;
        if (change && ema > threshold && t - lastFire >= cooldownS) {
            lastFire = t;
            cusum = 0;
            return true;
        }
        return false;
    }
}
