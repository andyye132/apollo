package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.List;

/**
 * Heart rate from a camera PPG brightness series. Same pipeline as tools/analyze_ppg.py: resample to
 * 30 Hz, band-pass 0.7-4 Hz, then heart rate two ways (spectral peak and median beat interval), which
 * must agree. The GOOD / WEAK / POOR thresholds are our own choices, not from a paper.
 */
final class PpgAnalysis {
    static final double FS = 30, LO = 0.7, HI = 4.0, HR_MAX_HZ = 3.5;
    static final int NFFT = 8192;
    static final double V_CLIPPED = 230;

    static final class Result {
        double hr, snr, hrBeats, rmssdMs, sdnnMs, meanIbiMs, acPct, durationS;
        double hrSpectrum;     // the tallest spectral peak; hr is this, or hrBeats when the beats are confirmed (see below)
        boolean fromBeats;
        boolean agree;
        String verdict;
        int beats, rejected;
        double[] tu, pulse;          // band-passed, flipped (systole up), on the 30 Hz grid
        int[] peaks;
        double[] beatT, beatBpm;     // beat-to-beat heart rate
        double[] winT, winBpm, winSnr;
        double[] specBpm, specP;     // 30-240 bpm, normalised to max 1
    }

    /** Signal choice from the tile grid: skin tiles that aren't clipped, else the centre (green if red clipped). */
    static final class Choice {
        double[] signal;
        String channel;
        int usableTiles, clippedTiles;
    }

