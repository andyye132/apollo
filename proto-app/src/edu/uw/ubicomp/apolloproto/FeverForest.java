package edu.uw.ubicomp.apolloproto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The random forest that turns a 90 s forehead run into a body temperature (trained on the computer by
 * tools/train_fever_forest.py, shipped as assets/fever_forest.txt).
 *
 * Features mirror tools/fever_explore.py exactly, from the run's raw CSV: the two sensors behind the screen
 * (pa-therm, chg-therm) averaged per second relative to the start of contact; the phone's start
 * temperature (mean of seconds -3..0), the warming rate (slope over 10 s..end of contact, °C/min), and the
 * rise at 5, 30 and 90 s. A forest can't predict outside the readings it was trained on, so it gets better
 * (and can flag fevers) only as runs with a wider spread of readings are added.
 */
final class FeverForest {
    static final String ASSET = "fever_forest.txt";
    private static final String[] FRONT = {"pa-therm", "chg-therm"};

    final String[] features;
    final Map<String, String> meta = new HashMap<>();
    private final List<double[][]> trees = new ArrayList<>();   // per tree: {feature, threshold, left, right, value} rows

    private static volatile FeverForest cached;

    private FeverForest(String[] features) {
        this.features = features;
    }

    /** The app's forest, read once (null when the asset is missing or unreadable). */
    static FeverForest get(InputStreamOpener assets) {
        FeverForest f = cached;
        if (f == null) {
            try (InputStream in = assets.open(ASSET)) {
                f = cached = read(in);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return f;
    }

    interface InputStreamOpener {
        InputStream open(String name) throws IOException;
    }

    static FeverForest read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        FeverForest f = null;
        double[][] tree = null;
        int filled = 0;
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p[0].equals("features")) {
                String[] names = new String[p.length - 1];
                System.arraycopy(p, 1, names, 0, names.length);
                f = new FeverForest(names);
            } else if (p[0].equals("meta")) {
                for (int i = 1; i < p.length; i++) {
                    int eq = p[i].indexOf('=');
                    if (eq > 0 && f != null) f.meta.put(p[i].substring(0, eq), p[i].substring(eq + 1));
                }
            } else if (p[0].equals("tree")) {
                tree = new double[Integer.parseInt(p[1])][];
                filled = 0;
                f.trees.add(tree);
            } else {
                tree[filled++] = new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                        Double.parseDouble(p[3]), Double.parseDouble(p[4])};
            }
        }
        if (f == null || f.trees.isEmpty()) throw new IOException("no forest in file");
        return f;
    }

    /** Body temperature, °C: the mean of every tree's leaf (sklearn compares features as float32). */
    double predict(double[] x) {
        double sum = 0;
        for (double[][] t : trees) {
            int n = 0;
            while (t[n][0] >= 0) n = (int) ((double) (float) x[(int) t[n][0]] <= t[n][1] ? t[n][2] : t[n][3]);
            sum += t[n][4];
        }
        return sum / trees.size();
    }

    int trees() {
        return trees.size();
    }

    String meta(String key, String fallback) {
        String v = meta.get(key);
        return v != null ? v : fallback;
    }

    // ---- Features ---------------------------------------------------------------------------------

    /** {start_c, rate, rise30, jump5, rise90} from a run's raw CSV, or null when the run is too short to read. */
    static double[] features(File csv) throws IOException {
        return features(Run.read(csv));
    }

    /** The same from in-memory points ({t, celsius, contact 1/0} per sample) for the two front sensors. */
    static double[] features(List<double[]> a, List<double[]> b) {
        return features(Run.of(a, b, new ArrayList<>()));
    }

    static double[] features(Run r) {
        if (r == null) return null;
        double[] tt = r.t, yy = r.front;
        int n = tt.length;
        double start = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (tt[i] >= -3 && tt[i] <= 0) {
                start += yy[i];
                k++;
            }
        }
        if (k > 0) {
            start /= k;
        } else {
            int best = 0;
            for (int i = 1; i < n; i++) if (Math.abs(tt[i]) < Math.abs(tt[best])) best = i;
            start = yy[best];
        }
        double rate = slope(tt, yy, 10, r.dur);
        if (Double.isNaN(rate)) return null;
        return new double[]{start, rate, interp(tt, yy, 30) - start, interp(tt, yy, 5) - start, interp(tt, yy, Math.min(90, r.dur)) - start};
    }

    /**
     * One run as both the forest and ReadingCheck see it: the screen-side temperature per second from the start
     * of contact, the contact's length, and the motion during contact ({t, 1 s median, 1 s peak rad/s}).
     */
    static final class Run {
        double[] t, front;
        double dur;
        final List<double[]> motion = new ArrayList<>();

        static Run read(File csv) throws IOException {
            Map<String, List<double[]>> by = new HashMap<>();   // sensor -> {t, value, contact 1/0}
            Map<Long, double[]> gyro = new HashMap<>();          // ms -> {t, median, peak}
            try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
                String line = r.readLine();   // header: epoch_ms,t_s,phase,sensor,type,celsius
                while ((line = r.readLine()) != null) {
                    String[] c = line.split(",");
                    if (c.length < 6) continue;
                    boolean contact = c[2].equals("contact") || c[2].equals("forehead");
                    if (c[3].equals(FRONT[0]) || c[3].equals(FRONT[1])) {
                        by.computeIfAbsent(c[3], k -> new ArrayList<>()).add(new double[]{Double.parseDouble(c[1]), Double.parseDouble(c[5]), contact ? 1 : 0});
                    } else if (contact && (c[3].equals("_gyro") || c[3].equals("_gyropeak"))) {
                        double[] g = gyro.computeIfAbsent(Long.parseLong(c[0]), k -> new double[]{Double.parseDouble(c[1]), Double.NaN, Double.NaN});
                        g[c[3].equals("_gyro") ? 1 : 2] = Double.parseDouble(c[5]);
                    }
                }
            }
            List<double[]> motion = new ArrayList<>(gyro.values());
            motion.sort((x, y) -> Double.compare(x[0], y[0]));
            return of(by.get(FRONT[0]), by.get(FRONT[1]), motion);
        }

        /** Null when there's no contact or fewer than 20 seconds with both sensors. Motion times are absolute; made relative here. */
        static Run of(List<double[]> a, List<double[]> b, List<double[]> motion) {
            if (a == null || b == null) return null;
            double c0 = Double.POSITIVE_INFINITY, c1 = Double.NEGATIVE_INFINITY;
            for (double[] p : a) {
                if (p[2] > 0) {
                    c0 = Math.min(c0, p[0]);
                    c1 = Math.max(c1, p[0]);
                }
            }
            if (Double.isInfinite(c0)) return null;
            Map<Long, Double> ma = perSecond(a, c0), mb = perSecond(b, c0);
            TreeSet<Long> secs = new TreeSet<>(ma.keySet());
            secs.retainAll(mb.keySet());
            if (secs.size() < 20) return null;
            Run r = new Run();
            r.t = new double[secs.size()];
            r.front = new double[secs.size()];
            int i = 0;
            for (long s : secs) {
                r.t[i] = s;
                r.front[i++] = (ma.get(s) + mb.get(s)) / 2;
            }
            r.dur = c1 - c0;
            for (double[] m : motion) r.motion.add(new double[]{m[0] - c0, m[1], m[2]});
            return r;
        }
    }

    /** Value per whole second from contact (the last sample wins, rounding half to even as Python does). */
    private static Map<Long, Double> perSecond(List<double[]> pts, double c0) {
        Map<Long, Double> m = new HashMap<>();
        for (double[] p : pts) m.put((long) Math.rint(p[0] - c0), p[1]);
        return m;
    }

    /** Least-squares slope over [from, to], in °C/min; NaN with fewer than 8 points. */
    private static double slope(double[] t, double[] y, double from, double to) {
        double st = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < t.length; i++) {
            if (t[i] >= from && t[i] <= to) {
                st += t[i];
                sy += y[i];
                n++;
            }
        }
        if (n < 8) return Double.NaN;
        double mt = st / n, my = sy / n, num = 0, den = 0;
        for (int i = 0; i < t.length; i++) {
            if (t[i] >= from && t[i] <= to) {
                num += (t[i] - mt) * (y[i] - my);
                den += (t[i] - mt) * (t[i] - mt);
            }
        }
        return num / den * 60;
    }

    /** np.interp: linear between samples, clamped at the ends. */
    private static double interp(double[] t, double[] y, double x) {
        if (x <= t[0]) return y[0];
        if (x >= t[t.length - 1]) return y[t.length - 1];
        int i = 1;
        while (t[i] < x) i++;
        double f = (x - t[i - 1]) / (t[i] - t[i - 1]);
        return y[i - 1] + f * (y[i] - y[i - 1]);
    }
}
