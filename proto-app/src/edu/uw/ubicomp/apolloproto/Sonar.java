package edu.uw.ubicomp.apolloproto;

/**
 * RageSense-style active acoustic sensing (CHI '26), the signal-processing half, in plain Java.
 *
 * The speaker loops an inaudible linear chirp (18-22 kHz, raised-cosine edges, 48 kHz); the microphone
 * hears the direct path plus echoes off the face and hands. Because the transmit signal repeats every
 * N samples, any N consecutive microphone samples hold one full period, so a circular cross-correlation
 * with the chirp gives the echo profile for that period with no clock alignment. Complex correlation
 * (analytic signal) makes sub-millimetre movement visible as phase change. Differencing consecutive
 * profiles removes static reflections (the paper's step), leaving only what moved.
 *
 * Differences from the paper: N = 512 samples (10.7 ms instead of 10 ms) so the FFT is a power of two,
 * and one microphone channel instead of two.
 */
final class Sonar {
    static final int FS = 48000, N = 512;
    static final double F0 = 18000, F1 = 22000, AMPLITUDE = 0.7;
    /** Echo range kept, in samples after the direct path: 10-170 samples = about 3.6-61 cm (3.57 mm per sample). */
    static final int BIN_FROM = 10, BIN_TO = 170, BINS = BIN_TO - BIN_FROM;
    static final double MM_PER_SAMPLE = 343.0 / FS / 2 * 1000;

    private final double[] chirpRe = new double[N], chirpIm = new double[N];   // conj spectrum of the chirp
    private final double[] re = new double[N], im = new double[N];
    private double[] prevRe, prevIm;
    private final double[] lagEnergy = new double[N];
    private int directLag = -1, framesSeen, offPeak;

    Sonar() {
        double[] c = chirp();
        System.arraycopy(c, 0, chirpRe, 0, N);
        Dsp.fft(chirpRe, chirpIm);
        for (int k = 0; k < N; k++) chirpIm[k] = -chirpIm[k];   // conjugate, for correlation
    }

    /** One period of the transmit chirp, with raised-cosine (Tukey, 10 %) edges so playback doesn't click. */
    static double[] chirp() {
        double[] x = new double[N];
        double T = (double) N / FS, taper = 0.1 * N;
        for (int n = 0; n < N; n++) {
            double t = (double) n / FS;
            double phase = 2 * Math.PI * (F0 * t + (F1 - F0) * t * t / (2 * T));
            double w = 1;
            if (n < taper) w = 0.5 - 0.5 * Math.cos(Math.PI * n / taper);
            else if (n > N - 1 - taper) w = 0.5 - 0.5 * Math.cos(Math.PI * (N - 1 - n) / taper);
            x[n] = AMPLITUDE * w * Math.sin(phase);
        }
        return x;
    }

    /** 16-bit PCM for AudioTrack: `periods` repeats of the chirp (loop the whole buffer). */
    static short[] pcm(int periods) {
        double[] c = chirp();
        short[] out = new short[N * periods];
        for (int i = 0; i < out.length; i++) out[i] = (short) Math.round(c[i % N] * 32767);
        return out;
    }

    /** Result of one period: motion energy in the face range, and the moved-amount per range bin (for the heat map). */
    static final class Frame {
        double motion;        // sum of |profile change|^2 over the face range / profile energy there
        float[] change;       // |complex profile change| per range bin, BINS long
        int directLag;
    }

    /**
     * Feed one period (N samples, any alignment, scaled to roughly [-1, 1]). Returns null until the
     * direct path has been located (first 20 periods) or when there's no previous profile to compare.
     */
    Frame process(double[] samples) {
        for (int i = 0; i < N; i++) {
            re[i] = samples[i];
            im[i] = 0;
        }
        Dsp.fft(re, im);
        // Cross-spectrum with the chirp, keeping positive frequencies only (doubled): the inverse
        // transform is then the analytic (complex) correlation, whose magnitude is the echo envelope.
        for (int k = 0; k < N; k++) {
            double a = re[k] * chirpRe[k] - im[k] * chirpIm[k];
            double b = re[k] * chirpIm[k] + im[k] * chirpRe[k];
            double keep = k == 0 || k == N / 2 ? 1 : k < N / 2 ? 2 : 0;
            re[k] = a * keep;
            im[k] = b * keep;
        }
        inverse(re, im);

        if (directLag < 0) {   // the direct speaker-to-mic path is the strongest, steadiest peak
            for (int k = 0; k < N; k++) lagEnergy[k] += re[k] * re[k] + im[k] * im[k];
            if (++framesSeen < 20) return null;
            int best = 0;
            for (int k = 1; k < N; k++) if (lagEnergy[k] > lagEnergy[best]) best = k;
            directLag = best;
        }
        // If the audio stream skipped samples, the direct path moves: re-find it after 10 frames off-peak.
        int peak = 0;
        double peakE = -1;
        for (int k = 0; k < N; k++) {
            double e2 = re[k] * re[k] + im[k] * im[k];
            if (e2 > peakE) {
                peakE = e2;
                peak = k;
            }
        }
        offPeak = Math.abs(peak - directLag) > 2 && Math.abs(peak - directLag) < N - 2 ? offPeak + 1 : 0;
        if (offPeak >= 10) {
            directLag = peak;
            offPeak = 0;
            prevRe = null;   // the old profile is no longer comparable
        }
        double[] pr = new double[BINS], pi = new double[BINS];
        for (int b = 0; b < BINS; b++) {
            int k = (directLag + BIN_FROM + b) % N;
            pr[b] = re[k];
            pi[b] = im[k];
        }
        Frame f = null;
        if (prevRe != null) {
            f = new Frame();
            f.change = new float[BINS];
            f.directLag = directLag;
            double moved = 0, energy = 1e-12;
            for (int b = 0; b < BINS; b++) {
                double dr = pr[b] - prevRe[b], di = pi[b] - prevIm[b];
                double d2 = dr * dr + di * di;
                f.change[b] = (float) Math.sqrt(d2);
                moved += d2;
                energy += pr[b] * pr[b] + pi[b] * pi[b];
            }
            f.motion = moved / energy;
        }
        prevRe = pr;
        prevIm = pi;
        return f;
    }

    int directLag() {
        return directLag;
    }

    /** Inverse FFT via the conjugate trick, scaled by 1/N. */
    private static void inverse(double[] re, double[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) im[i] = -im[i];
        Dsp.fft(re, im);
        for (int i = 0; i < n; i++) {
            re[i] /= n;
            im[i] = -im[i] / n;
        }
    }
}
