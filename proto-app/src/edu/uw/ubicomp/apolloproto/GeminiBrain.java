package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Apollo's replies from Google's Gemini API (generateContent, REST). Only used after the user turns on Gemini mode in the
 * chat (see ChatPage): then the conversation and a short summary of their readings go to Google. The key and model come
 * from proto-app/gemini.key and proto-app/gemini.model, which build.sh bakes into Secrets (both gitignored). A key in an
 * APK can be pulled out, so this is for the team's test phones; a real app would call Gemini through its own server.
 */
final class GeminiBrain implements Brain {
    private static final String TAG = "GeminiBrain";
    private static final int HISTORY_TURNS = 12;

    private static final String SYSTEM = "You are Apollo, a warm, brief health companion in a University of Washington research "
            + "prototype app. You help people reflect on their own readings and find care; you never diagnose, and you never "
            + "claim certainty about a medical condition. The app's measurements are research estimates, not medical devices: "
            + "the temperature comes from how fast the forehead warms the phone, heart rate from a fingertip on the camera, "
            + "breath strength from the barometer, and eye tracking counts blinks and gaze shifts. For anything that could be "
            + "urgent, tell the person to call 911 or see a doctor, and suggest the care:services action. Suggest a pharmacist "
            + "before combining medicines. Keep replies to 2-4 short sentences in plain words; mark one or two key facts with "
            + "**double asterisks**. Only mention readings that appear in the summary; if one is missing, offer the test that "
            + "measures it. Choose 0-2 actions from the allowed list that would help next.";

    /** Buttons the app knows how to open (MainActivity.act), with their labels. */
    private static final String[][] ACTIONS = {
            {"temp", "Take my temperature"}, {"hr", "Check my heart rate"}, {"breath", "Try breath strength"},
            {"calm", "Try calm sensing"}, {"calibrate", "Calibrate"}, {"trends", "See my trends"},
            {"care:meds", "Browse Care"}, {"care:services", "Find care nearby"}};

    @Override
    public String name() {
        return "Gemini";
    }

    static boolean configured() {
        return !Secrets.GEMINI_KEY.isEmpty();
    }

