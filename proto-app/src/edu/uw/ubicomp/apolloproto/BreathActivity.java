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
import android.os.SystemClock;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Breath strength: blow hard at the screen of a phone lying flat, and the barometer sees the glass flex
 * (BlowDetector). Compared with your own earlier results on this phone model, never shown as peak flow: across
 * four people it didn't track a peak-flow meter, because mouth shape leaks in (tools/barotest/README.md).
 * Cues are visual only, since sound and vibration move the air or the glass and the barometer would see them.
 */
public class BreathActivity extends Activity implements SensorEventListener {
    static final String TYPE = "breath_strength";
    private static final double DURATION_S = 36;
    private static final double[] CUES_S = {3, 12, 21, 30};   // four blows, 9 s apart
    private static final double CUE_LENGTH_S = 2.5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private Sensor barometer, accelerometer;
    private StepList steps;
    private TextView flatChip, cue, live, detail, startButton, resultBig, resultSub, resultBlows, resultChip;
    private LinearLayout resultCard;
    private ChartView chart;

    private boolean running, flat, moved, touchSeen;
    private long startedAtMs, startElapsedMs, t0Ns = -1;
    private final List<Double> t = new ArrayList<>(), p = new ArrayList<>();   // seconds, Pa
    private PrintWriter raw;
    private File rawFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sensors = getSystemService(SensorManager.class);
        barometer = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.label(this, "Test · barometer"), 4);
        Ui.add(col, Ui.title(this, "Breath strength"), 4);
        Ui.add(col, Ui.text(this, "How hard can you blow, compared with your own earlier results? A hard blow at the "
                + "screen pushes the glass in a little, and the phone's barometer feels the air inside squeeze.", 15, Ui.MUTED, false), 8);
        steps = Ui.add(col, new StepList(this, Ui.ACCENT,
                "Lay the phone flat on a table, screen up, and keep your hands off it",
                "Make a fist on the table next to the phone and bring your mouth to the height of your thumb",
                "Make a round \"O\" with your lips, about a finger wide, the same every time",
                "On each \"Blow now\", take a full breath and blow as hard and fast as you can (4 blows)"), 16);

        LinearLayout card = Ui.add(col, Ui.card(this), 14);
        flatChip = Ui.wrap(card, Ui.chip(this, "Checking the phone is flat", Ui.FAINT), 0);
        cue = Ui.add(card, Ui.styled(this, "", 26, Ui.TEXT, 700), 12);
        live = Ui.add(card, Ui.text(this, "", 15, Ui.TEXT, false), 6);
        detail = Ui.add(card, Ui.text(this, "", 13, Ui.MUTED, false), 6);
        startButton = Ui.add(card, Ui.button(this, "Start (about 40 s)", Ui.ACCENT, true, v -> toggle()), 14);
        chart = new ChartView(this, 140).title("Pressure change", "Pa");
        Ui.add(col, chart, 14);

        resultCard = Ui.add(col, Ui.card(this), 14);
        Ui.add(resultCard, Ui.label(this, "Result"), 0);
        resultBig = Ui.add(resultCard, Ui.styled(this, "", 30, Ui.TEXT, 700), 6);
        resultSub = Ui.add(resultCard, Ui.text(this, "", 13, Ui.MUTED, false), 4);
        resultChip = Ui.wrap(resultCard, Ui.chip(this, "", Ui.GOOD), 10);
        resultBlows = Ui.add(resultCard, Ui.text(this, "", 13, Ui.MUTED, false), 8);
        resultCard.setVisibility(View.GONE);

        Ui.add(col, Ui.text(this, "Not a lung function test: it tracks how hard you blow compared with yourself on this "
                + "phone, and can't diagnose anything. Pascals aren't comparable between phones or people. If breathing "
                + "is hard, contact a clinician.", 12, Ui.MUTED, false), 14);
        setContentView(Ui.page(this, col));

        if (barometer == null) {
            Ui.setChip(flatChip, "This phone has no barometer", Ui.BAD);
            startButton.setEnabled(false);
            startButton.setAlpha(0.4f);
        }
        showIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (running) abort("Stopped: the test needs the screen on and this app open.");
        sensors.unregisterListener(this);
    }

    private void toggle() {
        if (running) abort("Stopped.");
        else start();
    }

    private void start() {
        if (barometer == null) return;
        if (!flat) {
            detail.setText("Lay the phone flat on the table first.");
            return;
        }
        t.clear();
        p.clear();
        t0Ns = -1;
        moved = touchSeen = false;
        startedAtMs = System.currentTimeMillis();
        startElapsedMs = SystemClock.elapsedRealtime();
        rawFile = new File(Store.dir(this), "breath_" + startedAtMs + ".csv");
        try {
            raw = new PrintWriter(new FileWriter(rawFile));
            raw.println("t_ns,hpa");
        } catch (IOException e) {
            raw = null;
        }
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensors.registerListener(this, barometer, SensorManager.SENSOR_DELAY_FASTEST);
        resultCard.setVisibility(View.GONE);
        startButton.setText("Stop");
        steps.setCurrent(3);
        Store.event(this, startedAtMs, startedAtMs, "breath_started", Build.MODEL);
        ui.post(tick);
    }

    private void abort(String why) {
        running = false;
        sensors.unregisterListener(this, barometer);
        ui.removeCallbacks(tick);
        if (raw != null) raw.close();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setText("Start (about 40 s)");
        cue.setText("");
        live.setText("");
        detail.setText(why);
    }

    /** Cue text from wall time, so it keeps moving even if barometer events stall. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            double s = (SystemClock.elapsedRealtime() - startElapsedMs) / 1000.0;
            int blow = -1;
            for (int i = 0; i < CUES_S.length; i++) if (s >= CUES_S[i] && s < CUES_S[i] + CUE_LENGTH_S) blow = i;
            if (blow >= 0) {
                cue.setText("Blow now! (" + (blow + 1) + " of " + CUES_S.length + ")");
                cue.setTextColor(Ui.ACCENT);
            } else {
                cue.setText(s < CUES_S[0] ? "Get ready…" : "Breathe in, get ready…");
                cue.setTextColor(Ui.TEXT);
            }
            detail.setText(String.format(Locale.US, "%.0f s left · hands off the phone", Math.max(0, DURATION_S - s))
                    + (touchSeen ? " · that looked like a touch" : "") + (moved ? " · the phone moved" : ""));
            if (t.size() > 2) {
                int n = t.size();
                int from = Math.max(0, n - 250);   // last ~10 s at 25 Hz
                double[] xs = new double[n - from], ys = new double[n - from];
                for (int i = from; i < n; i++) {
                    xs[i - from] = t.get(i);
                    ys[i - from] = t.get(i) < BlowDetector.SETTLE_S ? 0 : liveDeviation(i);
                }
                live.setText(String.format(Locale.US, "Now %+.0f Pa", ys[ys.length - 1]));
                chart.clear();
                chart.add(new ChartView.Series(xs, ys, Ui.ACCENT, false, "pressure"));
                chart.invalidate();
            }
            ui.postDelayed(this, 250);
        }
    };

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            boolean nowFlat = z > 9.3f && Math.hypot(x, y) < 1.5;   // screen up: gravity almost all on +z
            if (running && !nowFlat) moved = true;
            if (nowFlat != flat || flatChip.getText().length() == 0 || flatChip.getText().toString().startsWith("Checking")) {
                flat = nowFlat;
                if (barometer != null) Ui.setChip(flatChip, flat ? "Phone is flat" : "Lay the phone flat to start", flat ? Ui.GOOD : Ui.WARN);
                if (!running) steps.setCurrent(flat ? 1 : 0);
            }
            return;
        }
        if (!running || e.sensor.getType() != Sensor.TYPE_PRESSURE) return;
        if (t0Ns < 0) t0Ns = e.timestamp;
        double s = (e.timestamp - t0Ns) / 1e9;
        t.add(s);
        p.add(e.values[0] * 100.0);
        if (raw != null) raw.println((e.timestamp - t0Ns) + "," + e.values[0]);
        if (s > BlowDetector.SETTLE_S && liveDeviation(p.size() - 1) > BlowDetector.TOUCH_PA) touchSeen = true;
        if (s >= DURATION_S) finishTest();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void finishTest() {
        running = false;
        sensors.unregisterListener(this, barometer);
        ui.removeCallbacks(tick);
        if (raw != null) raw.close();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setText("Again");
        cue.setText("Done");
        cue.setTextColor(Ui.TEXT);
        live.setText("");
        detail.setText(moved ? "The phone moved during the test, so treat this result with caution." : "");
        steps.setCurrent(4);

        double[] ta = toArray(t), hpa = new double[p.size()];
        for (int i = 0; i < hpa.length; i++) hpa[i] = p.get(i) / 100;
        BlowDetector.Summary s = BlowDetector.analyze(ta, hpa);
        double base = BlowDetector.baseline(earlierResults());
        showResult(s, base);
        if (s.usable()) save(s, base);
        Store.event(this, System.currentTimeMillis(), startedAtMs, "breath_done",
                String.format(Locale.US, "strength=%.1f quality=%s", s.strengthPa, s.quality));
    }

    private void showResult(BlowDetector.Summary s, double base) {
        resultCard.setVisibility(View.VISIBLE);
        StringBuilder blows = new StringBuilder();
        for (BlowDetector.Event e : s.blows()) blows.append(blows.length() == 0 ? "Blows: " : " · ").append(String.format(Locale.US, "%.0f Pa", e.peakPa));
        if (s.touches() > 0) blows.append(blows.length() == 0 ? "" : "\n").append(s.touches()).append(s.touches() == 1 ? " touch ignored" : " touches ignored");
        resultBlows.setText(blows.toString());

        if (s.quality == BlowDetector.Quality.TOO_FEW) {
            resultBig.setText("Retake");
            resultSub.setText("Fewer than 3 blows were detected. Keep your mouth at fist height above the middle of the screen and blow as hard as you can on each cue.");
            Ui.setChip(resultChip, "Not saved", Ui.WARN);
            return;
        }
        if (s.quality == BlowDetector.Quality.INCONSISTENT) {
            resultBig.setText(String.format(Locale.US, "%.0f Pa", s.strengthPa));
            resultSub.setText(String.format(Locale.US, "Your blows varied a lot (%.0f%%), so this one isn't saved. Keep the same mouth "
                    + "shape and distance each time.", s.spreadPct));
            Ui.setChip(resultChip, "Inconsistent · not saved", Ui.WARN);
            return;
        }
        int earlier = earlierResults().size();
        if (Double.isNaN(base)) {
            resultBig.setText(String.format(Locale.US, "%.0f Pa", s.strengthPa));
            resultSub.setText("Building your baseline (" + (earlier + 1) + " of " + BlowDetector.MIN_FOR_BASELINE + "). The number "
                    + "means little on its own; the comparison with your own results is the point.");
        } else {
            resultBig.setText(Math.round(100 * s.strengthPa / base) + "% of your baseline");
            resultSub.setText(String.format(Locale.US, "%.0f Pa today · baseline %.0f Pa, the median of your last %d results on this phone",
                    s.strengthPa, base, Math.min(earlier, BlowDetector.BASELINE_SESSIONS)));
        }
        Ui.setChip(resultChip, s.quality == BlowDetector.Quality.GOOD ? "Consistent blows" : "Fairly consistent", Ui.GOOD);
    }

    private void save(BlowDetector.Summary s, double base) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", startedAtMs);
            o.put("type", TYPE);
            o.put("strength_pa", round(s.strengthPa));
            o.put("spread_pct", round(s.spreadPct));
            o.put("quality", s.quality.name());
            JSONArray peaks = new JSONArray();
            for (BlowDetector.Event e : s.blows()) peaks.put(round(e.peakPa));
            o.put("blows_pa", peaks);
            o.put("touches", s.touches());
            o.put("moved", moved);
            o.put("device_model", Build.MODEL);
            if (!Double.isNaN(base)) {
                o.put("baseline_pa", round(base));
                o.put("pct_of_baseline", Math.round(100 * s.strengthPa / base));
            }
            o.put("method", "baro.blow.v1");
            if (rawFile != null) o.put("raw_file", rawFile.getName());
        } catch (JSONException ignored) {
        }
        Store.save(this, o);
    }

    /** Earlier usable results on this phone model, oldest first (absolute pascals depend on the phone). */
    private List<Double> earlierResults() {
        List<Double> out = new ArrayList<>();
        for (JSONObject o : Store.history(this, TYPE)) {
            if (o.optLong("id") != startedAtMs && Build.MODEL.equals(o.optString("device_model"))) out.add(o.optDouble("strength_pa"));
        }
        Collections.reverse(out);
        return out;
    }

    /** One line for the home screen tile. */
    static String tileLine(Activity a) {
        List<JSONObject> h = Store.history(a, TYPE);
        List<Double> mine = new ArrayList<>();
        for (JSONObject o : h) if (Build.MODEL.equals(o.optString("device_model"))) mine.add(o.optDouble("strength_pa"));
        if (mine.isEmpty()) return "Blow at the screen · compared with your own baseline";
        Collections.reverse(mine);   // oldest first
        double base = BlowDetector.baseline(mine.subList(0, mine.size() - 1));
        if (Double.isNaN(base)) return "Building your baseline (" + mine.size() + " of " + BlowDetector.MIN_FOR_BASELINE + ")";
        return "Last: " + Math.round(100 * mine.get(mine.size() - 1) / base) + "% of your baseline";
    }

    /** Pressure at sample i minus the median of the 4 s before it: the live view can't use future samples. */
    private double liveDeviation(int i) {
        int lo = i;
        while (lo > 0 && t.get(i) - t.get(lo - 1) <= 2 * BlowDetector.BASELINE_HALF_WINDOW_S) lo--;
        double[] w = new double[i - lo + 1];
        for (int k = 0; k < w.length; k++) w[k] = p.get(lo + k);
        return p.get(i) - BlowDetector.median(w);
    }

    private void showIdle() {
        cue.setText("");
        live.setText("");
        detail.setText("");
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
