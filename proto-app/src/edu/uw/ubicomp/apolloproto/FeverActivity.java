package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FeverPhone forehead test: phone-readiness check -> 60 s pre-hold -> 90 s screen on the forehead
 * (spoken cues; the screen ignores touches) -> report on how fast the forehead warmed the phone.
 *
 * Two protocols. "hand": hold by the corners, then press the screen to the forehead (as in the paper).
 * "table": the phone lies screen-up on a table and the forehead rests on it, elbows down. The table
 * protocol keeps the hand's heat out and makes the pressure (and so k) the same every time, which the
 * paper got from the touchscreen's contact area, unavailable here: the Pixel 4 drops the forehead touch
 * ~2.5 s after contact. Calibration is stored per protocol.
 */
public class FeverActivity extends Activity implements ThermalMonitor.Listener {
    private static final int PREHOLD_S = 60, CONTACT_S = 90;

    private enum Phase { INTRO, PREHOLD, CONTACT, ANALYZING, REPORT }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, FeverAnalysis.Series> data = new LinkedHashMap<>();
    private ThermalMonitor monitor;
    private Speaker speaker;
    private volatile Phase phase = Phase.INTRO;
    private long startMs;
    private boolean anyway, table;
    private int lastRemaining = Integer.MAX_VALUE;
    private StepList introSteps;
    private ThermalMonitor.State startState;
    private PrintWriter raw;
    private File rawFile;
    private TextView statusText, countdown, phaseText, nextText;
    private TextView startButton, anywayButton;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        monitor = ThermalMonitor.get(this);
        speaker = new Speaker(this);
        table = "table".equals(Store.prefs(this).getString("fever_protocol", "hand"));
        showIntro(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitor.acquire();
        monitor.addListener(this);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        monitor.removeListener(this);
        monitor.release();
        ui.removeCallbacks(tick);
        if (phase == Phase.PREHOLD || phase == Phase.CONTACT) abort("Stopped: the app went to the background.");
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (phase == Phase.PREHOLD || phase == Phase.CONTACT) {
            speaker.say("Cancelled.");
            abort("Cancelled.");
        } else {
            super.onBackPressed();
        }
    }

    // ---- Intro ----------------------------------------------------------------------------------

