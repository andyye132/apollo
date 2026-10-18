package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Breath strength from the barometer: finds forced blows in a recording and summarises a session. Plain Java
 * (no Android), so check.sh can run it on recorded CSVs; a port of tools/barotest/analyze_baro.py, which has
 * the measurements behind every constant (tools/barotest/README.md).
 *
 * A blow from fist height (~8-10 cm: a fist on the table, mouth at the top of the thumb) onto a phone lying flat flexes the screen of the sealed (IP68) phone and briefly raises
 * the pressure inside. On a Pixel 5 (Bosch BMP380, 25 Hz, ~1.6 Pa noise) maximal blows gave 15-90 Pa, repeatable
 * within a person (+-7%) but not comparable across people (mouth opening leaks in, push ~ flow^2 / area). So the
 * result is "blow strength against your own baseline on this phone", never peak flow.
 */
final class BlowDetector {
    static final double EVENT_PA = 6.0;              // ~4x the noise
    static final double MERGE_S = 0.5;               // samples above threshold closer than this are one blow
    static final double TOUCH_PA = 500.0;            // a finger press gave ~1,200 Pa; no blow came near 500
    static final double TOUCH_DURATION_S = 1.5;      // blows last 0.2-1.2 s; a press lasts as long as the press
    static final double BASELINE_HALF_WINDOW_S = 2.0;
    static final double SETTLE_S = 1.0;              // the baseline is one-sided at the very start; the first cue is at 3 s
    static final double GOOD_SPREAD_PCT = 15, OK_SPREAD_PCT = 30;
    static final int BASELINE_SESSIONS = 5, MIN_FOR_BASELINE = 3;

    static final class Event {
        final double tSec, peakPa, areaPaS, durationS;

        Event(double tSec, double peakPa, double areaPaS, double durationS) {
            this.tSec = tSec;
            this.peakPa = peakPa;
            this.areaPaS = areaPaS;
            this.durationS = durationS;
        }

        /** A touch or press on the phone, not a blow. */
        boolean isTouch() { return peakPa > TOUCH_PA || durationS > TOUCH_DURATION_S; }
    }

    /** GOOD / OK results are saved; INCONSISTENT and TOO_FEW ask for a retake. */
    enum Quality { GOOD, OK, INCONSISTENT, TOO_FEW }

    static final class Summary {
        final List<Event> events;
        final double strengthPa;   // mean of the top three blow peaks; NaN if fewer than three blows
        final double spreadPct;    // SD / mean of those three
        final Quality quality;

        Summary(List<Event> events, double strengthPa, double spreadPct, Quality quality) {
            this.events = events;
            this.strengthPa = strengthPa;
            this.spreadPct = spreadPct;
            this.quality = quality;
        }

        List<Event> blows() {
            List<Event> out = new ArrayList<>();
            for (Event e : events) if (!e.isTouch()) out.add(e);
            return out;
        }

        int touches() { return events.size() - blows().size(); }

        boolean usable() { return quality == Quality.GOOD || quality == Quality.OK; }
    }

    private BlowDetector() {
    }

    /** t in seconds since the start, hpa the barometer readings. */
    static Summary analyze(double[] t, double[] hpa) {
        List<Event> events = events(t, hpa);
        List<Double> peaks = new ArrayList<>();
        for (Event e : events) if (!e.isTouch()) peaks.add(e.peakPa);
        if (peaks.size() < 3) return new Summary(events, Double.NaN, Double.NaN, Quality.TOO_FEW);
        // No absolute floor: a weak-lunged person's maximal blow can be small, and dropping it would bias things.
        peaks.sort(Collections.reverseOrder());
        double mean = (peaks.get(0) + peaks.get(1) + peaks.get(2)) / 3, ss = 0;
        for (int i = 0; i < 3; i++) ss += (peaks.get(i) - mean) * (peaks.get(i) - mean);
        double spread = 100 * Math.sqrt(ss / 3) / mean;
        Quality q = spread <= GOOD_SPREAD_PCT ? Quality.GOOD : spread <= OK_SPREAD_PCT ? Quality.OK : Quality.INCONSISTENT;
        return new Summary(events, mean, spread, q);
    }

