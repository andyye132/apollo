package edu.uw.ubicomp.apolloproto;

import java.util.Locale;

/**
 * The WILD watch's pulse over a stretch of samples ({phoneMs, red, ir, green}): picks the cleanest of
 * the three light channels and says why when there's no usable pulse.
 *
 * "Moved" means the sensor jumped on the skin: the light level of the red or IR channel (the bright
 * ones; green also picks up room and flashlight light) changes by more than 1% between consecutive 1 s
 * means. The old test divided drift by the pulse size, so it fired whenever the pulse was faint: on five
 * Pixel 4 recordings (Oct 2026) it said "shifted" every time, but two of them had steps under 0.5% and
 * simply no pulse (IR pulse 0.02-0.04% of the light level); the other three had real 1.8-3.6% jumps.
 */
final class WatchAnalysis {
    static final String[] CHANNELS = {"red", "IR", "green"};
    static final double SHIFT_LIMIT_PCT = 1.0;

    static final class Assessment {
        PpgAnalysis.Result r;     // null when there isn't enough data or the sensor is frozen
        int channel = -1;         // index into CHANNELS
        boolean frozen, moved;
        double shiftPct = Double.NaN;   // largest 1 s jump in red/IR light level, % of the level
        String headline, advice;
    }

    private WatchAnalysis() {
    }

    static Assessment assess(double[][] d) {
        Assessment a = new Assessment();
        int n = d[0].length;
        if (n < 300 || d[0][n - 1] - d[0][0] < 6000) {
            a.headline = "Watch: waiting for data";
            a.advice = "";
            return a;
        }
        double[] t = new double[n];
        a.frozen = true;
        for (int i = 0; i < n; i++) {
            t[i] = (d[0][i] - d[0][0]) / 1000.0;
            a.frozen &= d[1][i] == d[1][0] && d[2][i] == d[2][0] && d[3][i] == d[3][0];
        }
        if (a.frozen) {
            a.headline = "Watch sensor isn't sampling";
            a.advice = "Its light readings are frozen. Reset the watch (press RST once) and reconnect.";
            return a;
        }
        for (int c = 0; c < 3; c++) {
            double[] raw = smooth(d[c + 1]);
            if (raw[0] < 0) continue;   // channel missing (-1)
            PpgAnalysis.Result r = PpgAnalysis.analyze(t, raw);
            if (c < 2) {   // red, IR
                double s = shiftPct(t, raw);
                a.shiftPct = Double.isNaN(a.shiftPct) ? s : Math.max(a.shiftPct, s);
            }
            if (a.r == null || rank(r) > rank(a.r)) {
                a.r = r;
                a.channel = c;
            }
        }
        a.moved = a.shiftPct > SHIFT_LIMIT_PCT;
        if ("POOR".equals(a.r.verdict)) {
            a.headline = "Watch: no clear pulse";
            a.advice = a.moved
                    ? "The sensor shifted on your skin. Rest the watch arm flat on the table and keep it still."
                    : "The pulse at the wrist is too faint. Wear the watch on the arm that isn't holding the phone, rest it "
                    + "flat, and tighten the strap so the sensor sits flat, a finger's width above the wrist bone.";
        } else {
            a.headline = String.format(Locale.US, "Watch: %s, %.0f bpm (%s light)", "GOOD".equals(a.r.verdict) ? "clear pulse" : "noisy pulse",
                    a.r.hr, CHANNELS[a.channel]);
            a.advice = a.moved ? "Some movement at the wrist: keep the watch arm still." : "";
        }
        return a;
    }

    /** GOOD > WEAK > POOR, then SNR. */
    private static double rank(PpgAnalysis.Result r) {
        int v = "GOOD".equals(r.verdict) ? 2 : "WEAK".equals(r.verdict) ? 1 : 0;
        return v * 10 + (Double.isNaN(r.snr) ? 0 : r.snr);
    }

    /** Largest step between consecutive 1 s means, as % of the mean light level. */
    static double shiftPct(double[] t, double[] raw) {
        double prev = Double.NaN, worst = 0, sum = 0;
        int n = 0;
        long bin = (long) Math.floor(t[0]);
        for (int i = 0; i <= t.length; i++) {
            if (i == t.length || (long) Math.floor(t[i]) != bin) {
                if (n > 0) {
                    double m = sum / n;
                    if (!Double.isNaN(prev)) worst = Math.max(worst, Math.abs(m - prev));
                    prev = m;
                }
                if (i == t.length) break;
                bin = (long) Math.floor(t[i]);
                sum = 0;
                n = 0;
            }
            sum += raw[i];
            n++;
        }
        double mean = Dsp.mean(raw);
        return mean > 0 ? 100 * worst / mean : Double.NaN;
    }

    /** Light smoothing (35 ms moving average) before resampling 200 Hz to the analysis rate. */
    static double[] smooth(double[] x) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            int lo = Math.max(0, i - 3), hi = Math.min(x.length, i + 4);
            double s = 0;
            for (int j = lo; j < hi; j++) s += x[j];
            y[i] = s / (hi - lo);
        }
        return y;
    }
}
