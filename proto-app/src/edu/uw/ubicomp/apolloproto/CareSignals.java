package edu.uw.ubicomp.apolloproto;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The recommender's memory: what the user told Apollo and what he answered, searches, products opened,
 * delivery tapped and shelves hidden. One JSON object per line in the app's private files (never synced,
 * never sent), the newest 400 kept. Also gathers the latest readings for CareModel.
 */
final class CareSignals {
    private static final String FILE = "care_signals.jsonl";
    private static final int KEEP = 400;
    private static CareModel model;

    private CareSignals() {
    }

    /** The recommender, its classifier trained once per run (a few milliseconds). */
    static synchronized CareModel model() {
        if (model == null) model = new CareModel(CareModel.tuned());
        return model;
    }

    static synchronized void log(Context c, CareModel.Kind kind, String text, String need) {
        File f = new File(c.getFilesDir(), FILE);
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("kind", kind.name());
            if (text != null) o.put("text", text);
            if (need != null) o.put("need", need);
            w.println(o);
        } catch (IOException | JSONException ignored) {
        }
        if (f.length() > 120_000) trim(f);
    }

    /** Forget everything (the "Reset" under Picked for you). */
    static synchronized void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
    }

    /** Everything remembered, as signals aged from now. */
    static synchronized List<CareModel.Signal> load(Context c) {
        List<CareModel.Signal> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (JSONObject o : read(new File(c.getFilesDir(), FILE))) {
            try {
                out.add(new CareModel.Signal(CareModel.Kind.valueOf(o.getString("kind")), (now - o.getLong("t")) / 3.6e6,
                        o.optString("text", null), o.optString("need", null)));
            } catch (JSONException | IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    /**
     * A chat turn: the user's words, Apollo's reply, and any shelf he pointed to. Returns a need the user's own
     * words point to with confidence, or null. Emergencies and crises aren't remembered (they're not shopping).
     */
    static String chat(Context c, String said, ApolloBrain.Msg reply) {
        for (String[] act : reply.actions) if (act[1].startsWith("dial:")) return null;
        log(c, CareModel.Kind.CHAT, said, null);
        log(c, CareModel.Kind.APOLLO, reply.text.replace("**", ""), null);
        for (String[] act : reply.actions) {
            if (act[1].startsWith("category:") && CareData.need(act[1].substring(9)) != null) {
                log(c, CareModel.Kind.APOLLO_PICK, null, act[1].substring(9));
            }
        }
        String best = null;
        double p = 0.6;
        for (Map.Entry<String, Double> e : model().needs(said).entrySet()) {
            if (e.getValue() > p) {
                best = e.getKey();
                p = e.getValue();
            }
        }
        return best;
    }

    /** The latest readings: temperature and pulse estimates, breath strength, today's steps, today's blinking and screen time. */
    static CareModel.Readings readings(Context c) {
        CareModel.Readings r = new CareModel.Readings();
        long now = System.currentTimeMillis();
        for (JSONObject o : Store.history(c, "forehead")) {
            if (o.optDouble("core_c_est", -1) > 0) {
                r.tempC = o.optDouble("core_c_est");
                r.tempAgeH = (now - o.optLong("id")) / 3.6e6;
                break;
            }
        }
        for (JSONObject o : Store.history(c, "heart_rate")) {
            if (!"POOR".equals(o.optString("verdict")) && o.optDouble("bpm", -1) > 0) {
                r.bpm = o.optDouble("bpm");
                r.bpmAgeH = (now - o.optLong("id")) / 3.6e6;
                break;
            }
        }
        for (JSONObject o : Store.history(c, "breath")) {
            String q = o.optString("quality");
            if (o.has("percent_of_baseline") && ("GOOD".equals(q) || "OK".equals(q))) {
                r.breathPct = o.optDouble("percent_of_baseline");
                r.breathAgeH = (now - o.optLong("id")) / 3.6e6;
                break;
            }
        }
        r.steps = Store.prefs(c).getLong("steps_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()), 0);
        try {   // passive eye tracking, when it has run today
            EyeLog.Day d = EyeLog.summarize(EyeLog.on(EyeLog.load(Store.dir(c)), now), now);
            r.blinkRate = d.blinkRate;
            r.screenMin = d.faceMinutes;
        } catch (RuntimeException ignored) {
        }
        return r;
    }

    private static List<JSONObject> read(File f) {
        List<JSONObject> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    if (!line.trim().isEmpty()) out.add(new JSONObject(line));
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void trim(File f) {
        List<JSONObject> all = read(f);
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
            for (JSONObject o : all.subList(Math.max(0, all.size() - KEEP), all.size())) w.println(o);
        } catch (IOException ignored) {
        }
    }
}
