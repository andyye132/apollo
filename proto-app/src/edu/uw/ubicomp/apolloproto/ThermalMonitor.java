package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads every thermistor the thermal HAL reports once a second (needs DUMP granted over adb) while
 * any screen holds it, and judges from the live readings whether it's safe to measure: READY when the
 * phone has lain still for 60 s (gyroscope, see Steadiness), its reference and front thermistors are
 * flat (|drift| <= 0.1 °C/min over that still time), it's at most 30 °C, and it isn't charging. These
 * replaced fixed waits (10 min after plugging in or the flashlight, Oct 2026): a settled phone no longer
 * waits, and an unsettled one still fails the flat check for as long as it really takes.
 */
final class ThermalMonitor implements android.hardware.SensorEventListener {
    static final class Sample {
        final String name;
        final int type;
        final double celsius;

        Sample(String name, int type, double celsius) {
            this.name = name;
            this.type = type;
            this.celsius = celsius;
        }
    }

    interface Listener {
        void onSamples(long epochMs, List<Sample> samples);
    }

    static final class State {
        String verdict = "SETTLING", message = "Starting", powerMode = "UNPLUGGED", refName;
        double phoneC = Double.NaN, driftCPerMin = Double.NaN, stillS;
    }

    static final String SETUP = "adb shell pm grant edu.uw.ubicomp.apolloproto android.permission.DUMP";
    private static final Pattern TEMP = Pattern.compile("mValue=([-0-9.eE]+|NaN), mType=(-?\\d+), mName=([^,]+),");
    private static final String[] REFERENCE = {"quiet-therm", "skin-therm", "VIRTUAL-SKIN", "sdm-therm-monitor", "battery"};
    private static ThermalMonitor instance;

    private final Context app;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<double[]> history = new ArrayDeque<>();   // {epochMs, reference celsius}
    private int users;
    private Thread thread;
    private volatile Boolean access;   // null until the first dump
    private volatile String refName, powerMode = "UNPLUGGED";
    private volatile long powerChangeMs = -1;
    private volatile State state = new State();
    /** The last 2 minutes of readings, {epochMs, List<Sample>, still (Boolean), gyro median (Double)}: the baseline before a test. */
    private final ArrayDeque<Object[]> recent = new ArrayDeque<>();
    /** Gyroscope magnitudes over the last second, {elapsedMs, rad/s}, for the still flag. */
    private final ArrayDeque<double[]> gyro = new ArrayDeque<>();
    private volatile boolean stillNow;
    private volatile double gyroNow = Double.NaN;
    private boolean gyroOn;
    static final double MAX_C = 30, MAX_DRIFT = 0.1, STILL_NEEDED_S = 60;
    private static final String[] FRONT = {"pa-therm", "chg-therm"};

