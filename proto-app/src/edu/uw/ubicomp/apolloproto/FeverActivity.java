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
 * FeverPhone forehead test, the same procedure as calibration (CalibrateActivity) so k carries over:
 * the phone lies untouched while this screen is open (that's the drift baseline) -> Start -> 10 s to
 * pick it up -> 90 s screen on the forehead (spoken cues; the screen ignores touches) -> report.
 * Times are seconds from the Start tap: baseline < 0, pickup 0-10, forehead 10-100. (Until Oct 2026
 * this had a 60 s hand pre-hold whose heat was subtracted, while calibration kept it: a forehead read
 * 4 °F low with k from calibration.)
 *
 * Hand-held only (as in the paper). A "table" protocol (forehead resting on the phone lying on a table)
 * was tried and dropped in Oct 2026: with no hand warming the phone and the table drawing heat from its
 * back, the rise was a third of hand-held, too small next to the phone's own drift. The table code path
 * remains (protocol "table") but isn't offered.
 */
public class FeverActivity extends Activity implements ThermalMonitor.Listener {
    private static final int PREHOLD_S = 10, CONTACT_S = 90, BUFFER_S = 90;

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
    private TextView statusText, phaseText, nextText, stepLabel;
    private TextView readyChip;
    private RingView countdown;
    private TextView startButton, anywayButton;
    private final List<double[]> stillLog = new ArrayList<>();   // {t from Start, still 1/0} before Start

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
        table = false;   // hand-held only (see above)
        Store.prefs(this).edit().putString("fever_protocol", "hand").apply();
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

    private static final String HOW = "Your forehead is warmer than the phone. Press the screen to it and heat flows into the "
            + "glass, where the phone's own temperature sensors feel it. The warmer you are, the faster the phone warms.\n\n"
            + "Apollo times that warming for 90 seconds, takes away the phone's own drift, and turns the rate into a body "
            + "temperature with your calibration or its trained model. The method comes from FeverPhone, a University of "
            + "Washington study.";
    private static final String TIPS = "•  Turn on airplane mode and take the phone out of its case.\n"
            + "•  You won't see the screen, so the phone talks you through it: a buzz means press now, then it counts down "
            + "60, 30 and 10 seconds. A buzz and \"Done\" mean finished.\n"
            + "•  Do it the way you calibrated: same grip, same firmness, same spot.\n"
            + "•  The screen ignores touches during the test. Press Back to cancel.\n"
            + "•  Don't cool the phone in a fridge: it warms unevenly for 20 minutes or more. Let it rest on a table.";

    private void showIntro(String note) {
        phase = Phase.INTRO;
        restoreScreen();
        holdForBaseline();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        TextView title = Ui.add(col, Ui.title(this, "A gentle temperature check."), 6);
        Ui.fitOneLine(title, 32, 20);
        Ui.add(col, Ui.text(this, "About 2.5 minutes. Apollo talks you through it.", 16, Ui.MUTED, false), 6);
        LinearLayout hero = Ui.add(col, Ui.card(this), 18);   // white, like the mascot's own background
        LinearLayout heroRow = Ui.add(hero, Ui.row(this), 0);
        heroRow.setGravity(Gravity.CENTER);
        heroRow.addView(Ui.mascot(this, "hello", 112));
        View phone = Ui.icon(this, "phone", Ui.LEAF, 84);
        ((LinearLayout.LayoutParams) phone.getLayoutParams()).leftMargin = Ui.dp(this, 18);
        heroRow.addView(phone);
        Info.centred(hero, Info.howItWorks(this, HOW, "Breda et al., FeverPhone, IMWUT 2023", "https://doi.org/10.1145/3580850"), 8);
        if (note != null) Ui.wrap(col, Ui.badge(this, note, Ui.WARN), 12);

        LinearLayout head = Ui.add(col, Ui.row(this), 22);
        head.addView(Ui.section(this, "Four steps"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Info.button(this, "Before you start", TIPS));
        introSteps = Ui.add(col, new StepList(this, Ui.TEMP,
                "Leave the phone on a table until it's ready",
                table ? "Tap Start, then get your forehead over the phone" : "Tap Start, then pick the phone up by its edges",
                table ? "At the buzz, rest your forehead on the screen for 90 seconds"
                        : "At the buzz, press the screen to your forehead for 90 seconds",
                "Read your results"), 8);

        // Is the phone ready? One line, right above Start; the numbers behind it wait under the (i).
        LinearLayout ready = Ui.add(col, Ui.row(this), 20);
        readyChip = Ui.chip(this, "Checking", Ui.FAINT);
        ready.addView(readyChip);
        ready.addView(Ui.space(this));
        IconView more = Ui.icon(this, "info", Ui.MUTED, 32);
        int pad = Ui.dp(this, 6);
        more.setPadding(pad, pad, pad, pad);
        more.setBackground(Ui.rounded(0x00000000, Ui.dp(this, 16)));
        more.setContentDescription("About the phone's readiness");
        Ui.tappable(more, v -> new Info.Sheet(this).title("Is the phone ready?").body(readinessDetail()).show());
        ready.addView(more);
        statusText = Ui.add(col, Ui.text(this, "", 14, Ui.MUTED, false), 4);
        int runs = FeverCalibration.runs(this, "UNPLUGGED|hand");   // tests run unplugged (at 100% on a cable borrows these)
        if (runs == 0) {
            TextView cal = Ui.add(col, Ui.styled(this, "Not calibrated yet: calibrate once first  ›", 14, Ui.CORAL_DARK, 600), 8);
            cal.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            cal.setOnClickListener(v -> startActivity(new android.content.Intent(this, CalibrateActivity.class)));
        }

        startButton = Ui.add(col, Ui.button(this, "Start", Ui.TEMP, true, v -> start(false)), 14);
        anywayButton = Ui.add(col, Ui.button(this, "Start anyway", Ui.MUTED, false, v -> start(true)), 10);
        setContentView(Ui.page(this, col));
        updateIntro();
    }

    /** What's behind the readiness line: the phone's temperature, its drift, power, airplane mode and calibration. */
    private String readinessDetail() {
        ThermalMonitor.State s = monitor.state();
        boolean airplane = Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        int runs = FeverCalibration.runs(this, "UNPLUGGED|hand");
        return String.format(Locale.US, "%s\n\nPhone temperature: %s\nIts own drift: %s\nPower: %s\nAirplane mode: %s\nCalibration: %s\n\n"
                        + "Starting while the phone is still warming or cooling gives wrong readings (a chilled phone read 4 °F low), "
                        + "so Start waits until it's steady.", s.message,
                Double.isNaN(s.phoneC) ? "-" : String.format(Locale.US, "%.1f °C", s.phoneC),
                Double.isNaN(s.driftCPerMin) ? "-" : String.format(Locale.US, "%+.2f °C a minute", s.driftCPerMin),
                s.powerMode.toLowerCase(Locale.US), airplane ? "on" : "off",
                runs > 0 ? runs + " run" + (runs == 1 ? "" : "s") : "not yet");
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
    private String calibKey() {   // plugged in at 100% (trickle) shares the unplugged calibration
        return ("TRICKLE".equals(startState.powerMode) ? "UNPLUGGED" : startState.powerMode) + "|" + protocol();
    }

    private void updateIntro() {
        if (statusText == null) return;
        ThermalMonitor.State s = monitor.state();
        boolean ready = "READY".equals(s.verdict), unsupported = "UNSUPPORTED".equals(s.verdict);
        Ui.setChip(readyChip, ready ? "Phone ready" : Ui.readyLabel(s.verdict), ready ? Ui.GOOD : unsupported || s.verdict == null ? Ui.FAINT : Ui.WARN);
        statusText.setText(s.message);
        if (introSteps != null) introSteps.setCurrent(ready ? 1 : 0);
        startButton.setAlpha(ready ? 1f : 0.35f);
        startButton.setEnabled(ready);
        anywayButton.setVisibility(ready || unsupported ? View.GONE : View.VISIBLE);
    }

    // ---- Measuring ------------------------------------------------------------------------------

    private void start(boolean anyway) {
        this.anyway = anyway;
        startState = monitor.state();
        lastRemaining = Integer.MAX_VALUE;
        startMs = System.currentTimeMillis();
        rawFile = new File(Store.dir(this), "forehead_" + startMs + ".csv");
        synchronized (data) {
            data.clear();
            try {
                raw = new PrintWriter(new FileWriter(rawFile), true);
                raw.println("epoch_ms,t_s,phase,sensor,type,celsius");
            } catch (IOException e) {
                raw = null;
            }
            // The baseline: the monitor's readings from before Start (it runs whenever an Apollo screen is open,
            // home included), with whether the phone lay still at each (the drift uses only still time).
            stillLog.clear();
            for (Object[] h : monitor.recentSince(startMs - BUFFER_S * 1000L)) {
                long ms = (Long) h[0];
                double t = (ms - startMs) / 1000.0;
                @SuppressWarnings("unchecked") List<ThermalMonitor.Sample> got = (List<ThermalMonitor.Sample>) h[1];
                for (ThermalMonitor.Sample smp : got) record(ms, t, "before", smp);
                stillLog.add(new double[]{t, (Boolean) h[2] ? 1 : 0});
                logStill(ms, t, "before", (Boolean) h[2], (Double) h[3]);
            }
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

        phase = Phase.PREHOLD;
        speaker.say(table ? "Get your forehead over the phone. Rest it on the screen at the buzz."
                : "Pick up the phone by its edges. Press the screen to your forehead at the buzz.");
    }

    @Override
    public void onSamples(long epochMs, List<ThermalMonitor.Sample> samples) {
        Phase p = phase;
        synchronized (data) {
            if (p != Phase.PREHOLD && p != Phase.CONTACT) return;
            double t = (epochMs - startMs) / 1000.0;
            for (ThermalMonitor.Sample s : samples) record(epochMs, t, p == Phase.PREHOLD ? "wait" : "forehead", s);
            logStill(epochMs, t, p == Phase.PREHOLD ? "wait" : "forehead", monitor.stillNow(), monitor.gyroNow());
            PrintWriter w = raw;
            if (w != null) w.println(String.format(Locale.US, "%d,%.3f,%s,_gyropeak,0,%.4f", epochMs, t,
                    p == Phase.PREHOLD ? "wait" : "forehead", monitor.gyroPeak()));
        }
    }

    /** Lying still (1/0) and the gyroscope behind it, into the raw file for offline checks (caller holds the data lock). */
    private void logStill(long epochMs, double t, String label, boolean still, double gyro) {
        PrintWriter w = raw;
        if (w == null) return;
        w.println(String.format(Locale.US, "%d,%.3f,%s,_still,0,%d", epochMs, t, label, still ? 1 : 0));
        w.println(String.format(Locale.US, "%d,%.3f,%s,_gyro,0,%.4f", epochMs, t, label, gyro));
    }

    /** Writes one sample to the raw file and the analysis series (caller holds the data lock). */
    private void record(long epochMs, double t, String label, ThermalMonitor.Sample s) {
        PrintWriter w = raw;   // the main thread nulls it at the end of a run
        if (w != null) w.println(String.format(Locale.US, "%d,%.3f,%s,%s,%d,%.4f", epochMs, t, label, s.name, s.type, s.celsius));
        if (s.name.equals("headroom_api") || s.name.equals("bcl-virt-monitor")) return;
        data.computeIfAbsent(s.name, k -> new FeverAnalysis.Series(k, s.type)).points.add(new double[]{t, s.celsius});
    }

    private void onTick() {
        if (phase == Phase.INTRO) {
            updateIntro();
            return;
        }
        double t = (System.currentTimeMillis() - startMs) / 1000.0;
        if (phase == Phase.PREHOLD) {
            int left = (int) Math.ceil(PREHOLD_S - t);
            stepLabel.setText("STEP 1 OF 2  ·  PICK UP");
            phaseText.setText(table ? "Get your forehead over the phone." : "Pick the phone up by its edges.");
            nextText.setText("At the buzz, the screen goes flat against your forehead.");
            countdown.setTime(PREHOLD_S - t, PREHOLD_S, "until the buzz");
            cue(left, false);
            if (t >= PREHOLD_S) {
                phase = Phase.CONTACT;
                lastRemaining = Integer.MAX_VALUE;
                speaker.buzz();
                speaker.say(table ? "Now. Rest your forehead on the middle of the screen. Stay still for ninety seconds."
                        : "Now. Press the screen flat against your forehead and hold still for ninety seconds.");
            }
        } else if (phase == Phase.CONTACT) {
            double c = t - PREHOLD_S;
            int left = (int) Math.ceil(CONTACT_S - c);
            stepLabel.setText("STEP 2 OF 2  ·  FOREHEAD CONTACT");
            phaseText.setText("Stay just like that.");
            nextText.setText("Keep the screen flat against your forehead. Apollo will tell you when you're done.");
            countdown.setTime(CONTACT_S - c, CONTACT_S, "of 90 seconds remaining");
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
        else if (left == 30 && onForehead) say = "Thirty seconds left.";
        else if (left == 10 && onForehead) say = "Ten seconds.";
        else if (onForehead && left >= 1 && left <= 5) say = new String[]{"one", "two", "three", "four", "five"}[left - 1];
        else if (!onForehead && left >= 1 && left <= 3) say = new String[]{"one", "two", "three"}[left - 1];
        if (say != null) speaker.say(say);
    }

    private void abort(String why) {
        synchronized (data) {
            if (raw != null) raw.close();
            raw = null;
            data.clear();   // the baseline starts afresh: the phone was just handled
        }
        showIntro(why);
    }

    private void finish90() {
        phase = Phase.ANALYZING;
        synchronized (data) {
            if (raw != null) raw.close();
            raw = null;
        }
        speaker.buzz();
        speaker.say("Done. You can lower the phone.");
        restoreScreen();
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.title(this, "Analysing…"), 0);
        setContentView(Ui.page(this, col));
        Map<String, FeverAnalysis.Series> copy;
        double[] st;
        boolean[] sf;
        synchronized (data) {
            copy = new LinkedHashMap<>(data);
            st = new double[stillLog.size()];
            sf = new boolean[stillLog.size()];
            for (int i = 0; i < st.length; i++) {
                st[i] = stillLog.get(i)[0];
                sf[i] = stillLog.get(i)[1] > 0.5;
            }
        }
        // Drift only from time the phone lay still before Start (as in calibration); the hand's heat stays in the rate.
        double[] baseline = Steadiness.baseline(st, sf, 0);
        new Thread(() -> {
            FeverAnalysis.Report rep = FeverAnalysis.analyze(copy, PREHOLD_S, PREHOLD_S + CONTACT_S, baseline[0], baseline[1]);
            JSONObject json = toJson(rep);
            if (json != null) Store.save(this, json);
            ui.post(() -> showReport(rep, json, copy));
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
            o.put("method", "feverphone.dump." + startState.powerMode.toLowerCase(Locale.US) + "." + protocol() + ".v4");   // v4: lying-still baseline
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
        if (rep.best == null) {
            Ui.add(col, Ui.title(this, "No thermistor data"), 6);
            Ui.add(col, Ui.text(this, "The phone didn't report thermistor readings. One-time setup from a computer: "
                    + ThermalMonitor.SETUP, 15, Ui.MUTED, false), 8);
            Ui.add(col, Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), 20);
            setContentView(Ui.page(this, col));
            return;
        }
        FeverAnalysis.SensorResult b = rep.best;
        Outcome oc = outcome(rep);
        saveOutcome(oc, json);
        boolean shaky = Double.isNaN(oc.core) || oc.unreliable != null;
        TextView title = Ui.add(col, Ui.title(this, Double.isNaN(oc.core) ? (oc.redo ? "Let's try that again." : "No reading this time.")
                : oc.unreliable != null ? "Here's a rough reading." : "Check-in complete."), 6);
        Ui.fitOneLine(title, 32, 22);
        LinearLayout head = Ui.add(col, Ui.row(this), 10);
        LinearLayout numCol = Ui.column(this);
        head.addView(numCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Ui.mascot(this, Double.isNaN(oc.core) ? "wink" : shaky ? "thinking" : oc.fever ? "curious" : "happy", 100));
        LinearLayout num = Ui.row(this);
        num.setGravity(Gravity.BOTTOM);
        numCol.addView(num);
        if (!Double.isNaN(oc.core)) {
            TextView value = Ui.number(this, "", 72);
            num.addView(value);
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ul.setMargins(Ui.dp(this, 10), 0, 0, Ui.dp(this, 18));
            num.addView(unitToggle(value, oc.core), ul);
            Ui.wrap(numCol, Ui.badge(this, oc.unreliable != null ? "Rough reading" : "✓  Good reading",
                    oc.unreliable != null ? Ui.WARN : Ui.GOOD), 4);
            if (oc.unreliable != null) {
                Ui.add(col, Ui.text(this, oc.unreliable, 14, Ui.WARN, false), 8);
                Ui.add(col, Ui.button(this, "Take it again", Ui.TEMP, false, v -> showIntro(null)), 10);
            }
            if (oc.fever) Ui.wrap(col, Ui.badge(this, "Possible fever: check with a thermometer", Ui.BAD), 8);
            if (oc.fromArm) Ui.wrap(col, Ui.badge(this, "Using the arm calibration: forehead contact differs, so this may be offset", Ui.WARN), 8);
        } else {
            num.addView(Ui.number(this, "--", 72));
            Ui.add(col, Ui.text(this, oc.redo ? oc.noNumber : "No temperature this time. " + oc.noNumber, 16, Ui.WARN, false), 4);
            if (oc.redo) Ui.add(col, Ui.button(this, "Try again", Ui.TEMP, true, v -> showIntro(null)), 12);
            if (oc.notCalibrated && !oc.redo) {
                LinearLayout fix = Ui.add(col, Ui.row(this), 10);
                LinearLayout.LayoutParams f1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                fix.addView(Ui.button(this, "Calibrate", Ui.TEMP, true, v -> startActivity(new android.content.Intent(this, CalibrateActivity.class))), f1);
                if (table && FeverCalibration.runs(this, startState.powerMode + "|hand") > 0) {
                    LinearLayout.LayoutParams f2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                    f2.leftMargin = Ui.dp(this, 10);
                    fix.addView(Ui.button(this, "Use hand-held", Ui.TEMP, false, v -> {
                        table = false;
                        Store.prefs(this).edit().putString("fever_protocol", "hand").apply();
                        showIntro(null);
                    }), f2);
                }
            }
        }
        Ui.add(col, Ui.text(this, String.format(Locale.US, "Your forehead warmed the phone %+.2f °C over 90 s.", b.riseC),
                15, Ui.TEXT, false), 14);

        // The rest (how the number was made, the curves, the sensors, the conditions) waits behind "Learn more".
        LinearLayout more = Info.learnMore(col, 8);
        Ui.add(more, Ui.text(this, String.format(Locale.US, "That's %+.2f °C a minute once the phone's own drift (%+.2f °C a minute) "
                + "is taken away.", b.correctedCPerMin, b.driftCPerMin), 14, Ui.MUTED, false), 4);
        if (!Double.isNaN(oc.core) && oc.e != null) detailsCard(more, oc);
        // Curves: change from contact start for the three cleanest sensors.
        ChartView chart = new ChartView(this, 210).title("Thermistors, change since the forehead touched", "°C").minSpan(1);
        chart.band(-40, 0, 0x0A000000, "baseline");
        chart.band(0, PREHOLD_S, 0x12000000, "pickup");
        chart.band(PREHOLD_S, PREHOLD_S + CONTACT_S, 0x1FE8705E, "forehead");
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
        Ui.add(more, chart, 12);

        LinearLayout t = Ui.add(more, Ui.card(this), 12);
        Ui.add(t, Ui.label(this, "Sensors, cleanest first"), 0);
        for (int i = 0; i < Math.min(5, rep.sensors.size()); i++) {
            FeverAnalysis.SensorResult s = rep.sensors.get(i);
            t.addView(Ui.kv(this, s.name, String.format(Locale.US, "%+.2f °C/min · rise %+.2f · SNR %.0f%s",
                    s.correctedCPerMin, s.riseC, s.snr, s.coarse ? " (coarse)" : "")));
        }
        LinearLayout d = Ui.add(more, Ui.card(this), 12);
        Ui.add(d, Ui.label(this, "Conditions"), 0);
        d.addView(Ui.kv(this, "Phone at the start", String.format(Locale.US, "%.2f °C (%s)", b.startC, b.name)));
        d.addView(Ui.kv(this, table ? "Phone's own drift (on the table)" : "Phone's own drift (in the hand)", String.format(Locale.US, "%+.2f °C/min", b.driftCPerMin)));
        d.addView(Ui.kv(this, "Readiness at start", startState.verdict.toLowerCase(Locale.US)));
        d.addView(Ui.kv(this, "Power", startState.powerMode.toLowerCase(Locale.US)));
        d.addView(Ui.kv(this, "Protocol", table ? "resting on a table" : "hand-held"));
        d.addView(Ui.kv(this, "Raw data", rawFile.getName()));
        if (!Double.isNaN(oc.forest)) {   // how the number was made, for the curious (not a caveat)
            d.addView(Ui.kv(this, "Temperature model", String.format(Locale.US, "random forest · %s runs, %s people",
                    oc.model.meta("runs", "?"), oc.model.meta("people", "?"))));
            if (!Double.isNaN(oc.physics)) d.addView(Ui.kv(this, "Physics estimate", String.format(Locale.US, "%.1f °C", oc.physics)));
        }
        if (oc.check != null) d.addView(Ui.kv(this, "Motion during the reading", oc.check.movingS == 0 ? "steady"
                : oc.check.movingS + " s moving"));
        Info.centred(col, Info.disclaimer(this), 12);

        LinearLayout buttons = Ui.add(col, Ui.row(this), 12);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(Ui.button(this, "Measure again", Ui.TEMP, false, v -> showIntro(null)), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = Ui.dp(this, 10);
        buttons.addView(Ui.button(this, "Done", Ui.TEMP, true, v -> finish()), half2);
        setContentView(Ui.page(this, col));
    }

    /** The unit, in a pill beside the reading: tap it to switch °C and °F (remembered). */
    private TextView unitToggle(TextView value, double celsius) {
        TextView u = Ui.styled(this, "", 20, Ui.CORAL_DARK, 700);
        u.setBackground(Ui.rounded(Ui.CORAL_TINT, Ui.dp(this, 100)));
        u.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        Runnable show = () -> {
            boolean f = Store.prefs(this).getBoolean("temp_in_f", false);
            value.setText(String.format(Locale.US, "%.1f", f ? celsius * 9 / 5 + 32 : celsius));
            u.setText(f ? "°F" : "°C");
            u.setContentDescription((f ? "Fahrenheit" : "Celsius") + ". Tap to switch.");
        };
        show.run();
        Ui.tappable(u, v -> {
            Store.prefs(this).edit().putBoolean("temp_in_f", !Store.prefs(this).getBoolean("temp_in_f", false)).apply();
            show.run();
        });
        return u;
    }

    /** What a run amounts to: a temperature, or the reason there isn't one. */
    private static final class Outcome {
        FeverAnalysis.Estimate e;
        String usedKey, noNumber, unreliable;   // noNumber: no estimate at all; unreliable: an estimate, but why it's rough
        boolean fromArm, fever, notCalibrated, usableForCalibration;
        int runs;
        double core = Double.NaN;   // the reading shown: the forest's when it has one, else the physics estimate
        double physics = Double.NaN, forest = Double.NaN;
        double[] features;          // what the forest saw: start, rate, rise at 30, 5 and 90 s
        FeverForest model;
        ReadingCheck check;         // what happened during the 90 s
        boolean redo;               // the run went wrong: ask for another
    }

    private Outcome outcome(FeverAnalysis.Report rep) {
        Outcome oc = new Outcome();
        String mode = calibKey(), armKey = startState.powerMode + "|arm";
        Map<String, Double> k = FeverCalibration.kBySensor(this, mode);
        if (k.isEmpty() && "TRICKLE".equals(startState.powerMode)) {   // plugged in at 100%: contact physics is the same as unplugged
            mode = "UNPLUGGED|" + protocol();
            k = FeverCalibration.kBySensor(this, mode);
        }
        oc.fromArm = k.isEmpty() && !FeverCalibration.kBySensor(this, armKey).isEmpty();
        oc.usedKey = oc.fromArm ? armKey : mode;   // (mode may be the unplugged fallback)
        if (oc.fromArm) k = FeverCalibration.kBySensor(this, armKey);
        oc.runs = FeverCalibration.runs(this, oc.usedKey);
        double[] dc = FeverAnalysis.drift(rep);   // the phone's own drift on the sensors the estimate uses
        double drift = dc[0], contact = dc[1];
        boolean baseline = !Double.isNaN(drift), drifting = FeverAnalysis.drifting(dc);
        oc.usableForCalibration = baseline && !drifting;
        // A good run is trusted. The only pre-start problem that changes the reading is a phone already warming or
        // cooling fast by itself; the rest of the judgement is about the 90 s on the forehead (ReadingCheck).
        String startProblem = drifting ? String.format(Locale.US, "The phone was still %s by itself (%+.2f °C/min) when you "
                + "started, so this reading is rough. Let it rest on the table a few minutes first next time.",
                drift > 0 ? "warming" : "cooling", drift) : null;
        if (k.isEmpty()) {
            oc.notCalibrated = true;
            oc.noNumber = table ? "\"On a table\" hasn't been calibrated yet, and its readings differ from hand-held ones (no hand "
                    + "warming the phone). Calibrate it once, or switch to hand-held, which is calibrated."
                    : "This way of measuring hasn't been calibrated yet. Calibrate it once on your skin.";
        } else {
            oc.e = FeverAnalysis.estimate(rep, k);
            if (oc.e.sensors == 0) {
                oc.noNumber = "None of the calibrated sensors warmed enough to read.";
            } else {
                oc.core = SkinToCore.coreC(oc.e.skinC);
                oc.fever = SkinToCore.possibleFever(oc.core);
            }
        }
        oc.physics = oc.core;
        // The random forest (tools/train_fever_forest.py) reads the run's raw file, so it needs no calibration on
        // this phone. It only knows the phone it was trained on: other phones' sensors have other names.
        oc.model = FeverForest.get(getAssets()::open);
        if (oc.model != null) {
            try {
                oc.features = FeverForest.features(rawFile);
            } catch (IOException e) {
                oc.features = null;
            }
            if (oc.features != null) {
                oc.forest = oc.model.predict(oc.features);
                oc.core = oc.forest;
                oc.fever = SkinToCore.possibleFever(oc.core);
                oc.noNumber = null;
            }
        }
        try {
            oc.check = ReadingCheck.of(rawFile).orRough(startProblem);
        } catch (IOException e) {
            oc.check = null;
        }
        if (oc.check != null && oc.check.verdict == ReadingCheck.Verdict.REDO) {
            oc.redo = true;
            oc.core = Double.NaN;
            oc.fever = false;
            oc.noNumber = oc.check.reason;
        } else if (oc.check != null && oc.check.verdict == ReadingCheck.Verdict.ROUGH) {
            oc.unreliable = oc.check.reason;
        } else {
            oc.unreliable = oc.check == null ? startProblem : null;
        }
        return oc;
    }

    private void saveOutcome(Outcome oc, JSONObject json) {
        try {
            if (!Double.isNaN(oc.core)) {
                json.put("core_c_est", r3(oc.core));   // what the app showed (the forest's when it ran)
                if (oc.unreliable != null) json.put("unreliable_reason", oc.unreliable);
                json.put("unreliable", oc.unreliable != null);
                json.put("possible_fever", oc.fever);
                if (oc.e != null && !Double.isNaN(oc.physics)) {
                    json.put("physics_core_c", r3(oc.physics));
                    json.put("skin_c_est", r3(oc.e.skinC));
                    json.put("skin_spread_c", r3(oc.e.spreadC));
                    json.put("calibration_runs", oc.runs);
                    json.put("calibration_site", oc.fromArm ? "arm" : protocol());
                }
                if (!Double.isNaN(oc.forest)) {
                    json.put("forest_core_c", r3(oc.forest));
                    json.put("forest_features", new JSONArray(oc.features));
                    json.put("forest_model", "runs=" + oc.model.meta("runs", "?") + " loo_mae=" + oc.model.meta("loo_mae", "?")
                            + " trained=" + oc.model.meta("trained", "?"));
                }
            } else {
                json.put("no_number_reason", oc.noNumber);
                json.put("unreliable", true);
            }
            json.put("started_before_ready", anyway);
            if (oc.check != null) {
                json.put("reading_check", oc.check.verdict.name());
                if (oc.check.reason != null) json.put("check_reason", oc.check.reason);
                json.put("moving_s", oc.check.movingS);
                json.put("contact_drop_c", r3(oc.check.drop));
            }
            Store.save(this, json);
        } catch (JSONException ignored) {
        }
    }

    /** How the estimate was made: each calibrated sensor's skin temperature. */
    private void detailsCard(LinearLayout col, Outcome oc) {
        LinearLayout card = Ui.add(col, Ui.card(this), 14);
        Ui.add(card, Ui.label(this, "How it was worked out"), 0);
        boolean disagree = !Double.isNaN(oc.e.spreadC) && oc.e.spreadC > 0.5;
        Ui.add(card, Ui.text(this, String.format(Locale.US, "Forehead skin ≈ %.1f °C: the median of %d calibrated sensor%s%s, using %d "
                + "calibration run%s (%s). Body temperature from skin with a model fitted on 1,020 people (FDA dataset); it "
                + "flags fever from %.2f °C.", oc.e.skinC, oc.e.sensors, oc.e.sensors == 1 ? "" : "s",
                Double.isNaN(oc.e.spreadC) ? "" : String.format(Locale.US, ", which agree within ±%.2f °C%s", oc.e.spreadC,
                        disagree ? " (they disagree, so treat as rough)" : ""),
                oc.runs, oc.runs == 1 ? "" : "s", oc.usedKey.toLowerCase(Locale.US).replace("|", ", "), SkinToCore.FEVER_FLAG_C),
                13, disagree ? Ui.WARN : Ui.MUTED, false), 6);
        for (String line : oc.e.lines) Ui.add(card, Ui.text(this, line, 12, Ui.MUTED, false), 3);
    }

    private int pairedCount() {
        int n = 0;
        for (JSONObject o : Store.history(this, "forehead")) if (o.has("ref_c")) n++;
        return n;
    }
}
