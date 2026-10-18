package edu.uw.ubicomp.barotest;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Logs the barometer at its fastest rate for N seconds to <name>.csv (t_ns,hpa) in the app's files dir, and shows
 * the live change from the first second's baseline in pascals, so a blow at the screen is visible immediately.
 * Usage: am start -n edu.uw.ubicomp.barotest/.BaroLogActivity --ei seconds 8 --es name P1_1
 * Without extras it runs a 10-minute live view and logs to live.csv.
 */
public class BaroLogActivity extends Activity implements SensorEventListener {
    private SensorManager sm;
    private PrintWriter out;
    private TextView view;
    private long t0 = -1, endNs;
    private double baseSum, base = Double.NaN, peak = 0;
    private int baseN, n;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new TextView(this);
        view.setTextSize(40);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.BLACK);
        view.setGravity(Gravity.CENTER);
        setContentView(view);

        int seconds = getIntent().getIntExtra("seconds", 600);
        String name = getIntent().getStringExtra("name");
        if (name == null) name = "live";
        endNs = seconds * 1_000_000_000L;
        try {
            out = new PrintWriter(new File(getExternalFilesDir(null), name + ".csv"));
            out.println("t_ns,hpa");
        } catch (IOException e) {
            view.setText("log error: " + e.getMessage());
            return;
        }
        sm = getSystemService(SensorManager.class);
        Sensor baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (baro == null) {
            view.setText("No barometer");
            return;
        }
        sm.registerListener(this, baro, SensorManager.SENSOR_DELAY_FASTEST);
        view.setText("Recording " + name + "\n" + baro.getName());
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (t0 < 0) t0 = e.timestamp;
        long t = e.timestamp - t0;
        double hpa = e.values[0];
        out.println(t + "," + hpa);
        n++;
        if (t < 1_000_000_000L) {          // first second: baseline
            baseSum += hpa;
            baseN++;
        } else {
            if (Double.isNaN(base)) base = baseSum / Math.max(1, baseN);
            double pa = (hpa - base) * 100;
            if (Math.abs(pa) > Math.abs(peak)) peak = pa;
            view.setText(String.format(Locale.US, "%+.1f Pa\npeak %+.1f Pa\n%.1f Hz", pa, peak, n / (t / 1e9)));
        }
        if (t >= endNs) finish();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onPause() {
        super.onPause();
        if (sm != null) sm.unregisterListener(this);
        if (out != null) out.close();
        finish();
    }
}
