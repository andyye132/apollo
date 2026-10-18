package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Home: the two tests, today's steps and climb, the paper experiments, the watch, and recent results. */
public class MainActivity extends Activity implements SensorEventListener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ThermalMonitor monitor;
    private SensorManager sensors;
    private TextView phoneStatus, heartLast, tempLast, tempReady, stepsText, baroText, watchText;
    private TextView phoneChip, watchChip, stepsCaption, baroCaption, calibLast;
    private LinearLayout history;
    private double basePressure = Double.NaN, pressure = Double.NaN;
    private long stepsToday = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        monitor = ThermalMonitor.get(this);
        sensors = getSystemService(SensorManager.class);
        build();
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitor.acquire();
        Sensor steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Sensor baro = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (steps != null) sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
        if (baro != null) sensors.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL);
        refreshResults();
        ui.post(tick);
        // The watch is the reference: reconnect to it automatically unless the user chose Disconnect.
        WatchLink w = WatchLink.get(this);
        boolean btOk = android.os.Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (btOk && w.state() == WatchLink.State.IDLE && Store.prefs(this).getBoolean("watch_auto", false)) w.connectLast();
    }

    @Override
    protected void onPause() {
        super.onPause();
        monitor.release();
        sensors.unregisterListener(this);
        ui.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            Sensor steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (steps != null) sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void build() {
        LinearLayout col = Ui.column(this);
        LinearLayout head = Ui.add(col, Ui.row(this), 0);
        LinearLayout names = Ui.column(this);
        names.addView(Ui.title(this, "Apollo"));
        names.addView(Ui.text(this, new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date())
                + " · data stays on this phone", 13, Ui.MUTED, false));
        head.addView(names, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Ui.iconDisc(this, "☀", Ui.TEMP, 44));

        LinearLayout chips = Ui.add(col, Ui.row(this), 14);
        phoneChip = Ui.chip(this, "Phone: checking", Ui.MUTED);
        chips.addView(phoneChip);
        watchChip = Ui.chip(this, "Watch off", Ui.MUTED);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Ui.dp(this, 8);
        chips.addView(watchChip, gap);
        watchChip.setOnClickListener(v -> startActivity(new Intent(this, WatchActivity.class)));
        phoneStatus = Ui.add(col, Ui.text(this, "", 12, Ui.MUTED, false), 6);

        Ui.add(col, Ui.section(this, "Measure · temperature first"), 22);
        LinearLayout temp = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, FeverActivity.class))), 8);
        LinearLayout tempText = tileBody(temp, "🌡️", Ui.TEMP, "1 · Forehead temperature");
        tempLast = Ui.add(tempText, Ui.text(this, "", 17, Ui.TEXT, true), 3);
        tempReady = Ui.add(tempText, Ui.text(this, "", 12.5f, Ui.MUTED, false), 3);
        temp.addView(Ui.chevron(this));

        LinearLayout heart = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, HeartRateActivity.class))), 10);
        LinearLayout heartText = tileBody(heart, "♥", Ui.HEART, "2 · Heart rate");
        heartLast = Ui.add(heartText, Ui.text(this, "", 17, Ui.TEXT, true), 3);
        Ui.add(heartText, Ui.text(this, "Fingertip on the camera, about 30 s", 12.5f, Ui.MUTED, false), 3);
        heart.addView(Ui.chevron(this));

        LinearLayout calib = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, CalibrateActivity.class))), 10);
        LinearLayout calibText = tileBody(calib, "⊕", Ui.TEMP, "Setup · once");
        calibLast = Ui.add(calibText, Ui.text(this, "Calibrate temperature", 17, Ui.TEXT, true), 3);
        Ui.add(calibText, Ui.text(this, "Press the phone on skin you measured with a thermometer", 12.5f, Ui.MUTED, false), 3);
        calib.addView(Ui.chevron(this));

        Ui.add(col, Ui.section(this, "Today"), 22);
        LinearLayout stats = Ui.add(col, Ui.row(this), 8);
        stepsText = stat(stats, "steps", Ui.STEPS, false);
        baroText = stat(stats, "height change", Ui.ACCENT, true);
        stepsText.setText("–");
        baroText.setText("–");

        Ui.add(col, Ui.section(this, "Experiments · from the lab's papers"), 22);
        LinearLayout calm = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, CalmSenseActivity.class))), 8);
        LinearLayout calmText = tileBody(calm, "≈", Ui.ACCENT, "RageSense · CHI 2026");
        Ui.add(calmText, Ui.text(this, "Calm sensing", 17, Ui.TEXT, true), 3);
        Ui.add(calmText, Ui.text(this, "Inaudible sonar notices agitation and nudges you to breathe", 12.5f, Ui.MUTED, false), 3);
        calm.addView(Ui.chevron(this));
        LinearLayout touch = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, TouchLabActivity.class))), 10);
        LinearLayout touchText = tileBody(touch, "⚽", Ui.STEPS, "Magical Touch · CHI 2026");
        Ui.add(touchText, Ui.text(this, "Ball rescue", 17, Ui.TEXT, true), 3);
        Ui.add(touchText, Ui.text(this, "Your hands on the screen become sand that balls bounce off", 12.5f, Ui.MUTED, false), 3);
        touch.addView(Ui.chevron(this));
        LinearLayout watch = Ui.add(col, Ui.tile(this, v -> startActivity(new Intent(this, WatchActivity.class))), 10);
        LinearLayout watchBody = tileBody(watch, "◎", Ui.MUTED, "Reference watch (WILD)");
        watchText = Ui.add(watchBody, Ui.text(this, "", 17, Ui.TEXT, true), 3);
        Ui.add(watchBody, Ui.text(this, "Optional: connect before a heart-rate test to compare", 12.5f, Ui.MUTED, false), 3);
        watch.addView(Ui.chevron(this));

        Ui.add(col, Ui.section(this, "Recent results"), 22);
        LinearLayout hist = Ui.add(col, Ui.card(this), 8);
        history = Ui.add(hist, Ui.column(this), 0);

        Ui.add(col, Ui.text(this, "Research prototype, not a medical device. Raw data: /sdcard/Android/data/"
                + getPackageName() + "/files", 11.5f, Ui.MUTED, false), 16);
        setContentView(Ui.page(this, col));
    }

    /** Icon disc + text column inside a tile; returns the text column (its first line is a small label). */
    private LinearLayout tileBody(LinearLayout tile, String glyph, int color, String label) {
        tile.addView(Ui.iconDisc(this, glyph, color, 46));
        LinearLayout text = Ui.column(this);
        text.addView(Ui.label(this, label));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(this, 14);
        tile.addView(text, lp);
        return text;
    }

    /** Half-width stat card: big number over a caption. Returns the number view. */
    private TextView stat(LinearLayout row, String caption, int color, boolean second) {
        LinearLayout card = Ui.card(this);
        TextView big = Ui.text(this, "", 26, color, true);
        card.addView(big);
        TextView cap = Ui.text(this, caption, 12.5f, Ui.MUTED, false);
        card.addView(cap);
        if (second) baroCaption = cap;
        else stepsCaption = cap;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (second) lp.leftMargin = Ui.dp(this, 10);
        row.addView(card, lp);
        return big;
    }

    private void updateStatus() {
        WatchLink w = WatchLink.get(this);
        watchText.setText(w.streaming() ? "Connected: " + w.deviceName() : "Not connected");
        watchText.setTextColor(w.streaming() ? Ui.GOOD : Ui.TEXT);
        Ui.setChip(watchChip, w.streaming() ? "Watch on" : "Watch off", w.streaming() ? Ui.GOOD : Ui.MUTED);
        ThermalMonitor.State s = monitor.state();
        if (!Double.isNaN(s.phoneC)) {
            phoneStatus.setText(String.format(Locale.US, "Phone %.1f °C · %s · %s", s.phoneC,
                    Double.isNaN(s.driftCPerMin) ? "measuring drift" : String.format(Locale.US, "%+.2f °C/min", s.driftCPerMin),
                    s.powerMode.toLowerCase(Locale.US)));
        }
        String v = s.verdict == null ? "" : s.verdict;
        Ui.setChip(phoneChip, "READY".equals(v) ? "Phone ready" : "SETTLING".equals(v) ? "Phone settling"
                : "UNSUPPORTED".equals(v) ? "No thermistors" : "Phone not ready", Ui.verdictColor(s.verdict));
        tempReady.setText(s.message);
        tempReady.setTextColor(Ui.verdictColor(s.verdict));
    }

    private void refreshResults() {
        List<JSONObject> hr = Store.history(this, "heart_rate"), fh = Store.history(this, "forehead");
        heartLast.setText(hr.isEmpty() ? "No readings yet" : heartLine(hr.get(0)));
        tempLast.setText(fh.isEmpty() ? "No readings yet" : tempLine(fh.get(0)));
        int calibRuns = Store.history(this, "calibration").size();
        calibLast.setText(calibRuns == 0 ? "Calibrate temperature" : "Calibrate temperature · " + calibRuns + " run" + (calibRuns == 1 ? "" : "s"));
        history.removeAllViews();
        List<JSONObject> all = Store.history(this, null);
        if (all.isEmpty()) history.addView(Ui.text(this, "Nothing yet. Results appear here.", 14, Ui.MUTED, false));
        for (int i = 0; i < Math.min(8, all.size()); i++) {
            JSONObject o = all.get(i);
            String type = o.optString("type");
            boolean isHeart = "heart_rate".equals(type);
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            r.addView(Ui.text(this, "●", 10, isHeart ? Ui.HEART : Ui.TEMP, false));
            LinearLayout t = Ui.column(this);
            t.addView(Ui.text(this, isHeart ? heartLine(o) : "calibration".equals(type) ? calibLine(o) : tempLine(o), 14, Ui.TEXT, false));
            t.addView(Ui.text(this, time(o.optLong("id")), 12, Ui.MUTED, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = Ui.dp(this, 12);
            r.addView(t, lp);
            history.addView(r);
        }
    }

    private static String heartLine(JSONObject o) {
        String s = "POOR".equals(o.optString("verdict")) ? "No reliable reading"
                : String.format(Locale.US, "%.0f bpm · %s", o.optDouble("bpm"), o.optString("verdict").toLowerCase(Locale.US));
        if (o.has("ref_bpm")) s += String.format(Locale.US, " · Fitbit %.0f", o.optDouble("ref_bpm"));
        return s;
    }

    private static String calibLine(JSONObject o) {
        String s = String.format(Locale.US, "Calibration on %s · skin %.1f °C", "arm".equals(o.optString("site")) ? "arm" : "forehead",
                o.optDouble("ref_c"));
        if (o.has("check_error_c")) s += String.format(Locale.US, " · predicted %+.2f off", o.optDouble("check_error_c"));
        return s;
    }

    private static String tempLine(JSONObject o) {
        String s = o.has("core_c_est") && o.optDouble("core_c_est") > 0
                ? String.format(Locale.US, "≈ %.1f °C%s · skin %.1f", o.optDouble("core_c_est"),
                        o.optBoolean("possible_fever") ? " possible fever" : "", o.optDouble("skin_c_est"))
                : o.has("skin_c_est") && o.optDouble("skin_c_est") > 0
                ? String.format(Locale.US, "≈ %.1f °C skin · warming %+.2f °C/min", o.optDouble("skin_c_est"), o.optDouble("rate_c_per_min"))
                : String.format(Locale.US, "Warming %+.2f °C/min", o.optDouble("rate_c_per_min"));
        if (o.optBoolean("unreliable")) s += " · unreliable";
        if (o.has("ref_c")) s += String.format(Locale.US, " · thermometer %.1f °C", o.optDouble("ref_c"));
        return s;
    }

    private static String time(long ms) {
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(ms));
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            // The counter runs since boot; keep the first value seen each day as that day's baseline.
            long total = (long) e.values[0];
            SharedPreferences p = Store.prefs(this);
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            long base = p.getLong("steps_base", -1);
            if (!today.equals(p.getString("steps_day", "")) || base < 0 || total < base) {
                base = total;
                p.edit().putString("steps_day", today).putLong("steps_base", base).apply();
            }
            stepsToday = total - base;
            stepsText.setText(String.format(Locale.US, "%,d", stepsToday));
            stepsCaption.setText("steps since the app first opened today");
        } else if (e.sensor.getType() == Sensor.TYPE_PRESSURE) {
            pressure = e.values[0];
            if (Double.isNaN(basePressure)) basePressure = pressure;
            double dh = SensorManager.getAltitude((float) basePressure, (float) pressure);
            if (Math.abs(dh) < 0.05) dh = 0;   // no "-0.0 m"
            baroText.setText(String.format(Locale.US, "%+.1f m", dh));
            baroCaption.setText(String.format(Locale.US, "about %.1f floors since opening · %.1f hPa", dh / 3.0, pressure));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
