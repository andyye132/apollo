package edu.uw.ubicomp.apolloproto;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Known-answer tests for the plain-Java cores (run on the Mac: proto-app/check.sh core).
 *  - Sonar: finds the direct path; a moving echo gives far more motion energy than a still one.
 *  - JitaTrigger: quiet on a steady baseline, fires once on a sustained rise, respects the cooldown.
 *  - TouchWorld: a ball dropped on a touch bounces back up; a firmer touch bounces it higher.
 *  - FeverAnalysis: calibrating k at one known temperature recovers other temperatures from Newton curves.
 *  - FeverForest: a run that warms 1.2 °C/min from 28 °C gives those features, and a stump forest splits on them.
 *  - ReadingCheck: a still run is good; placing the phone doesn't count; a bump is rough; losing contact, no warming
 *    or moving the whole time asks for a redo.
 *  - BlowDetector: four blows on a noisy, drifting 25 Hz trace are found, a finger press is left out, the top three
 *    give the strength; too few or uneven blows are flagged; the baseline is the median of the last five.
 *  - EyeMetrics: the adaptive gaze-shift threshold catches 12° moves the old fixed one missed, without false shifts
 *    on a still gaze, and stays quiet on a noisy picture.
 *  - EyeLog: reads old and new minute files, weights rates by face time, and seeds baselines from new minutes only.
 *  - FloorCounter: 3 flights of stairs count 3; an elevator, the weather, a door's pressure jump and flat walking
 *    count none; going down counts as down; a long hill counts like Apple Health does.
 */