    private void showIntro(String note) {
        phase = Phase.INTRO;
        restoreScreen();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Forehead temperature (FeverPhone)"), 4);
        Ui.add(col, Ui.title(this, "Forehead warming test"), 4);
        Ui.add(col, Ui.text(this, "Your forehead warms the phone through the screen. How fast it warms is what "
                + "FeverPhone (UW Ubicomp, 2023) turns into a temperature. About 2.5 minutes.", 15, Ui.MUTED, false), 8);
        if (note != null) Ui.add(col, Ui.badge(this, note, Ui.WARN), 12);

        LinearLayout pick = Ui.add(col, Ui.row(this), 14);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        pick.addView(Ui.button(this, "Hand-held", Ui.TEMP, !table, v -> setProtocol(false)), l1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        l2.leftMargin = Ui.dp(this, 10);
        pick.addView(Ui.button(this, "On a table", Ui.TEMP, table, v -> setProtocol(true)), l2);
        Ui.add(col, Ui.text(this, table
                ? "Table: the phone lies screen-up and you rest your forehead on it. Same pressure every time and no hand "
                + "heat, so it should be more repeatable. Calibrated separately."
                : "Hand-held: as in the paper. Easier anywhere, but the pressure varies run to run, which changes the "
                + "reading.", 13, Ui.MUTED, false), 6);
        introSteps = Ui.add(col, new StepList(this, Ui.TEMP,
                "Wait until the phone is ready (green, below)",
                table ? "Tap Start and leave the phone screen-up on the table for 1 minute"
                        : "Tap Start, then hold the phone by its corners for 1 minute",
                table ? "When the phone says so, lean on your elbows and rest your forehead on the screen for 90 seconds"
                        : "When the phone says so, press the screen to your forehead for 90 seconds",
                "Read your results"), 16);

        LinearLayout ready = Ui.add(col, Ui.card(this), 12);
        Ui.add(ready, Ui.label(this, "Is the phone ready?"), 0);
        statusText = Ui.add(ready, Ui.text(this, "Checking…", 16, Ui.TEXT, true), 8);
        phaseText = Ui.add(ready, Ui.text(this, "", 13, Ui.MUTED, false), 6);

        LinearLayout tips = Ui.add(col, Ui.card(this), 12);
        Ui.add(tips, Ui.label(this, "Before you start"), 0);
        String[] how = {
                "•  Turn on airplane mode and take the phone out of its case.",
                "•  You won't see the screen on your forehead, so the phone talks you through it: it tells you when "
                        + "to switch, then counts down 60, 30 and 10 seconds and the last 5. A buzz means switch or done.",
                "•  The screen ignores touches during the test. Press Back to cancel.",
                "•  Have a thermometer? Take your temperature afterwards and type it into the results.",
        };
        for (String s : how) Ui.add(tips, Ui.text(this, s, 15, Ui.TEXT, false), 8);
        Ui.add(col, Ui.text(this, "Do this before a heart-rate test: the camera light warms the phone.", 13, Ui.MUTED, false), 12);

        startButton = Ui.add(col, Ui.button(this, "Start", Ui.TEMP, true, v -> start(false)), 20);
        anywayButton = Ui.add(col, Ui.button(this, "Start anyway (marked unreliable)", Ui.MUTED, false, v -> start(true)), 10);
        setContentView(Ui.page(this, col));
        updateIntro();
    }

    private void setProtocol(boolean onTable) {
        if (onTable == table) return;
        table = onTable;
        Store.prefs(this).edit().putString("fever_protocol", table ? "table" : "hand").apply();
        showIntro(null);
    }

    private String protocol() {
        return table ? "table" : "hand";
    }

    /** Calibration is only valid for the same power mode and protocol. */
    private String calibKey() {
        return startState.powerMode + "|" + protocol();
    }

    private void updateIntro() {
        if (statusText == null) return;
        ThermalMonitor.State s = monitor.state();
        boolean ready = "READY".equals(s.verdict), unsupported = "UNSUPPORTED".equals(s.verdict);
        statusText.setText(s.message);
        statusText.setTextColor(Ui.verdictColor(s.verdict));
        boolean airplane = Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        phaseText.setText(String.format(Locale.US, "Phone %s · %s · power: %s · airplane mode %s",
                Double.isNaN(s.phoneC) ? "-" : String.format(Locale.US, "%.1f °C", s.phoneC),
                Double.isNaN(s.driftCPerMin) ? "drift -" : String.format(Locale.US, "%+.2f °C/min", s.driftCPerMin),
                s.powerMode.toLowerCase(Locale.US), airplane ? "on" : "OFF"));
        if (introSteps != null) introSteps.setCurrent(ready ? 1 : 0);
        startButton.setAlpha(ready ? 1f : 0.35f);
        startButton.setEnabled(ready);
        anywayButton.setVisibility(ready || unsupported ? View.GONE : View.VISIBLE);
    }

    // ---- Measuring ------------------------------------------------------------------------------

    private void start(boolean anyway) {
        this.anyway = anyway;
        startState = monitor.state();
        synchronized (data) {
            data.clear();
        }
        lastRemaining = Integer.MAX_VALUE;
        startMs = System.currentTimeMillis();
        rawFile = new File(Store.dir(this), "forehead_" + startMs + ".csv");
        try {
            raw = new PrintWriter(new FileWriter(rawFile), true);
            raw.println("epoch_ms,t_s,phase,sensor,type,celsius");
        } catch (IOException e) {
            raw = null;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.2f;   // FeverPhone fixes brightness at 20%
        getWindow().setAttributes(lp);

        // Full-screen, touch-proof view: the forehead will be pressing on it.
        FrameLayout blocker = new FrameLayout(this);
        blocker.setBackgroundColor(Color.BLACK);
        blocker.setOnTouchListener((v, e) -> true);
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER);
        phaseText = Ui.add(col, Ui.text(this, "", 20, Ui.MUTED, true), 0);
        phaseText.setGravity(Gravity.CENTER);
        countdown = Ui.add(col, Ui.text(this, "", 72, Ui.TEMP, true), 8);
        countdown.setGravity(Gravity.CENTER);
        nextText = Ui.add(col, Ui.text(this, "", 15, Ui.MUTED, false), 8);
        nextText.setGravity(Gravity.CENTER);
        TextView hint = Ui.add(col, Ui.text(this, "Back button cancels", 13, Ui.MUTED, false), 24);
        hint.setGravity(Gravity.CENTER);
        blocker.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        blocker.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(blocker);
        statusText = null;

        phase = Phase.PREHOLD;
        speaker.say(table ? "Step one. Leave the phone screen-up on the table and don't touch it, for one minute."
                : "Step one. Hold the phone by its corners, with the screen facing away from you, for one minute.");
    }

    @Override
    public void onSamples(long epochMs, List<ThermalMonitor.Sample> samples) {
        Phase p = phase;
        if (p != Phase.PREHOLD && p != Phase.CONTACT) return;
        double t = (epochMs - startMs) / 1000.0;
        String label = p == Phase.PREHOLD ? "hand" : "forehead";
        synchronized (data) {
            for (ThermalMonitor.Sample s : samples) {
                PrintWriter w = raw;   // the main thread nulls it at the end of a run
                if (w != null) w.println(String.format(Locale.US, "%d,%.3f,%s,%s,%d,%.4f", epochMs, t, label, s.name, s.type, s.celsius));
                if (s.name.equals("headroom_api") || s.name.equals("bcl-virt-monitor")) continue;
                data.computeIfAbsent(s.name, k -> new FeverAnalysis.Series(k, s.type)).points.add(new double[]{t, s.celsius});
            }
        }
    }

    private void onTick() {
        if (phase == Phase.INTRO) {
            updateIntro();
            return;
        }
        double t = (System.currentTimeMillis() - startMs) / 1000.0;
        if (phase == Phase.PREHOLD) {
            int left = (int) Math.ceil(PREHOLD_S - t);
            phaseText.setText(table ? "Step 1 of 2 · Leave the phone on the table" : "Step 1 of 2 · Hold by the corners, screen away");
            nextText.setText("Next: screen on your forehead. You'll hear when.");
            countdown.setText(String.valueOf(Math.max(0, left)));
            cue(left, false);
            if (t >= PREHOLD_S) {
                phase = Phase.CONTACT;
                lastRemaining = Integer.MAX_VALUE;
                speaker.buzz();
                speaker.say(table ? "Step two. Lean on your elbows and rest your forehead on the middle of the screen. Stay still for ninety seconds."
                        : "Step two. Press the screen flat against your forehead, sideways, and hold still for ninety seconds.");
            }
        } else if (phase == Phase.CONTACT) {
            double c = t - PREHOLD_S;
            int left = (int) Math.ceil(CONTACT_S - c);
            phaseText.setText(table ? "Step 2 of 2 · Forehead resting on the screen" : "Step 2 of 2 · Screen on your forehead, hold still");
            nextText.setText("A buzz and \"Done\" mean you can lower the phone.");
            countdown.setText(String.valueOf(Math.max(0, left)));
            cue(left, true);
            if (c >= CONTACT_S) finish90();
        }
    }

    /**
     * Spoken timing, since the screen can't be seen on the forehead: 60, 30 and 10 seconds left, then a
     * countdown. Each remaining-seconds value is spoken at most once.
     */
    private void cue(int left, boolean onForehead) {
        if (left == lastRemaining) return;
        lastRemaining = left;
        String say = null;
        if (left == 60 && onForehead) say = "One minute left. Keep still.";
        else if (left == 30) say = onForehead ? "Thirty seconds left." : table ? "Thirty seconds." : "Thirty seconds. Keep holding the corners.";
        else if (left == 10) say = onForehead ? "Ten seconds." : "Ten seconds. Next, the screen goes on your forehead.";
        else if (onForehead && left >= 1 && left <= 5) say = new String[]{"one", "two", "three", "four", "five"}[left - 1];
        else if (!onForehead && left >= 1 && left <= 3) say = new String[]{"one", "two", "three"}[left - 1];
        if (say != null) speaker.say(say);
    }

    private void abort(String why) {
        if (raw != null) raw.close();
        raw = null;
        showIntro(why);
    }

    private void finish90() {
        phase = Phase.ANALYZING;
        if (raw != null) raw.close();
        raw = null;
        speaker.buzz();
        speaker.say("Done. You can lower the phone.");
        restoreScreen();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.title(this, "Analysing…"), 0);
        setContentView(Ui.page(this, col));
        Map<String, FeverAnalysis.Series> copy;
        synchronized (data) {
            copy = new LinkedHashMap<>(data);
        }
        new Thread(() -> {
            FeverAnalysis.Report rep = FeverAnalysis.analyze(copy, PREHOLD_S, PREHOLD_S + CONTACT_S);
            JSONObject json = toJson(rep);
            if (json != null) Store.save(this, json);
            ui.post(() -> showReport(rep, json, copy));
        }).start();
    }

