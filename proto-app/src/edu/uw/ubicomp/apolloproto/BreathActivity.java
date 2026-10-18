package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
import java.util.List;
import java.util.Locale;

/**
 * Breath strength: four hard blows at the screen of a phone lying flat; the barometer sees the sealed phone's
 * glass flex (tools/barotest/README.md). Ported from mmh132's Compose screen and BlowSession (blow-strength
 * branch). The result is compared with your own earlier sessions on this phone model, never shown as peak flow.
 * Cues are visual only: sound or vibration would move the air or the glass, which the barometer would see.
 */
public class BreathActivity extends Activity implements SensorEventListener {
    static final double DURATION_S = 36;
    static final double[] CUES_S = {3, 12, 21, 30};   // four blows, 9 s apart
    static final double CUE_LENGTH_S = 2.5, LIVE_WINDOW_S = 4;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private Sensor barometer, accelerometer;
    private boolean running, flat, moved, touched;
    private long t0Ns = -1, startMs;
    private final List<Double> t = new ArrayList<>(), p = new ArrayList<>();   // s, Pa
    private double lastAboveS = -10, aboveSinceS = -1, peakPa;
    private int blowsSoFar;
    private PrintWriter raw;
    private File rawFile;

    // Live views
    private TextView flatText, cueText, devText, blowsText, warnText;
    private android.view.View startButton;
    private RingView ring;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sensors = getSystemService(SensorManager.class);
        barometer = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        showIntro();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        if (running) sensors.registerListener(this, barometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
        if (running) stop("Stopped: the app went to the background.");
    }

    @Override
    public void onBackPressed() {
        if (running) stop(null);
        else super.onBackPressed();
    }

    // ---- Intro ----------------------------------------------------------------------------------

    private static final String HOW = "Your phone has a tiny air-pressure sensor, a barometer, sealed inside. Blow hard at the screen "
            + "from a few centimetres away and the glass flexes very slightly, squeezing the air inside: the barometer feels a "
            + "brief pressure blip.\n\nA harder blow gives a bigger blip. Apollo takes your best three of four blows and compares "
            + "them with your own earlier results on this phone. It's not a lung function test: lip shape and aim matter too, "
            + "so your own trend is what means something.";
    private static final String TIPS = "•  No case pressing on the screen.\n"
            + "•  Hands off the phone: a touch looks a hundred times bigger than a blow.\n"
            + "•  Lay a finger on the middle of the screen, put your lips just above it, then lift the finger away.\n"
            + "•  Keep the same round \"O\", about a finger wide, every time.\n"
            + "•  Take a full breath and blow as hard and fast as you can. Four blows, 9 seconds apart.";