    private ThermalMonitor(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized ThermalMonitor get(Context c) {
        if (instance == null) instance = new ThermalMonitor(c);
        return instance;
    }

    synchronized void acquire() {
        users++;
        if (!gyroOn) {
            android.hardware.SensorManager sm = app.getSystemService(android.hardware.SensorManager.class);
            android.hardware.Sensor g = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
            if (g != null) gyroOn = sm.registerListener(this, g, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        }
        if (thread == null) {
            thread = new Thread(this::loop, "thermal-monitor");
            thread.setDaemon(true);
            thread.start();
        }
        notifyAll();
    }

    synchronized void release() {
        users = Math.max(0, users - 1);
        if (users == 0 && gyroOn) {
            app.getSystemService(android.hardware.SensorManager.class).unregisterListener(this);
            gyroOn = false;
        }
    }

    @Override
    public void onSensorChanged(android.hardware.SensorEvent e) {
        double m = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        long now = SystemClock.elapsedRealtime();
        synchronized (gyro) {
            gyro.addLast(new double[]{now, m});
            while (now - gyro.peekFirst()[0] > 1000) gyro.removeFirst();
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
    }

    /** Median gyroscope rotation over the last second (NaN without a gyroscope or readings). */
    private double gyroMedian() {
        synchronized (gyro) {
            if (gyro.size() < 5) return Double.NaN;
            double[] v = new double[gyro.size()];
            int i = 0;
            for (double[] g : gyro) v[i++] = g[1];
            return Dsp.median(v);
        }
    }

    /** Whether the phone is lying still right now, and the rotation behind it (for the raw files). */
    boolean stillNow() {
        return stillNow;
    }

    double gyroNow() {
        return gyroNow;
    }

    /** Largest gyroscope rotation in the last second, rad/s: a bump or a drop shows here before the median moves. */
    double gyroPeak() {
        synchronized (gyro) {
            double m = Double.NaN;
            for (double[] g : gyro) m = Double.isNaN(m) ? g[1] : Math.max(m, g[1]);
            return m;
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    State state() {
        return state;
    }

    /** Readings since `epochMs` (at most the last 2 minutes), oldest first, as {epochMs (Long), List<Sample>, still (Boolean), gyro (Double)}. */
    List<Object[]> recentSince(long epochMs) {
        List<Object[]> out = new ArrayList<>();
        synchronized (recent) {
            for (Object[] r : recent) if ((Long) r[0] >= epochMs) out.add(r);
        }
        return out;
    }

    /** False once a dump has been refused; null before the first attempt. */
    Boolean access() {
        return access;
    }

    private void loop() {
        while (true) {
            synchronized (this) {
                while (users == 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            long start = SystemClock.elapsedRealtime(), now = System.currentTimeMillis();
            List<Sample> samples = readOnce(now);
            gyroNow = gyroMedian();
            stillNow = Steadiness.still(gyroNow);
            synchronized (recent) {
                recent.addLast(new Object[]{now, samples, stillNow, gyroNow});
                while (now - (Long) recent.peekFirst()[0] > 120_000) recent.removeFirst();
            }
            update(now, samples);
            for (Listener l : listeners) l.onSamples(now, samples);
            SystemClock.sleep(Math.max(50, 1000 - (SystemClock.elapsedRealtime() - start)));
        }
    }

    private List<Sample> readOnce(long now) {
        List<Sample> out = new ArrayList<>();
        File dump = new File(app.getCacheDir(), "thermalservice.txt");
        try (FileOutputStream fos = new FileOutputStream(dump)) {
            Debug.dumpService("thermalservice", fos.getFD(), null);
        } catch (IOException ignored) {
        }
        boolean inHal = false;
        try (BufferedReader r = new BufferedReader(new FileReader(dump))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("Permission Denial")) {
                    access = false;
                    break;
                }
                if (line.startsWith("Current temperatures from HAL")) {
                    inHal = true;
                } else if (inHal) {
                    if (!line.startsWith("\t") && !line.startsWith(" ")) break;
                    Matcher m = TEMP.matcher(line);
                    if (m.find()) out.add(new Sample(m.group(3), Integer.parseInt(m.group(2)), Double.parseDouble(m.group(1))));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        if (!out.isEmpty()) access = true;

        Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            out.add(new Sample("battery_api", 2, battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0));
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            String mode = plugged == 0 ? "UNPLUGGED" : level * 100 >= 100 * scale ? "TRICKLE" : "CHARGING";
            if (!mode.equals(powerMode) && !history.isEmpty()) powerChangeMs = now;
            powerMode = mode;
        }
        PowerManager pm = app.getSystemService(PowerManager.class);
        out.add(new Sample("headroom_api", 3, pm.getThermalHeadroom(0)));
        return out;
    }

    private void update(long now, List<Sample> samples) {
        if (refName == null) {
            for (String want : REFERENCE) {
                for (Sample s : samples) if (s.name.equals(want)) refName = want;
                if (refName != null) break;
            }
        }
        Double ref = null;
        for (Sample s : samples) if (s.name.equals(refName)) ref = s.celsius;
        if (ref != null && !ref.isNaN()) {
            history.addLast(new double[]{now, ref});
            while (now - history.peekFirst()[0] > 15 * 60_000) history.removeFirst();
        }

        State st = new State();
        st.refName = refName;
        st.powerMode = powerMode;
        st.phoneC = ref != null ? ref : Double.NaN;
        // The still stretch and the drift over it, on the reference and front thermistors.
        List<Object[]> rows = recentSince(now - 120_000);
        double[] t = new double[rows.size()];
        boolean[] still = new boolean[rows.size()];
        List<String> names = new ArrayList<>();
        if (refName != null) names.add(refName);
        for (String f : FRONT) names.add(f);
        List<double[][]> series = new ArrayList<>();
        for (String name : names) series.add(new double[2][rows.size()]);
        for (int i = 0; i < rows.size(); i++) {
            t[i] = ((Long) rows.get(i)[0] - now) / 1000.0;
            still[i] = (Boolean) rows.get(i)[2];
            @SuppressWarnings("unchecked") List<Sample> got = (List<Sample>) rows.get(i)[1];
            for (int j = 0; j < names.size(); j++) {
                series.get(j)[0][i] = t[i];
                series.get(j)[1][i] = Double.NaN;
                for (Sample smp : got) if (smp.name.equals(names.get(j))) series.get(j)[1][i] = smp.celsius;
            }
        }
        for (double[][] sr : series) {   // drop missing readings
            int n = 0;
            for (double v : sr[1]) if (!Double.isNaN(v)) n++;
            double[][] c = new double[2][n];
            for (int i = 0, j = 0; i < sr[1].length; i++) if (!Double.isNaN(sr[1][i])) { c[0][j] = sr[0][i]; c[1][j++] = sr[1][i]; }
            sr[0] = c[0];
            sr[1] = c[1];
        }
        double stillS = Steadiness.stillFor(t, still);
        st.stillS = stillS;
        st.driftCPerMin = stillS >= 10 ? Steadiness.maxDrift(series, -stillS, 0) : Double.NaN;

        if (Boolean.FALSE.equals(access)) {
            st.verdict = "UNSUPPORTED";
            st.message = "Needs a one-time setup from a computer: " + SETUP;
        } else if (ref == null) {
            st.verdict = "UNSUPPORTED";
            st.message = "No thermistor readings yet";
        } else if ("CHARGING".equals(powerMode)) {
            st.verdict = "BLOCKED";
            st.message = "Charging warms the phone. Unplug it, or wait until it's full.";
        } else if (st.phoneC > MAX_C) {
            st.verdict = "TOO_WARM";
            st.message = String.format(Locale.US, "%.1f °C now. Best below %.0f °C, so give it a few minutes on the table.", st.phoneC, MAX_C);
        } else if (stillS < STILL_NEEDED_S) {
            st.verdict = "SETTLING";
            st.message = stillS < 2 ? "Lay it flat on the table and leave it be."
                    : String.format(Locale.US, "Lying still · %.0f of %.0f seconds", stillS, STILL_NEEDED_S);
        } else if (Double.isNaN(st.driftCPerMin) || Math.abs(st.driftCPerMin) > MAX_DRIFT) {
            st.verdict = "SETTLING";
            st.message = String.format(Locale.US, "Its temperature is still %s (%+.2f °C/min). Almost there.",
                    st.driftCPerMin > 0 ? "rising" : "easing", st.driftCPerMin);
        } else {
            st.verdict = "READY";
            st.message = String.format(Locale.US, "Still and steady at %.1f °C.", st.phoneC);
        }
        state = st;
    }
}
