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
    }
}
