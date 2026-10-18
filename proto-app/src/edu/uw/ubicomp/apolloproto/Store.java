package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local-only storage: one JSON object per result in results.jsonl, raw sessions as CSV, both in the
 * app's external files dir so `adb pull /sdcard/Android/data/edu.uw.ubicomp.apolloproto/files` gets
 * everything. Nothing leaves the phone.
 */
final class Store {
    static final String RESULTS = "results.jsonl";

    private Store() {
    }

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("apollo", Context.MODE_PRIVATE);
    }

    /** Inserts or replaces (by "id") a result. */
    static synchronized void save(Context c, JSONObject result) {
        List<JSONObject> all = readAll(c);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).optLong("id") == result.optLong("id")) {
                all.set(i, result);
                replaced = true;
            }
        }
        if (!replaced) all.add(result);
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(dir(c), RESULTS)))) {
            for (JSONObject o : all) w.println(o.toString());
        } catch (IOException ignored) {
        }
    }

    /** Appends one timestamped event (phone wall clock) to events.csv, for lining up with other devices. */
    static synchronized void event(Context c, long epochMs, long measurementId, String event, String detail) {
        File f = new File(dir(c), "events.csv");
        boolean fresh = !f.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            if (fresh) w.println("epoch_ms,measurement_id,event,detail");
            w.println(epochMs + "," + measurementId + "," + event + "," + detail.replace(',', ';'));
        } catch (IOException ignored) {
        }
    }

    /** Results of one type ("heart_rate", "forehead", or null for all), newest first. */
    static synchronized List<JSONObject> history(Context c, String type) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : readAll(c)) if (type == null || type.equals(o.optString("type"))) out.add(o);
        Collections.reverse(out);
        return out;
    }

    private static List<JSONObject> readAll(Context c) {
        List<JSONObject> out = new ArrayList<>();
        File f = new File(dir(c), RESULTS);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.trim().isEmpty()) continue;
                try {
                    out.add(new JSONObject(line));
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }
}
