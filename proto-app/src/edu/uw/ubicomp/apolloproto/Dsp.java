package edu.uw.ubicomp.apolloproto;

import java.util.Arrays;

/** Small signal-processing helpers. Plain Java (no Android types) so they also run in the JVM checks. */
final class Dsp {
    private Dsp() {
    }

    /** Linear interpolation of (t, x) onto an even grid at fs from t[0]. Returns {tu, xu}. */
    static double[][] resample(double[] t, double[] x, double fs) {
        int n = t.length;
        int m = Math.max(0, (int) Math.floor((t[n - 1] - t[0]) * fs));
        double[] tu = new double[m], xu = new double[m];
        int j = 0;
        for (int i = 0; i < m; i++) {
            double ti = t[0] + i / fs;
            while (j < n - 2 && t[j + 1] < ti) j++;
            double dt = t[j + 1] - t[j];
            double a = dt > 0 ? (ti - t[j]) / dt : 0;
            tu[i] = ti;
            xu[i] = x[j] + a * (x[j + 1] - x[j]);
        }
        return new double[][]{tu, xu};
    }

    /**
     * Zero-phase band-pass: a 2nd-order Butterworth high-pass then low-pass (RBJ biquads), run
     * forward and backward like scipy's filtfilt, with odd reflection padding at both ends.
     */
    static double[] bandpass(double[] x, double fs, double lo, double hi) {
        int n = x.length;
        double mean = mean(x);
        int pad = Math.min(n - 1, (int) (3 * fs));
        double[] y = new double[n + 2 * pad];
        for (int i = 0; i < n; i++) y[pad + i] = x[i] - mean;
        for (int i = 0; i < pad; i++) {
            y[pad - 1 - i] = 2 * y[pad] - y[pad + 1 + i];
            y[pad + n + i] = 2 * y[pad + n - 1] - y[pad + n - 2 - i];
        }
        double[] hp = biquad(lo, fs, true), lp = biquad(hi, fs, false);
        for (int pass = 0; pass < 2; pass++) {
            run(y, hp);
            run(y, lp);
            reverse(y);
        }
        return Arrays.copyOfRange(y, pad, pad + n);
    }

    /** RBJ cookbook biquad coefficients {b0, b1, b2, a1, a2}, normalised by a0, Q = 1/sqrt(2). */
    private static double[] biquad(double f0, double fs, boolean highPass) {
        double w0 = 2 * Math.PI * f0 / fs, cos = Math.cos(w0), alpha = Math.sin(w0) / (2 / Math.sqrt(2));
        double a0 = 1 + alpha;
        double b0 = highPass ? (1 + cos) / 2 : (1 - cos) / 2;
        double b1 = highPass ? -(1 + cos) : 1 - cos;
        return new double[]{b0 / a0, b1 / a0, b0 / a0, -2 * cos / a0, (1 - alpha) / a0};
    }

    private static void run(double[] x, double[] c) {
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double x0 = x[i];
            double y0 = c[0] * x0 + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            x[i] = y0;
        }
    }

    private static void reverse(double[] x) {
        for (int i = 0, j = x.length - 1; i < j; i++, j--) {
            double t = x[i];
            x[i] = x[j];
            x[j] = t;
        }
    }

    /** |FFT|^2 of the Hann-windowed signal zero-padded to nfft (a power of two); bins 0..nfft/2. */
    static double[] powerSpectrum(double[] x, int nfft) {
        double[] re = new double[nfft], im = new double[nfft];
        int n = Math.min(x.length, nfft);
        for (int i = 0; i < n; i++) re[i] = x[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        fft(re, im);
        double[] p = new double[nfft / 2 + 1];
        for (int k = 0; k < p.length; k++) p[k] = re[k] * re[k] + im[k] * im[k];
        return p;
    }

    /** In-place iterative radix-2 FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - vr;
                    im[b] = im[a] - vi;
                    re[a] += vr;
                    im[a] += vi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    /**
     * Local maxima at least `distance` samples apart with prominence >= minProminence, like
     * scipy.signal.find_peaks: taller peaks win when two are too close.
     */
    static int[] findPeaks(double[] x, int distance, double minProminence) {
        int n = x.length;
        int[] cand = new int[n];
        int c = 0;
        for (int i = 1; i < n - 1; i++) {
            if (x[i] > x[i - 1] && x[i] >= x[i + 1] && prominence(x, i) >= minProminence) cand[c++] = i;
        }
        Integer[] order = new Integer[c];
        for (int i = 0; i < c; i++) order[i] = cand[i];
        Arrays.sort(order, (a, b) -> Double.compare(x[b], x[a]));
        boolean[] keep = new boolean[n];
        int kept = 0;
        for (int i : order) {
            boolean clash = false;
            for (int d = Math.max(0, i - distance + 1); d < Math.min(n, i + distance) && !clash; d++) clash = keep[d];
            if (!clash) {
                keep[i] = true;
                kept++;
            }
        }
        int[] out = new int[kept];
        for (int i = 0, k = 0; i < n; i++) if (keep[i]) out[k++] = i;
        return out;
    }

    private static double prominence(double[] x, int i) {
        double leftMin = x[i], rightMin = x[i];
        for (int j = i - 1; j >= 0 && x[j] <= x[i]; j--) leftMin = Math.min(leftMin, x[j]);
        for (int j = i + 1; j < x.length && x[j] <= x[i]; j++) rightMin = Math.min(rightMin, x[j]);
        return x[i] - Math.max(leftMin, rightMin);
    }

    /** Least-squares line: {slope, intercept, residual std (ddof 2)}; NaNs when fewer than 3 points. */
    static double[] lineFit(double[] t, double[] y) {
        int n = t.length;
        if (n < 3) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double mt = mean(t), my = mean(y), stt = 0, sty = 0;
        for (int i = 0; i < n; i++) {
            stt += (t[i] - mt) * (t[i] - mt);
            sty += (t[i] - mt) * (y[i] - my);
        }
        if (stt == 0) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double m = sty / stt, b = my - m * mt, ss = 0;
        for (int i = 0; i < n; i++) {
            double r = y[i] - (m * t[i] + b);
            ss += r * r;
        }
        return new double[]{m, b, n > 3 ? Math.sqrt(ss / (n - 2)) : Double.NaN};
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return x.length > 0 ? s / x.length : Double.NaN;
    }

    static double std(double[] x) {
        double m = mean(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return x.length > 0 ? Math.sqrt(s / x.length) : Double.NaN;
    }

    static double median(double[] x) {
        return percentile(x, 50);
    }

    /** Linear-interpolated percentile, like numpy's default. */
    static double percentile(double[] x, double q) {
        if (x.length == 0) return Double.NaN;
        double[] s = x.clone();
        Arrays.sort(s);
        double pos = q / 100 * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = Math.min(s.length - 1, lo + 1);
        return s[lo] + (pos - lo) * (s[hi] - s[lo]);
    }
}
