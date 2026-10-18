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
    private String site;   // the forehead protocol whose k this calibrates: "hand" (hand-held, the only one offered)
    /** What touches the screen: "forehead" (a calibration run), "hand" (control: held in the air), "none" (control: on the table). */
    private String contact;
    private final List<double[]> stillLog = new ArrayList<>();   // {t, still 1/0, gyro rad/s} before Start
    private String person = "";
    private int lastRemaining = Integer.MAX_VALUE;
    private ThermalMonitor.State startState;
    private PrintWriter raw;
    private File rawFile;
    private StepList steps;
    private TextView statusText, detailText, phaseText, nextText, startButton, anywayButton, stepLabel;
    private Ui.Readiness readiness;
    private RingView countdown;

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
        site = "hand";   // hand-held forehead only (arm and table calibration were dropped, Oct 2026)
        contact = Store.prefs(this).getString("calib_contact", "forehead");
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

    private boolean control() {
        return !"forehead".equals(contact);
    }

    private String contactName() {
        return "hand".equals(contact) ? "Hand-only control" : "none".equals(contact) ? "Phone-only control" : "Forehead calibration";
    }

    /** Plugged in at 100% (trickle) shares the unplugged calibration: the contact physics is the same. */
    private String key(String powerMode) {
        return ("TRICKLE".equals(powerMode) ? "UNPLUGGED" : powerMode) + "|" + site;
    }

    // ---- Intro ----------------------------------------------------------------------------------

    private void showIntro(String note) {
        phase = Phase.INTRO;
        restoreScreen();
        holdForBaseline();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Forehead temperature · setup"), 4);
        Ui.add(col, Ui.title(this, "Calibrate"), 4);
        Ui.add(col, Ui.text(this, "Forehead runs, each with your thermometer reading, teach the app how your forehead warms the "
                + "phone. The two control runs measure the warming that doesn't come from your forehead.", 15, Ui.MUTED, false), 8);
        if (note != null) Ui.wrap(col, Ui.badge(this, note, Ui.WARN), 12);

        String[] keys = {"forehead", "hand", "none"};
        int current = java.util.Arrays.asList(keys).indexOf(contact);
        Ui.add(col, Ui.segmented(this, new String[]{"Forehead", "Hand only", "Phone only"}, current, i -> setContact(keys[i])), 16);
        Ui.add(col, Ui.text(this, "hand".equals(contact)
                ? "Control: hold the phone exactly as in a forehead run, but in the air with the screen facing away from you. "
                + "It measures how much your hand warms the phone."
                : "none".equals(contact)
                ? "Control: the phone stays flat on the table the whole time. It measures how much the phone warms itself "
                + "during a test (black screen, voice)."
                : "Calibration: the screen on your forehead, hand-held, with your thermometer reading taken right before.",
                13, Ui.MUTED, false), 6);

        steps = Ui.add(col, new StepList(this, Ui.TEMP, "hand".equals(contact) ? new String[]{
                "Leave the phone flat on the table, untouched, until it says ready",
                "Tap Start, then pick it up by its edges the way you would for a forehead run (10 s)",
                "At the buzz, hold it still in the air, screen facing away from you, for 90 s",
                "Save the run"} : "none".equals(contact) ? new String[]{
                "Leave the phone flat on the table, untouched, until it says ready",
                "Tap Start and don't touch the phone",
                "It records for 100 s by itself",
                "Save the run"} : new String[]{
                "Leave the phone flat on the table, untouched, until it says ready",
                "Take your temperature with your thermometer",
                "Tap Start, then pick the phone up by its edges (10 s)",
                "At the buzz, press the screen flat on your forehead and hold still until it says done (90 s)",
                "Type the thermometer reading"}), 16);

        readiness = new Ui.Readiness(this);
        Ui.add(col, readiness.card, 12);
        statusText = readiness.message;
        detailText = readiness.detail;

        storedCard(col);

        LinearLayout tips = Ui.add(col, Ui.card(this), 12);
        Ui.add(tips, Ui.label(this, "Tips"), 0);
        String[] how = {
                "•  Thermometer: its normal (body temperature) mode, right before Start.",
                "•  Same grip and firmness every run, by the edges: firm and flat, like holding it to your ear.",
                "•  Don't touch the phone while it gets ready: it needs a minute lying still to learn its own drift.",
                "•  Name or initials on every run, so each person's runs can be told apart.",
        };
        for (String s : how) Ui.add(tips, Ui.text(this, s, 14, Ui.TEXT, false), 8);

        startButton = Ui.add(col, Ui.button(this, "Start", Ui.TEMP, true, v -> start(false)), 20);
        anywayButton = Ui.add(col, Ui.button(this, "Start anyway (phone not steady)", Ui.MUTED, false, v -> start(true)), 10);
        Ui.add(col, Ui.text(this, "Starting on a phone that is still warming or cooling gives wrong readings (a chilled phone read "
                + "4 °F low). Don't cool it in a fridge: it warms unevenly for 20+ minutes and can collect condensation. "
                + "Let it sit on the table instead.", 12, Ui.MUTED, false), 6);
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

    private void setContact(String c) {
        if (c.equals(contact)) return;
        contact = c;
        Store.prefs(this).edit().putString("calib_contact", contact).apply();
        showIntro(null);
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
        readiness.show(s, "");
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
        if (p != Phase.WAIT && p != Phase.CONTACT) return;   // before Start, the monitor's own history is the baseline
        double t = (epochMs - openMs) / 1000.0;
        synchronized (data) {
            PrintWriter w = raw;   // the main thread nulls it when a run ends
            String label = p == Phase.CONTACT ? "contact" : "wait";
            for (ThermalMonitor.Sample s : samples) {
                if (w != null) w.println(String.format(Locale.US, "%d,%.3f,%s,%s,%d,%.4f", epochMs, t, label, s.name, s.type, s.celsius));
                if (s.name.equals("headroom_api") || s.name.equals("bcl-virt-monitor")) continue;
                data.computeIfAbsent(s.name, k -> new FeverAnalysis.Series(k, s.type)).points.add(new double[]{t, s.celsius});
            }
            if (w != null) {
                w.println(String.format(Locale.US, "%d,%.3f,%s,_still,0,%d", epochMs, t, label, monitor.stillNow() ? 1 : 0));
                w.println(String.format(Locale.US, "%d,%.3f,%s,_gyro,0,%.4f", epochMs, t, label, monitor.gyroNow()));
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
            // The untouched baseline: the monitor's readings from before Start (it runs whenever an Apollo
            // screen is open, home included), so a quick Start still has one.
            data.clear();
            stillLog.clear();
            for (Object[] h : monitor.recentSince(runId - BUFFER_S * 1000L)) {
                double t = ((Long) h[0] - openMs) / 1000.0;
                stillLog.add(new double[]{t, (Boolean) h[2] ? 1 : 0, (Double) h[3]});
                @SuppressWarnings("unchecked") List<ThermalMonitor.Sample> got = (List<ThermalMonitor.Sample>) h[1];
                for (ThermalMonitor.Sample s : got) {
                    if (s.name.equals("headroom_api") || s.name.equals("bcl-virt-monitor")) continue;
                    data.computeIfAbsent(s.name, k -> new FeverAnalysis.Series(k, s.type)).points.add(new double[]{t, s.celsius});
                }
            }
            try {   // the baseline goes in the file too
                PrintWriter w = new PrintWriter(new FileWriter(rawFile), true);
                w.println("epoch_ms,t_s,phase,sensor,type,celsius");
                for (FeverAnalysis.Series s : data.values()) {
                    for (double[] p : s.points) {
                        w.println(String.format(Locale.US, "%d,%.3f,before,%s,%d,%.4f", openMs + Math.round(p[0] * 1000), p[0], s.name, s.type, p[1]));
                    }
                }
                for (double[] q : stillLog) {   // lying still (1/0) and the gyroscope behind it, for offline checks
                    long ms = openMs + Math.round(q[0] * 1000);
                    w.println(String.format(Locale.US, "%d,%.3f,before,_still,0,%.0f", ms, q[0], q[1]));
                    w.println(String.format(Locale.US, "%d,%.3f,before,_gyro,0,%.4f", ms, q[0], q[2]));
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
        // The "hold still" screen from the design boards, kept pure black: black pixels are off on this
        // display, so it adds the least heat to the measurement.
        col.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        View face = Ui.mascot(this, "calm", 64);
        ((LinearLayout.LayoutParams) face.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(face);
        stepLabel = Ui.add(col, Ui.styled(this, "", 12, 0x99FFFFFF, 700), 18);
        stepLabel.setLetterSpacing(0.14f);
        stepLabel.setGravity(Gravity.CENTER);
        phaseText = Ui.add(col, Ui.styled(this, "", 26, 0xFFFFFFFF, 700), 8);
        phaseText.setGravity(Gravity.CENTER);
        countdown = new RingView(this, true);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.gravity = Gravity.CENTER_HORIZONTAL;
        rl.topMargin = Ui.dp(this, 28);
        col.addView(countdown, rl);
        nextText = Ui.add(col, Ui.styled(this, "", 15, 0xB3FFFFFF, 400), 28);
        nextText.setGravity(Gravity.CENTER);
        TextView hint = Ui.add(col, Ui.styled(this, "Touch is locked  ·  Back cancels", 13, 0x73FFFFFF, 400), 22);
        hint.setGravity(Gravity.CENTER);
        blocker.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        blocker.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(blocker);
        statusText = null;

        phase = Phase.WAIT;
        speaker.say("none".equals(contact) ? "Don't touch the phone. Recording starts at the buzz."
                : "hand".equals(contact) ? "Pick up the phone by its edges. At the buzz, hold it in the air, screen facing away from you."
                : "Pick up the phone by its edges. Press it on your forehead at the buzz.");
    }

    private void onTick() {
        if (phase == Phase.INTRO) {
            updateIntro();
            return;
        }
        double t = (System.currentTimeMillis() - openMs) / 1000.0;
        if (phase == Phase.WAIT) {
            int left = (int) Math.ceil(startT + WAIT_S - t);
            stepLabel.setText("GET READY");
            phaseText.setText("none".equals(contact) ? "Leave it on the table." : "Pick the phone up by its edges.");
            nextText.setText("none".equals(contact) ? "Recording starts at the buzz." : "hand".equals(contact)
                    ? "At the buzz, hold it in the air, screen facing away from you." : "At the buzz, press the screen on your forehead.");
            countdown.setTime(startT + WAIT_S - t, WAIT_S, "until the buzz");
            if (left != lastRemaining && left >= 1 && left <= 3) speaker.say(new String[]{"one", "two", "three"}[left - 1]);
            lastRemaining = left;
            if (t >= startT + WAIT_S) {
                contactT = t;
                phase = Phase.CONTACT;
                lastRemaining = Integer.MAX_VALUE;
                speaker.buzz();
                speaker.say("none".equals(contact) ? "Recording. Leave the phone alone for ninety seconds."
                        : "hand".equals(contact) ? "Now hold it still in the air, screen away from you, for ninety seconds."
                        : "Press now, flat and firm. Hold still for ninety seconds.");
            }
        } else if (phase == Phase.CONTACT) {
            int left = (int) Math.ceil(contactT + CONTACT_S - t);
            stepLabel.setText("none".equals(contact) ? "PHONE-ONLY CONTROL" : "hand".equals(contact) ? "HAND-ONLY CONTROL" : "FOREHEAD CALIBRATION");
            phaseText.setText("none".equals(contact) ? "Leave it be." : "Stay just like that.");
            nextText.setText("A buzz and \"Done\" mean you can " + ("none".equals(contact) ? "pick it up." : "lift the phone."));
            countdown.setTime(contactT + CONTACT_S - t, CONTACT_S, "of 90 seconds remaining");
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
        speaker.say(control() ? "Done. You can put the phone down." : "Done. You can lift the phone. Now type the thermometer reading.");
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
        // Drift only from time the phone lay still before Start (gyroscope), never from handling it.
        double[] st = new double[stillLog.size()];
        boolean[] sf = new boolean[stillLog.size()];
        for (int i = 0; i < st.length; i++) {
            st[i] = stillLog.get(i)[0];
            sf[i] = stillLog.get(i)[1] > 0.5;
        }
        double[] base = Steadiness.baseline(st, sf, startT);
        double driftFrom = base[0], driftTo = base[1];
        double baselineS = Double.isNaN(driftFrom) ? 0 : driftTo - driftFrom;
        new Thread(() -> {
            FeverAnalysis.Report rep = FeverAnalysis.analyze(copy, from, to, driftFrom, driftTo);
            ui.post(() -> showReport(rep, copy, baselineS));
        }).start();
    }

    /**
     * While the phone gets ready, keep the screen on (a screen-off pause would stop the readings) and at the
     * test's 20% brightness, so the baseline drift is measured with the same screen heat as the test itself.
     */
    private void holdForBaseline() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.2f;
        getWindow().setAttributes(lp);
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
        Ui.add(col, Ui.label(this, contactName()), 4);
        if (rep.best == null) {
            Ui.add(col, Ui.title(this, "No thermistor data"), 6);
            Ui.add(col, Ui.text(this, "The phone didn't report thermistor readings. One-time setup from a computer: "
                    + ThermalMonitor.SETUP, 15, Ui.MUTED, false), 8);
            Ui.add(col, Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), 20);
            setContentView(Ui.page(this, col));
            return;
        }
        FeverAnalysis.SensorResult b = rep.best;
        double shown = b.correctedCPerMin;
        if (control()) {   // a control's "cleanest" sensor means nothing: show the front sensors the estimate uses
            double sum = 0;
            int n = 0;
            for (FeverAnalysis.SensorResult r : rep.sensors) {
                if (FeverAnalysis.PREFERRED.contains(r.name) && !Double.isNaN(r.correctedCPerMin)) {
                    sum += r.correctedCPerMin;
                    n++;
                }
            }
            if (n > 0) shown = sum / n;
        }
        Ui.add(col, Ui.title(this, String.format(Locale.US, "%+.2f °C/min", shown)), 6);
        Ui.add(col, Ui.text(this, control()
                ? "How fast the phone warmed without your forehead (front sensors). Save it so the analysis can separate your "
                + "hand's and the phone's own heat from your forehead's."
                : String.format(Locale.US, "How fast your forehead warmed the phone (%s, which started at %.2f °C). Type the "
                + "thermometer reading to turn this into k.", b.name, b.startC), 14, Ui.MUTED, false), 2);
        if (anyway) Ui.wrap(col, Ui.badge(this, "The phone wasn't steady at the start: this run may be off", Ui.WARN), 10);
        if (baselineS < Steadiness.MIN_BASELINE_S) {
            Ui.wrap(col, Ui.badge(this, "The phone wasn't lying still for 20 s before Start, so its own drift isn't known and "
                    + "this run is less accurate. Next time leave it flat until it says ready.", Ui.WARN), 10);
        }

        if (control()) controlCard(col, rep, baselineS);
        else calibrationCard(col, rep, calKey);

        chartAndDetails(col, rep, series);
    }

    /** A control run: saved for the analysis, never used for k. */
    private void controlCard(LinearLayout col, FeverAnalysis.Report rep, double baselineS) {
        LinearLayout card = Ui.add(col, Ui.card(this), 16);
        Ui.add(card, Ui.label(this, "Save this control run"), 0);
        EditText who = Ui.add(card, Ui.textInput(this, "Whose hand (name or initials)"), 10);
        who.setText(Store.prefs(this).getString("calib_person", ""));
        TextView out = Ui.text(this, "", 14, Ui.TEXT, false);
        boolean[] saved = {false};
        Ui.add(card, Ui.button(this, "Save", Ui.TEMP, true, v -> {
            if (saved[0]) return;
            saved[0] = true;
            person = who.getText().toString().trim();
            Store.prefs(this).edit().putString("calib_person", person).apply();
            try {
                JSONObject o = new JSONObject();
                o.put("id", runId);
                o.put("type", "control");
                o.put("contact", contact);
                if (!person.isEmpty()) o.put("person", person);
                o.put("power_mode", startState.powerMode);
                o.put("unreliable", anyway);
                o.put("baseline_s", Math.round(baselineS));
                o.put("sensors", sensorsJson(rep, Double.NaN));
                o.put("raw_file", rawFile.getName());
                o.put("method", "feverphone.control." + contact + ".v1");
                Store.save(this, o);
                out.setText("Saved. It isn't used as a calibration; the analysis uses it to separate the heat sources.");
            } catch (JSONException e) {
                out.setText("Couldn't save: " + e.getMessage());
            }
        }), 10);
        Ui.add(card, out, 10);
    }

    private void calibrationCard(LinearLayout col, FeverAnalysis.Report rep, String calKey) {
        LinearLayout cal = Ui.add(col, Ui.card(this), 16);
        Ui.add(cal, Ui.label(this, "Thermometer reading"), 0);
        // Most thermometers show body temperature; a body reading is turned into the forehead skin
        // temperature that the skin-to-core model maps back onto it (SkinToCore.skinFor).
        boolean[] body = {!"skin".equals(Store.prefs(this).getString("calib_ref_kind", "body"))};
        LinearLayout kind = Ui.add(cal, Ui.column(this), 10);
        Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            kind.removeAllViews();
            kind.addView(Ui.segmented(this, new String[]{"Body temp (~37)", "Skin / surface"}, body[0] ? 0 : 1, i -> {
                body[0] = i == 0;
                Store.prefs(this).edit().putString("calib_ref_kind", body[0] ? "body" : "skin").apply();
                paint[0].run();
            }));
        };
        paint[0].run();
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
    }

    private void chartAndDetails(LinearLayout col, FeverAnalysis.Report rep, Map<String, FeverAnalysis.Series> series) {
        ChartView chart = new ChartView(this, 200).title("Thermistors, change since contact", "°C").minSpan(1);
        double origin = contactT;
        chart.band(0, CONTACT_S, 0x1FE8705E, "hand".equals(contact) ? "in the air" : "none".equals(contact) ? "on the table" : "forehead");
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
            o.put("contact", "forehead");
            o.put("sensors", sensorsJson(rep, refC));
            o.put("raw_file", rawFile.getName());
            o.put("method", "feverphone.calibration." + site + ".v2");   // v2: drift from the lying-still baseline
            Store.save(this, o);
        } catch (JSONException ignored) {
        }
        return sb.toString();
    }

    /** Each usable sensor's start temperature, rate and drift (and k when there's a reference). */
    private static JSONArray sensorsJson(FeverAnalysis.Report rep, double refC) throws JSONException {
        JSONArray sensors = new JSONArray();
        for (FeverAnalysis.SensorResult s : rep.sensors) {
            if (s.coarse || Double.isNaN(s.correctedCPerMin)) continue;
            JSONObject j = new JSONObject().put("name", s.name).put("start_c", Math.round(s.startC * 1000) / 1000.0)
                    .put("rate_c_per_min", Math.round(s.correctedCPerMin * 1000) / 1000.0);
            if (!Double.isNaN(s.driftCPerMin)) j.put("drift_c_per_min", Math.round(s.driftCPerMin * 1000) / 1000.0);
            double k = Double.isNaN(refC) ? Double.NaN : FeverAnalysis.kFromReference(s, refC);
            if (!Double.isNaN(k)) j.put("k", Math.round(k * 10000) / 10000.0);
            sensors.put(j);
        }
        return sensors;
    }
}
