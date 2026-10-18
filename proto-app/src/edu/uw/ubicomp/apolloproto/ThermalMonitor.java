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
 * any screen holds it, and judges whether the phone is steady enough for FeverPhone. Rules from the
 * design doc's "Measurement order and phone heat": READY needs |drift| <= 0.1 C/min over 120 s, phone
 * below 32 C, not actively charging, and 10 min since a power change or a heart-rate test (torch).
 */
final class ThermalMonitor {
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
        double phoneC = Double.NaN, driftCPerMin = Double.NaN;
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

    private ThermalMonitor(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized ThermalMonitor get(Context c) {
        if (instance == null) instance = new ThermalMonitor(c);
        return instance;
    }

    synchronized void acquire() {
        users++;
        if (thread == null) {
            thread = new Thread(this::loop, "thermal-monitor");
            thread.setDaemon(true);
            thread.start();
        }
        notifyAll();
    }

    synchronized void release() {
        users = Math.max(0, users - 1);
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
        List<double[]> recent = new ArrayList<>();
        for (double[] h : history) if (now - h[0] <= 120_000) recent.add(h);
        double spanS = recent.size() > 1 ? (recent.get(recent.size() - 1)[0] - recent.get(0)[0]) / 1000 : 0;
        if (recent.size() > 10) {
            double[] t = new double[recent.size()], y = new double[recent.size()];
            for (int i = 0; i < t.length; i++) {
                t[i] = recent.get(i)[0] / 60000.0;
                y[i] = recent.get(i)[1];
            }
            st.driftCPerMin = Dsp.lineFit(t, y)[0];
        }
        long lastHeat = Store.prefs(app).getLong("last_heat_ms", 0);
        double heatMin = (now - lastHeat) / 60000.0, powerMin = powerChangeMs < 0 ? 99 : (now - powerChangeMs) / 60000.0;

        if (Boolean.FALSE.equals(access)) {
            st.verdict = "UNSUPPORTED";
            st.message = "Needs a one-time setup from a computer: " + SETUP;
        } else if (ref == null) {
            st.verdict = "UNSUPPORTED";
            st.message = "No thermistor readings yet";
        } else if (st.phoneC >= 32) {
            st.verdict = "TOO_WARM";
            st.message = String.format(Locale.US, "Phone is %.1f °C. Let it cool below 32 °C first", st.phoneC);
        } else if ("CHARGING".equals(powerMode)) {
            st.verdict = "BLOCKED";
            st.message = "Charging heats the phone unevenly. Unplug it, or wait until it's at 100%";
        } else if (powerMin < 10) {
            st.verdict = "SETTLING";
            st.message = String.format(Locale.US, "Power was plugged in or out %.0f min ago. About %.0f min to go", powerMin, 10 - powerMin);
        } else if (heatMin < 10) {
            st.verdict = "SETTLING";
            st.message = String.format(Locale.US, "The camera light warmed the phone %.0f min ago. About %.0f min to go", heatMin, Math.ceil(10 - heatMin));
        } else if (spanS < 110) {
            st.verdict = "SETTLING";
            st.message = String.format(Locale.US, "Learning the phone's own temperature: %.0f s to go", 120 - spanS);
        } else if (Math.abs(st.driftCPerMin) > 0.1) {
            st.verdict = "SETTLING";
            st.message = String.format(Locale.US, "Phone temperature is still %s (%+.2f °C/min)",
                    st.driftCPerMin > 0 ? "rising" : "falling", st.driftCPerMin);
        } else {
            st.verdict = "READY";
            st.message = String.format(Locale.US, "Phone is steady at %.1f °C", st.phoneC);
        }
        state = st;
    }
}
