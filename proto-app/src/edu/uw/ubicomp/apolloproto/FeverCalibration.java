package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-phone-model, per-power-mode FeverPhone k values (per minute), one list per thermistor, one entry
 * per calibration run against a known temperature. The estimate uses each sensor's median k.
 */
final class FeverCalibration {
    private FeverCalibration() {
    }

    private static String key(String powerMode) {
        return "fever_k|" + Build.MODEL + "|" + powerMode;
    }

    /** File a computer can push (adb push ... files/calibration_import.json) to set k: {"UNPLUGGED|hand": {"pa-therm": [0.19], ...}}. */
    static final String IMPORT_FILE = "calibration_import.json";

    /** Applies a pushed calibration file once (its keys replace what's stored), then renames it. */
    static synchronized void importIfPresent(Context c) {
        java.io.File f = new java.io.File(Store.dir(c), IMPORT_FILE);
        if (!f.exists()) return;
        try {
            JSONObject in = new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            android.content.SharedPreferences.Editor e = Store.prefs(c).edit();
            for (Iterator<String> it = in.keys(); it.hasNext(); ) {
                String key = it.next();   // "<power mode>|<site>"
                e.putString("fever_k|" + Build.MODEL + "|" + key, in.getJSONObject(key).toString());
            }
            e.apply();
            f.renameTo(new java.io.File(Store.dir(c), "calibration_imported_" + System.currentTimeMillis() + ".json"));
            Store.event(c, System.currentTimeMillis(), 0, "calibration_imported", in.toString());
        } catch (Exception ex) {
            f.renameTo(new java.io.File(Store.dir(c), "calibration_import_FAILED.json"));
        }
    }

    private static JSONObject load(Context c, String powerMode) {
        importIfPresent(c);
        try {
            return new JSONObject(Store.prefs(c).getString(key(powerMode), "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Median k per sensor, from all calibration runs in this power mode. */
    static Map<String, Double> kBySensor(Context c, String powerMode) {
        JSONObject all = load(c, powerMode);
        Map<String, Double> out = new HashMap<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String sensor = it.next();
            JSONArray a = all.optJSONArray(sensor);
            if (a == null || a.length() == 0) continue;
            double[] v = new double[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = a.optDouble(i);
            out.put(sensor, Dsp.median(v));
        }
        return out;
    }

    /** Every stored k per sensor (one per run), for showing how consistent the runs are. */
    static Map<String, double[]> allK(Context c, String powerMode) {
        JSONObject all = load(c, powerMode);
        Map<String, double[]> out = new HashMap<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String sensor = it.next();
            JSONArray a = all.optJSONArray(sensor);
            if (a == null || a.length() == 0) continue;
            double[] v = new double[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = a.optDouble(i);
            out.put(sensor, v);
        }
        return out;
    }

    /** Number of calibration runs stored (the longest per-sensor list). */
    static int runs(Context c, String powerMode) {
        JSONObject all = load(c, powerMode);
        int n = 0;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) n = Math.max(n, all.optJSONArray(it.next()).length());
        return n;
    }

    /** Adds one run's k per sensor (NaN entries skipped). Returns how many sensors were usable. */
    static int add(Context c, String powerMode, FeverAnalysis.Report rep, double refC) {
        JSONObject all = load(c, powerMode);
        int used = 0;
        try {
            for (FeverAnalysis.SensorResult s : rep.sensors) {
                double k = FeverAnalysis.kFromReference(s, refC);
                if (Double.isNaN(k)) continue;
                JSONArray a = all.optJSONArray(s.name);
                if (a == null) {
                    a = new JSONArray();
                    all.put(s.name, a);
                }
                a.put(Math.round(k * 10000) / 10000.0);
                used++;
            }
        } catch (JSONException ignored) {
        }
        Store.prefs(c).edit().putString(key(powerMode), all.toString()).apply();
        return used;
    }

    static void clear(Context c, String powerMode) {
        Store.prefs(c).edit().remove(key(powerMode)).apply();
    }
}