    private void restoreScreen() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    // ---- Report ---------------------------------------------------------------------------------

    private JSONObject toJson(FeverAnalysis.Report rep) {
        if (rep.best == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("id", startMs);
            o.put("type", "forehead");
            o.put("sensor", rep.best.name);
            o.put("rate_c_per_min", r3(rep.best.correctedCPerMin));
            o.put("contact_slope_c_per_min", r3(rep.best.slopeCPerMin));
            o.put("drift_c_per_min", r3(rep.best.driftCPerMin));
            o.put("rise_c", r3(rep.best.riseC));
            o.put("start_c", r3(rep.best.startC));
            o.put("snr", r3(rep.best.snr));
            o.put("phone_verdict", startState.verdict);
            o.put("power_mode", startState.powerMode);
            o.put("unreliable", anyway);
            o.put("raw_file", rawFile.getName());
            o.put("protocol", protocol());
            o.put("method", "feverphone.dump." + startState.powerMode.toLowerCase(Locale.US) + "." + protocol() + ".v2");
            // Every usable sensor's T0 and slope, so k can be recalibrated offline (tools/fever_calibrate.py).
            JSONArray sensors = new JSONArray();
            for (FeverAnalysis.SensorResult s : rep.sensors) {
                if (s.coarse) continue;
                sensors.put(new JSONObject().put("name", s.name).put("start_c", r3(s.startC))
                        .put("rate_c_per_min", r3(s.correctedCPerMin)).put("snr", r3(s.snr)));
            }
            o.put("sensors", sensors);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static double r3(double v) {
        return Double.isNaN(v) ? -999 : Math.round(v * 1000) / 1000.0;
    }

    private void showReport(FeverAnalysis.Report rep, JSONObject json, Map<String, FeverAnalysis.Series> series) {
        phase = Phase.REPORT;
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Forehead test · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(startMs))), 4);
        if (rep.best == null) {
            Ui.add(col, Ui.title(this, "No thermistor data"), 6);
            Ui.add(col, Ui.text(this, "The phone didn't report thermistor readings. One-time setup from a computer: "
                    + ThermalMonitor.SETUP, 15, Ui.MUTED, false), 8);
            Ui.add(col, Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), 20);
            setContentView(Ui.page(this, col));
            return;
        }
        FeverAnalysis.SensorResult b = rep.best;
        LinearLayout head = Ui.add(col, Ui.row(this), 6);
        head.addView(Ui.text(this, String.format(Locale.US, "%+.2f", b.correctedCPerMin), 56, Ui.TEXT, true));
        head.addView(Ui.text(this, "  °C/min", 20, Ui.MUTED, false));
        Ui.add(col, Ui.text(this, "How fast your forehead warmed the phone (the phone's own drift removed)", 14, Ui.MUTED, false), 2);
        boolean reliable = !anyway && "READY".equals(startState.verdict);
        Ui.add(col, Ui.badge(this, reliable ? "Phone was steady: conditions OK" : "Phone wasn't steady: treat as unreliable",
                reliable ? Ui.GOOD : Ui.WARN), 10);

        LinearLayout ins = Ui.add(col, Ui.card(this), 16);
        Ui.add(ins, Ui.label(this, "What this tells you"), 0);
        for (String line : insights(rep, json)) Ui.add(ins, Ui.text(this, "•  " + line, 15, Ui.TEXT, false), 8);

        physicsCard(col, rep, json);

        LinearLayout cal = Ui.add(col, Ui.card(this), 14);
        Ui.add(cal, Ui.label(this, "Your thermometer, body temperature (optional)"), 0);
        Ui.add(cal, Ui.text(this, "Type its normal (body) reading to compare with the phone's estimate. Each pair is also "
                + "data for a better skin-to-core model (FeverPhone trained on 37 people).", 13, Ui.MUTED, false), 6);
        LinearLayout refRow = Ui.add(cal, Ui.row(this), 8);
        EditText input = Ui.numberInput(this, "°C, e.g. 36.8");
        refRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView out = Ui.text(this, "", 14, Ui.TEXT, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(this, 10);
        refRow.addView(Ui.button(this, "Save", Ui.ACCENT, false, v -> {
            try {
                double c = Double.parseDouble(input.getText().toString());
                if (c > 50) c = (c - 32) * 5 / 9;   // typed in Fahrenheit
                json.put("ref_c", Math.round(c * 100) / 100.0);
                Store.save(this, json);
                String vs = json.has("core_c_est") ? String.format(Locale.US, " The phone estimated %.2f °C: %+.2f °C off.",
                        json.optDouble("core_c_est"), json.optDouble("core_c_est") - c) : "";
                out.setText(String.format(Locale.US, "Saved %.2f °C.%s Paired readings so far: %d (FeverPhone trained on 37 people).",
                        c, vs, pairedCount()));
            } catch (NumberFormatException | JSONException e) {
                out.setText("Type a number first");
            }
        }), sp);
        Ui.add(cal, out, 8);

        // Curves: change from contact start for the three cleanest sensors.
        ChartView chart = new ChartView(this, 210).title("Thermistors, change since the forehead touched", "°C").minSpan(1);
        chart.band(0, PREHOLD_S, 0x14FFFFFF, table ? "table" : "hand");
        chart.band(PREHOLD_S, PREHOLD_S + CONTACT_S, 0x26F2A33A, "forehead");
        int[] colors = {Ui.TEMP, Ui.ACCENT, Ui.STEPS};
        for (int i = 0; i < Math.min(3, rep.sensors.size()); i++) {
            FeverAnalysis.Series s = series.get(rep.sensors.get(i).name);
            if (s == null) continue;
            double base = rep.sensors.get(i).startC;
            double[] x = new double[s.points.size()], y = new double[s.points.size()];
            for (int j = 0; j < x.length; j++) {
                x[j] = s.points.get(j)[0];
                y[j] = s.points.get(j)[1] - base;
            }
            chart.add(new ChartView.Series(x, y, colors[i], false, rep.sensors.get(i).name));
        }
        Ui.add(col, chart, 18);

        LinearLayout t = Ui.add(col, Ui.card(this), 14);
        Ui.add(t, Ui.label(this, "Sensors, cleanest first"), 0);
        for (int i = 0; i < Math.min(5, rep.sensors.size()); i++) {
            FeverAnalysis.SensorResult s = rep.sensors.get(i);
            t.addView(Ui.kv(this, s.name, String.format(Locale.US, "%+.2f °C/min · rise %+.2f · SNR %.0f%s",
                    s.correctedCPerMin, s.riseC, s.snr, s.coarse ? " (coarse)" : "")));
        }
        LinearLayout d = Ui.add(col, Ui.card(this), 14);
        Ui.add(d, Ui.label(this, "Conditions"), 0);
        d.addView(Ui.kv(this, "Phone at the start", String.format(Locale.US, "%.2f °C (%s)", b.startC, b.name)));
        d.addView(Ui.kv(this, table ? "Phone's own drift (on the table)" : "Phone's own drift (in the hand)", String.format(Locale.US, "%+.2f °C/min", b.driftCPerMin)));
        d.addView(Ui.kv(this, "Readiness at start", startState.verdict.toLowerCase(Locale.US)));
        d.addView(Ui.kv(this, "Power", startState.powerMode.toLowerCase(Locale.US)));
        d.addView(Ui.kv(this, "Protocol", table ? "resting on a table" : "hand-held"));
        d.addView(Ui.kv(this, "Raw data", rawFile.getName()));
        Ui.add(col, Ui.text(this, "Research prototype, not a medical device, and not a fever reading.", 12, Ui.MUTED, false), 14);

        LinearLayout buttons = Ui.add(col, Ui.row(this), 18);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(Ui.button(this, "Back to intro", Ui.TEMP, false, v -> showIntro(null)), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = Ui.dp(this, 10);
        buttons.addView(Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), half2);
        setContentView(Ui.page(this, col));
    }

    private List<String> insights(FeverAnalysis.Report rep, JSONObject json) {
        FeverAnalysis.SensorResult b = rep.best;
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.US, "Over 90 s your forehead warmed the phone by %+.2f °C (%s), a rate of %+.2f °C/min "
                + "after removing the phone's own drift of %+.2f °C/min.", b.riseC, b.name, b.correctedCPerMin, b.driftCPerMin));
        List<Double> past = new ArrayList<>();
        for (JSONObject o : Store.history(this, "forehead")) {
            if (o.optLong("id") == startMs || o.optBoolean("unreliable")) continue;
            if (!o.optString("power_mode").equals(json.optString("power_mode"))) continue;
            if (!o.optString("protocol", "hand").equals(protocol())) continue;
            past.add(o.optDouble("rate_c_per_min"));
        }
        if (past.isEmpty()) {
            out.add("This is your first reading in this power mode and protocol. A few readings on normal days give you a personal baseline to compare against.");
        } else {
            double[] p = new double[past.size()];
            for (int i = 0; i < p.length; i++) p[i] = past.get(i);
            double med = Dsp.median(p);
            out.add(String.format(Locale.US, "Your usual rate is %+.2f °C/min (median of %d). This one is %+.0f%%. A faster rate can "
                    + "mean warmer skin, but also firmer or more central contact, so one reading proves little.",
                    med, p.length, 100 * (b.correctedCPerMin - med) / Math.abs(med)));
        }
        out.add("Warming rate = k × (skin − phone). k is how well heat flows from your skin into this sensor, and it "
                + "can't be read off one 90 s curve (guessing where the curve levels off gave 34 to 94 °C on one "
                + "recording). Calibrate k once against a known temperature (below), and every later reading "
                + "turns into a skin temperature.");
        if (!"READY".equals(json.optString("phone_verdict")) || anyway) {
            out.add("The phone wasn't steady when you started (" + startState.message.toLowerCase(Locale.US)
                    + "), so this rate partly reflects the phone itself.");
        }
        return out;
    }

    /** Skin temperature from calibrated k (when there is one), and the input that adds a calibration run. */
    private void physicsCard(LinearLayout col, FeverAnalysis.Report rep, JSONObject json) {
        String mode = calibKey();
        LinearLayout card = Ui.add(col, Ui.card(this), 14);
        Ui.add(card, Ui.label(this, "Forehead skin temperature (physics)"), 0);
        Map<String, Double> k = FeverCalibration.kBySensor(this, mode);
        String armKey = startState.powerMode + "|arm";
        boolean fromArm = k.isEmpty() && !FeverCalibration.kBySensor(this, armKey).isEmpty();
        String usedKey = fromArm ? armKey : mode;   // no forehead calibration: fall back to the arm's, with a caveat
        if (fromArm) k = FeverCalibration.kBySensor(this, armKey);
        FeverAnalysis.Estimate e = FeverAnalysis.estimate(rep, k);
        int runs = FeverCalibration.runs(this, usedKey);
        if (e.sensors > 0) {
            Ui.add(card, Ui.text(this, String.format(Locale.US, "≈ %.1f °C", e.skinC), 40, Ui.TEXT, true), 6);
            if (fromArm) {
                Ui.add(card, Ui.badge(this, "Using your arm calibration: forehead contact differs, so this may be offset", Ui.WARN), 6);
            }
            boolean disagree = !Double.isNaN(e.spreadC) && e.spreadC > 0.5;
            Ui.add(card, Ui.text(this, String.format(Locale.US, "Median of %d calibrated sensor%s%s, from %d calibration run%s "
                    + "on this phone (%s). Skin, not core temperature.", e.sensors, e.sensors == 1 ? "" : "s",
                    Double.isNaN(e.spreadC) ? "" : String.format(Locale.US, " (spread ±%.2f °C%s)", e.spreadC, disagree ? ": they disagree, so treat as rough" : ""),
                    runs, runs == 1 ? "" : "s", usedKey.toLowerCase(Locale.US).replace("|", ", ")), 13, disagree ? Ui.WARN : Ui.MUTED, false), 4);
            for (String line : e.lines) Ui.add(card, Ui.text(this, line, 12, Ui.MUTED, false), 3);
            double core = SkinToCore.coreC(e.skinC);
            boolean fever = SkinToCore.possibleFever(core);
            Ui.add(card, Ui.label(this, "Estimated core (oral) temperature"), 16);
            Ui.add(card, Ui.text(this, String.format(Locale.US, "≈ %.1f °C", core), 40, fever ? Ui.BAD : Ui.TEXT, true), 6);
            if (fever) Ui.add(card, Ui.badge(this, "Possible fever: check with a thermometer", Ui.BAD), 6);
            Ui.add(card, Ui.text(this, String.format(Locale.US, "From the skin estimate with a model fitted on 1,020 people (FDA "
                    + "dataset): typical error ±%.1f °C. It flags fever from %.2f °C, which caught 70%% of fevers with 10%% "
                    + "false alarms there.", SkinToCore.TYPICAL_ERROR_C, SkinToCore.FEVER_FLAG_C), 12, Ui.MUTED, false), 4);
            try {
                json.put("skin_c_est", r3(e.skinC));
                json.put("core_c_est", r3(core));
                json.put("possible_fever", fever);
                json.put("skin_spread_c", r3(e.spreadC));
                json.put("calibration_runs", runs);
                json.put("calibration_site", fromArm ? "arm" : protocol());
                Store.save(this, json);
            } catch (JSONException ignored) {
            }
        } else {
            Ui.add(card, Ui.text(this, "Not calibrated for this protocol and power mode yet, so no temperature.", 15, Ui.TEXT, true), 6);
            Ui.add(card, Ui.button(this, "Calibrate on your skin", Ui.TEMP, false,
                    v -> startActivity(new android.content.Intent(this, CalibrateActivity.class))), 10);
        }
        Ui.add(card, Ui.text(this, "Calibrate k: type the true temperature of what the screen touched. On the bench rig, the "
                + "water-bag temperature; on a person, a forehead (skin) IR thermometer reading taken right before, not an "
                + "oral one. Three or more runs make k trustworthy.", 13, Ui.MUTED, false), 10);
        LinearLayout row = Ui.add(card, Ui.row(this), 8);
        EditText ref = Ui.numberInput(this, "°C, e.g. 37.0");
        row.addView(ref, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView out = Ui.text(this, "", 13, Ui.TEXT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 10);
        row.addView(Ui.button(this, "Calibrate", Ui.TEMP, false, v -> {
            try {
                double c = Double.parseDouble(ref.getText().toString());
                if (c > 50) c = (c - 32) * 5 / 9;   // typed in Fahrenheit
                int used = FeverCalibration.add(this, mode, rep, c);
                json.put("calibration_ref_c", r3(c));
                Store.save(this, json);
                if (used == 0) {
                    out.setText("No sensor was usable: the reference has to be at least 2 °C warmer than the phone, and the phone has to have warmed.");
                    return;
                }
                StringBuilder sb = new StringBuilder(String.format(Locale.US, "Added k for %d sensor%s (run %d). Current k:",
                        used, used == 1 ? "" : "s", FeverCalibration.runs(this, mode)));
                for (Map.Entry<String, Double> en : FeverCalibration.kBySensor(this, mode).entrySet()) {
                    sb.append(String.format(Locale.US, "\n%s  %.3f /min", en.getKey(), en.getValue()));
                }
                out.setText(sb);
            } catch (NumberFormatException | JSONException ex) {
                out.setText("Type a number first");
            }
        }), lp);
        Ui.add(card, out, 8);
    }

    private int pairedCount() {
        int n = 0;
        for (JSONObject o : Store.history(this, "forehead")) if (o.has("ref_c")) n++;
        return n;
    }
}
