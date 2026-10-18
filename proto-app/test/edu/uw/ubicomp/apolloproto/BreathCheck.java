package edu.uw.ubicomp.apolloproto;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs BlowDetector on barometer recordings (t_ns,hpa: tools/barotest's BaroLogActivity or the app's breath_*.csv),
 * to compare with tools/barotest/analyze_baro.py. Usage: proto-app/check.sh tools/barotest/out/.../cal_P1.csv
 */
public class BreathCheck {
    public static void main(String[] args) throws IOException {
        for (String path : args) {
            List<double[]> rows = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                r.readLine();
                for (String line; (line = r.readLine()) != null; ) {
                    String[] a = line.split(",");
                    if (a.length < 2 || a[1].isEmpty()) continue;
                    try {
                        rows.add(new double[]{Double.parseDouble(a[0]) / 1e9, Double.parseDouble(a[1])});
                    } catch (NumberFormatException ignored) {   // a half-written last line
                    }
                }
            }
            double[] t = new double[rows.size()], p = new double[rows.size()];
            for (int i = 0; i < t.length; i++) {
                t[i] = rows.get(i)[0];
                p[i] = rows.get(i)[1];
            }
            BlowDetector.Summary s = BlowDetector.analyze(t, p);
            System.out.println(path + ": " + s.events.size() + " events");
            for (BlowDetector.Event e : s.events) {
                System.out.println(String.format(Locale.US, "  t=%5.1fs  peak %+6.1f Pa  area %5.1f Pa*s%s", e.tSec, e.peakPa,
                        e.areaPaS, e.isTouch() ? "   (touch, ignored)" : ""));
            }
            System.out.println(String.format(Locale.US, "  strength %.1f Pa (top-3 mean), spread %.0f%%, %s",
                    s.strengthPa, s.spreadPct, s.quality));
        }
    }
}