public class CoreCheck {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        sonar();
        jita();
        touch();
        fever();
        skinToCore();
        exposure();
        jolt();
        steadiness();
        forest();
        readingCheck();
        blows();
        eyes();
        floors();
        System.out.println(failures == 0 ? "all core checks passed" : failures + " core check(s) FAILED");
        if (failures > 0) System.exit(1);
    }

    /** Chirp value at a fractional sample position (periodic), for synthesising delayed echoes. */
    static double chirpAt(double n) {
        double p = ((n % Sonar.N) + Sonar.N) % Sonar.N;
        double[] c = Sonar.chirp();
        int i = (int) Math.floor(p);
        double f = p - i;
        return c[i] * (1 - f) + c[(i + 1) % Sonar.N] * f;
    }

    static void sonar() {
        System.out.println("Sonar (RageSense acoustic sensing)");
        Random rng = new Random(1);
        double motionStill = 0, motionMoving = 0;
        for (int mode = 0; mode < 2; mode++) {
            Sonar s = new Sonar();
            long sample = 37;   // arbitrary playback/record misalignment
            double sum = 0;
            int frames = 0;
            for (int period = 0; period < 120; period++) {
                double[] x = new double[Sonar.N];
                // Face echo 80 samples (~29 cm) behind the direct path, moving 1 mm when mode == 1.
                double echo = 20 + 80 + (mode == 1 ? 0.28 * Math.sin(2 * Math.PI * period / 25.0) : 0);
                for (int i = 0; i < Sonar.N; i++, sample++) {
                    x[i] = 0.5 * chirpAt(sample - 20) + 0.05 * chirpAt(sample - echo) + 0.002 * rng.nextGaussian();
                }
                Sonar.Frame f = s.process(x);
                if (f != null && period > 30) {
                    sum += f.motion;
                    frames++;
                }
            }
            if (mode == 0) motionStill = sum / frames;
            else motionMoving = sum / frames;
            // Mic sample i is chirp(i + 37 - 20), so the correlation peaks at lag -17 mod N = 495.
            if (mode == 0) check(s.directLag() == ((20 - 37) % Sonar.N + Sonar.N) % Sonar.N,
                    "direct path located at the expected lag (" + s.directLag() + ")");
        }
        // An audio glitch drops 7 samples: the direct path jumps 7 lags and the sonar should re-lock.
        Sonar g = new Sonar();
        long sample = 37;
        for (int period = 0; period < 60; period++) {
            if (period == 30) sample += 7;
            double[] x = new double[Sonar.N];
            for (int i = 0; i < Sonar.N; i++, sample++) x[i] = 0.5 * chirpAt(sample - 20) + 0.002 * rng.nextGaussian();
            g.process(x);
        }
        check(g.directLag() == 495 - 7, "re-locks after the stream skips 7 samples (" + g.directLag() + ")");
        System.out.printf(Locale.US, "        motion energy: still %.2e, moving %.2e (x%.0f)%n", motionStill, motionMoving, motionMoving / motionStill);
        check(motionMoving > 5 * motionStill, "a 1 mm moving echo stands out from a still one (5x or more)");
    }

    static void jita() {
        System.out.println("JitaTrigger (RageSense intervention timing)");
        Random rng = new Random(2);
        JitaTrigger j = new JitaTrigger();
        int baselineFires = 0, riseFires = 0;
        double firstFire = Double.NaN;
        for (double t = 0; t < 180; t += 0.5) {   // 3 min steady
            if (j.update(t, 1 + 0.1 * rng.nextGaussian())) baselineFires++;
        }
        for (double t = 180; t < 240; t += 0.5) {  // 1 min sustained rise
            if (j.update(t, 2 + 0.1 * rng.nextGaussian())) {
                riseFires++;
                if (Double.isNaN(firstFire)) firstFire = t;
            }
        }
        check(baselineFires == 0, "no intervention on a steady baseline (" + baselineFires + ")");
        check(riseFires >= 1 && riseFires <= 2, "fires on a sustained rise, cooldown limits repeats (" + riseFires + ")");
        check(firstFire - 180 < 6, String.format(Locale.US, "fires within 6 s of the rise (%.1f s)", firstFire - 180));
    }

    static double bounceHeight(double intensity) {
        TouchWorld w = new TouchWorld(1080, 1620);
        w.clearField();
        w.addTouch(540, 1100, 300, 160, Math.PI / 2, intensity);   // a sideways touch: a flat "shelf"
        w.smoothField();
        w.balls.clear();
        w.spawnBall();
        TouchWorld.Ball b = w.balls.get(0);
        b.x = 540;
        b.y = 300;
        b.vx = 0;
        b.vy = 0;
        double lowest = 0, apexAfter = Double.NaN;
        boolean goingUp = false;
        for (int i = 0; i < 600 && !w.balls.isEmpty(); i++) {
            w.step(1 / 120.0, false);
            if (w.balls.isEmpty()) break;
            lowest = Math.max(lowest, b.y);
            if (b.vy < 0) goingUp = true;
            if (goingUp && b.vy >= 0) {
                apexAfter = b.y;
                break;
            }
        }
        return goingUp ? lowest - apexAfter : 0;
    }

    static void touch() {
        System.out.println("TouchWorld (Magical Touch physics)");
        double soft = bounceHeight(0.35), firm = bounceHeight(1.0);
        System.out.printf(Locale.US, "        rebound height: light touch %.0f px, firm touch %.0f px%n", soft, firm);
        check(soft > 0 && firm > 0, "a falling ball bounces off a touch instead of passing through");
        check(firm > soft * 1.2, "firmer touch = denser gravel = higher bounce");
    }

    /** A thermistor curve: drift in the hand, then Newton heating toward skinC after a 10 s lag. */
    static FeverAnalysis.Series curve(String name, double k, double t0, double skinC, double drift, Random rng) {
        FeverAnalysis.Series s = new FeverAnalysis.Series(name, 3);
        double atContact = t0 + drift * 60 / 60;   // value reached at t = 60 s
        for (double t = 0; t <= 150; t += 1) {
            double v;
            if (t < 60) v = t0 + drift * t / 60;
            else {
                double tc = Math.max(0, t - 60 - 8);   // thermal lag
                v = skinC - (skinC - atContact) * Math.exp(-k * tc / 60) + drift * (t - 60) / 60;
            }
            s.points.add(new double[]{t, v + 0.004 * rng.nextGaussian()});
        }
        return s;
    }

    static FeverAnalysis.Report run(double skinC, double t0, double drift, Random rng) {
        Map<String, FeverAnalysis.Series> data = new LinkedHashMap<>();
        double[] ks = {0.12, 0.21, 0.30};
        String[] names = {"quiet-therm", "pa-therm", "sdm-therm"};
        for (int i = 0; i < 3; i++) data.put(names[i], curve(names[i], ks[i], t0 + 0.3 * i, skinC, drift, rng));
        return FeverAnalysis.analyze(data, 60, 150);
    }

    static double[] fill(double v) {
        double[] a = new double[24];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** Replays hr_1792290766703: locked at 11 ms on a settling finger, which then went clipped for the whole run. */
    static void exposure() {
        System.out.println("ExposureControl (camera PPG)");
        ExposureControl c = new ExposureControl(100_000L, 30_000_000L);
        long ns = 0, step = 200_000_000L;
        c.start(11_120_000L, ns);
        for (int i = 0; i < 3; i++) c.check(fill(181), fill(188), ns += step);   // settling finger, not clipped yet
        check(!c.adjusting && c.exp == 11_120_000L, "locks while the finger reads in range (11.12 ms)");
        for (int i = 0; i < 5; i++) c.check(fill(84), fill(254), ns += step);   // settled: red clipped (V 254)
        check(c.adjusting && c.exp < 11_120_000L, String.format(Locale.US, "re-tunes when the finger goes clipped after the lock (now %.2f ms)", c.exp / 1e6));
        ExposureControl b = new ExposureControl(100_000L, 30_000_000L);
        b.start(11_120_000L, 0);
        double[] y = fill(250), v = fill(128);   // blown to white: no longer skin-coloured
        b.check(y, v, step);
        check(b.exp < 11_120_000L, "tiles blown out to white count as clipped");
        ExposureControl g = new ExposureControl(100_000L, 30_000_000L);
        g.start(850_000L, 0);
        ns = 0;
        for (int i = 0; i < 20; i++) g.check(fill(34), fill(200), ns += step);   // well exposed: V 200, red 135
        check(!g.adjusting && g.exp == 850_000L, "a well-exposed finger stays locked");
    }

    /** 30 fps finger brightness with a 2% pulse at 85 bpm, plus hand tremor; then the events. */
    static void jolt() {
        System.out.println("JoltDetector (heart-rate restarts, lenient)");
        Random rng = new Random(5);
        JoltDetector d = new JoltDetector();
        boolean fired = false;
        for (int i = 0; i < 600; i++) {   // 20 s clean: pulse 2% of level, slow 3% breathing wander, tremor
            double t = i / 30.0;
            double b = 140 * (1 + 0.01 * Math.sin(2 * Math.PI * 85 / 60 * t) + 0.015 * Math.sin(2 * Math.PI * 0.25 * t));
            fired |= d.frame(t, b, 0.05 + 0.05 * rng.nextDouble(), 0.1 + 0.2 * rng.nextDouble());
        }
        check(!fired, "a clean recording with breathing wander and hand tremor never restarts");
        d.reset();
        fired = false;
        for (int i = 0; i < 300 && !fired; i++) {   // finger slides: brightness up 19% at 5 s
            double t = i / 30.0;
            fired = d.frame(t, 140 * (t < 5 ? 1 : 1.19), 0.05, 0.1);
        }
        check(fired && d.reason.contains("brightness"), "a slipped finger (+19% brightness) restarts: " + d.reason);
        d.reset();
        fired = false;
        for (int i = 0; i < 300 && !fired; i++) {   // a flinch: 1.6 rad/s rotation at 4 s
            double t = i / 30.0;
            fired = d.frame(t, 140, t > 4 && t < 4.1 ? 1.6 : 0.05, 0.2);
        }
        check(fired && d.reason.contains("rotated"), "a flinch (1.6 rad/s) restarts: " + d.reason);
        d.reset();
        fired = false;
        for (int i = 0; i < 300; i++) {   // a small adjustment: 0.6 rad/s, 1.2 m/s², +6% brightness
            double t = i / 30.0;
            fired |= d.frame(t, 140 * (t < 5 ? 1 : 1.06), t > 4 && t < 4.1 ? 0.6 : 0.05, t > 4 && t < 4.1 ? 1.2 : 0.2);
        }
        check(!fired, "a small adjustment (0.6 rad/s, 1.2 m/s², +6% brightness) does not restart");
    }

    /** The latest hand-held run's shape: still on the table, handled ~35 s before Start (tapping through the app). */
    static void steadiness() {
        System.out.println("Steadiness (readiness and the drift baseline)");
        int n = 121;
        double[] t = new double[n];
        boolean[] still = new boolean[n];
        for (int i = 0; i < n; i++) {
            t[i] = -120 + i;
            double gyro = t[i] < -35 ? 0.002 : t[i] < -2 ? 0.07 : 0.003;   // handled from -35 s, put down at -2 s
            if (t[i] == -60) gyro = 0.05;   // a tap while it lies on the table
            still[i] = Steadiness.still(gyro);
        }
        double[] b = Steadiness.baseline(t, still, 0);
        check(Double.isNaN(b[0]), "2 s of stillness right before Start isn't a baseline (" + b[0] + ")");
        double[] w = Steadiness.lastStill(t, still, -36);
        check(w[0] == -120 && w[1] == -36, String.format(Locale.US, "a tap doesn't break a still stretch (%.0f..%.0f s)", w[0], w[1]));
        boolean[] calm = new boolean[n];
        java.util.Arrays.fill(calm, true);
        double[] b2 = Steadiness.baseline(t, calm, 0);
        check(Math.abs(b2[0] + 40) < 1e-9 && Math.abs(b2[1] - 0.5) < 1e-9, "a phone left alone gets the last 40 s as its baseline");
        check(Steadiness.stillFor(t, calm) == 120 && Steadiness.stillFor(t, still) == 2, "still-for counts only the current stretch (put down 2 s ago)");
        double[][] flat = new double[2][n], warming = new double[2][n];
        for (int i = 0; i < n; i++) {
            flat[0][i] = warming[0][i] = t[i];
            flat[1][i] = 28 + 0.05 * t[i] / 60;
            warming[1][i] = 28 + 0.4 * t[i] / 60;
        }
        double d = Steadiness.maxDrift(java.util.Arrays.asList(flat, warming), -60, 0);
        check(Math.abs(d - 0.4) < 0.01, String.format(Locale.US, "readiness looks at the worst sensor (%.2f °C/min)", d));
    }

    /** Must match tools/skin_to_core.py's fit (values printed by that script). */
    static void skinToCore() {
        System.out.println("SkinToCore (FDA forehead -> oral model)");
        double[][] cases = {{34.57, 23.0, 37.0747}, {33.0, 23.0, 36.4131}, {35.5, 23.0, 37.4666}, {35.0, 26.0, 37.1269}};
        double worst = 0;
        for (double[] c : cases) worst = Math.max(worst, Math.abs(SkinToCore.coreC(c[0], c[1]) - c[2]));
        check(worst < 1e-3, String.format(Locale.US, "matches the Python fit (worst %.5f °C)", worst));
        check(Math.abs(SkinToCore.coreC(SkinToCore.skinFor(36.83)) - 36.83) < 1e-9
                        && Math.abs(SkinToCore.skinFor(36.83) - 34.09) < 0.01,
                String.format(Locale.US, "body 36.83 °C -> forehead skin %.2f °C -> back to 36.83", SkinToCore.skinFor(36.83)));
        check(!SkinToCore.possibleFever(SkinToCore.coreC(34.5)) && SkinToCore.possibleFever(SkinToCore.coreC(35.2)),
                "fever flag: forehead 34.5 °C no, 35.2 °C yes (cutoff ~35.05 °C skin at the default room 24 °C)");
    }

    static void fever() {
        System.out.println("FeverAnalysis (FeverPhone k calibration)");
        Random rng = new Random(3);
        Map<String, java.util.List<Double>> runs = new HashMap<>();
        double[][] calib = {{37.0, 29.0, -0.3}, {37.0, 31.0, 0.0}, {37.0, 27.5, -0.5}};   // reference, phone start, drift
        for (double[] c : calib) {
            for (FeverAnalysis.SensorResult s : run(c[0], c[1], c[2], rng).sensors) {
                runs.computeIfAbsent(s.name, x -> new java.util.ArrayList<>()).add(FeverAnalysis.kFromReference(s, c[0]));
            }
        }
        Map<String, Double> k = new HashMap<>();
        for (Map.Entry<String, java.util.List<Double>> e : runs.entrySet()) {
            double[] v = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            k.put(e.getKey(), Dsp.median(v));
        }
        System.out.printf(Locale.US, "        calibrated k_eff: %s%n", k);
        double worst = 0;
        for (double skin : new double[]{34.5, 35.5, 36.5, 38.0}) {
            FeverAnalysis.Estimate e = FeverAnalysis.estimate(run(skin, 28 + 3 * rng.nextDouble(), -0.4 * rng.nextDouble(), rng), k);
            System.out.printf(Locale.US, "        true %.1f °C -> estimate %.2f °C (spread %.2f across %d sensors)%n", skin, e.skinC, e.spreadC, e.sensors);
            worst = Math.max(worst, Math.abs(e.skinC - skin));
        }
        check(worst < 0.3, String.format(Locale.US, "recovers skin temperature within 0.3 °C after calibration (worst %.2f)", worst));
        // The Oct 17 table run: phone warming +0.4 °C/min on its own, contact +0.44 °C/min -> no number.
        Map<String, FeverAnalysis.Series> d = new LinkedHashMap<>();
        for (String name : new String[]{"pa-therm", "chg-therm"}) {
            FeverAnalysis.Series s = new FeverAnalysis.Series(name, 3);
            for (double tt = -60; tt <= 100; tt += 1) s.points.add(new double[]{tt, 29.3 + 0.4 * tt / 60 + 0.004 * rng.nextGaussian()});
            d.put(name, s);
        }
        double[] dr = FeverAnalysis.drift(FeverAnalysis.analyze(d, 10, 100, -41, -1));
        check(FeverAnalysis.drifting(dr), String.format(Locale.US, "a phone warming on its own (+%.2f °C/min) gives no number", dr[0]));
        Map<String, FeverAnalysis.Series> d2 = new LinkedHashMap<>();
        for (String name : new String[]{"pa-therm", "chg-therm"}) {
            FeverAnalysis.Series s = new FeverAnalysis.Series(name, 3);
            for (double tt = -3; tt <= 100; tt += 1) s.points.add(new double[]{tt, 29.3 + (tt > 10 ? 0.8 * (tt - 10) / 60 : 0)});
            d2.put(name, s);
        }
        double[] dr2 = FeverAnalysis.drift(FeverAnalysis.analyze(d2, 10, 100, -41, -1));
        check(Double.isNaN(dr2[0]), "3 s of baseline counts as no baseline (no number), not as a drift");
        FeverAnalysis.Report ok = run(36.5, 29, 0.02, rng);
        check(!FeverAnalysis.drifting(FeverAnalysis.drift(ok)), "a settled phone (+0.02 °C/min) passes the drift check");
    }

    static void forest() {
        System.out.println("FeverForest");
        // A run like the app records: 30 s still at 28 °C, a 10 s wait, then 90 s on a forehead warming 1.2 °C/min.
        java.util.List<double[]> a = new java.util.ArrayList<>(), b = new java.util.ArrayList<>();
        for (int s = -40; s <= 89; s++) {
            double c = 28 + (s > 0 ? 1.2 * s / 60.0 : 0);
            a.add(new double[]{100.25 + s, c + 0.05, s >= 0 ? 1 : 0});
            b.add(new double[]{100.25 + s, c - 0.05, s >= 0 ? 1 : 0});
        }
        double[] x = FeverForest.features(a, b);
        check(x != null && Math.abs(x[0] - 28) < 1e-9, "start temperature 28.00 (got " + (x == null ? "none" : String.format(Locale.US, "%.3f", x[0])) + ")");
        check(x != null && Math.abs(x[1] - 1.2) < 1e-9, "warming rate 1.20 °C/min");
        check(x != null && Math.abs(x[2] - 0.6) < 1e-9 && Math.abs(x[3] - 0.1) < 1e-9, "rise 0.60 °C at 30 s and 0.10 °C at 5 s");
        check(x != null && Math.abs(x[4] - 1.2 * 89 / 60) < 1e-9, "rise at the end of contact (89 s)");
        check(FeverForest.features(a.subList(0, 10), b.subList(0, 10)) == null, "too short a run gives no features");
        String text = "features start_c rate rise30 jump5 rise90\nmeta runs=2 people=1 loo_mae=0.1\n"
                + "tree 3\n1 1.0 1 2 37.0\n-2 -2.0 -1 -1 36.5\n-2 -2.0 -1 -1 37.5\n"
                + "tree 1\n-2 -2.0 -1 -1 37.0\n";
        try {
            FeverForest f = FeverForest.read(new java.io.ByteArrayInputStream(text.getBytes("UTF-8")));
            check(f.trees() == 2 && "2".equals(f.meta("runs", "")), "reads the trees and the meta line");
            check(Math.abs(f.predict(new double[]{28, 0.8, 0, 0, 0}) - 36.75) < 1e-12, "slow warming goes left: (36.5 + 37.0) / 2");
            check(Math.abs(f.predict(new double[]{28, 1.2, 0, 0, 0}) - 37.25) < 1e-12, "fast warming goes right: (37.5 + 37.0) / 2");
        } catch (java.io.IOException e) {
            check(false, "reads a forest: " + e);
        }
    }

    /** A synthetic 90 s contact: warming `rate` °C/min, with gyro {median, peak} from `motion(t)`. */
    static ReadingCheck run(double rate, java.util.function.DoubleUnaryOperator temp, java.util.function.DoubleFunction<double[]> motion) {
        double[] t = new double[100], y = new double[100];
        java.util.List<double[]> m = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            t[i] = i - 10;
            y[i] = temp.applyAsDouble(t[i]);
            if (t[i] >= 0) {
                double[] g = motion.apply(t[i]);
                m.add(new double[]{t[i], g[0], g[1]});
            }
        }
        return ReadingCheck.of(t, y, 89, m);
    }

    static void readingCheck() {
        System.out.println("ReadingCheck");
        java.util.function.DoubleUnaryOperator warm = s -> 28 + (s > 0 ? 1.2 * s / 60 : 0);
        java.util.function.DoubleFunction<double[]> still = s -> new double[]{0.01, 0.03};
        java.util.function.DoubleFunction<double[]> placed = s -> s < 2 ? new double[]{2.3, 4.0} : new double[]{0.01, 0.03};
        check(run(1.2, warm, still).verdict == ReadingCheck.Verdict.GOOD, "a still run on a forehead is good");
        check(run(1.2, warm, placed).verdict == ReadingCheck.Verdict.GOOD, "pressing the phone on (first 2 s, 2.3 rad/s) doesn't count");
        check(run(1.2, warm, s -> Math.abs(s - 40) < 0.5 ? new double[]{0.4, 3.5} : new double[]{0.01, 0.03}).verdict
                == ReadingCheck.Verdict.ROUGH, "a bump at 40 s (peak 3.5 rad/s) makes it rough");
        check(run(1.2, warm, s -> s >= 30 && s < 35 ? new double[]{0.2, 0.4} : new double[]{0.01, 0.03}).verdict
                == ReadingCheck.Verdict.ROUGH, "moving for 5 s makes it rough");
        check(run(1.2, warm, s -> s >= 20 && s < 45 ? new double[]{0.2, 0.4} : new double[]{0.01, 0.03}).verdict
                == ReadingCheck.Verdict.REDO, "moving for 25 s asks for a redo");
        check(run(0, s -> 28.0, still).verdict == ReadingCheck.Verdict.REDO, "no warming (not on a forehead) asks for a redo");
        check(run(1.2, s -> s < 50 ? warm.applyAsDouble(s) : warm.applyAsDouble(50) - 0.4 * (s - 50) / 10, still).verdict
                == ReadingCheck.Verdict.REDO, "falling 0.4 °C after 50 s (lifted off) asks for a redo");
        check(run(1.2, warm, still).orRough("still warming").verdict == ReadingCheck.Verdict.ROUGH, "a phone still warming by itself makes a good run rough");
    }

    /** A 36 s, 25 Hz barometer trace (hPa) at 1013 hPa with 1.6 Pa noise and 3 Pa/min drift, plus pulses {t, Pa, width s}. */
    static double[][] baro(double[][] pulses, long seed) {
        Random r = new Random(seed);
        int n = 36 * 25;
        double[] t = new double[n], hpa = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = i / 25.0;
            double pa = 101300 + 3 * t[i] / 60 + 1.6 * r.nextGaussian();
            for (double[] q : pulses) {
                double d = t[i] - q[0];
                if (q[2] > 1) {
                    if (d >= 0 && d < q[2]) pa += q[1];   // a press: flat for as long as it lasts
                } else {
                    pa += q[1] * Math.exp(-d * d / (2 * q[2] * q[2] / 9));         // the blow
                    pa -= 0.4 * q[1] * Math.exp(-(d - q[2]) * (d - q[2]) / (2 * 0.04));   // the glass springing back
                }
            }
            hpa[i] = pa / 100;
        }
        return new double[][]{t, hpa};
    }

    static void blows() {
        System.out.println("BlowDetector");
        double[][] s = baro(new double[][]{{4, 50, 0.5}, {13, 55, 0.5}, {22, 52, 0.5}, {31, 48, 0.5}, {17, 1200, 2.0}}, 7);
        BlowDetector.Summary sum = BlowDetector.analyze(s[0], s[1]);
        check(sum.blows().size() == 4, "finds the four blows (got " + sum.blows().size() + ")");
        check(sum.touches() == 1, "leaves out the 1,200 Pa finger press");
        check(Math.abs(sum.strengthPa - (55 + 52 + 50) / 3.0) < 4, String.format(Locale.US, "strength is the top-3 mean, ~52 Pa (got %.1f)", sum.strengthPa));
        check(sum.quality == BlowDetector.Quality.GOOD, "even blows are consistent (" + sum.quality + ")");
        double[][] few = baro(new double[][]{{4, 50, 0.5}, {13, 55, 0.5}}, 8);
        check(BlowDetector.analyze(few[0], few[1]).quality == BlowDetector.Quality.TOO_FEW, "two blows are too few");
        double[][] quiet = baro(new double[][]{}, 9);
        check(BlowDetector.analyze(quiet[0], quiet[1]).events.isEmpty(), "noise and drift alone give no blows");
        double[][] uneven = baro(new double[][]{{4, 15, 0.5}, {13, 60, 0.5}, {22, 110, 0.5}}, 10);
        check(BlowDetector.analyze(uneven[0], uneven[1]).quality == BlowDetector.Quality.INCONSISTENT, "15, 60 and 110 Pa are inconsistent");
        check(Double.isNaN(BlowDetector.baseline(java.util.Arrays.asList(40.0, 42.0))), "no baseline before three sessions");
        check(BlowDetector.baseline(java.util.Arrays.asList(10.0, 12.0, 11.0, 50.0, 13.0, 12.0)) == 12.0, "baseline: median of the last five (12)");
    }

    /** Synthetic eyes at 15 fps: two 30 px eyes, iris jitter sd px, a gaze jump of amp eye widths every 3 s. {caught, jumps, false/min} */
    static double[] gaze(EyeMetrics m, double amp, double sdPx, long seed) {
        Random r = new Random(seed);
        int jumps = 0, caught = 0, falseShifts = 0, until = -1;
        boolean pending = false;
        double pos = 0.5;
        int n = 15 * 180;
        for (int i = 0; i < n; i++) {
            if (amp > 0 && i > 0 && i % 45 == 0) {
                pos = pos == 0.5 ? 0.5 + amp : 0.5;
                jumps++;
                pending = true;
                until = i + 15;
            }
            float[] xs = new float[478], ys = new float[478];
            for (int eye = 0; eye < 2; eye++) {
                double x0 = eye == 0 ? 150 : 250, y0 = 300;
                int a = eye == 0 ? EyeMetrics.R_CORNER_A : EyeMetrics.L_CORNER_A, b = eye == 0 ? EyeMetrics.R_CORNER_B : EyeMetrics.L_CORNER_B;
                int iris = eye == 0 ? EyeMetrics.R_IRIS : EyeMetrics.L_IRIS;
                xs[a] = (float) (x0 + 0.3 * sdPx * r.nextGaussian());
                ys[a] = (float) (y0 + 0.3 * sdPx * r.nextGaussian());
                xs[b] = (float) (x0 + 30 + 0.3 * sdPx * r.nextGaussian());
                ys[b] = (float) (y0 + 0.3 * sdPx * r.nextGaussian());
                xs[iris] = (float) (x0 + pos * 30 + sdPx * r.nextGaussian());
                ys[iris] = (float) (y0 + sdPx * r.nextGaussian());
            }
            m.update(i * 1000L / 15, xs, ys, 0.05);
            if (m.shiftEvent) {
                if (pending && i <= until) {
                    caught++;
                    pending = false;
                } else {
                    falseShifts++;
                }
            }
            if (i > until) pending = false;
        }
        return new double[]{caught, jumps, falseShifts / 3.0};
    }

    static void eyes() {
        System.out.println("EyeMetrics / EyeLog");
        double[] old = gaze(new EyeMetrics(15, EyeMetrics.SHIFT_THRESHOLD, 2.2), 0.08, 0.5, 1);
        double[] adapt = gaze(new EyeMetrics(15), 0.08, 0.5, 1);
        check(old[0] == 0, String.format(Locale.US, "the old fixed threshold misses 12° moves (%.0f of %.0f)", old[0], old[1]));
        check(adapt[0] >= 0.9 * adapt[1], String.format(Locale.US, "the adaptive one catches them (%.0f of %.0f)", adapt[0], adapt[1]));
        check(gaze(new EyeMetrics(15), 0, 0.5, 2)[2] <= 0.4, "no false shifts on a still gaze (0.5 px jitter)");
        check(gaze(new EyeMetrics(15), 0, 1.0, 3)[2] <= 0.4, "no false shifts on a still gaze (1 px jitter)");
        check(gaze(new EyeMetrics(15), 0, 2.5, 4)[2] <= 5, "a noisy picture (2.5 px) stays under 5 false shifts a minute");
        try {
            String oldCsv = "minute_start_ms,minute_end_ms,frames,face_s,paused_s,blinks,gaze_shifts,blinks_per_face_min,shifts_per_face_min,read\n"
                    + "1000,61000,900,60.0,0.0,15,4,15.0,4.0,Learning your baseline\n";
            String newCsv = "minute_start_ms,minute_end_ms,frames,face_s,paused_s,blinks,gaze_shifts,blinks_per_face_min,shifts_per_face_min,read,shift_threshold,jitter\n"
                    + "61000,121000,900,30.0,0.0,10,6,20.0,12.0,Restless,0.0500,0.0080\n"
                    + "121000,181000,900,10.0,0.0,2,1,12.0,6.0,Not enough face time,0.0500,0.0080\n";
            java.util.List<EyeLog.Minute> a = EyeLog.parse(oldCsv), b = EyeLog.parse(newCsv);
            check(a.size() == 1 && Double.isNaN(a.get(0).threshold) && b.size() == 2 && b.get(0).threshold == 0.05,
                    "reads files with and without the threshold column");
            java.util.List<EyeLog.Minute> all = new java.util.ArrayList<>(a);
            all.addAll(b);
            EyeLog.Day d = EyeLog.summarize(all, 1000);
            check(d.ratedMinutes == 2 && d.restlessMinutes == 1, "rates 2 minutes (one restless); a 10 s minute isn't rated");
            check(Math.abs(d.blinkRate - 25 * 60 / 90.0) < 1e-9 && Math.abs(d.shiftRate - 10 * 60 / 90.0) < 1e-9,
                    "day rates are weighted by face time (16.7 blinks, 6.7 shifts a minute)");
            check(EyeLog.recentShiftRates(all, 10, 0).equals(java.util.Collections.singletonList(12.0)),
                    "the baseline takes only minutes from the adaptive detector");
        } catch (java.io.IOException e) {
            check(false, "parses minute files: " + e);
        }
    }

    /**
     * A day at 1 Hz: alt(s) metres, walking(s) true when stepping (1.6 steps a second), 1.6 Pa barometer noise, and
     * pressure drift of driftHpaPerHour. Returns {up, down} after everything is processed.
     */
    static int[] climb(int seconds, java.util.function.IntToDoubleFunction alt, java.util.function.IntPredicate walking,
                       double driftHpaPerHour, long seed) {
        Random r = new Random(seed);
        FloorCounter f = new FloorCounter();
        long t0 = 1_000_000;
        for (int s = 0; s < seconds; s++) {
            long t = t0 + s * 1000L;
            if (walking.test(s)) {
                f.step(t);
                if (s % 3 != 0) f.step(t + 600);   // ~1.6 steps a second
            }
            double hpa = 1013.25 * Math.pow(1 - alt.applyAsDouble(s) / 44330.0, 5.255) - driftHpaPerHour * s / 3600 + 0.016 * r.nextGaussian();
            f.pressure(t, hpa);
            f.flush(t);
        }
        f.flushAll();
        return new int[]{f.up, f.down};
    }

    static void floors() {
        System.out.println("FloorCounter");
        // Stand a minute, climb 9.3 m in 45 s (3 flights), stand a minute.
        int[] stairs = climb(165, s -> s < 60 ? 0 : s < 105 ? 9.3 * (s - 60) / 45 : 9.3, s -> s >= 60 && s < 105, 0, 1);
        check(stairs[0] == 3 && stairs[1] == 0, "three flights of stairs count 3 up (got " + stairs[0] + " up, " + stairs[1] + " down)");
        // Walk in, ride 12 m up in 20 s standing still, walk out on the flat.
        int[] lift = climb(140, s -> s < 40 ? 0 : s < 60 ? 12.0 * (s - 40) / 20 : 12, s -> s < 40 || s >= 70, 0, 2);
        check(lift[0] == 0, "an elevator ride counts nothing (got " + lift[0] + ")");
        // Two hours of weather (pressure falling 0.75 hPa an hour: ~6 m apparent rise), walking 10 min in every 30.
        int[] weather = climb(7200, s -> 0, s -> s % 1800 < 600, 0.75, 3);
        check(weather[0] == 0 && weather[1] == 0, "two hours of weather drift with walks count nothing (got " + weather[0] + ")");
        // Walking on the flat when a door opens: a 2.5 m step in apparent altitude that stays.
        int[] door = climb(180, s -> s < 90 ? 0 : 2.5, s -> true, 0, 4);
        check(door[0] == 0 && door[1] == 0, "a door's pressure jump while walking counts nothing");
        int[] downstairs = climb(165, s -> s < 60 ? 6.2 : s < 95 ? 6.2 - 6.2 * (s - 60) / 35 : 0, s -> s >= 60 && s < 95, 0, 5);
        check(downstairs[0] == 0 && downstairs[1] == 2, "two flights down count 2 down, 0 up");
        int[] hill = climb(300, s -> 6.5 * s / 300.0, s -> true, 0, 6);
        check(hill[0] == 2, "a 6.5 m hill walked up counts 2 flights, as Apple Health does (got " + hill[0] + ")");
        FloorCounter lag = new FloorCounter();
        for (int s = 0; s < 60; s++) {
            lag.step(s * 1000L);
            lag.step(s * 1000L + 500);
            lag.pressure(s * 1000L, 1013.25 * Math.pow(1 - (s < 10 ? 0 : 3.2 * Math.min(1, (s - 10) / 20.0)) / 44330.0, 5.255));
        }
        int early = lag.flush(59_000);
        int late = lag.flushAll();
        check(early == 0 && late == 1, "readings wait 40 s for their steps, then count");
    }
}
