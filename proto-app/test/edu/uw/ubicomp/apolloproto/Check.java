package edu.uw.ubicomp.apolloproto;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs the app's analysis code on recorded CSVs on the JVM, to compare with tools/analyze_ppg.py and
 * tools/analyze_forehead.py. Usage (from proto-app/): ./check.sh
 */
public class Check {
    public static void main(String[] args) throws IOException {
        for (String a : args) {
            String name = new java.io.File(a).getName();
            if (name.contains("forehead") || name.startsWith("calib_")) fever(a);
            else if (name.startsWith("watch_")) watch(a);
            else if (name.equals("results.jsonl")) replay(a);
            else ppg(a);
        }
    }

    static List<String[]> rows(String path, String[] headerOut) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String[] h = r.readLine().split(",");
            System.arraycopy(h, 0, headerOut, 0, h.length);
            for (String line; (line = r.readLine()) != null; ) out.add(line.split(","));
        }
        return out;
    }

    static void ppg(String path) throws IOException {
        String[] h = new String[200];
        List<String[]> rows = rows(path, h);
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < h.length && h[i] != null; i++) col.put(h[i], i);
        int n = rows.size();
        double[] t = new double[n], y = new double[n], u = new double[n], v = new double[n], epoch = new double[n], locked = new double[n];
        boolean hasTiles = col.containsKey("ty0");
        double[][] ty = new double[n][hasTiles ? 24 : 0], tv = new double[n][hasTiles ? 24 : 0];
        double t0 = Double.parseDouble(rows.get(0)[col.get("sensor_ns")]);
        for (int i = 0; i < n; i++) {
            String[] r = rows.get(i);
            t[i] = (Double.parseDouble(r[col.get("sensor_ns")]) - t0) / 1e9;
            epoch[i] = Double.parseDouble(r[col.get("epoch_ms")]);
            y[i] = Double.parseDouble(r[col.get("y")]);
            u[i] = Double.parseDouble(r[col.get("u")]);
            v[i] = Double.parseDouble(r[col.get("v")]);
            locked[i] = Double.parseDouble(r[col.get("ae_locked")]);
            for (int k = 0; hasTiles && k < 24; k++) {
                ty[i][k] = Double.parseDouble(r[col.get("ty" + k)]);
                tv[i][k] = Double.parseDouble(r[col.get("tv" + k)]);
            }
        }
        String phases = path.replace(".csv", "_phases.csv");
        List<double[]> segs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (new java.io.File(phases).exists()) {
            String[] ph = new String[8];
            for (String[] p : rows(phases, ph)) {
                double start = Double.parseDouble(p[1]), end = Double.parseDouble(p[2]);
                double s = start + 6000;
                for (int i = 0; i < n; i++) {
                    if (epoch[i] >= start && epoch[i] <= end - 1000 && locked[i] == 1) {
                        s = Math.max(s, epoch[i] + 1000);
                        break;
                    }
                }
                segs.add(new double[]{s, end - 1000});
                names.add(p[0]);
            }
        } else {
            segs.add(new double[]{epoch[0] + 8000, epoch[n - 1]});
            names.add("recording");
        }
        System.out.println("== " + path);
        for (int s = 0; s < segs.size(); s++) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < n; i++) if (epoch[i] >= segs.get(s)[0] && epoch[i] <= segs.get(s)[1]) idx.add(i);
            if (idx.size() < 300) {
                System.out.println("  " + names.get(s) + ": too short");
                continue;
            }
            int m = idx.size();
            double[] st = new double[m], sy = new double[m], su = new double[m], sv = new double[m];
            double[][] sty = new double[m][], stv = new double[m][];
            for (int j = 0; j < m; j++) {
                int i = idx.get(j);
                st[j] = t[i];
                sy[j] = y[i];
                su[j] = u[i];
                sv[j] = v[i];
                sty[j] = ty[i];
                stv[j] = tv[i];
            }
            PpgAnalysis.Choice c = PpgAnalysis.chooseSignal(sy, su, sv, sty, stv);
            PpgAnalysis.Result r = PpgAnalysis.analyze(st, c.signal);
            System.out.printf(Locale.US, "  %-10s %6.1f bpm  SNR %.2f  beats %6.1f  %-4s  (%s)  windows %s%s%n",
                    names.get(s), r.hrSpectrum, r.snr, r.hrBeats, r.verdict, c.channel, range(r.winBpm),
                    r.fromBeats ? "  <- beats confirmed by a spectral peak" : "");
        }
    }

    static String range(double[] x) {
        if (x.length == 0) return "-";
        double[] s = x.clone();
        Arrays.sort(s);
        return String.format(Locale.US, "%.0f-%.0f", s[0], s[s.length - 1]);
    }

    static String field(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":\"?([^,\"}]*)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Re-runs every saved heart-rate test on the exact window its report used, next to the watch. */
    static void replay(String path) throws IOException {
        java.io.File dir = new java.io.File(path).getParentFile();
        System.out.println("test             phone now (spectrum/beats, SNR, verdict)      was      watch");
        for (String line : java.nio.file.Files.readAllLines(new java.io.File(path).toPath())) {
            if (!line.contains("\"heart_rate\"") || field(line, "window_start_ms") == null) continue;
            java.io.File raw = new java.io.File(dir, field(line, "raw_file"));
            if (!raw.exists()) continue;
            double a = Double.parseDouble(field(line, "window_start_ms")), b = Double.parseDouble(field(line, "window_end_ms"));
            String[] h = new String[200];
            List<String[]> rows = rows(raw.getPath(), h);
            Map<String, Integer> col = new LinkedHashMap<>();
            for (int i = 0; i < h.length && h[i] != null; i++) col.put(h[i], i);
            if (!col.containsKey("ty0")) continue;
            List<String[]> in = new ArrayList<>();
            for (String[] r : rows) {
                double e = Double.parseDouble(r[col.get("epoch_ms")]);
                if (e >= a && e <= b) in.add(r);
            }
            int m = in.size();
            double[] st = new double[m], sy = new double[m], su = new double[m], sv = new double[m];
            double[][] sty = new double[m][24], stv = new double[m][24];
            double t0 = Double.parseDouble(in.get(0)[col.get("sensor_ns")]);
            for (int j = 0; j < m; j++) {
                String[] r = in.get(j);
                st[j] = (Double.parseDouble(r[col.get("sensor_ns")]) - t0) / 1e9;
                sy[j] = Double.parseDouble(r[col.get("y")]);
                su[j] = Double.parseDouble(r[col.get("u")]);
                sv[j] = Double.parseDouble(r[col.get("v")]);
                for (int k = 0; k < 24; k++) {
                    sty[j][k] = Double.parseDouble(r[col.get("ty" + k)]);
                    stv[j][k] = Double.parseDouble(r[col.get("tv" + k)]);
                }
            }
            PpgAnalysis.Choice c = PpgAnalysis.chooseSignal(sy, su, sv, sty, stv);
            PpgAnalysis.Result r = PpgAnalysis.analyze(st, c.signal);
            // The jolt detector's brightness half on this recording (the app's live value: mean red over skin tiles).
            JoltDetector jd = new JoltDetector();
            String jolt = "no jolt";
            for (int j = 0; j < m; j++) {
                double sum = 0;
                int ns = 0;
                for (int k = 0; k < 24; k++) {
                    if (ExposureControl.isSkin(sty[j][k], stv[j][k])) {
                        sum += sty[j][k] + 1.402 * (stv[j][k] - 128);
                        ns++;
                    }
                }
                if (ns > 0 && jd.frame(st[j], sum / ns, Double.NaN, Double.NaN)) {
                    jolt = String.format(Locale.US, "JOLT at %.1f s (%s)", st[j], jd.reason);
                    break;
                }
            }
            String w = field(line, "watch_bpm");
            System.out.printf(Locale.US, "%s  %5.1f (%5.1f/%5.1f, %.2f, %-4s)%s   %5s %-4s   %-14s %s%n", field(line, "id"),
                    r.verdict.equals("POOR") ? Double.NaN : r.hr, r.hrSpectrum, r.hrBeats, r.snr, r.verdict, r.fromBeats ? " beats" : "      ",
                    field(line, "bpm"), field(line, "verdict"), w == null ? "-" : w + " " + field(line, "watch_verdict"), jolt);
        }
    }

    /** A watch recording saved with a heart-rate test: the report's ~20 s window (file starts 5 s before the test). */
    static void watch(String path) throws IOException {
        String[] h = new String[16];
        List<String[]> rows = rows(path, h);
        double t0 = Double.parseDouble(rows.get(0)[0]);
        List<double[]> keep = new ArrayList<>();
        for (String[] r : rows) {
            double ms = Double.parseDouble(r[0]);
            if (ms - t0 >= 9000 && ms - t0 < 29000) {
                keep.add(new double[]{ms, Double.parseDouble(r[2]), Double.parseDouble(r[3]), Double.parseDouble(r[4])});
            }
        }
        double[][] d = new double[4][keep.size()];
        for (int i = 0; i < keep.size(); i++) for (int c = 0; c < 4; c++) d[c][i] = keep.get(i)[c];
        WatchAnalysis.Assessment a = WatchAnalysis.assess(d);
        if (System.getenv("DEBUG_WATCH") != null) {
            double[] tt = new double[d[0].length];
            for (int i = 0; i < tt.length; i++) tt[i] = (d[0][i] - d[0][0]) / 1000.0;
            double[] raw = WatchAnalysis.smooth(d[2]);
            PpgAnalysis.Result r = PpgAnalysis.analyze(tt, raw);
            System.out.printf(Locale.US, "   IR: pulse %.3f%% of the light level, 1 s jump %.2f%%, snr %.2f, hr %.1f%n", r.acPct,
                    WatchAnalysis.shiftPct(tt, raw), r.snr, r.hr);
        }
        System.out.printf(Locale.US, "== %s  %d samples: %s · channel %s · SNR %.2f · 1 s jump %.2f%% · moved %s%n   %s%n",
                new java.io.File(path).getName(), keep.size(), a.headline, a.channel < 0 ? "-" : WatchAnalysis.CHANNELS[a.channel],
                a.r == null ? Double.NaN : a.r.snr, a.shiftPct, a.moved, a.advice);
    }

    static void fever(String path) throws IOException {
        String[] h = new String[8];
        Map<String, FeverAnalysis.Series> data = new LinkedHashMap<>();
        double contactStart = Double.NaN, contactEnd = Double.NaN;
        List<String[]> rows = rows(path, h);
        int o = "epoch_ms".equals(h[0]) ? 1 : 0;   // the app's raw file has an extra leading epoch column
        for (String[] r : rows) {
            double t = Double.parseDouble(r[o]);
            String phase = r[o + 1], name = r[o + 2];
            if (name.startsWith("headroom") || name.equals("bcl-virt-monitor")) continue;
            if (phase.equals("forehead") || phase.equals("contact")) {   // forehead test / calibration screen
                if (Double.isNaN(contactStart) || t < contactStart) contactStart = t;
                if (Double.isNaN(contactEnd) || t > contactEnd) contactEnd = t;
            }
            data.computeIfAbsent(name, k -> new FeverAnalysis.Series(k, Integer.parseInt(r[o + 3])))
                    .points.add(new double[]{t, Double.parseDouble(r[o + 4])});
        }
        FeverAnalysis.Report rep = FeverAnalysis.analyze(data, contactStart, contactEnd);
        System.out.println("== " + path + String.format(Locale.US, "  contact %.0f-%.0f s", contactStart, contactEnd));
        for (int i = 0; i < Math.min(6, rep.sensors.size()); i++) {
            FeverAnalysis.SensorResult s = rep.sensors.get(i);
            System.out.printf(Locale.US, "  %-18s drift %+.2f  contact %+.2f  corrected %+.2f C/min  rise %+.2f  snr %6.1f%s%n",
                    s.name, s.driftCPerMin, s.slopeCPerMin, s.correctedCPerMin, s.riseC, s.snr, s.coarse ? "  (coarse)" : "");
        }
        // What a calibration would do: k if the skin had been at a typical 34.5 °C, and the skin each k implies.
        Map<String, Double> k = new LinkedHashMap<>();
        for (FeverAnalysis.SensorResult s : rep.sensors) {
            double ks = FeverAnalysis.kFromReference(s, 34.5);
            if (!Double.isNaN(ks)) k.put(s.name, ks);
        }
        System.out.print("  k_eff if skin was 34.5 C:");
        for (Map.Entry<String, Double> e : k.entrySet()) System.out.printf(Locale.US, "  %s %.3f/min", e.getKey(), e.getValue());
        System.out.println();
    }
}
