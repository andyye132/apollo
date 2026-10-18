package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * RageSense (CHI '26) on a stock phone: the speaker plays an inaudible 18-22 kHz chirp, the microphone
 * listens for echoes off your face and hands (Sonar), hand taps are gated out with the IMU (the paper's
 * threshold: mean + 0.25 SD of short-time motion energy), and the paper's just-in-time trigger (EMA,
 * 60 s dynamic threshold with k 1.2 -> 0.8, CUSUM) decides when to show a short calming message.
 *
 * What's different: RageSense estimates valence and arousal with a 25M-parameter network trained on
 * 20 players; that model and its data aren't public. Here the trigger runs on a movement-intensity index
 * (log echo-change energy in the face range) instead of predicted arousal, and messages come from a
 * fixed library (the paper's own fallback) instead of a cloud LLM.
 */
public class CalmSenseActivity extends Activity implements SensorEventListener {
    private static final String[] MESSAGES = {
            "Breathe out slowly.", "Drop your shoulders.", "Unclench your jaw.", "One slow breath.",
            "Loosen your grip.", "Pause. You're fine.", "Look away for a sec.", "Easy does it.",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random rng = new Random();
    private volatile boolean running;
    private Thread worker;
    private AudioTrack track;
    private AudioRecord record;
    private SensorManager sensors;
    private PrintWriter log;
    private long sessionMs;
    private StepList steps;
    private TextView status, detail, indexText, message, startButton;
    private HeatMap heat;
    private ChartView chart;

    // IMU gating (paper Sec. 4.1.1): short-time energy over 0.1 s, threshold mean + 0.25 SD.
    private final double[] imuWin = new double[20];
    private int imuHead;
    private double imuMean = Double.NaN, imuVar = 0, imuEnergy;
    private volatile boolean handBusy;
    private long gatedFrames, totalFrames;

    // Index series for the chart.
    private final List<double[]> series = new ArrayList<>();   // {t, index, threshold}
    private int interventions;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sensors = getSystemService(SensorManager.class);
        LinearLayout col = Ui.column(this);
        Ui.add(col, Ui.homeLink(this), 0);
        Ui.add(col, Ui.title(this, "Calm sensing"), 6);
        Ui.add(col, Ui.text(this, "Notices restless movement and nudges you to take a breath.", 16, Ui.MUTED, false), 6);
        LinearLayout hero = Ui.add(col, Ui.card(this), 18);   // white, like the mascot's own background
        LinearLayout heroRow = Ui.add(hero, Ui.row(this), 0);
        heroRow.setGravity(Gravity.CENTER);
        heroRow.addView(Ui.mascot(this, "calm", 112));
        View waves = Ui.icon(this, "waves", Ui.LEAF, 84);
        ((LinearLayout.LayoutParams) waves.getLayoutParams()).leftMargin = Ui.dp(this, 18);
        heroRow.addView(waves);
        Info.centred(hero, Info.howItWorks(this, HOW, "Liu et al., RageSense, CHI 2026", "https://doi.org/10.1145/3772318.3791076"), 8);
        LinearLayout head = Ui.add(col, Ui.row(this), 22);
        head.addView(Ui.section(this, "Four steps"), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Info.button(this, "Tips", TIPS));
        steps = Ui.add(col, new StepList(this, Ui.ACCENT,
                "Turn the volume up most of the way",
                "Hold the phone like you're reading, 20-30 cm away",
                "Tap Start and use the phone as usual",
                "Apollo nudges you when your movement spikes"), 8);

        LinearLayout card = Ui.add(col, Ui.card(this), 14);
        status = Ui.add(card, Ui.text(this, "Not running", 18, Ui.TEXT, true), 0);
        detail = Ui.add(card, Ui.text(this, "", 13, Ui.MUTED, false), 6);
        indexText = Ui.add(card, Ui.text(this, "", 15, Ui.TEXT, false), 6);
        startButton = Ui.add(card, Ui.button(this, "Start", Ui.ACCENT, true, v -> toggle()), 14);

        message = Ui.add(col, Ui.text(this, "", 22, Ui.BG, true), 14);
        message.setBackground(Ui.rounded(Ui.STEPS, Ui.dp(this, 16)));
        message.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        message.setVisibility(View.GONE);

        // What the sonar sees, for the curious.
        LinearLayout more = Info.learnMore(col, 10);
        LinearLayout viz = Ui.add(more, Ui.card(this), 12);
        Ui.add(viz, Ui.label(this, "Echo changes by distance (last 3 s)"), 0);
        heat = new HeatMap(this);
        Ui.add(viz, heat, 8);
        Ui.add(viz, Ui.text(this, "Bright = something moved at that distance from the phone. Top 4 cm, bottom 60 cm.",
                12, Ui.MUTED, false), 6);
        chart = new ChartView(this, 150).title("Movement index vs your trigger threshold", "");
        Ui.add(more, chart, 12);
        Info.centred(col, Info.disclaimer(this), 12);
        setContentView(Ui.page(this, col));
    }

    private static final String HOW = "The speaker plays a tone too high for you to hear, and the microphone listens to the echoes "
            + "off your face and hands, like a bat's sonar. When you fidget or tense up, the echoes change.\n\nApollo learns your "
            + "usual movement in the first 30 seconds. When it jumps well above that, a short calming message appears.\n\n"
            + "The paper predicts emotion with a neural network trained on 20 gamers, which isn't public. Apollo keeps its "
            + "sensing and its trigger, with a movement index in place of predicted arousal and fixed calming messages "
            + "instead of AI-written ones.";
    private static final String TIPS = "•  Media volume most of the way up: you won't hear the tone, but the echoes need it.\n"
            + "•  Keep the phone 20-30 cm from your face, like when gaming or reading.\n"
            + "•  The first 30 seconds learn your usual movement, so start calm.\n"
            + "•  No audio is saved, only how much movement the echoes showed.";

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    private void toggle() {
        if (running) {
            stop();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else status.setText("Microphone permission is needed");
    }

    @SuppressWarnings("MissingPermission")
    private void start() {
        AudioManager am = getSystemService(AudioManager.class);
        int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        steps.setCurrent(vol * 10 >= max * 6 ? 2 : 0);
        if (!"true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND))) {
            detail.setText("Warning: this phone doesn't report near-ultrasound speaker support, so the echoes may be weak.");
        }
        short[] pcm = Sonar.pcm(94);   // about 1 s, looped forever
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Sonar.FS).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        track.write(pcm, 0, pcm.length);
        track.setLoopPoints(0, pcm.length, -1);
        int minBuf = AudioRecord.getMinBufferSize(Sonar.FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // VOICE_RECOGNITION: Android guarantees near-ultrasound response here when the phone reports support (CDD 7.8.3).
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Sonar.FS, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, Sonar.N * 2 * 16));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            status.setText("Couldn't open the microphone");
            track.release();
            record.release();
            track = null;
            record = null;
            return;
        }
        sessionMs = System.currentTimeMillis();
        try {
            log = new PrintWriter(new FileWriter(new File(Store.dir(this), "calm_" + sessionMs + ".csv")), true);
            log.println("epoch_ms,motion_index,ema,threshold,hand_gated_pct,intervention");
        } catch (IOException e) {
            log = null;
        }
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        series.clear();
        interventions = 0;
        gatedFrames = totalFrames = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        running = true;
        track.play();
        record.startRecording();
        worker = new Thread(this::loop, "calm-sense");
        worker.start();
        startButton.setText("Stop");
        status.setText("Listening… learning your baseline");
        if (getIntent().getBooleanExtra("checkin", false)) {   // part of a check-in: one minute, then on to the next test
            status.setText(String.format(Locale.US, "Listening for %d s… learning your baseline", CHECKIN_S));
            autoStop.postDelayed(() -> {
                stop();
                status.setText("Done. Next test coming up…");
                autoStop.postDelayed(this::finish, 1500);
            }, CHECKIN_S * 1000L);
        }
        Store.event(this, sessionMs, sessionMs, "calm_started", String.format(Locale.US, "volume=%d/%d", vol, max));
    }

    /** In a check-in, a session runs this long, then the screen closes so the next test can start. */
    static final int CHECKIN_S = 60, MIN_SAVED_S = 45;
    private final android.os.Handler autoStop = new android.os.Handler(android.os.Looper.getMainLooper());

    private void stop() {
        if (!running) return;
        running = false;
        autoStop.removeCallbacksAndMessages(null);
        try {
            if (worker != null) worker.join(500);
        } catch (InterruptedException ignored) {
        }
        sensors.unregisterListener(this);
        if (track != null) {
            track.stop();
            track.release();
        }
        if (record != null) {
            record.stop();
            record.release();
        }
        track = null;
        record = null;
        if (log != null) log.close();
        log = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startButton.setText("Start");
        status.setText(String.format(Locale.US, "Stopped · %d message%s shown", interventions, interventions == 1 ? "" : "s"));
        Store.event(this, System.currentTimeMillis(), sessionMs, "calm_stopped", "interventions=" + interventions);
        long ms = System.currentTimeMillis() - sessionMs;
        if (ms >= MIN_SAVED_S * 1000L) {   // a real session: a result for Today and the check-in
            try {
                Store.save(this, new org.json.JSONObject().put("id", sessionMs).put("type", "calm")
                        .put("duration_s", Math.round(ms / 1000.0)).put("interventions", interventions)
                        .put("hand_gated_pct", totalFrames == 0 ? 0 : Math.round(100.0 * gatedFrames / totalFrames))
                        .put("raw_file", "calm_" + sessionMs + ".csv"));
            } catch (org.json.JSONException ignored) {
            }
        }
    }

    /** Audio thread: one chirp period at a time -> echo change -> 0.5 s movement index -> trigger. */
    private void loop() {
        Sonar sonar = new Sonar();
        JitaTrigger trigger = new JitaTrigger();
        short[] buf = new short[Sonar.N];
        double[] x = new double[Sonar.N];
        double sum = 0;
        int n = 0, gatedInBlock = 0, framesInBlock = 0;
        long blockStart = System.currentTimeMillis();
        AudioRecord rec = record;
        PrintWriter out = log;
        int failures = 0;
        while (running) {
            int got = 0;
            while (got < Sonar.N && running) {
                int r = rec.read(buf, got, Sonar.N - got);
                if (r <= 0) break;
                got += r;
            }
            if (got < Sonar.N) {
                if (!running) break;
                if (++failures >= 50) {   // the mic stopped delivering audio: give up instead of spinning
                    ui.post(() -> {
                        stop();
                        status.setText("The microphone stopped delivering audio. Try Start again.");
                    });
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }
            failures = 0;
            for (int i = 0; i < Sonar.N; i++) x[i] = buf[i] / 32768.0;
            Sonar.Frame f = sonar.process(x);
            if (f == null) continue;
            framesInBlock++;
            totalFrames++;
            if (handBusy) {   // the paper suspends acoustic sensing while the hands are tapping
                gatedInBlock++;
                gatedFrames++;
            } else {
                sum += f.motion;
                n++;
            }
            float[] row = f.change;
            ui.post(() -> heat.push(row));
            long now = System.currentTimeMillis();
            if (now - blockStart >= 500) {
                double t = (now - sessionMs) / 1000.0;
                double index = n > 0 ? Math.log10(sum / n + 1e-9) : Double.NaN;
                boolean fire = !Double.isNaN(index) && trigger.update(t, index);
                double gatedPct = 100.0 * gatedInBlock / Math.max(1, framesInBlock);
                if (out != null) {
                    out.println(String.format(Locale.US, "%d,%.4f,%.4f,%.4f,%.1f,%d", now, index, trigger.ema(), trigger.threshold, gatedPct, fire ? 1 : 0));
                }
                double thr = trigger.threshold, ema = trigger.ema();
                int lag = sonar.directLag();
                ui.post(() -> onBlock(t, index, ema, thr, gatedPct, lag, fire));
                sum = 0;
                n = gatedInBlock = framesInBlock = 0;
                blockStart = now;
            }
        }
    }

    private void onBlock(double t, double index, double ema, double threshold, double gatedPct, int lag, boolean fire) {
        if (!running) return;
        steps.setCurrent(t < 30 ? 2 : 3);
        status.setText(t < 30 ? String.format(Locale.US, "Learning your baseline… %.0f s", 30 - t) : "Listening");
        detail.setText(String.format(Locale.US, "Echo locked (direct path at %d) · %.0f%% of the last half-second paused for hand taps",
                lag, gatedPct));
        if (!Double.isNaN(index)) {
            indexText.setText(String.format(Locale.US, "Movement index %.2f (smoothed %.2f) · trigger at %s", index, ema,
                    Double.isNaN(threshold) ? "…" : String.format(Locale.US, "%.2f", threshold)));
            series.add(new double[]{t, ema, threshold});
            while (!series.isEmpty() && t - series.get(0)[0] > 120) series.remove(0);
            double[] xs = new double[series.size()], ys = new double[series.size()], ths = new double[series.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = series.get(i)[0];
                ys[i] = series.get(i)[1];
                ths[i] = series.get(i)[2];
            }
            chart.clear();
            chart.add(new ChartView.Series(xs, ths, Ui.TEMP, false, "threshold"));
            chart.add(new ChartView.Series(xs, ys, Ui.ACCENT, false, "movement"));
            chart.invalidate();
        }
        if (fire) {
            interventions++;
            String m = MESSAGES[rng.nextInt(MESSAGES.length)];
            message.setText(m);
            message.setVisibility(View.VISIBLE);
            Store.event(this, System.currentTimeMillis(), sessionMs, "calm_intervention", m);
            ui.postDelayed(() -> message.setVisibility(View.GONE), 5000);   // the paper shows it for ~5 s
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        double mag = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) mag *= 2;   // rad/s and m/s^2 on comparable scales
        imuWin[imuHead] = mag * mag;
        imuHead = (imuHead + 1) % imuWin.length;
        double s = 0;
        for (double v : imuWin) s += v;
        imuEnergy = s / imuWin.length;
        // Running mean and variance of the energy (about a 10 s memory at this rate).
        if (Double.isNaN(imuMean)) imuMean = imuEnergy;
        double a = 0.005, d = imuEnergy - imuMean;
        imuMean += a * d;
        imuVar = (1 - a) * (imuVar + a * d * d);
        handBusy = imuEnergy > imuMean + 0.25 * Math.sqrt(imuVar);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Scrolling range-time map of echo change (the paper's differenced echo spectrogram). */
    private static final class HeatMap extends View {
        private final Bitmap bmp = Bitmap.createBitmap(300, Sonar.BINS, Bitmap.Config.ARGB_8888);
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect(0, 0, 300, Sonar.BINS), dst = new Rect();
        private final int[] column = new int[Sonar.BINS];
        private float scale = 1e-3f;
        private int x;

        HeatMap(Context c) {
            super(c);
            bmp.eraseColor(0xFFF3EEE7);   // ivory: still
            setMinimumHeight(Ui.dp(c, 150));
        }

        void push(float[] change) {
            float max = 0;
            for (float v : change) max = Math.max(max, v);
            scale = Math.max(scale * 0.995f, max);   // slowly adapting brightness
            for (int b = 0; b < change.length; b++) {
                float t = (float) Math.sqrt(Math.min(1f, change[b] / scale));
                // ivory (still) to living coral (moved)
                int r = (int) (0xF3 + (0xE8 - 0xF3) * t), g = (int) (0xEE + (0x70 - 0xEE) * t), bl = (int) (0xE7 + (0x5E - 0xE7) * t);
                column[b] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            bmp.setPixels(column, 0, 1, x, 0, 1, Sonar.BINS);
            x = (x + 1) % bmp.getWidth();
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), getSuggestedMinimumHeight());
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight(), bw = bmp.getWidth();
            // Draw the ring buffer oldest-first so time runs left to right.
            float split = (float) (bw - x) / bw * w;
            src.set(x, 0, bw, Sonar.BINS);
            dst.set(0, 0, (int) split, h);
            c.drawBitmap(bmp, src, dst, paint);
            src.set(0, 0, x, Sonar.BINS);
            dst.set((int) split, 0, w, h);
            c.drawBitmap(bmp, src, dst, paint);
        }
    }
}
