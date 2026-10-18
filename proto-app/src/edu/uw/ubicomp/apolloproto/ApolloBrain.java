package edu.uw.ubicomp.apolloproto;

import android.content.Context;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Apollo's chat replies, worked out on the phone from what the app knows: the latest readings, the
 * phone's readiness, steps, and the Care listings. Keyword intents, no network and no model: it
 * reflects on data and points to the right screen; it never diagnoses. Emergencies come first.
 */
final class ApolloBrain {
    /** A chat message. Apollo's may carry "Based on" sources and action buttons {label, action}. */
    static final class Msg {
        final boolean mine;
        final String text;   // **bold** marks emphasis
        final String basedOn;
        final List<String[]> actions = new ArrayList<>();

        Msg(boolean mine, String text, String basedOn) {
            this.mine = mine;
            this.text = text;
            this.basedOn = basedOn;
        }

        Msg act(String label, String action) {
            actions.add(new String[]{label, action});
            return this;
        }
    }

    // Actions MainActivity knows: temp, hr, calibrate, calm, trends, care:meds, care:services,
    // category:<id>, dial:<number>.

    static final String[] SUGGESTIONS = {
            "How's my heart rate?", "Do I have a fever?", "Find urgent care nearby", "What helps a headache?",
            "How does the temperature check work?", "I feel stressed", "How many steps today?"};

    private ApolloBrain() {
    }

    static Msg greeting() {
        return new Msg(false, "Hi, I'm **Apollo**. Ask me about your readings, what to pick up at the pharmacy, "
                + "or where to find care nearby.", null);
    }

