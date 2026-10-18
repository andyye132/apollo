package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FeverPhone-style analysis of thermistor curves around 90 s of forehead contact. Per sensor: the
 * phone's own drift during the last 40 s of the hand pre-hold, the warming slope during contact
 * (FeverPhone's rate-of-temperature-change feature), the slope corrected for drift, and an SNR to
 * pick the cleanest sensor.
 *
 * Physics: Newton's law of heating, dT/dt = k (T_skin - T). The thermistor's warming slope at contact is
 * k (T_skin - T0), where T0 is the phone's temperature just before contact. k (per minute) lumps
 * together contact conductance, contact area, the thermistor's distance from the skin and the phone's
 * heat capacity, so it differs per sensor and per phone, and can't be read off one 90 s curve: with
 * tau = 1/k around 4-5 min the curve is nearly straight, and fitting where it levels off is unstable
 * (34.5 to 94 °C on the same Pixel 4 run, depending only on where the fit starts). Instead k is
 * CALIBRATED: press the screen on something of known temperature (the paper's sous-vide water bag, or a
 * forehead measured with a skin IR thermometer), then k_i = slope_i / (T_ref - T0_i) per sensor. With k
 * known, each sensor gives T_skin = T0_i + slope_i / k_i; the sensors' agreement is a quality check.
 * Skin to core (oral) temperature is a separate, further model.
 */
final class FeverAnalysis {
    static final class Series {
        final String name;
        final int type;
        final List<double[]> points = new ArrayList<>();   // {t seconds since start, celsius}

        Series(String name, int type) {
            this.name = name;
            this.type = type;
        }

        double[][] window(double from, double to) {
            List<double[]> in = new ArrayList<>();
            for (double[] p : points) if (p[0] >= from && p[0] < to && !Double.isNaN(p[1])) in.add(p);
            double[] t = new double[in.size()], y = new double[in.size()];
            for (int i = 0; i < in.size(); i++) {
                t[i] = in.get(i)[0];
                y[i] = in.get(i)[1];
            }
            return new double[][]{t, y};
        }
    }

    static final class SensorResult implements Comparable<SensorResult> {
        String name;
        int type;
        double startC, endC, driftCPerMin, slopeCPerMin, correctedCPerMin, riseC, noiseC, snr, stepC;
        boolean coarse;

        @Override
        public int compareTo(SensorResult o) {
            if (coarse != o.coarse) return coarse ? 1 : -1;
            return Double.compare(nanLow(o.snr), nanLow(snr));
        }

        private static double nanLow(double v) {
            return Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
        }
    }

    static final class Report {
        List<SensorResult> sensors = new ArrayList<>();
        SensorResult best;
        double contactStartS, contactEndS;
    }

    /** Thermistors lag the skin: on the Pixel 4 the rise starts ~10 s after contact, so the slope skips it. */
    static final double LAG_S = 10;

    static Report analyze(Map<String, Series> data, double contactStartS, double contactEndS) {
        return analyze(data, contactStartS, contactEndS, contactStartS - 40, contactStartS);
    }

    /**
     * Drift is fitted over [driftFromS, driftToS): for the calibration-style procedure that's the phone
     * lying untouched before Start, NOT the 10 s pickup, whose hand heat (up to +2.6 °C/min on the Pixel 4)
     * made the old 40 s window over-subtract until some sensors' rates came out negative (Oct 2026).
     * The hand's heat during contact then stays in the rate, in calibration and measurement alike, so k carries it.
     */
    static Report analyze(Map<String, Series> data, double contactStartS, double contactEndS, double driftFromS, double driftToS) {
        Report rep = new Report();
        rep.contactStartS = contactStartS;
        rep.contactEndS = contactEndS;
        for (Series s : data.values()) {
            double[][] pre = s.window(driftFromS, driftToS);
            double[][] on = s.window(contactStartS + LAG_S, contactEndS);
            double[][] atContact = s.window(contactStartS - 3, contactStartS + 0.5);
            if (on[0].length < 10) continue;
            SensorResult r = new SensorResult();
            r.name = s.name;
            r.type = s.type;
            // A drift needs a real baseline: 3 s of readings gave a meaningless slope (Oct 2026).
            double[] drift = pre[0].length >= 15 ? Dsp.lineFit(pre[0], pre[1]) : new double[]{Double.NaN, Double.NaN, Double.NaN};
            double[] contact = Dsp.lineFit(on[0], on[1]);
            r.driftCPerMin = drift[0] * 60;
            r.slopeCPerMin = contact[0] * 60;
            r.correctedCPerMin = r.slopeCPerMin - (Double.isNaN(r.driftCPerMin) ? 0 : r.driftCPerMin);
            r.noiseC = contact[2];
            r.startC = atContact[1].length > 0 ? Dsp.mean(atContact[1]) : on[1][0];   // T0: just before contact
            r.endC = on[1][on[1].length - 1];
            r.riseC = r.endC - r.startC;
            r.snr = r.noiseC > 0 ? r.correctedCPerMin * (contactEndS - contactStartS - LAG_S) / 60 / r.noiseC : Double.NaN;
            r.stepC = stepSize(s);
            r.coarse = r.stepC >= 0.09;   // 0.1 °C sensors can't resolve a 90 s slope
            rep.sensors.add(r);
        }
        Collections.sort(rep.sensors);
        if (!rep.sensors.isEmpty()) rep.best = rep.sensors.get(0);
        return rep;
    }

    /** A drift faster than this (°C/min), or than 40% of the contact rise, swamps the forehead's warming. */
    static final double DRIFT_LIMIT = 0.25;

    /** The phone's own drift on the sensors the estimate uses: {drift, contact slope}; drift NaN when there was no baseline. */
    static double[] drift(Report rep) {
        double drift = Double.NaN, contact = Double.NaN;
        boolean anyPreferred = false;
        for (SensorResult r : rep.sensors) anyPreferred |= PREFERRED.contains(r.name);
        for (SensorResult r : rep.sensors) {
            boolean use = anyPreferred ? PREFERRED.contains(r.name) : r == rep.best;
            if (!use || Double.isNaN(r.driftCPerMin)) continue;
            if (Double.isNaN(drift) || Math.abs(r.driftCPerMin) > Math.abs(drift)) {
                drift = r.driftCPerMin;
                contact = r.slopeCPerMin;
            }
        }
        return new double[]{drift, contact};
    }

    static boolean drifting(double[] d) {
        return !Double.isNaN(d[0]) && (Math.abs(d[0]) > DRIFT_LIMIT || Math.abs(d[0]) > 0.4 * Math.abs(d[1]));
    }

    // ---- Calibration and the physics estimate ------------------------------------------------------

    /** k for one sensor from a run against a known temperature (per minute); NaN if not usable. */
    static double kFromReference(SensorResult s, double refC) {
        double gap = refC - s.startC;
        if (s.coarse || gap < 2 || Double.isNaN(s.correctedCPerMin) || s.correctedCPerMin <= 0) return Double.NaN;
        return s.correctedCPerMin / gap;
    }

    static final class Estimate {
        double skinC = Double.NaN, spreadC = Double.NaN;
        int sensors;
        List<String> lines = new ArrayList<>();   // "quiet-therm: k 0.212/min -> 34.6 °C"
    }

    /**
     * Skin temperature from each calibrated sensor, T0 + slope / k, combined by median; spread = SD
     * across sensors. `kBySensor` holds each sensor's calibrated k (median of its calibration runs).
     */
    /**
     * Thermistors trusted for the estimate when present. On three people's Pixel 4 calibrations (Oct 2026),
     * these two front sensors predicted each other's temperatures best (leave-one-person-out ~0.8 °C); adding
     * sdm-therm-monitor and quiet-therm, deeper in the phone and slower, roughly doubled the error. Other
     * phones, without these names, use every calibrated sensor.
     */
    static final java.util.Set<String> PREFERRED = new java.util.HashSet<>(java.util.Arrays.asList("pa-therm", "chg-therm"));

    static Estimate estimate(Report rep, Map<String, Double> kBySensor) {
        Estimate e = new Estimate();
        List<Double> vals = new ArrayList<>();
        boolean preferredOnly = false;
        for (SensorResult s : rep.sensors) preferredOnly |= PREFERRED.contains(s.name) && kBySensor.containsKey(s.name);
        for (SensorResult s : rep.sensors) {
            if (preferredOnly && !PREFERRED.contains(s.name)) continue;
            Double k = kBySensor.get(s.name);
            if (k == null || k <= 0 || s.coarse || Double.isNaN(s.correctedCPerMin)) continue;
            double t = s.startC + s.correctedCPerMin / k;
            vals.add(t);
            e.lines.add(String.format(java.util.Locale.US, "%s: k %.3f/min, start %.2f °C, %+.2f °C/min -> %.2f °C",
                    s.name, k, s.startC, s.correctedCPerMin, t));
        }
        e.sensors = vals.size();
        if (vals.isEmpty()) return e;
        double[] v = new double[vals.size()];
        for (int i = 0; i < v.length; i++) v[i] = vals.get(i);
        e.skinC = Dsp.median(v);
        e.spreadC = v.length > 1 ? Dsp.std(v) : Double.NaN;
        return e;
    }

    private static double stepSize(Series s) {
        List<Double> vals = new ArrayList<>();
        for (double[] p : s.points) if (!Double.isNaN(p[1])) vals.add(Math.round(p[1] * 1e4) / 1e4);
        Collections.sort(vals);
        double step = Double.NaN;
        for (int i = 1; i < vals.size(); i++) {
            double d = vals.get(i) - vals.get(i - 1);
            if (d > 0 && (Double.isNaN(step) || d < step)) step = d;
        }
        return step;
    }
}
