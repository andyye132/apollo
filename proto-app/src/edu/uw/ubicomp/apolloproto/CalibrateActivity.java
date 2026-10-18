package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calibrates FeverPhone's k against skin of known temperature: measure a patch of skin with a
 * thermometer, then Start -> 10 s to pick the phone up -> 90 s with the screen pressed on that patch ->
 * type the thermometer reading. k_i = rate_i / (skin - phone) per thermistor, stored per power mode and
 * site; later forehead tests turn their warming rate into a skin temperature with it.
 *
 * Recording starts when this screen opens, so the phone's own drift is fitted over the 40 s before
 * contact: a 10 s window is too short (on our Pixel 4 recordings its drift estimate scatters by
 * 0.2-0.5 °C/min, as big as the signal). Contact is exactly 90 s because k is only valid for the
 * window length it was calibrated on, and the forehead test uses 90 s.
 */
public class CalibrateActivity extends Activity implements ThermalMonitor.Listener {
    private static final int WAIT_S = 10, CONTACT_S = 90, BUFFER_S = 90;

    private enum Phase { INTRO, WAIT, CONTACT, ANALYZING, REPORT }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, FeverAnalysis.Series> data = new LinkedHashMap<>();
    private ThermalMonitor monitor;
    private Speaker speaker;
    private volatile Phase phase = Phase.INTRO;
    private long openMs, runId;
    private double startT, contactT;
    private boolean anyway;
    private String site;   // "arm", or the forehead protocol ("hand" / "table")
    private String person = "";
    private int lastRemaining = Integer.MAX_VALUE;
    private ThermalMonitor.State startState;
    private PrintWriter raw;
    private File rawFile;
    private StepList steps;
    private TextView statusText, detailText, countdown, phaseText, nextText, startButton, anywayButton;

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
        openMs = System.currentTimeMillis();
        site = Store.prefs(this).getString("calib_site", "arm");
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
        if (phase == Phase.WAIT || phase == Phase.CONTACT) abort("Stopped: the app went to the background.");
    }

    @Override
    protected void onDestroy() {
        speaker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (phase == Phase.WAIT || phase == Phase.CONTACT) {
            speaker.say("Cancelled.");
            abort("Cancelled.");
        } else {
            super.onBackPressed();
        }
    }

    private boolean onArm() {
        return "arm".equals(site);
    }

    private String siteName() {
        return onArm() ? "arm" : "table".equals(site) ? "forehead (on a table)" : "forehead (hand-held)";
    }

    private String key(String powerMode) {
        return powerMode + "|" + site;
    }

    // ---- Intro ----------------------------------------------------------------------------------

    private void showIntro(String note) {
        phase = Phase.INTRO;
        restoreScreen();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Forehead temperature · setup"), 4);
        Ui.add(col, Ui.title(this, "Calibrate on your skin"), 4);
        Ui.add(col, Ui.text(this, "The phone measures how fast skin warms it. To turn that into degrees it needs one "
                + "number per sensor, k, which you get by pressing it on skin whose temperature you measured. "
                + "Do 3 to 5 runs once; after that every reading uses them.", 15, Ui.MUTED, false), 8);
        if (note != null) Ui.add(col, Ui.badge(this, note, Ui.WARN), 12);

        LinearLayout pick = Ui.add(col, Ui.row(this), 14);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        pick.addView(Ui.button(this, "Arm", Ui.TEMP, onArm(), v -> setSite("arm")), l1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        l2.leftMargin = Ui.dp(this, 10);
        String forehead = Store.prefs(this).getString("fever_protocol", "hand");
        pick.addView(Ui.button(this, "Forehead", Ui.TEMP, !onArm(), v -> setSite(forehead)), l2);
        Ui.add(col, Ui.text(this, onArm()
                ? "Arm: inner forearm, easy to measure. Arm and forehead contact differ, so forehead readings from an "
                + "arm calibration may carry an offset; a forehead calibration avoids that."
                : "Forehead, " + ("table".equals(site) ? "resting on the phone on a table" : "hand-held")
                + " (the protocol picked on the forehead screen). Needs a thermometer that reads skin, not body temperature.",
                13, Ui.MUTED, false), 6);

        String spot = onArm() ? "inner forearm" : "forehead";
        steps = Ui.add(col, new StepList(this, Ui.TEMP,
                "table".equals(site) ? "Leave the phone screen-up on the table until it says ready"
                        : "Leave the phone on the table until it says ready",
                "Measure your skin where you'll press (" + spot + ") and remember the number",
                "table".equals(site) ? "Tap Start. You have 10 seconds to get your forehead over the phone"
                        : "Tap Start, then pick the phone up by its edges. You have 10 seconds",
                "At the buzz, press the screen flat on your " + spot + " and hold still until it says done (90 s)",
                "Type the thermometer reading"), 16);

        LinearLayout ready = Ui.add(col, Ui.card(this), 12);
        Ui.add(ready, Ui.label(this, "Is the phone ready?"), 0);
        statusText = Ui.add(ready, Ui.text(this, "Checking…", 16, Ui.TEXT, true), 8);
        detailText = Ui.add(ready, Ui.text(this, "", 13, Ui.MUTED, false), 6);

        storedCard(col);

        LinearLayout tips = Ui.add(col, Ui.card(this), 12);
        Ui.add(tips, Ui.label(this, "Tips"), 0);
        String[] how = {
                "•  Take the skin reading right before Start, at the exact spot. If your thermometer has a surface or "
                        + "object mode, use it: the normal mode shows an estimated body temperature (~37 °C), not skin (~33-35 °C).",
                "•  Same pressure each run: firm and flat, like holding it to your ear. Pressure changes k.",
                "•  The skin must be at least 2 °C warmer than the phone, so start with a cool, settled phone.",
                "•  Spread runs over different days or skin temperatures so k isn't tuned to one moment.",
        };
        for (String s : how) Ui.add(tips, Ui.text(this, s, 14, Ui.TEXT, false), 8);

        startButton = Ui.add(col, Ui.button(this, "Start", Ui.TEMP, true, v -> start(false)), 20);
        anywayButton = Ui.add(col, Ui.button(this, "Start anyway (phone not steady)", Ui.MUTED, false, v -> start(true)), 10);
        setContentView(Ui.page(this, col));
        updateIntro();
    }

    /** What's stored for this site: runs, k per sensor and how much it varies between runs. */
    private void storedCard(LinearLayout col) {
        String powerMode = monitor.state().powerMode;
        Map<String, double[]> all = FeverCalibration.allK(this, key(powerMode));
        LinearLayout card = Ui.add(col, Ui.card(this), 12);
        Ui.add(card, Ui.label(this, "Saved calibration · " + siteName() + " · " + powerMode.toLowerCase(Locale.US)), 0);
        if (all.isEmpty()) {
            Ui.add(card, Ui.text(this, "None yet.", 15, Ui.TEXT, true), 8);
            return;
        }
        Ui.add(card, Ui.text(this, FeverCalibration.runs(this, key(powerMode)) + " run(s). k per sensor, and how much it "
                + "varies between runs (under 10% is good):", 13, Ui.MUTED, false), 6);
        for (Map.Entry<String, double[]> e : all.entrySet()) card.addView(Ui.kv(this, e.getKey(), kSummary(e.getValue())));
        Ui.add(card, Ui.button(this, "Reset this calibration", Ui.BAD, false, v -> new AlertDialog.Builder(this)
                .setMessage("Delete all saved calibration runs for " + siteName() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    FeverCalibration.clear(this, key(powerMode));
                    showIntro("Calibration reset.");
                })
                .setNegativeButton("Keep", null).show()), 12);
    }

    private static String kSummary(double[] k) {
        double m = Dsp.median(k);
        if (k.length < 2) return String.format(Locale.US, "%.3f /min", m);
        return String.format(Locale.US, "%.3f /min · ±%.0f%% · %d runs", m, 100 * Dsp.std(k) / Dsp.mean(k), k.length);
    }

    private void setSite(String s) {
        if (s.equals(site)) return;
        site = s;
        Store.prefs(this).edit().putString("calib_site", site).apply();
        showIntro(null);
    }

    private void updateIntro() {
        if (statusText == null) return;
        ThermalMonitor.State s = monitor.state();
        boolean ready = "READY".equals(s.verdict), unsupported = "UNSUPPORTED".equals(s.verdict);
        statusText.setText(s.message);
        statusText.setTextColor(Ui.verdictColor(s.verdict));
        detailText.setText(String.format(Locale.US, "Phone %s · %s · power: %s",
                Double.isNaN(s.phoneC) ? "-" : String.format(Locale.US, "%.1f °C", s.phoneC),
                Double.isNaN(s.driftCPerMin) ? "drift -" : String.format(Locale.US, "%+.2f °C/min", s.driftCPerMin),
                s.powerMode.toLowerCase(Locale.US)));
        steps.setCurrent(ready ? 1 : 0);
        startButton.setAlpha(ready ? 1f : 0.35f);
        startButton.setEnabled(ready);
        anywayButton.setVisibility(ready || unsupported ? View.GONE : View.VISIBLE);
    }

    // ---- Recording ------------------------------------------------------------------------------

    @Override
    public void onSamples(long epochMs, List<ThermalMonitor.Sample> samples) {
        Phase p = phase;
        if (p == Phase.ANALYZING || p == Phase.REPORT) return;
        double t = (epochMs - openMs) / 1000.0;
        synchronized (data) {
            PrintWriter w = raw;   // the main thread nulls it when a run ends
            String label = p == Phase.CONTACT ? "contact" : "wait";
            for (ThermalMonitor.Sample s : samples) {
                if (w != null) w.println(String.format(Locale.US, "%d,%.3f,%s,%s,%d,%.4f", epochMs, t, label, s.name, s.type, s.celsius));
                if (s.name.equals("headroom_api") || s.name.equals("bcl-virt-monitor")) continue;
                List<double[]> pts = data.computeIfAbsent(s.name, k -> new FeverAnalysis.Series(k, s.type)).points;
                pts.add(new double[]{t, s.celsius});
                if (p == Phase.INTRO) while (!pts.isEmpty() && t - pts.get(0)[0] > BUFFER_S) pts.remove(0);
            }
        }
    }

    private void start(boolean anyway) {
        this.anyway = anyway;
        startState = monitor.state();
        runId = System.currentTimeMillis();
        startT = (runId - openMs) / 1000.0;
        lastRemaining = Integer.MAX_VALUE;
        rawFile = new File(Store.dir(this), "calib_" + runId + ".csv");
        synchronized (data) {
            try {   // the buffered minute before Start is the drift baseline, so it goes in the file too
                PrintWriter w = new PrintWriter(new FileWriter(rawFile), true);
                w.println("epoch_ms,t_s,phase,sensor,type,celsius");
                for (FeverAnalysis.Series s : data.values()) {
                    for (double[] p : s.points) {
                        w.println(String.format(Locale.US, "%d,%.3f,before,%s,%d,%.4f", openMs + Math.round(p[0] * 1000), p[0], s.name, s.type, p[1]));
                    }
                }
                raw = w;
            } catch (IOException e) {
                raw = null;
            }
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.2f;   // same display heat as the forehead test
        getWindow().setAttributes(lp);

        FrameLayout blocker = new FrameLayout(this);   // black and touch-proof: skin will be pressing on it
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

        phase = Phase.WAIT;
        speaker.say("table".equals(site) ? "Get your forehead over the phone. Rest it on the screen at the buzz."
                : "Pick up the phone by its edges. Press it on your " + (onArm() ? "arm" : "forehead") + " at the buzz.");
    }

    private void onTick() {
        if (phase == Phase.INTRO) {
            updateIntro();
            return;
        }
        double t = (System.currentTimeMillis() - openMs) / 1000.0;
        if (phase == Phase.WAIT) {
            int left = (int) Math.ceil(startT + WAIT_S - t);
            phaseText.setText("Get ready");
            nextText.setText("At the buzz, press the screen on your " + (onArm() ? "inner forearm" : "forehead") + ".");
            countdown.setText(String.valueOf(Math.max(0, left)));
            if (left != lastRemaining && left >= 1 && left <= 3) speaker.say(new String[]{"one", "two", "three"}[left - 1]);
            lastRemaining = left;
            if (t >= startT + WAIT_S) {
                contactT = t;
                phase = Phase.CONTACT;
                lastRemaining = Integer.MAX_VALUE;
                speaker.buzz();
                speaker.say("Press now, flat and firm. Hold still for ninety seconds.");
            }
        } else if (phase == Phase.CONTACT) {
            int left = (int) Math.ceil(contactT + CONTACT_S - t);
            phaseText.setText("Hold still");
            nextText.setText("A buzz and \"Done\" mean you can lift the phone.");
            countdown.setText(String.valueOf(Math.max(0, left)));
            if (left != lastRemaining) {
                lastRemaining = left;
                String say = left == 60 ? "One minute left." : left == 30 ? "Thirty seconds." : left == 10 ? "Ten seconds."
                        : left >= 1 && left <= 5 ? new String[]{"one", "two", "three", "four", "five"}[left - 1] : null;
                if (say != null) speaker.say(say);
            }
            if (t >= contactT + CONTACT_S) finishRun();
        }
    }

    private void abort(String why) {
        synchronized (data) {
            if (raw != null) raw.close();
            raw = null;
        }
        showIntro(why);
    }

    private void finishRun() {
        phase = Phase.ANALYZING;
        synchronized (data) {
            if (raw != null) raw.close();
            raw = null;
        }
        speaker.buzz();
        speaker.say("Done. You can lift the phone. Now type the thermometer reading.");
        restoreScreen();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.title(this, "Analysing…"), 0);
        setContentView(Ui.page(this, col));
        Map<String, FeverAnalysis.Series> copy = new LinkedHashMap<>();
        synchronized (data) {
            for (Map.Entry<String, FeverAnalysis.Series> e : data.entrySet()) {
                FeverAnalysis.Series s = new FeverAnalysis.Series(e.getKey(), e.getValue().type);
                s.points.addAll(e.getValue().points);
                copy.put(e.getKey(), s);
            }
            data.clear();   // the next run buffers afresh
        }
        double from = contactT, to = contactT + CONTACT_S;
        double baselineS = copy.values().stream().mapToDouble(s -> s.points.isEmpty() ? from : from - s.points.get(0)[0]).max().orElse(0);
        new Thread(() -> {
            FeverAnalysis.Report rep = FeverAnalysis.analyze(copy, from, to);
            ui.post(() -> showReport(rep, copy, baselineS));
        }).start();
    }

    private void restoreScreen() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    // ---- Report ---------------------------------------------------------------------------------

    private void showReport(FeverAnalysis.Report rep, Map<String, FeverAnalysis.Series> series, double baselineS) {
        phase = Phase.REPORT;
        String calKey = key(startState.powerMode);
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Calibration run · " + siteName()), 4);
        if (rep.best == null) {
            Ui.add(col, Ui.title(this, "No thermistor data"), 6);
            Ui.add(col, Ui.text(this, "The phone didn't report thermistor readings. One-time setup from a computer: "
                    + ThermalMonitor.SETUP, 15, Ui.MUTED, false), 8);
            Ui.add(col, Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), 20);
            setContentView(Ui.page(this, col));
            return;
        }
        FeverAnalysis.SensorResult b = rep.best;
        Ui.add(col, Ui.title(this, String.format(Locale.US, "%+.2f °C/min", b.correctedCPerMin)), 6);
        Ui.add(col, Ui.text(this, String.format(Locale.US, "How fast your skin warmed the phone (%s, which started at %.2f °C). "
                + "Type the thermometer reading to turn this into k.", b.name, b.startC), 14, Ui.MUTED, false), 2);
        if (anyway) Ui.add(col, Ui.badge(this, "The phone wasn't steady at the start: this run may be off", Ui.WARN), 10);
        if (baselineS < 30) {
            Ui.add(col, Ui.badge(this, String.format(Locale.US, "Only %.0f s of baseline before contact: open this screen "
                    + "a minute before Start next time", baselineS), Ui.WARN), 10);
        }

        LinearLayout cal = Ui.add(col, Ui.card(this), 16);
        Ui.add(cal, Ui.label(this, "Thermometer reading"), 0);
        // Most thermometers show body temperature; a body reading is turned into the forehead skin
        // temperature that the skin-to-core model maps back onto it (SkinToCore.skinFor).
        boolean[] body = {!"skin".equals(Store.prefs(this).getString("calib_ref_kind", "body"))};
        LinearLayout kind = Ui.add(cal, Ui.row(this), 8);
        TextView[] kinds = new TextView[2];
        Runnable paint = () -> {
            for (int i = 0; i < 2; i++) {
                boolean on = (i == 0) == body[0];
                kinds[i].setTextColor(on ? Ui.BG : Ui.TEMP);
                kinds[i].setBackground(Ui.rounded(on ? Ui.TEMP : Ui.CARD_2, Ui.dp(this, 10)));
            }
        };
        for (int i = 0; i < 2; i++) {
            final boolean isBody = i == 0;
            kinds[i] = Ui.button(this, isBody ? "Body temp (~37)" : "Skin / surface", Ui.TEMP, false, v -> {
                body[0] = isBody;
                Store.prefs(this).edit().putString("calib_ref_kind", isBody ? "body" : "skin").apply();
                paint.run();
            });
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i == 1) kp.leftMargin = Ui.dp(this, 8);
            kind.addView(kinds[i], kp);
        }
        paint.run();
        EditText who = Ui.add(cal, Ui.textInput(this, "Your name or initials (optional)"), 10);
        who.setText(Store.prefs(this).getString("calib_person", ""));
        LinearLayout row = Ui.add(cal, Ui.row(this), 8);
        EditText input = Ui.numberInput(this, "°C or °F, e.g. 98.3");
        row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView out = Ui.text(this, "", 14, Ui.TEXT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 10);
        boolean[] saved = {false};
        row.addView(Ui.button(this, "Save", Ui.TEMP, true, v -> {
            if (saved[0]) return;
            double ref;
            try {
                ref = Double.parseDouble(input.getText().toString());
            } catch (NumberFormatException e) {
                out.setText("Type a number first");
                return;
            }
            if (ref > 50) ref = (ref - 32) * 5 / 9;   // typed in Fahrenheit
            if (body[0] ? (ref < 35 || ref > 42) : (ref < 25 || ref > 40)) {
                out.setText(String.format(Locale.US, "%.1f °C isn't a %s temperature. Check the number and the button above.",
                        ref, body[0] ? "body" : "skin"));
                return;
            }
            saved[0] = true;
            person = who.getText().toString().trim();
            Store.prefs(this).edit().putString("calib_person", person).apply();
            out.setText(save(rep, body[0] ? SkinToCore.skinFor(ref) : ref, body[0] ? ref : Double.NaN, calKey));
        }), lp);
        Ui.add(cal, out, 10);

        ChartView chart = new ChartView(this, 200).title("Thermistors, change since contact", "°C").minSpan(1);
        double origin = contactT;
        chart.band(0, CONTACT_S, 0x26F2A33A, onArm() ? "arm" : "forehead");
        int[] colors = {Ui.TEMP, Ui.ACCENT, Ui.STEPS};
        for (int i = 0; i < Math.min(3, rep.sensors.size()); i++) {
            FeverAnalysis.Series s = series.get(rep.sensors.get(i).name);
            if (s == null) continue;
            double base = rep.sensors.get(i).startC;
            double[] x = new double[s.points.size()], y = new double[s.points.size()];
            for (int j = 0; j < x.length; j++) {
                x[j] = s.points.get(j)[0] - origin;
                y[j] = s.points.get(j)[1] - base;
            }
            chart.add(new ChartView.Series(x, y, colors[i], false, rep.sensors.get(i).name));
        }
        Ui.add(col, chart, 16);

        LinearLayout t = Ui.add(col, Ui.card(this), 14);
        Ui.add(t, Ui.label(this, "Sensors, cleanest first"), 0);
        for (int i = 0; i < Math.min(6, rep.sensors.size()); i++) {
            FeverAnalysis.SensorResult s = rep.sensors.get(i);
            t.addView(Ui.kv(this, s.name, String.format(Locale.US, "start %.2f · %+.2f °C/min · drift %+.2f%s",
                    s.startC, s.correctedCPerMin, s.driftCPerMin, s.coarse ? " (coarse)" : "")));
        }
        Ui.add(col, Ui.text(this, "Raw data: " + rawFile.getName(), 12, Ui.MUTED, false), 10);

        LinearLayout buttons = Ui.add(col, Ui.row(this), 18);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(Ui.button(this, "Another run", Ui.TEMP, false, v -> {
            openMs = System.currentTimeMillis();
            showIntro(null);
        }), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = Ui.dp(this, 10);
        buttons.addView(Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), half2);
        setContentView(Ui.page(this, col));
    }

    /**
     * Checks this run against the calibration so far (an honest test: those k never saw this run), then
     * adds it. Returns the text to show.
     */
    private String save(FeverAnalysis.Report rep, double refC, double bodyC, String calKey) {
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(bodyC)) {
            sb.append(String.format(Locale.US, "Body %.2f °C -> forehead skin %.2f °C (what the skin-to-core model maps back to "
                    + "%.2f).%n%n", bodyC, refC, bodyC));
        }
        Map<String, Double> before = FeverCalibration.kBySensor(this, calKey);
        FeverAnalysis.Estimate check = FeverAnalysis.estimate(rep, before);
        if (check.sensors > 0 && !Double.isNaN(bodyC)) {
            double core = SkinToCore.coreC(check.skinC);
            sb.append(String.format(Locale.US, "Check: your earlier calibration estimates %.2f °C body temperature for this run; "
                    + "the thermometer says %.2f (%+.2f °C).%s%n%n", core, bodyC, core - bodyC,
                    Math.abs(core - bodyC) > 0.4 ? " That's a big miss: was the pressure or spot different?" : ""));
        } else if (check.sensors > 0) {
            sb.append(String.format(Locale.US, "Check: your earlier calibration predicts %.2f °C for this run; the thermometer says "
                    + "%.2f (%+.2f °C).%s%n%n", check.skinC, refC, check.skinC - refC,
                    Math.abs(check.skinC - refC) > 0.5 ? " That's a big miss: was the pressure or spot different?" : ""));
        }
        int used = FeverCalibration.add(this, calKey, rep, refC);
        if (used == 0) {
            sb.append(String.format(Locale.US, "Not saved: no sensor was usable. Your skin (%.1f °C) has to be at least 2 °C warmer "
                    + "than the phone (%.1f °C), and the phone has to have warmed.", refC, rep.best.startC));
        } else {
            sb.append(String.format(Locale.US, "Saved k for %d sensors. Calibration now (%d runs):", used, FeverCalibration.runs(this, calKey)));
            List<Map.Entry<String, double[]>> all = new ArrayList<>(FeverCalibration.allK(this, calKey).entrySet());
            for (Map.Entry<String, double[]> e : all) sb.append(String.format(Locale.US, "%n%s  %s", e.getKey(), kSummary(e.getValue())));
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", runId);
            o.put("type", "calibration");
            o.put("site", site);
            if (!person.isEmpty()) o.put("person", person);
            o.put("ref_c", Math.round(refC * 100) / 100.0);   // skin temperature used for k
            if (!Double.isNaN(bodyC)) o.put("body_c", Math.round(bodyC * 100) / 100.0);
            o.put("power_mode", startState.powerMode);
            o.put("unreliable", anyway);
            o.put("sensors_used", used);
            if (check.sensors > 0) o.put("check_error_c", Math.round((check.skinC - refC) * 100) / 100.0);
            JSONArray sensors = new JSONArray();
            for (FeverAnalysis.SensorResult s : rep.sensors) {
                if (s.coarse || Double.isNaN(s.correctedCPerMin)) continue;
                double k = FeverAnalysis.kFromReference(s, refC);
                JSONObject j = new JSONObject().put("name", s.name).put("start_c", Math.round(s.startC * 1000) / 1000.0)
                        .put("rate_c_per_min", Math.round(s.correctedCPerMin * 1000) / 1000.0);
                if (!Double.isNaN(k)) j.put("k", Math.round(k * 10000) / 10000.0);
                sensors.put(j);
            }
            o.put("sensors", sensors);
            o.put("raw_file", rawFile.getName());
            o.put("method", "feverphone.calibration." + site + ".v1");
            Store.save(this, o);
        } catch (JSONException ignored) {
        }
        return sb.toString();
    }
}