    static Msg reply(Context c, String raw, long stepsToday) {
        String q = " " + raw.toLowerCase(Locale.US).replace('’', '\'').replaceAll("[^a-z0-9' ]", " ") + " ";

        // 1. Emergencies, before anything else.
        if (has(q, "suicid", "kill myself", "end my life", "hurt myself", "self harm", "want to die")) {
            return new Msg(false, "I'm really glad you told me. You deserve support right now. Please call or text "
                    + "**988** (Suicide & Crisis Lifeline) to talk to someone tonight. If you're in immediate danger, call **911**.", null)
                    .act("Call 988", "dial:988").act("Call 911", "dial:911");
        }
        if (has(q, "chest pain", "can't breathe", "cant breathe", "cannot breathe", "trouble breathing", "stroke",
                "seizure", "unconscious", "passed out", "overdose", "bleeding a lot", "severe bleeding", "face drooping")) {
            return new Msg(false, "That could be an emergency. **Call 911 now**, or have someone nearby call for you. "
                    + "Don't wait for a reading from me.", null)
                    .act("Call 911", "dial:911").act("Find the nearest ER", "care:services");
        }

        List<JSONObject> hr = readable(Store.history(c, "heart_rate"));
        List<JSONObject> fh = new ArrayList<>();
        for (JSONObject o : Store.history(c, "forehead")) if (o.optDouble("core_c_est", -1) > 0) fh.add(o);

        // 2. The user's own readings.
        if (has(q, "heart", "pulse", "bpm", "hr ")) {
            if (hr.isEmpty()) {
                return new Msg(false, "I don't have a heart-rate reading yet. It's a **20-second** fingertip check with the "
                        + "camera and flash. Want to take one now?", null).act("Check my heart rate", "hr");
            }
            JSONObject last = hr.get(0);
            double bpm = last.optDouble("bpm");
            String range = bpm < 60 ? "a little under the usual 60–100 resting range, which is common for fit people and while relaxed."
                    : bpm <= 100 ? "inside the usual 60–100 resting range."
                    : "above the usual 60–100 resting range. Movement, caffeine, heat or stress can do that; try again after a few calm minutes.";
            String trend = "";
            if (hr.size() >= 3) {
                double sum = 0;
                for (int i = 0; i < Math.min(10, hr.size()); i++) sum += hr.get(i).optDouble("bpm");
                trend = String.format(Locale.US, " Your recent average is **%.0f bpm**.", sum / Math.min(10, hr.size()));
            }
            return new Msg(false, String.format(Locale.US, "Your latest check-in was **%.0f bpm**, with a %s. That's %s%s",
                    bpm, "GOOD".equals(last.optString("verdict")) ? "good signal" : "weak signal", range, trend),
                    "heart rate · " + when(last.optLong("id")))
                    .act("Take another reading", "hr").act("See my trends", "trends");
        }
        if (has(q, "fever", "temperature", "temp ", "hot ", "chills", "feverish", "warm ")) {
            if (fh.isEmpty()) {
                int runs = Store.history(c, "calibration").size();
                return new Msg(false, "I don't have a temperature estimate yet. The check takes about **2.5 minutes**: the phone "
                        + "rests until it's steady, then you hold the screen to your forehead for 90 seconds."
                        + (runs == 0 ? " Calibrate once first so I can turn warming into a temperature." : ""), null)
                        .act("Take my temperature", "temp").act(runs == 0 ? "Calibrate first" : "Fever care", runs == 0 ? "calibrate" : "category:pain_fever");
            }
            JSONObject last = fh.get(0);
            double core = last.optDouble("core_c_est");
            boolean high = core >= SkinToCore.FEVER_FLAG_C;
            return new Msg(false, String.format(Locale.US, "Your last estimate was **%.1f °C (%.1f °F)**. %s "
                            + "It's a research estimate from the phone warming test, so a thermometer has the final word.",
                    core, core * 9 / 5 + 32, high ? "That's a bit above typical. Rest, drink fluids, and recheck in a few hours. "
                            + "See a doctor if it passes 39.4 °C (103 °F) or lasts more than 3 days."
                            : "That's in the typical range."),
                    "temperature · " + when(last.optLong("id")))
                    .act("Check again", "temp").act(high ? "Fever care" : "See my trends", high ? "category:pain_fever" : "trends");
        }
        if (has(q, "step", "walk", "activity", "active", "move")) {
            String n = stepsToday < 0 ? "I can't see the step counter yet (Apollo needs the activity permission)."
                    : String.format(Locale.US, "You've taken **%,d steps** today, %.0f%% of a 10,000 goal.%s", stepsToday,
                    stepsToday / 100.0, stepsToday < 3000 ? " A 10-minute walk adds about 1,000." : " Nice going.");
            return new Msg(false, n, stepsToday < 0 ? null : "step counter · today");
        }

        // 3. Care: places and products.
        if (has(q, "urgent care", "hospital", "clinic", "doctor", "er ", "emergency room", "nearby", "near me", "where can i",
                "lab ", "vaccine", "pediatric", "virtual visit")) {
            return new Msg(false, "Here's where to look. **ER** for life-threatening or severe symptoms, **urgent care** for "
                    + "same-day issues that aren't emergencies, and a **virtual visit** for quick questions from home.", null)
                    .act("Find care nearby", "care:services");
        }
        String cat = has(q, "headache", "pain", "ache", "sore ", "ibuprofen", "tylenol", "acetaminophen", "thermometer") ? "pain_fever"
                : has(q, "cough", "cold", "sore throat", "congest", "flu") ? "cold_cough"
                : has(q, "allerg", "sneez", "hay fever", "itchy") ? "allergy"
                : has(q, "stomach", "nause", "diarrh", "indigest", "heartburn", "constipat") ? "stomach"
                : has(q, "dehydrat", "electrolyte", "thirst", "hydrat", "heat") ? "electrolytes"
                : has(q, "eye", "scrape", "cut ", "bandage", "first aid", "burn") ? "first_aid"
                : has(q, "vitamin", "supplement") ? "vitamins"
                : has(q, "sleep", "insomnia", "can't fall asleep") ? "sleep" : null;
        if (cat != null) {
            String[] d = CareData.detail(cat);
            String tip = "pain_fever".equals(cat) ? "For a headache, water, food and a quiet break help first; a store-brand pain reliever can take the edge off. "
                    : "sleep".equals(cat) ? "A steady bedtime, a cool dark room and no screens for the last 30 minutes do the most. "
                    : "electrolytes".equals(cat) ? "Sip water through the day; on hot days or after a stomach bug, an electrolyte mix helps. "
                    : "";
            return new Msg(false, tip + "In Care, **" + d[0] + "** has options from nearby stores, with delivery links. "
                    + "Check with a pharmacist if you take other medication.", null)
                    .act("Open " + d[0], "category:" + cat);
        }

        // 4. Feelings and the app itself.
        if (has(q, "stress", "anxious", "anxiety", "overwhelm", "angry", "upset", "calm", "panic")) {
            return new Msg(false, "Let's slow it down together. Breathe in for **4**, hold for **4**, out for **6**, and repeat "
                    + "five times. Calm sensing can also notice restless movement and nudge you to pause.", null)
                    .act("Try calm sensing", "calm");
        }
        if (has(q, "how does", "how do i", "how to", "calibrat", "accura", "work")) {
            return new Msg(false, "The **temperature check** times how fast your forehead warms the phone through the screen "
                    + "(FeverPhone, UW). It needs a still, cool phone, so it waits until it's ready. The **heart-rate check** "
                    + "watches your fingertip dim slightly with each heartbeat, using the camera and flash.", null)
                    .act("Take my temperature", "temp").act("Calibrate", "calibrate");
        }
        if (has(q, "how am i", "summary", "today", "my day", "morning", "doing")) {
            StringBuilder b = new StringBuilder();
            String based = "";
            if (!hr.isEmpty()) {
                b.append(String.format(Locale.US, "Latest heart rate **%.0f bpm**. ", hr.get(0).optDouble("bpm")));
                based = "heart rate · " + when(hr.get(0).optLong("id"));
            }
            if (!fh.isEmpty()) {
                b.append(String.format(Locale.US, "Last temperature **%.1f °C**. ", fh.get(0).optDouble("core_c_est")));
                based += (based.isEmpty() ? "" : "\n") + "temperature · " + when(fh.get(0).optLong("id"));
            }
            if (stepsToday >= 0) b.append(String.format(Locale.US, "**%,d steps** so far today. ", stepsToday));
            if (b.length() == 0) b.append("No readings yet today. A quick check-in gives me something to go on. ");
            b.append("Anything you'd like to look at more closely?");
            return new Msg(false, b.toString(), based.isEmpty() ? null : based).act("Start a check-in", "temp");
        }
        if (has(q, " hi ", "hello", " hey ", "good morning", "good evening")) {
            return new Msg(false, "Hello! How are you feeling today?", null);
        }
        if (has(q, "thank")) return new Msg(false, "Anytime. I'm here when you need me.", null);

        return new Msg(false, "I'm a small helper that lives on this phone, so I know about your **readings**, "
                + "**nearby care** and the **tests in this app**. Try asking about your heart rate, a fever, or where to find urgent care.", null);
    }

    private static boolean has(String q, String... keys) {
        for (String k : keys) if (q.contains(k)) return true;
        return false;
    }

    private static List<JSONObject> readable(List<JSONObject> rows) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : rows) if (!"POOR".equals(o.optString("verdict")) && o.optDouble("bpm", -1) > 0) out.add(o);
        return out;
    }

    private static String when(long ms) {
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(ms));
    }
}