    static Choice chooseSignal(double[] centreY, double[] centreU, double[] centreV, double[][] ty, double[][] tv) {
        int n = centreY.length, tiles = ty.length > 0 ? ty[0].length : 0;
        Choice c = new Choice();
        boolean[] usable = new boolean[tiles];
        for (int k = 0; k < tiles; k++) {
            int skin = 0;
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = tv[i][k];
                if (tv[i][k] - 128 > 15 && ty[i][k] > 15) skin++;
            }
            if (skin >= 0.9 * n) {
                if (Dsp.median(v) <= V_CLIPPED) {
                    usable[k] = true;
                    c.usableTiles++;
                } else {
                    c.clippedTiles++;
                }
            }
        }
        c.signal = new double[n];
        if (c.usableTiles > 0) {
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int k = 0; k < tiles; k++) if (usable[k]) s += ty[i][k] + 1.402 * (tv[i][k] - 128);
                c.signal[i] = s / c.usableTiles;
            }
            c.channel = "red, " + c.usableTiles + " skin tiles";
            return c;
        }
        boolean clipped = Dsp.median(centreV) > V_CLIPPED;
        for (int i = 0; i < n; i++) {
            c.signal[i] = clipped
                    ? centreY[i] - 0.344 * (centreU[i] - 128) - 0.714 * (centreV[i] - 128)
                    : centreY[i] + 1.402 * (centreV[i] - 128);
        }
        c.channel = clipped ? "green, centre (red clipped)" : "red, centre";
        return c;
    }

    static Result analyze(double[] t, double[] brightness) {
        Result r = new Result();
        double[][] rs = Dsp.resample(t, brightness, FS);
        r.tu = rs[0];
        r.durationS = t[t.length - 1] - t[0];
        double[] raw = rs[1];
        double[] x = Dsp.bandpass(raw, FS, LO, HI);

        double[] spec = spectral(x);
        r.hr = spec[0];
        r.snr = spec[1];
        double[] p = Dsp.powerSpectrum(x, NFFT);
        List<double[]> sp = new ArrayList<>();
        double pmax = 0;
        for (int k = 0; k < p.length; k++) {
            double bpm = 60.0 * k * FS / NFFT;
            if (bpm >= 30 && bpm <= 240) {
                sp.add(new double[]{bpm, p[k]});
                pmax = Math.max(pmax, p[k]);
            }
        }
        r.specBpm = new double[sp.size()];
        r.specP = new double[sp.size()];
        for (int i = 0; i < sp.size(); i++) {
            r.specBpm[i] = sp.get(i)[0];
            r.specP[i] = pmax > 0 ? sp.get(i)[1] / pmax : 0;
        }

        // Beats on the flipped signal: more blood means a darker frame, so systole points up after flipping.
        r.pulse = new double[x.length];
        for (int i = 0; i < x.length; i++) r.pulse[i] = -x[i];
        r.peaks = Dsp.findPeaks(r.pulse, (int) (FS * 60 / 200), 0.4 * Dsp.std(r.pulse));
        int np = 0;
        double[] beatTime = new double[r.peaks.length];
        for (int pk : r.peaks) {
            if (pk <= 0 || pk >= r.pulse.length - 1) continue;
            double y0 = r.pulse[pk - 1], y1 = r.pulse[pk], y2 = r.pulse[pk + 1], den = y0 - 2 * y1 + y2;
            beatTime[np++] = (pk + (den != 0 ? 0.5 * (y0 - y2) / den : 0)) / FS;
        }
        r.beats = r.peaks.length;
        double[] ibi = new double[Math.max(0, np - 1)];
        for (int i = 0; i + 1 < np; i++) ibi[i] = beatTime[i + 1] - beatTime[i];
        r.beatT = new double[ibi.length];
        r.beatBpm = new double[ibi.length];
        for (int i = 0; i < ibi.length; i++) {
            r.beatT[i] = r.tu[0] + beatTime[i + 1];
            r.beatBpm[i] = 60 / ibi[i];
        }
        double med = Dsp.median(ibi);
        List<Double> good = new ArrayList<>();
        for (double v : ibi) if (Math.abs(v - med) < 0.3 * med) good.add(v);
        r.rejected = ibi.length - good.size();
        double[] g = new double[good.size()];
        for (int i = 0; i < g.length; i++) g[i] = good.get(i);
        r.hrBeats = g.length > 0 ? 60 / Dsp.median(g) : Double.NaN;
        r.meanIbiMs = g.length > 0 ? 1000 * Dsp.mean(g) : Double.NaN;
        r.sdnnMs = g.length > 1 ? 1000 * Dsp.std(g) : Double.NaN;
        if (g.length > 2) {
            double s = 0;
            for (int i = 0; i + 1 < g.length; i++) s += (g[i + 1] - g[i]) * (g[i + 1] - g[i]);
            r.rmssdMs = 1000 * Math.sqrt(s / (g.length - 1));
        } else {
            r.rmssdMs = Double.NaN;
        }
        r.acPct = 100 * (Dsp.percentile(x, 95) - Dsp.percentile(x, 5)) / Dsp.mean(raw);

        int win = (int) (10 * FS), step = (int) (2 * FS);
        List<double[]> w = new ArrayList<>();
        for (int i = 0; i + win <= x.length; i += step) {
            double[] seg = java.util.Arrays.copyOfRange(x, i, i + win);
            double[] s = spectral(seg);
            w.add(new double[]{r.tu[0] + (i + win / 2.0) / FS, s[0], s[1]});
        }
        r.winT = new double[w.size()];
        r.winBpm = new double[w.size()];
        r.winSnr = new double[w.size()];
        for (int i = 0; i < w.size(); i++) {
            r.winT[i] = w.get(i)[0];
            r.winBpm[i] = w.get(i)[1];
            r.winSnr[i] = w.get(i)[2];
        }

        r.hrSpectrum = r.hr;
        r.agree = !Double.isNaN(r.hrBeats) && Math.abs(r.hr - r.hrBeats) <= 5;
        // Heart rate swings with breathing (77-93 bpm within one 20 s Pixel 4 recording, Oct 2026), which can
        // split the spectrum into two peaks; the tallest then misses the median beat rate by more than 5 bpm
        // although the beats are right (they matched the watch). So the beats also count as confirmed when
        // the spectrum has a peak at their rate at least half as tall as the tallest, and that rate isn't
        // a harmonic of the tallest (double-counted beats). The reported rate is then the beats'.
        if (!r.agree && !Double.isNaN(r.hrBeats) && peakNear(p, r.hrBeats) >= 0.5 && !harmonic(r.hr, r.hrBeats)) {
            r.agree = true;
            r.fromBeats = true;
            r.hr = r.hrBeats;
        }
        r.verdict = r.snr >= 0.5 && r.agree ? "GOOD" : r.snr >= 0.3 && r.agree ? "WEAK" : "POOR";
        return r;
    }

    /**
     * Baseline drift relative to pulse size: the range of 1 s means of the raw signal over the pulse's
     * p5-p95 amplitude. Large values mean the sensor shifted on the skin (steps), not a faint pulse.
     * Pixel 4 / WILD watch, Oct 2026: a good phone recording 2.7, two failed watch recordings 3.7 and 7.7.
     */
    static double driftRatio(double[] t, double[] raw, Result r) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, sum = 0, mean = Dsp.mean(raw);
        int n = 0;
        long bin = (long) Math.floor(t[0]);
        for (int i = 0; i <= t.length; i++) {
            if (i == t.length || (long) Math.floor(t[i]) != bin) {
                if (n > 0) {
                    lo = Math.min(lo, sum / n);
                    hi = Math.max(hi, sum / n);
                }
                if (i == t.length) break;
                bin = (long) Math.floor(t[i]);
                sum = 0;
                n = 0;
            }
            sum += raw[i];
            n++;
        }
        double pulse = r.acPct / 100 * mean;
        return pulse > 0 ? (hi - lo) / pulse : Double.POSITIVE_INFINITY;
    }

    /** Tallest spectral power within 2 bpm of `bpm`, relative to the tallest peak in the heart-rate band. */
    static double peakNear(double[] p, double bpm) {
        double df = FS / NFFT, best = 0, near = 0;
        for (int k = 0; k < p.length; k++) {
            double f = k * df;
            if (f >= LO && f <= HR_MAX_HZ) best = Math.max(best, p[k]);
            if (Math.abs(f * 60 - bpm) <= 2) near = Math.max(near, p[k]);
        }
        return best > 0 ? near / best : 0;
    }

    /** True when one rate is about twice the other. */
    static boolean harmonic(double a, double b) {
        double hi = Math.max(a, b), lo = Math.min(a, b);
        return Math.abs(hi / lo - 2) < 0.15;
    }

    /** {bpm, snr}: spectral peak in 0.7-3.5 Hz; SNR = power within 0.1 Hz of the peak and its harmonic / band power. */
    static double[] spectral(double[] x) {
        double[] p = Dsp.powerSpectrum(x, NFFT);
        double df = FS / NFFT, peak = 0, best = -1, near = 0, total = 0;
        for (int k = 0; k < p.length; k++) {
            double f = k * df;
            if (f >= LO && f <= HR_MAX_HZ && p[k] > best) {
                best = p[k];
                peak = f;
            }
        }
        for (int k = 0; k < p.length; k++) {
            double f = k * df;
            if (f < LO || f > HI) continue;
            total += p[k];
            if (Math.abs(f - peak) <= 0.1 || Math.abs(f - 2 * peak) <= 0.1) near += p[k];
        }
        return new double[]{60 * peak, total > 0 ? near / total : 0};
    }
}