    static List<Event> events(double[] t, double[] hpa) {
        int n = t.length;
        List<Event> out = new ArrayList<>();
        if (n < 3) return out;
        double[] pa = new double[n], dev = new double[n];
        for (int i = 0; i < n; i++) pa[i] = hpa[i] * 100;
        for (int i = 0; i < n; i++) dev[i] = pa[i] - centeredMedian(t, pa, i);
        double dt = (t[n - 1] - t[0]) / (n - 1);

        // Touches, found against the 4 s *before* each sample. A press longer than ~2 s takes over the centred
        // median below and vanishes from dev, but against the preceding window its ~1,200 Pa jump always shows.
        List<double[]> touches = new ArrayList<>();   // {start, end, peak}
        for (int i = 0; i < n; i++) {
            if (t[i] < SETTLE_S) continue;
            double before = pa[i] - trailingMedian(t, pa, i);
            if (Math.abs(before) <= TOUCH_PA) continue;
            double[] prev = touches.isEmpty() ? null : touches.get(touches.size() - 1);
            if (prev != null && t[i] - prev[1] <= 2 * BASELINE_HALF_WINDOW_S) {   // a press and its release are one touch
                prev[1] = t[i];
                prev[2] = Math.max(prev[2], Math.abs(before));
            } else {
                touches.add(new double[]{t[i], t[i], Math.abs(before)});
            }
        }

        int start = -1, last = -1;
        for (int i = 0; i <= n; i++) {
            boolean above = i < n && t[i] >= SETTLE_S && dev[i] > EVENT_PA && !nearTouch(touches, t[i]);
            boolean gap = i == n || (above && start >= 0 && t[i] - t[last] > MERGE_S);
            if (start >= 0 && gap) {
                int k = start;
                double area = 0;
                for (int j = start; j <= last; j++) {
                    if (dev[j] > dev[k]) k = j;
                    if (dev[j] > 0) area += dev[j] * dt;
                }
                out.add(new Event(t[k], dev[k], area, t[last] - t[start] + dt));
                start = -1;
            }
            if (above) {
                if (start < 0) start = i;
                last = i;
            }
        }
        for (double[] k : touches) out.add(new Event(k[0], k[2], 0, k[1] - k[0] + dt));   // isTouch(): peak > TOUCH_PA
        out.sort((a, b) -> Double.compare(a.tSec, b.tSec));
        return out;
    }

    /** Within 1 s of a touch: the press's edges and the hand settling aren't blows. */
    private static boolean nearTouch(List<double[]> touches, double ts) {
        for (double[] k : touches) if (ts >= k[0] - 1 && ts <= k[1] + 1) return true;
        return false;
    }

    /** Median of the 4 s ending at index i (no later samples). */
    static double trailingMedian(double[] t, double[] v, int i) {
        int lo = i;
        while (lo > 0 && t[i] - t[lo - 1] <= 2 * BASELINE_HALF_WINDOW_S) lo--;
        return median(Arrays.copyOfRange(v, lo, i + 1));
    }

    /** Median of the samples within +-BASELINE_HALF_WINDOW_S: slow drift drops out, a short blow doesn't move it. */
    static double centeredMedian(double[] t, double[] v, int i) {
        int lo = i, hi = i;
        while (lo > 0 && t[i] - t[lo - 1] <= BASELINE_HALF_WINDOW_S) lo--;
        while (hi < t.length - 1 && t[hi + 1] - t[i] <= BASELINE_HALF_WINDOW_S) hi++;
        return median(Arrays.copyOfRange(v, lo, hi + 1));
    }

    /** Personal baseline: median of the last BASELINE_SESSIONS earlier results, or NaN before MIN_FOR_BASELINE. */
    static double baseline(List<Double> earlierOldestFirst) {
        int n = earlierOldestFirst.size();
        if (n < MIN_FOR_BASELINE) return Double.NaN;
        List<Double> recent = new ArrayList<>(earlierOldestFirst.subList(Math.max(0, n - BASELINE_SESSIONS), n));
        double[] a = new double[recent.size()];
        for (int i = 0; i < a.length; i++) a[i] = recent.get(i);
        return median(a);
    }

    static double median(double[] a) {
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2;
    }
}