    @Override
    public ApolloBrain.Msg reply(Context c, List<ApolloBrain.Msg> history, long stepsToday) {
        if (!configured()) return null;
        try {
            JSONObject body = new JSONObject()
                    .put("systemInstruction", parts(SYSTEM + "\n\nWhat the app knows right now:\n" + readings(c, stepsToday)))
                    .put("contents", contents(history))
                    .put("generationConfig", new JSONObject()
                            .put("temperature", 0.6)
                            .put("responseMimeType", "application/json")
                            .put("responseSchema", schema()));
            JSONObject out = post(body);
            String json = out.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text");
            JSONObject r = new JSONObject(json);
            String text = r.optString("text", "").trim();
            if (text.isEmpty()) return null;
            ApolloBrain.Msg m = new ApolloBrain.Msg(false, text, "Gemini · your readings summary");
            JSONArray acts = r.optJSONArray("actions");
            for (int i = 0; acts != null && i < acts.length() && m.actions.size() < 2; i++) {
                String label = label(acts.optString(i));
                if (label != null) m.act(label, acts.optString(i));
            }
            return m;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "no Gemini reply: " + e);
            return null;
        }
    }

    private static JSONObject post(JSONObject body) throws IOException, JSONException {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + Secrets.GEMINI_MODEL + ":generateContent");
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setConnectTimeout(6000);
        http.setReadTimeout(20000);
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", "application/json");
        http.setRequestProperty("x-goog-api-key", Secrets.GEMINI_KEY);
        try {
            try (OutputStream o = http.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + ": " + read(http.getErrorStream()));
            return new JSONObject(read(http.getInputStream()));
        } finally {
            http.disconnect();
        }
    }

    /** The last few turns, Apollo's as "model". Gemini needs the first turn to be the user's. */
    private static JSONArray contents(List<ApolloBrain.Msg> history) throws JSONException {
        JSONArray a = new JSONArray();
        int from = Math.max(0, history.size() - HISTORY_TURNS);
        while (from < history.size() && !history.get(from).mine) from++;
        for (int i = from; i < history.size(); i++) {
            ApolloBrain.Msg m = history.get(i);
            JSONObject turn = parts(m.text);
            turn.put("role", m.mine ? "user" : "model");
            a.put(turn);
        }
        return a;
    }

    /** A short, plain summary of the readings Apollo already shows the user. Nothing else from the phone is sent. */
    static String readings(Context c, long stepsToday) {
        List<String> lines = new ArrayList<>();
        List<JSONObject> hr = new ArrayList<>();
        for (JSONObject o : Store.history(c, "heart_rate")) if (!"POOR".equals(o.optString("verdict")) && o.optDouble("bpm", -1) > 0) hr.add(o);
        if (!hr.isEmpty()) {
            double sum = 0;
            int n = Math.min(10, hr.size());
            for (int i = 0; i < n; i++) sum += hr.get(i).optDouble("bpm");
            lines.add(String.format(Locale.US, "- Heart rate: latest %.0f bpm (%s signal, %s); average of the last %d: %.0f bpm",
                    hr.get(0).optDouble("bpm"), "GOOD".equals(hr.get(0).optString("verdict")) ? "good" : "weak",
                    when(hr.get(0).optLong("id")), n, sum / n));
        }
        for (JSONObject o : Store.history(c, "forehead")) {
            if (o.optDouble("core_c_est", -1) <= 0) continue;
            double core = o.optDouble("core_c_est");
            lines.add(String.format(Locale.US, "- Temperature estimate: %.1f °C / %.1f °F (%s); fever flag at %.1f °C",
                    core, core * 9 / 5 + 32, when(o.optLong("id")), SkinToCore.FEVER_FLAG_C));
            break;
        }
        List<JSONObject> br = Store.history(c, "breath");
        if (!br.isEmpty() && br.get(0).has("percent_of_baseline")) {
            lines.add(String.format(Locale.US, "- Breath strength: %d%% of their usual (%s)",
                    br.get(0).optInt("percent_of_baseline"), when(br.get(0).optLong("id"))));
        }
        if (stepsToday >= 0) lines.add(String.format(Locale.US, "- Steps today: %,d (goal 10,000)", stepsToday));
        if (EyeState.running && !Double.isNaN(EyeState.blinkRate)) {
            lines.add(String.format(Locale.US, "- Eyes, last minute: %.0f blinks/min, %.0f gaze shifts/min, read \"%s\" (exploratory)",
                    EyeState.blinkRate, EyeState.shiftRate, EyeState.read));
        }
        lines.add("- Now: " + new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(new Date()));
        return lines.size() == 1 ? "- No readings yet.\n" + lines.get(0) : String.join("\n", lines);
    }

    private static JSONObject schema() throws JSONException {
        JSONArray allowed = new JSONArray();
        for (String[] a : ACTIONS) allowed.put(a[0]);
        for (CareData.Category cat : CareData.CATALOG) allowed.put("category:" + cat.id);
        return new JSONObject().put("type", "OBJECT")
                .put("properties", new JSONObject()
                        .put("text", new JSONObject().put("type", "STRING"))
                        .put("actions", new JSONObject().put("type", "ARRAY")
                                .put("items", new JSONObject().put("type", "STRING").put("enum", allowed))))
                .put("required", new JSONArray().put("text"));
    }

    private static String label(String action) {
        for (String[] a : ACTIONS) if (a[0].equals(action)) return a[1];
        if (action.startsWith("category:")) {
            for (CareData.Category cat : CareData.CATALOG) if (action.equals("category:" + cat.id)) return "Open " + cat.title;
        }
        return null;
    }

    private static JSONObject parts(String text) throws JSONException {
        return new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String when(long ms) {
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(ms));
    }
}