    private void showIntro() {
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        TextView title = Ui.add(col, Ui.title(this, "A quick lung check."), 6);
        Ui.fitOneLine(title, 32, 22);
        Ui.add(col, Ui.text(this, "How hard you can blow, compared with your own usual.", 16, Ui.MUTED, false), 6);
        if (barometer == null) {
            Ui.add(col, Ui.text(this, "This phone has no barometer, so breath strength isn't available on it.", 16, Ui.WARN, false), 18);
            setContentView(Ui.page(this, col));
            return;
        }
        LinearLayout hero = Ui.add(col, Ui.card(this), 18);   // white, like the mascot's own background
        LinearLayout heroRow = Ui.add(hero, Ui.row(this), 0);
        heroRow.setGravity(Gravity.CENTER);
        heroRow.addView(Ui.mascot(this, "hello", 112));
        View phone = Ui.icon(this, "phone", Ui.LEAF, 84);
        ((LinearLayout.LayoutParams) phone.getLayoutParams()).leftMargin = Ui.dp(this, 18);
        heroRow.addView(phone);
        Info.centred(hero, Info.howItWorks(this, HOW, "Hafez et al., Barometers Can Hear, 2020", "https://arxiv.org/abs/2008.03872"), 8);
        LinearLayout head = Ui.add(col, Ui.row(this), 22);
        head.addView(Ui.section(this, "Four steps"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Info.button(this, "Tips", TIPS));
        Ui.add(col, new StepList(this, Ui.CORAL,
                "Lay the phone flat, screen up, hands off",
                "Hold your lips just above the middle of the screen",
                "Make a round \"O\", about a finger wide",
                "Blow hard four times when it says Blow now"), 8);
        LinearLayout ready = Ui.add(col, Ui.card(this), 14);
        flatText = Ui.add(ready, Ui.styled(this, "", 16, Ui.TEXT, 600), 0);
        List<Double> past = sessions();
        double base = BlowDetector.baseline(past);
        Ui.add(ready, Ui.text(this, Double.isNaN(base)
                ? String.format(Locale.US, "Your first %d results build your baseline (%d so far).", BlowDetector.MIN_FOR_BASELINE, past.size())
                : String.format(Locale.US, "Your usual: %.0f Pa (median of your last %d).", base,
                Math.min(past.size(), BlowDetector.BASELINE_SESSIONS)), 14, Ui.MUTED, false), 6);
        startButton = Ui.add(col, Ui.button(this, "Start (about 40 s)", Ui.CORAL, true, v -> start()), 18);
        Info.centred(col, Info.disclaimer(this), 12);
        setContentView(Ui.page(this, col));
        showFlat();
    }

    private void showFlat() {
        if (flatText == null || running) return;
        flatText.setText(flat ? "✓  Phone is flat" : "Lay the phone flat to start");
        flatText.setTextColor(flat ? Ui.GOOD : Ui.WARN);
        startButton.setEnabled(flat);
        startButton.setAlpha(flat ? 1f : 0.35f);
    }

    // ---- The test -------------------------------------------------------------------------------

    private void start() {
        if (barometer == null) return;
        t.clear();
        p.clear();
        t0Ns = -1;
        lastAboveS = -10;
        aboveSinceS = -1;
        peakPa = 0;
        blowsSoFar = 0;
        moved = touched = false;
        startMs = System.currentTimeMillis();
        rawFile = new File(Store.dir(this), "breath_" + startMs + ".csv");
        try {
            raw = new PrintWriter(new FileWriter(rawFile), true);
            raw.println("t_s,hpa,flat");
        } catch (IOException e) {
            raw = null;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        cueText = Ui.add(col, Ui.styled(this, "Get ready", 30, Ui.TEXT, 700), 16);
        cueText.setGravity(Gravity.CENTER);
        ring = new RingView(this, false);   // the target: aim at the middle of the screen
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.gravity = Gravity.CENTER_HORIZONTAL;
        rl.topMargin = Ui.dp(this, 22);
        col.addView(ring, rl);
        devText = Ui.add(col, Ui.styled(this, "Pressure change +0 Pa", 16, Ui.MUTED, 500), 20);
        devText.setGravity(Gravity.CENTER);
        blowsText = Ui.add(col, Ui.styled(this, "Blows detected: 0", 16, Ui.TEXT, 600), 6);
        blowsText.setGravity(Gravity.CENTER);
        warnText = Ui.add(col, Ui.styled(this, "", 15, Ui.WARN, 500), 14);
        warnText.setGravity(Gravity.CENTER);
        Ui.add(col, Ui.styled(this, "Hands off the phone  ·  Back cancels", 13, Ui.FAINT, 400), 24).setGravity(Gravity.CENTER);
        setContentView(Ui.page(this, col));

        running = true;
        sensors.registerListener(this, barometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            double x = e.values[0], y = e.values[1], z = e.values[2];
            flat = z > 9.3 && Math.hypot(x, y) < 1.5;   // screen up on a table: gravity almost all on +z
            if (running && !flat) moved = true;
            showFlat();
        } else if (e.sensor.getType() == Sensor.TYPE_PRESSURE && running) {
            onPressure(e.timestamp, e.values[0]);
        }
    }

    private void onPressure(long tNs, double hpa) {
        if (t0Ns < 0) t0Ns = tNs;
        double ts = (tNs - t0Ns) / 1e9;
        t.add(ts);
        p.add(hpa * 100);
        if (raw != null) raw.println(String.format(Locale.US, "%.4f,%.4f,%d", ts, hpa, flat ? 1 : 0));
        double dev = ts < 1 ? 0 : p.get(p.size() - 1) - BlowDetector.trailingMedian(t, p, p.size() - 1, LIVE_WINDOW_S);
        if (dev > BlowDetector.EVENT_PA) {
            if (ts - lastAboveS > BlowDetector.MERGE_S) {
                aboveSinceS = ts;
                if (dev < BlowDetector.TOUCH_PA) blowsSoFar++;
            }
            lastAboveS = ts;
        }
        if (dev > BlowDetector.TOUCH_PA) touched = true;
        peakPa = Math.max(peakPa, dev);

        String cue;
        int cueIdx = -1;
        for (int i = 0; i < CUES_S.length; i++) if (ts >= CUES_S[i] && ts < CUES_S[i] + CUE_LENGTH_S) cueIdx = i;
        if (cueIdx >= 0) cue = String.format(Locale.US, "Blow now! (%d of %d)", cueIdx + 1, CUES_S.length);
        else cue = ts < CUES_S[0] ? "Get ready" : "Breathe in and get ready";
        cueText.setText(cue);
        cueText.setTextColor(cueIdx >= 0 ? Ui.CORAL_DARK : Ui.TEXT);
        double next = Double.NaN;
        for (double c : CUES_S) if (c > ts) {
            next = c;
            break;
        }
        if (cueIdx >= 0) ring.setValue("Blow", 1f, "as hard as you can");
        else if (!Double.isNaN(next)) ring.setTime(next - ts, 9, "until the next blow");
        else ring.setTime(DURATION_S - ts, DURATION_S - CUES_S[CUES_S.length - 1], "until done");
        devText.setText(String.format(Locale.US, "Pressure change %+.0f Pa", Math.max(-99, Math.min(999, dev))));
        blowsText.setText("Blows detected: " + blowsSoFar);
        warnText.setText(touched ? "That looked like a touch. Keep your hands off the phone." : moved ? "The phone moved. Keep it flat on the table." : "");
        if (ts >= DURATION_S) finishRun();
    }

    private void stop(String why) {
        running = false;
        sensors.unregisterListener(this, barometer);
        if (raw != null) raw.close();
        raw = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showIntro();
        if (why != null) android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void finishRun() {
        running = false;
        sensors.unregisterListener(this, barometer);
        if (raw != null) raw.close();
        raw = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        double[] ts = new double[t.size()], hpa = new double[p.size()];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = t.get(i);
            hpa[i] = p.get(i) / 100;
        }
        BlowDetector.Summary s = BlowDetector.analyze(ts, hpa);
        List<Double> past = sessions();
        double base = BlowDetector.baseline(past);
        boolean counts = !moved && (s.quality == BlowDetector.Quality.GOOD || s.quality == BlowDetector.Quality.OK);
        if (counts) record(s.strengthPa, past);
        save(s, base, counts);
        showResult(s, base, past.size(), counts);
    }

    // ---- Result ---------------------------------------------------------------------------------

    private void showResult(BlowDetector.Summary s, double base, int before, boolean counts) {
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        boolean tooFew = s.quality == BlowDetector.Quality.TOO_FEW;
        boolean redo = tooFew || s.quality == BlowDetector.Quality.INCONSISTENT;
        TextView title = Ui.add(col, Ui.title(this, tooFew ? "Let's try that again." : redo ? "Those blows varied a lot."
                : moved ? "Here's a rough result." : "Nice work."), 6);
        Ui.fitOneLine(title, 32, 22);
        LinearLayout head = Ui.add(col, Ui.row(this), 14);
        head.setGravity(Gravity.BOTTOM);
        if (tooFew) {
            head.addView(Ui.number(this, "--", 72));
            Ui.add(col, Ui.text(this, String.format(Locale.US, "Fewer than 3 blows were detected (%d). Blow from closer (a finger's width) "
                    + "and as hard as you can.", s.blows().size()), 16, Ui.WARN, false), 6);
        } else {
            boolean pct = !Double.isNaN(base);
            head.addView(Ui.number(this, pct ? String.format(Locale.US, "%.0f", 100 * s.strengthPa / base) : String.format(Locale.US, "%.0f", s.strengthPa), 72));
            TextView unit = Ui.styled(this, pct ? "%\nof your usual" : "Pa", 15, Ui.MUTED, 500);
            unit.setPadding(Ui.dp(this, 8), 0, 0, Ui.dp(this, 12));
            head.addView(unit);
            String quality = s.quality == BlowDetector.Quality.GOOD ? "✓  Consistent blows" : s.quality == BlowDetector.Quality.OK
                    ? "Fairly consistent" : "Inconsistent, retake";
            Ui.wrap(col, Ui.badge(this, quality, s.quality == BlowDetector.Quality.GOOD ? Ui.GOOD : Ui.WARN), 8);
            Ui.add(col, Ui.text(this, pct ? String.format(Locale.US, "%.0f Pa today; your usual is %.0f Pa.", s.strengthPa, base)
                    : String.format(Locale.US, "Building your baseline (%d of %d). Pascals mean little on their own; the comparison "
                    + "with your own results is the point.", Math.min(before + (counts ? 1 : 0), BlowDetector.MIN_FOR_BASELINE),
                    BlowDetector.MIN_FOR_BASELINE), 14, Ui.MUTED, false), 8);
            if (moved) Ui.add(col, Ui.text(this, "The phone moved during the test, so this result is rough and wasn't added to your baseline.",
                    14, Ui.WARN, false), 6);
        }
        if (s.touches() > 0) Ui.add(col, Ui.text(this, s.touches() == 1 ? "One touch was left out. Keep your hands off the phone."
                : s.touches() + " touches were left out. Keep your hands off the phone.", 14, Ui.WARN, false), 6);

        LinearLayout more = Info.learnMore(col, 10);
        LinearLayout d = Ui.add(more, Ui.card(this), 4);
        Ui.add(d, Ui.label(this, "Your blows"), 0);
        List<BlowDetector.Event> blows = s.blows();
        StringBuilder peaks = new StringBuilder();
        for (BlowDetector.Event e : blows) peaks.append(peaks.length() == 0 ? "" : ", ").append(String.format(Locale.US, "%.0f", e.peakPa));
        d.addView(Ui.kv(this, "Peaks", blows.isEmpty() ? "none" : peaks + " Pa"));
        if (!Double.isNaN(s.spreadPct)) d.addView(Ui.kv(this, "Spread of the top 3", String.format(Locale.US, "%.0f%%", s.spreadPct)));
        d.addView(Ui.kv(this, "Phone", Build.MODEL));
        if (rawFile != null) d.addView(Ui.kv(this, "Raw data", rawFile.getName()));

        Info.centred(col, Info.disclaimer(this), 12);
        LinearLayout buttons = Ui.add(col, Ui.row(this), 12);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(Ui.button(this, "Try again", Ui.CORAL, redo, v -> showIntro()), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = Ui.dp(this, 10);
        buttons.addView(Ui.button(this, "Done", Ui.CORAL, !redo, v -> finish()), half2);
        setContentView(Ui.page(this, col));
    }

    // ---- Storage --------------------------------------------------------------------------------

    /** Past good sessions' strengths on this phone model (absolute pascals depend on the phone's glass). */
    private List<Double> sessions() {
        List<Double> out = new ArrayList<>();
        for (String v : Store.prefs(this).getString("blow_sessions_" + Build.MODEL, "").split(",")) {
            try {
                if (!v.isEmpty()) out.add(Double.parseDouble(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private void record(double strengthPa, List<Double> past) {
        List<Double> kept = new ArrayList<>(past);
        kept.add(strengthPa);
        StringBuilder b = new StringBuilder();
        for (Double v : kept.subList(Math.max(0, kept.size() - 50), kept.size())) {
            b.append(b.length() == 0 ? "" : ",").append(String.format(Locale.US, "%.2f", v));
        }
        Store.prefs(this).edit().putString("blow_sessions_" + Build.MODEL, b.toString()).apply();
    }

    private void save(BlowDetector.Summary s, double base, boolean counts) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", startMs);
            o.put("type", "breath");
            o.put("method", "baro.blow.v1");
            o.put("quality", s.quality.name());
            if (!Double.isNaN(s.strengthPa)) o.put("strength_pa", Math.round(s.strengthPa * 10) / 10.0);
            if (!Double.isNaN(s.spreadPct)) o.put("spread_pct", Math.round(s.spreadPct * 10) / 10.0);
            if (!Double.isNaN(base)) o.put("baseline_pa", Math.round(base * 10) / 10.0);
            if (!Double.isNaN(base) && !Double.isNaN(s.strengthPa)) o.put("percent_of_baseline", Math.round(100 * s.strengthPa / base));
            JSONArray peaks = new JSONArray();
            for (BlowDetector.Event e : s.blows()) peaks.put(Math.round(e.peakPa * 10) / 10.0);
            o.put("peaks_pa", peaks);
            o.put("touches", s.touches());
            o.put("moved", moved);
            o.put("added_to_baseline", counts);
            o.put("device", Build.MODEL);
            o.put("samples", t.size());
            if (rawFile != null) o.put("raw_file", rawFile.getName());
            Store.save(this, o);
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
