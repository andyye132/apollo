package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Thumb/fingertip camera heart rate: instructions -> guided 20 s capture -> report, all on the phone. */
public class HeartRateActivity extends Activity implements PpgCamera.Listener, android.hardware.SensorEventListener {
    /** Coverage needed: recording starts at 90% of the lens covered, and a slip is under 70% for 1 s. */
    private static final double COVER_OK = 0.9, COVER_SLIP = 0.7;
    /** A jolt restarts the recording at most this many times per test; after that it records through. */
    private static final int MAX_JOLT_RESTARTS = 2;
    private static final double RECORD_S = 20;
    private static final int LIVE = 180;   // 6 s of live trace at 30 fps

    private enum Phase { INTRO, WAIT_SKIN, SETTLING, RECORDING, ANALYZING, REPORT }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<PpgCamera.Frame> recording = new ArrayList<>();
    private final double[] liveT = new double[LIVE], liveR = new double[LIVE];
    private int liveHead, liveCount;
    private Phase phase = Phase.INTRO;
    private Speaker speaker;
    private PpgCamera camera;
    private PrintWriter raw;
    private File rawFile;
    private long recordStartNs = -1, lostSinceNs = -1, startedAtMs;
    private boolean saidFitbit;
    private TextView status, detail, coverageHint, watchLine;
    private volatile boolean watchBusy;
    private int framesSinceWatch;
    private ProgressBar progress;
    private ChartView live;
    private CoverageView coverage;
    private StepList steps;
    private int framesSinceHint;
    private double minCoverage = 1;
    private final JoltDetector jolts = new JoltDetector();
    private int joltRestarts;
    private long restartAfterNs = -1;
    private android.hardware.SensorManager sensors;
    private volatile float maxGyro, maxAccel;   // largest since the last camera frame
    private long fullCoverageMs = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        speaker = new Speaker(this);
        showIntro(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phase == Phase.INTRO) showIntro(null);   // refresh the watch status after visiting the Watch screen
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (phase == Phase.WAIT_SKIN || phase == Phase.SETTLING || phase == Phase.RECORDING) {
            event(System.currentTimeMillis(), "stopped_background", phase.name());
            stopCamera();
            showIntro("Stopped: the app went to the background.");
        }
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        speaker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (phase == Phase.WAIT_SKIN || phase == Phase.SETTLING || phase == Phase.RECORDING) {
            event(System.currentTimeMillis(), "cancelled", phase.name());
            stopCamera();
            speaker.say("Cancelled.");
            showIntro("Cancelled.");
        } else {
            super.onBackPressed();
        }
    }

    // ---- Intro ----------------------------------------------------------------------------------

    private static final String HOW = "Your fingertip is full of tiny blood vessels. Each heartbeat pushes a little more blood "
            + "into them, so a little less of the flash's light makes it back to the camera. The camera watches that faint "
            + "pulsing about 30 times a second, and Apollo counts the beats.\n\nIt counts them two ways, from the rhythm of the "
            + "brightness and from the gaps between beats, and only shows a number when the two agree. The method was "
            + "checked against a reference monitor at rest and after exercise in the study below.";
    private static final String TIPS = "•  Sit and rest your hand on a table.\n"
            + "•  Press lightly. Pressing hard squeezes the blood out and the signal fades.\n"
            + "•  Slide your finger until the screen shows no red squares. Recording starts by itself.\n"
            + "•  The phone speaks each step and tells you when it's done.\n"
            + "•  Comparing with a watch? Wear it on the other arm, rest that arm flat and keep it still.";
    private static final String[] STEPS = {"Cover the camera and flash with your fingertip", "Hold still while the camera tunes",
            "Keep still for 20 seconds", "Read your results"};

    private void showIntro(String note) {
        phase = Phase.INTRO;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        TextView title = Ui.add(col, Ui.title(this, "Find your rhythm."), 6);
        Ui.fitOneLine(title, 32, 24);
        Ui.add(col, Ui.text(this, "A quick pulse check with your camera.", 16, Ui.MUTED, false), 6);
        LinearLayout hero = Ui.add(col, Ui.card(this), 18);   // white, like the mascot's own background
        LinearLayout heroRow = Ui.add(hero, Ui.row(this), 0);
        heroRow.setGravity(Gravity.CENTER);
        heroRow.addView(Ui.mascot(this, "hello", 112));
        View phone = Ui.icon(this, "phone", Ui.LEAF, 84);
        ((LinearLayout.LayoutParams) phone.getLayoutParams()).leftMargin = Ui.dp(this, 18);
        heroRow.addView(phone);
        Info.centred(hero, Info.howItWorks(this, HOW, "Yan et al., JMIR mHealth 2017", "https://doi.org/10.2196/mhealth.7275"), 8);
        if (note != null) Ui.wrap(col, Ui.badge(this, note, Ui.WARN), 12);

        LinearLayout head = Ui.add(col, Ui.row(this), 22);
        head.addView(Ui.section(this, "Four steps"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Info.button(this, "Tips", TIPS));
        Ui.add(col, new StepList(this, Ui.HEART, STEPS), 8);
        watchRow(col);
        Ui.add(col, Ui.button(this, "Start", Ui.HEART, true, v -> maybeTemperatureFirst()), 20);
        setContentView(Ui.page(this, col));
    }

    /** The reference watch, in one line: connected, or a way to connect it. */
    private void watchRow(LinearLayout col) {
        WatchLink watch = WatchLink.get(this);
        LinearLayout r = Ui.add(col, Ui.row(this), 16);
        r.addView(Ui.icon(this, "watch", watch.streaming() ? Ui.GOOD : Ui.FAINT, 18));
        TextView t = Ui.styled(this, watch.streaming() ? "Reference watch connected" : "Reference watch not connected", 14,
                watch.streaming() ? Ui.GOOD : Ui.MUTED, 500);
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!watch.streaming()) {
            TextView c = Ui.styled(this, "Connect", 14, Ui.CORAL_DARK, 600);
            c.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), 0, Ui.dp(this, 6));
            c.setOnClickListener(v -> startActivity(new Intent(this, WatchActivity.class)));
            r.addView(c);
        }
    }

    /** Design doc rule: the torch warms the phone, so offer the temperature test first when it hasn't run. */
    private void maybeTemperatureFirst() {
        boolean thermal = !Boolean.FALSE.equals(ThermalMonitor.get(this).access());
        long lastFever = 0;
        List<JSONObject> fevers = Store.history(this, "forehead");
        if (!fevers.isEmpty()) lastFever = fevers.get(0).optLong("id");
        if (thermal && System.currentTimeMillis() - lastFever > 30 * 60_000) {
            Info.Sheet sheet = new Info.Sheet(this);   // Apollo, one brow up, asking
            Info.centred(sheet.col, Ui.mascot(this, "ask", 120), 8);
            Ui.add(sheet.col, Ui.styled(this, "Taking your temperature too?", 22, Ui.TEXT, 700), 14).setGravity(Gravity.CENTER);
            Ui.add(sheet.col, Ui.text(this, "Do it first. The camera's light warms the phone, which throws the temperature "
                    + "check off for about 10 minutes.", 15, Ui.MUTED, false), 6).setGravity(Gravity.CENTER);
            Ui.add(sheet.col, Ui.button(this, "Temperature first", Ui.CORAL, true, v -> {
                sheet.dismiss();
                startActivity(new Intent(this, FeverActivity.class));
                finish();
            }), 22);
            Ui.add(sheet.col, Ui.button(this, "Just heart rate", Ui.MUTED, false, v -> {
                sheet.dismiss();
                startMeasuring();
            }), 10);
            sheet.show();
        } else {
            startMeasuring();
        }
    }

    // ---- Measuring ------------------------------------------------------------------------------

    private void startMeasuring() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        phase = Phase.WAIT_SKIN;
        recording.clear();
        liveCount = 0;
        saidFitbit = false;
        minCoverage = 1;
        joltRestarts = 0;
        restartAfterNs = -1;
        fullCoverageMs = -1;
        startedAtMs = System.currentTimeMillis();
        event(startedAtMs, "test_started", "");
        rawFile = new File(Store.dir(this), "hr_" + startedAtMs + ".csv");
        try {
            raw = new PrintWriter(new FileWriter(rawFile), true);
            StringBuilder h = new StringBuilder("sensor_ns,epoch_ms,y,u,v,exposure_ns,iso,ae_locked,coverage");
            for (int k = 0; k < PpgCamera.TILES; k++) h.append(",ty").append(k);
            for (int k = 0; k < PpgCamera.TILES; k++) h.append(",tv").append(k);
            h.append(",gyro_max,accel_max");   // phone motion since the previous frame (rad/s, m/s²)
            raw.println(h);
        } catch (IOException e) {
            raw = null;
        }

        LinearLayout col = Ui.column(this);
        steps = Ui.add(col, new StepList(this, Ui.HEART, STEPS), 4);
        status = Ui.add(col, Ui.text(this, "Cover the camera completely", 24, Ui.TEXT, true), 12);
        detail = Ui.add(col, Ui.text(this, "", 14, Ui.MUTED, false), 6);
        watchLine = Ui.add(col, Ui.text(this, "", 14, Ui.MUTED, true), 6);
        watchLine.setVisibility(WatchLink.get(this).streaming() ? View.VISIBLE : View.GONE);

        // The camera feed with uncovered areas tinted red and too-bright areas amber.
        LinearLayout cov = Ui.add(col, Ui.card(this), 16);
        Ui.add(cov, Ui.label(this, "What the camera sees (from the screen side)"), 0);
        coverage = new CoverageView(this);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        cl.topMargin = Ui.dp(this, 10);
        cov.addView(coverage, cl);
        coverageHint = Ui.add(cov, Ui.text(this, "Starting the camera…", 16, Ui.TEXT, true), 12);
        Ui.add(cov, Ui.text(this, "Red shows what isn't covered. 90% covered is enough: the frame turns green.", 13, Ui.MUTED, false), 6);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.HEART));
        Ui.add(col, progress, 18);
        live = new ChartView(this, 200).title("Live pulse", "").hideYAxis();
        Ui.add(col, live, 18);
        Ui.add(col, Ui.button(this, "Cancel", Ui.MUTED, false, v -> onBackPressed()), 24);
        setContentView(Ui.page(this, col));

        speaker.say("Cover the camera and the flash with your fingertip. Press lightly.");
        camera = new PpgCamera(this, this);
        camera.start();
        sensors = getSystemService(android.hardware.SensorManager.class);
        for (int type : new int[]{android.hardware.Sensor.TYPE_GYROSCOPE, android.hardware.Sensor.TYPE_LINEAR_ACCELERATION}) {
            android.hardware.Sensor s = sensors.getDefaultSensor(type);
            if (s != null) sensors.registerListener(this, s, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    public void onSensorChanged(android.hardware.SensorEvent e) {
        float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        if (e.sensor.getType() == android.hardware.Sensor.TYPE_GYROSCOPE) maxGyro = Math.max(maxGyro, m);
        else maxAccel = Math.max(maxAccel, m);
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startMeasuring();
        else showIntro("The camera permission is needed for this test.");
    }

    @Override
    public void onFrame(PpgCamera.Frame f) {
        f.gyroMax = maxGyro;
        f.accelMax = maxAccel;
        maxGyro = 0;
        maxAccel = 0;
        if (raw != null) {
            StringBuilder line = new StringBuilder(String.format(Locale.US, "%d,%d,%.3f,%.3f,%.3f,%d,%d,%d,%.3f",
                    f.sensorNs, f.epochMs, f.y, f.u, f.v, f.exposureNs, f.iso, f.fixed ? 1 : 0, f.coverage));
            for (double t : f.ty) line.append(String.format(Locale.US, ",%.2f", t));
            for (double t : f.tv) line.append(String.format(Locale.US, ",%.2f", t));
            line.append(String.format(Locale.US, ",%.3f,%.3f", f.gyroMax, f.accelMax));
            raw.println(line);
        }
        ui.post(() -> handleFrame(f));
    }

    @Override
    public void onCameraError(String message) {
        ui.post(() -> {
            event(System.currentTimeMillis(), "camera_error", message);
            stopCamera();
            showIntro(message);
        });
    }

    private void handleFrame(PpgCamera.Frame f) {
        if (phase != Phase.WAIT_SKIN && phase != Phase.SETTLING && phase != Phase.RECORDING) return;
        liveT[liveHead] = f.sensorNs / 1e9;
        liveR[liveHead] = f.liveRed;
        liveHead = (liveHead + 1) % LIVE;
        liveCount = Math.min(liveCount + 1, LIVE);
        boolean full = f.coverage >= COVER_OK;
        detail.setText(String.format(Locale.US, "Covered: %.0f%% · exposure %.2f ms", Math.floor(100 * f.coverage), f.exposureNs / 1e6));
        coverage.update(f);
        if (++framesSinceWatch >= 60 && !watchBusy && WatchLink.get(this).streaming()) {   // watch check every ~2 s
            framesSinceWatch = 0;
            watchBusy = true;
            new Thread(() -> {
                WatchAnalysis.Assessment a = WatchAnalysis.assess(WatchLink.get(this).last(8));
                ui.post(() -> {
                    watchBusy = false;
                    if (watchLine == null) return;
                    watchLine.setText(a.headline + (a.advice.isEmpty() ? "" : ". " + a.advice));
                    watchLine.setTextColor(a.r != null && "GOOD".equals(a.r.verdict) ? Ui.GOOD
                            : a.r != null && "WEAK".equals(a.r.verdict) ? Ui.WARN : Ui.BAD);
                });
            }).start();
        }
        if (++framesSinceHint >= 6) {   // ~5 times a second is plenty for text
            framesSinceHint = 0;
            coverageHint.setText(coverage.hint());
        }

        steps.setCurrent(phase == Phase.WAIT_SKIN ? 0 : phase == Phase.SETTLING ? 1 : 2);
        switch (phase) {
            case WAIT_SKIN:
                status.setText("Cover the camera with your fingertip");
                if (full) {
                    phase = Phase.SETTLING;
                    if (fullCoverageMs < 0) fullCoverageMs = f.epochMs;
                    event(f.epochMs, "full_coverage", "");
                }
                break;
            case SETTLING:
                status.setText(f.sensorNs < restartAfterNs ? "Starting over in a moment. Hold still…" : "Covered. Hold still, tuning the camera…");
                if (f.coverage < COVER_OK - 0.1) {
                    phase = Phase.WAIT_SKIN;
                    event(f.epochMs, "coverage_lost", String.format(Locale.US, "%.2f", f.coverage));
                } else if (f.fixed && full && f.sensorNs >= restartAfterNs) {
                    phase = Phase.RECORDING;
                    recordStartNs = f.sensorNs;
                    lostSinceNs = -1;
                    recording.clear();
                    jolts.reset();
                    minCoverage = 1;
                    event(f.epochMs, "recording_start", String.format(Locale.US, "exposure_ms=%.3f iso=%d", f.exposureNs / 1e6, f.iso));
                    speaker.say("Recording. Hold still.");
                }
                break;
            case RECORDING:
                recording.add(f);
                double elapsed = (f.sensorNs - recordStartNs) / 1e9;
                progress.setProgress((int) (1000 * Math.min(1, elapsed / RECORD_S)));
                status.setText(String.format(Locale.US, "Recording… %.0f s", Math.max(0, RECORD_S - elapsed)));
                minCoverage = Math.min(minCoverage, f.coverage);
                if (!f.fixed) {   // the finger settled brighter and the camera is retuning: start over once it locks
                    phase = Phase.SETTLING;
                    event(f.epochMs, "exposure_retune", String.format(Locale.US, "exposure_ms=%.3f", f.exposureNs / 1e6));
                    progress.setProgress(0);
                    speaker.say("Retuning the camera. Keep holding still.");
                    break;
                }
                if (jolts.frame(f.sensorNs / 1e9, f.liveRed, f.gyroMax, f.accelMax)) {
                    if (joltRestarts < MAX_JOLT_RESTARTS) {
                        joltRestarts++;
                        phase = Phase.SETTLING;
                        restartAfterNs = f.sensorNs + 1_500_000_000L;   // let the movement die down first
                        event(f.epochMs, "jolt_restart", jolts.reason);
                        progress.setProgress(0);
                        speaker.say("You moved. Starting the recording over. Hold still.");
                        break;
                    }
                    event(f.epochMs, "jolt_ignored", jolts.reason);   // restarted enough: record through it
                    jolts.reset();
                }
                if (f.coverage < COVER_SLIP) {
                    if (lostSinceNs < 0) lostSinceNs = f.sensorNs;
                    else if (f.sensorNs - lostSinceNs > 1_000_000_000L) {
                        phase = Phase.WAIT_SKIN;
                        event(f.epochMs, "finger_slipped", String.format(Locale.US, "%.2f", f.coverage));
                        progress.setProgress(0);
                        speaker.say("Your finger slipped. Cover the camera again.");
                    }
                } else {
                    lostSinceNs = -1;
                }
                if (elapsed >= RECORD_S / 2 && !saidFitbit) {
                    saidFitbit = true;
                    speaker.say("If you're wearing a Fitbit, check its heart rate now.");
                }
                if (elapsed >= RECORD_S) finishRecording();
                break;
            default:
                break;
        }
        drawLive();
    }

    private void drawLive() {
        int n = liveCount;
        if (n < 10) return;
        double[] t = new double[n], r = new double[n];
        for (int i = 0; i < n; i++) {
            int k = (liveHead - n + i + LIVE) % LIVE;
            t[i] = liveT[k];
            r[i] = liveR[k];
        }
        // Remove a 1 s moving average and flip, so each beat points up.
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - 15), hi = Math.min(n, i + 16);
            double s = 0;
            for (int j = lo; j < hi; j++) s += r[j];
            d[i] = -(r[i] - s / (hi - lo));
            t[i] -= t[n - 1];
        }
        live.clear();
        live.add(new ChartView.Series(t, d, Ui.HEART, false, null));
        live.invalidate();
    }

    private void finishRecording() {
        phase = Phase.ANALYZING;
        stopCamera();
        Store.prefs(this).edit().putLong("last_heat_ms", System.currentTimeMillis()).apply();
        speaker.buzz();
        speaker.say("Done.");
        status.setText("Analysing…");
        List<PpgCamera.Frame> frames = new ArrayList<>(recording);
        if (!frames.isEmpty()) event(frames.get(frames.size() - 1).epochMs, "recording_end", "");
        new Thread(() -> {
            int n = frames.size();
            double[] t = new double[n], y = new double[n], u = new double[n], v = new double[n];
            double[][] ty = new double[n][], tv = new double[n][];
            double exposure = 0, iso = 0;
            for (int i = 0; i < n; i++) {
                PpgCamera.Frame f = frames.get(i);
                t[i] = (f.sensorNs - frames.get(0).sensorNs) / 1e9;
                y[i] = f.y;
                u[i] = f.u;
                v[i] = f.v;
                ty[i] = f.ty;
                tv[i] = f.tv;
                exposure += f.exposureNs / 1e6 / n;
                iso += (double) f.iso / n;
            }
            PpgAnalysis.Choice choice = PpgAnalysis.chooseSignal(y, u, v, ty, tv);
            PpgAnalysis.Result r = PpgAnalysis.analyze(t, choice.signal);
            double fps = (n - 1) / t[n - 1], exposureMs = exposure;
            int isoMean = (int) iso;
            JSONObject json = toJson(r, choice, fps, exposureMs, isoMean);
            try {   // the exact analysed window, phone wall clock, for lining up with a reference device
                json.put("window_start_ms", frames.get(0).epochMs);
                json.put("window_end_ms", frames.get(n - 1).epochMs);
                json.put("full_coverage_ms", fullCoverageMs);
            } catch (JSONException ignored) {
            }
            PpgAnalysis.Result watch = analyseWatch(frames.get(0).epochMs, frames.get(n - 1).epochMs, json);
            Store.save(this, json);
            ui.post(() -> showReport(r, choice, json, fps, exposureMs, isoMean, watch));
        }).start();
    }

    /** The connected watch over the same phone-clock window (cleanest light channel); null when there's no usable data. */
    private PpgAnalysis.Result analyseWatch(long a, long b, JSONObject json) {
        WatchLink link = WatchLink.get(this);
        double[][] ws = link.between(a, b);
        int wn = ws[0].length;
        if (wn < 400 || (ws[0][wn - 1] - ws[0][0]) < 15000) return null;   // not connected, or too little data
        WatchAnalysis.Assessment wa = WatchAnalysis.assess(ws);
        PpgAnalysis.Result w = wa.r;
        try {
            json.put("watch_samples", wn);
            json.put("watch_lost_pct", round(100 * link.dropFraction(), 1));
            json.put("watch_frozen", wa.frozen);
            if (w != null) {
                json.put("watch_bpm", round(w.hr, 1));
                json.put("watch_bpm_beats", round(w.hrBeats, 1));
                json.put("watch_snr", round(w.snr, 3));
                json.put("watch_verdict", w.verdict);
                json.put("watch_channel", WatchAnalysis.CHANNELS[wa.channel]);
                json.put("watch_shift_pct", round(wa.shiftPct, 2));
                json.put("watch_moved", wa.moved);
            }
            File wf = new File(Store.dir(this), "watch_" + startedAtMs + ".csv");
            link.writeCsv(wf, startedAtMs - 5000, b + 1000);
            json.put("watch_file", wf.getName());
        } catch (JSONException | IOException ignored) {
        }
        return w;
    }

    private void event(long epochMs, String name, String detail) {
        Store.event(this, epochMs, startedAtMs, name, detail);
    }

    private void stopCamera() {
        if (camera != null) camera.stop();
        if (sensors != null) sensors.unregisterListener(this);
        camera = null;
        if (raw != null) raw.close();
        raw = null;
    }

    // ---- Report ---------------------------------------------------------------------------------

    private JSONObject toJson(PpgAnalysis.Result r, PpgAnalysis.Choice c, double fps, double exposureMs, int iso) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", startedAtMs);
            o.put("type", "heart_rate");
            o.put("bpm", round(r.hr, 1));
            o.put("bpm_beats", round(r.hrBeats, 1));
            o.put("snr", round(r.snr, 3));
            o.put("verdict", r.verdict);
            o.put("beats", r.beats);
            o.put("rmssd_ms", round(r.rmssdMs, 0));
            o.put("sdnn_ms", round(r.sdnnMs, 0));
            o.put("channel", c.channel);
            o.put("fps", round(fps, 2));
            o.put("exposure_ms", round(exposureMs, 2));
            o.put("iso", iso);
            o.put("raw_file", rawFile.getName());
            o.put("min_coverage", round(minCoverage, 3));
            o.put("method", "ppg.finger.v2");
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private void showReport(PpgAnalysis.Result r, PpgAnalysis.Choice c, JSONObject json, double fps, double exposureMs, int iso,
                            PpgAnalysis.Result watch) {
        phase = Phase.REPORT;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        boolean poor = "POOR".equals(r.verdict);   // never show an unreliable number big
        boolean good = "GOOD".equals(r.verdict);
        Ui.add(col, Ui.homeLink(this), 0);
        TextView title = Ui.add(col, Ui.title(this, poor ? "Let's try that again." : good ? "Hello, heartbeat." : "A little faint, but here."), 6);
        Ui.fitOneLine(title, 32, 22);

        LinearLayout head = Ui.add(col, Ui.row(this), 10);
        LinearLayout numCol = Ui.column(this);
        LinearLayout num = Ui.row(this);
        num.setGravity(Gravity.BOTTOM);
        num.addView(Ui.number(this, poor ? "--" : String.format(Locale.US, "%.0f", r.hr), 84));
        if (!poor) {
            TextView unit = Ui.styled(this, "bpm", 20, Ui.MUTED, 600);
            unit.setPadding(Ui.dp(this, 8), 0, 0, Ui.dp(this, 20));
            num.addView(unit);
        }
        numCol.addView(num);
        Ui.wrap(numCol, Ui.badge(this, verdictText(r.verdict), Ui.verdictColor(r.verdict)), 2);
        head.addView(numCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Ui.mascot(this, poor ? "thinking" : good ? "happy" : "curious", 104));
        if (poor) Ui.add(col, Ui.text(this, verdictWhy(r), 14, Ui.MUTED, false), 10);   // no number: say why up front

        LinearLayout pc = Ui.add(col, Ui.card(this), 18);
        LinearLayout ph = Ui.add(pc, Ui.row(this), 0);
        ph.addView(Ui.icon(this, "heart", Ui.CORAL, 18));
        TextView pt = Ui.styled(this, "Your pulse", 15, Ui.TEXT, 600);
        pt.setPadding(Ui.dp(this, 7), 0, 0, 0);
        ph.addView(pt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ph.addView(Ui.label(this, "Last 8 seconds"));
        PulseView pulse = new PulseView(this);
        Ui.add(pc, pulse, 10).getLayoutParams().height = Ui.dp(this, 130);
        pulse.set(r.tu, r.pulse, r.peaks, 8);

        // Everything else waits behind "Learn more": why the verdict, comparisons, and the numbers behind it.
        LinearLayout more = Info.learnMore(col, 10);
        if (!poor) Ui.add(more, Ui.text(this, verdictWhy(r), 14, Ui.MUTED, false), 4);
        // Reference comparison, the reason this prototype exists.
        LinearLayout ref = Ui.add(more, Ui.card(this), 12);
        Ui.add(ref, Ui.label(this, "Compare with your Fitbit"), 0);
        LinearLayout refRow = Ui.add(ref, Ui.row(this), 8);
        EditText input = Ui.numberInput(this, "Fitbit bpm");
        refRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView refOut = Ui.text(this, "", 15, Ui.TEXT, false);
        TextView save = Ui.button(this, "Save", Ui.ACCENT, false, v -> {
            try {
                double fitbit = Double.parseDouble(input.getText().toString());
                json.put("ref_bpm", fitbit);
                Store.save(this, json);
                refOut.setText(String.format(Locale.US, "Phone %.0f vs Fitbit %.0f: %+.1f bpm (%+.1f%%)", r.hr, fitbit,
                        r.hr - fitbit, 100 * (r.hr - fitbit) / fitbit));
            } catch (NumberFormatException | JSONException e) {
                refOut.setText("Type a number first");
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Ui.dp(this, 10);
        refRow.addView(save, sp);
        Ui.add(ref, refOut, 8);
        Ui.add(ref, Ui.text(this, accuracySummary(), 13, Ui.MUTED, false), 6);

        if (json.has("watch_samples")) {
            LinearLayout wc = Ui.add(more, Ui.card(this), 12);
            Ui.add(wc, Ui.label(this, "Compared with the watch"), 0);
            if (watch == null) {
                Ui.add(wc, Ui.text(this, "The watch's sensor wasn't sampling (flat signal). Reset the watch.", 14, Ui.WARN, false), 6);
            } else {
                boolean wpoor = "POOR".equals(watch.verdict);
                Ui.add(wc, Ui.text(this, String.format(Locale.US, "Watch %s (%s) · phone %s", wpoor ? "--" : String.format(Locale.US, "%.0f bpm", watch.hr),
                        verdictText(watch.verdict).toLowerCase(Locale.US), poor ? "--" : String.format(Locale.US, "%.0f bpm", r.hr)), 18, Ui.TEXT, true), 6);
                if (!poor && !wpoor) {
                    Ui.add(wc, Ui.text(this, String.format(Locale.US, "Phone − watch: %+.1f bpm (%+.1f%%)", r.hr - watch.hr,
                            100 * (r.hr - watch.hr) / watch.hr), 15, Math.abs(r.hr - watch.hr) <= 5 ? Ui.GOOD : Ui.WARN, true), 4);
                }
                Ui.add(wc, Ui.text(this, String.format(Locale.US, "Watch SNR %.2f (%s light) · %d samples · %.0f%% lost over Bluetooth",
                        watch.snr, json.optString("watch_channel", "green"), json.optInt("watch_samples"), json.optDouble("watch_lost_pct")), 13, Ui.MUTED, false), 4);
                if (wpoor || json.optBoolean("watch_moved")) {
                    Ui.add(wc, Ui.text(this, json.optBoolean("watch_moved")
                            ? "Why: the watch sensor shifted on your skin during the recording (its light level jumped " + String.format(Locale.US, "%.1f", json.optDouble("watch_shift_pct"))
                            + "% in a second). Rest the watch arm flat on the table, away from the phone's flashlight, and keep it still."
                            : "Why: the pulse at the wrist was too faint (not movement). Wear the watch on the arm that isn't holding the "
                            + "phone, rest it flat, and tighten the strap so the sensor sits flat on the skin.", 14, Ui.WARN, false), 6);
                }
                ChartView both = new ChartView(this, 160).title("Pulses on the phone's clock, last 10 s", "").hideYAxis();
                both.add(zSeries(r, Ui.HEART, "phone"));
                both.add(zSeries(watch, Ui.STEPS, "watch"));
                Ui.add(wc, both, 10);
            }
        }

        LinearLayout d = Ui.add(more, Ui.card(this), 12);
        Ui.add(d, Ui.label(this, "Details"), 0);
        d.addView(Ui.kv(this, "From the spectrum", String.format(Locale.US, "%.1f bpm", r.hrSpectrum)));
        d.addView(Ui.kv(this, "From beat intervals", Double.isNaN(r.hrBeats) ? "-" : String.format(Locale.US, "%.1f bpm", r.hrBeats)));
        d.addView(Ui.kv(this, "Signal quality (SNR)", String.format(Locale.US, "%.2f  (good ≥ 0.5)", r.snr)));
        d.addView(Ui.kv(this, "Beats found", String.format(Locale.US, "%d, %d odd intervals ignored", r.beats, r.rejected)));
        d.addView(Ui.kv(this, "Range over 10 s windows", range(r.winBpm)));
        d.addView(Ui.kv(this, "Pulse strength", String.format(Locale.US, "%.2f%% of brightness", r.acPct)));
        d.addView(Ui.kv(this, "Beat-to-beat (RMSSD)", Double.isNaN(r.rmssdMs) ? "-" : String.format(Locale.US, "%.0f ms (rough)", r.rmssdMs)));
        d.addView(Ui.kv(this, "Signal used", c.channel + (c.clippedTiles > 0 ? ", " + c.clippedTiles + " clipped tiles left out" : "")));
        d.addView(Ui.kv(this, "Lowest coverage while recording", String.format(Locale.US, "%.0f%%", Math.floor(100 * minCoverage))));
        d.addView(Ui.kv(this, "Camera", String.format(Locale.US, "%.1f fps · %.2f ms · ISO %d", fps, exposureMs, iso)));
        d.addView(Ui.kv(this, "Recording", String.format(Locale.US, "%.0f s", r.durationS)));
        d.addView(Ui.kv(this, "Raw data", rawFile.getName()));
        Info.centred(col, Info.disclaimer(this), 12);

        LinearLayout buttons = Ui.add(col, Ui.row(this), 12);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(Ui.button(this, "Measure again", Ui.HEART, false, v -> maybeTemperatureFirst()), half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = Ui.dp(this, 10);
        buttons.addView(Ui.button(this, "Done", Ui.HEART, true, v -> finish()), half2);
        setContentView(Ui.page(this, col));
    }

    /** Last 10 s of a pulse, z-scored, for overlaying two devices. */
    private static ChartView.Series zSeries(PpgAnalysis.Result r, int color, String label) {
        double end = r.tu.length > 0 ? r.tu[r.tu.length - 1] : 0;
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        double m = Dsp.mean(r.pulse), sd = Dsp.std(r.pulse);
        for (int i = 0; i < r.tu.length; i++) {
            if (r.tu[i] < end - 10) continue;
            xs.add(r.tu[i]);
            ys.add((r.pulse[i] - m) / (sd > 0 ? sd : 1));
        }
        return new ChartView.Series(arr(xs), arr(ys), color, false, label);
    }

    private static String verdictText(String v) {
        return "GOOD".equals(v) ? "Clear signal" : "WEAK".equals(v) ? "Usable, but noisy" : "Unreliable: retake";
    }

    private static String verdictWhy(PpgAnalysis.Result r) {
        if (!r.agree) {
            return String.format(Locale.US, "The two estimates disagree (%.0f vs %.0f bpm), so there's no trustworthy number. "
                    + "Usually that means movement, too much pressure, or the camera not fully on skin.", r.hr, r.hrBeats);
        }
        if ("GOOD".equals(r.verdict)) {
            return String.format(Locale.US, "Two independent estimates agree within %.1f bpm and the pulse stands out clearly.",
                    Math.abs(r.hr - r.hrBeats));
        }
        return "The estimates agree, but the pulse is faint compared with the noise. Try lighter pressure and keeping still.";
    }

    /** Running comparison against Fitbit readings saved so far. */
    private String accuracySummary() {
        List<Double> errs = new ArrayList<>();
        for (JSONObject o : Store.history(this, "heart_rate")) {
            if (o.has("ref_bpm") && !"POOR".equals(o.optString("verdict"))) errs.add(o.optDouble("bpm") - o.optDouble("ref_bpm"));
        }
        if (errs.isEmpty()) return "Saved comparisons build up an accuracy record (only GOOD and WEAK readings count).";
        double mae = 0;
        for (double e : errs) mae += Math.abs(e) / errs.size();
        return String.format(Locale.US, "So far: %d comparisons, average miss %.1f bpm (target: within 5 bpm).", errs.size(), mae);
    }

    private static double[] arr(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static String range(double[] x) {
        if (x.length == 0) return "-";
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : x) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return String.format(Locale.US, "%.0f-%.0f bpm", lo, hi);
    }
}
