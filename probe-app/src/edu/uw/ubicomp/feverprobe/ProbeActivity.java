package edu.uw.ubicomp.feverprobe;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Black, touch-inert screen that keeps the phone awake and logs, once a second, every thermistor
 * the thermal HAL reports (via the DUMP permission), the public battery temperature, and the
 * public thermal headroom. Every touch event is logged too, as a stand-in for FeverPhone's
 * raw-capacitance contact features. CSVs land in /sdcard/Android/data/<package>/files/.
 */
public class ProbeActivity extends Activity {
    private static final Pattern TEMP =
            Pattern.compile("mValue=([-0-9.eE]+|NaN), mType=(-?\\d+), mName=([^,]+),");

    private volatile boolean running;
    private PrintWriter thermal;
    private PrintWriter touch;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.2f; // FeverPhone fixes brightness at 20%
        getWindow().setAttributes(lp);

        View view = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                logTouch(e);
                return true;
            }
        };
        view.setBackgroundColor(Color.BLACK);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        File dir = getExternalFilesDir(null);
        long stamp = System.currentTimeMillis();
        try {
            thermal = new PrintWriter(new FileWriter(new File(dir, "thermal_" + stamp + ".csv")), true);
            touch = new PrintWriter(new FileWriter(new File(dir, "touch_" + stamp + ".csv")), true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        thermal.println("epoch_ms,sensor,type,value");
        touch.println("epoch_ms,action,pointers,x,y,touch_major,touch_minor,size,pressure");
        running = true;
        new Thread(this::sampleLoop, "thermal-sampler").start();
    }

    @Override
    protected void onPause() {
        running = false;
        touch.close();
        super.onPause();
    }

    private void sampleLoop() {
        PrintWriter out = thermal;
        PowerManager pm = getSystemService(PowerManager.class);
        File dump = new File(getCacheDir(), "thermalservice.txt");
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        while (running) {
            long t = System.currentTimeMillis();
            try (FileOutputStream fos = new FileOutputStream(dump)) {
                Debug.dumpService("thermalservice", fos.getFD(), null);
            } catch (IOException e) {
                out.println(t + ",dump_error,0,0");
            }
            parseHalTemperatures(dump, t, out);

            Intent battery = registerReceiver(null, batteryFilter);
            if (battery != null) {
                int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                out.println(t + ",battery_api,2," + tenths / 10f);
            }
            out.println(t + ",headroom_api,3," + pm.getThermalHeadroom(0));
            SystemClock.sleep(1000);
        }
        out.close();
    }

    /** Copies the "Current temperatures from HAL" block of a thermalservice dump into the CSV. */
    private static void parseHalTemperatures(File dump, long t, PrintWriter out) {
        boolean inHal = false;
        try (BufferedReader r = new BufferedReader(new FileReader(dump))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("Permission Denial")) {
                    out.println(t + ",dump_denied,0,0");
                    return;
                }
                if (line.startsWith("Current temperatures from HAL")) {
                    inHal = true;
                } else if (inHal) {
                    if (!line.startsWith("\t") && !line.startsWith(" ")) return;
                    Matcher m = TEMP.matcher(line);
                    if (m.find()) out.println(t + "," + m.group(3) + "," + m.group(2) + "," + m.group(1));
                }
            }
        } catch (IOException e) {
            out.println(t + ",dump_read_error,0,0");
        }
    }

    private void logTouch(MotionEvent e) {
        PrintWriter out = touch;
        long t = System.currentTimeMillis();
        String action = MotionEvent.actionToString(e.getActionMasked());
        for (int i = 0; i < e.getPointerCount(); i++) {
            out.println(t + "," + action + "," + e.getPointerCount() + "," + e.getX(i) + "," + e.getY(i)
                    + "," + e.getTouchMajor(i) + "," + e.getTouchMinor(i) + "," + e.getSize(i)
                    + "," + e.getPressure(i));
        }
    }
}
