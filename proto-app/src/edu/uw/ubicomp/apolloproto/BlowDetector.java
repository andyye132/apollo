package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds forced blows in a barometer recording and summarises a breath-strength session. A Java port of mmh132's
 * BlowDetector.kt (blow-strength branch), itself a port of tools/barotest/analyze_baro.py; the measurements behind
 * every constant are in tools/barotest/README.md.
 *
 * A blow from 2-3 cm onto a phone lying flat flexes the screen of the sealed (IP68) phone and briefly raises the
 * pressure inside it. On a Pixel 5 (Bosch BMP380, 25 Hz, ~1.6 Pa noise; the Pixel 4 has the same chip) maximal
 * blows gave 15-90 Pa, repeatable within a person (±7%) but not comparable across people (the mouth opening leaks
 * in), so the result is blow strength against your own baseline, never peak flow.
 */
final class BlowDetector {
    static final double EVENT_PA = 6.0;             // ~4x the noise
    static final double MERGE_S = 0.5;              // samples above threshold closer than this are one blow
    static final double TOUCH_PA = 500.0;           // a finger press gives ~1,200 Pa; no blow came near 500
    static final double TOUCH_DURATION_S = 1.5;     // blows last 0.2-1.2 s; a press lasts as long as the press
    static final double BASELINE_HALF_WINDOW_S = 2.0;
    static final double GOOD_SPREAD_PCT = 15.0, OK_SPREAD_PCT = 30.0;
    static final int BASELINE_SESSIONS = 5, MIN_FOR_BASELINE = 3;

    enum Quality { GOOD, OK, INCONSISTENT, TOO_FEW }

    private BlowDetector() {
    }

    static final class Event {
        final double tSec, peakPa, areaPaS, durationS;

        Event(double tSec, double peakPa, double areaPaS, double durationS) {
            this.tSec = tSec;
            this.peakPa = peakPa;
            this.areaPaS = areaPaS;
            this.durationS = durationS;
        }

        /** A touch or press on the phone, not a blow. */
        boolean isTouch() {
            return peakPa > TOUCH_PA || durationS > TOUCH_DURATION_S;
        }
    }

    static final class Summary {
        final List<Event> events;
        final double strengthPa, spreadPct;   // mean and spread (SD / mean, %) of the top three blows; NaN with fewer
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

        int touches() {
            return events.size() - blows().size();
        }
    }

    /** {@code tSec}: seconds since the start; {@code hpa}: pressure readings. */
    static Summary analyze(double[] tSec, double[] hpa) {
        List<Event> events = events(tSec, hpa);
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

    static List<Event> events(double[] tSec, double[] hpa) {
        int n = tSec.length;
        List<Event> out = new ArrayList<>();
        if (n < 3) return out;
        double[] pa = new double[n], dev = new double[n];
        for (int i = 0; i < n; i++) pa[i] = hpa[i] * 100;
        for (int i = 0; i < n; i++) dev[i] = pa[i] - centeredMedian(tSec, pa, i);
        double dt = (tSec[n - 1] - tSec[0]) / (n - 1);
        int start = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (dev[i] <= EVENT_PA) continue;
            if (start >= 0 && tSec[i] - tSec[last] > MERGE_S) {
                out.add(close(tSec, dev, start, last, dt));
                start = -1;
            }
            if (start < 0) start = i;
            last = i;
        }
        if (start >= 0) out.add(close(tSec, dev, start, last, dt));
        return out;
    }

    private static Event close(double[] t, double[] dev, int start, int last, double dt) {
        int k = start;
        double area = 0;
        for (int i = start; i <= last; i++) {
            if (dev[i] > dev[k]) k = i;
            if (dev[i] > 0) area += dev[i] * dt;
        }
        return new Event(t[k], dev[k], area, t[last] - t[start] + dt);
    }

    /** Median of the samples within ±BASELINE_HALF_WINDOW_S: slow drift drops out, a short blow doesn't move it. */
    private static double centeredMedian(double[] t, double[] v, int i) {
        int lo = i, hi = i;
        while (lo > 0 && t[i] - t[lo - 1] <= BASELINE_HALF_WINDOW_S) lo--;
        while (hi < t.length - 1 && t[hi + 1] - t[i] <= BASELINE_HALF_WINDOW_S) hi++;
        return median(Arrays.copyOfRange(v, lo, hi + 1));
    }

    /** Median of the last {@code windowS} seconds ending at index i, for the live display (no future samples). */
    static double trailingMedian(List<Double> t, List<Double> v, int i, double windowS) {
        int lo = i;
        while (lo > 0 && t.get(i) - t.get(lo - 1) <= windowS) lo--;
        double[] a = new double[i - lo + 1];
        for (int j = 0; j < a.length; j++) a[j] = v.get(lo + j);
        return median(a);
    }

    /** Personal baseline from previous sessions only (median of the last 5), or NaN before there are 3. */
    static double baseline(List<Double> previous) {
        if (previous.size() < MIN_FOR_BASELINE) return Double.NaN;
        List<Double> recent = previous.subList(Math.max(0, previous.size() - BASELINE_SESSIONS), previous.size());
        double[] a = new double[recent.size()];
        for (int j = 0; j < a.length; j++) a[j] = recent.get(j);
        return median(a);
    }

    private static double median(double[] a) {
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2;
    }
}
