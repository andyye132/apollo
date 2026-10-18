package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Counts steps and flights climbed all day, the way the phone's own health apps do: the barometer and step detector
 * run in the low-power sensor hub, batched, and this service takes their events every half minute or so. Android
 * only delivers continuous sensors like the barometer to a foreground service, hence the quiet notification.
 * Started whenever Home opens; restarted by the system if it's killed.
 */
public class AllDayService extends Service implements SensorEventListener {
    static volatile boolean running;
    private static final String CHANNEL = "all_day";
    private static final int NOTIFICATION_ID = 8;
    private static final int BARO_PERIOD_US = 1_000_000, BATCH_US = 30_000_000;   // 1 Hz, delivered every 30 s

    private SensorManager sensors;
    private FloorCounter floors;
    private String day;

    /** Start it if it isn't running (needs activity recognition for the step detector). Call while Apollo is visible. */
    static void start(Context c) {
        if (running) return;
        if (c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return;
        c.startForegroundService(new Intent(c, AllDayService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (running) return START_STICKY;
        running = true;
        day = DailyCounts.today();
        floors = new FloorCounter();
        floors.up = DailyCounts.floorsUp(this, day);       // carry on from earlier today
        floors.down = DailyCounts.floorsDown(this, day);
        sensors = getSystemService(SensorManager.class);
        Sensor baro = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE);
        Sensor stepDetector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        Sensor stepCounter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (baro != null) sensors.registerListener(this, baro, BARO_PERIOD_US, BATCH_US);
        if (stepDetector != null) sensors.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US);
        if (stepCounter != null) sensors.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US);
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        long t = now - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000;   // event time, wall clock
        switch (e.sensor.getType()) {
            case Sensor.TYPE_STEP_DETECTOR:
                floors.step(t);
                break;
            case Sensor.TYPE_STEP_COUNTER:
                DailyCounts.onStepCounter(this, (long) e.values[0]);
                break;
            case Sensor.TYPE_PRESSURE:
                String d = DailyCounts.day(t);
                if (!d.equals(day)) {   // midnight: finish yesterday, start today from zero
                    floors.flushAll();
                    DailyCounts.setFloors(this, day, floors.up, floors.down);
                    FloorCounter next = new FloorCounter();
                    next.up = DailyCounts.floorsUp(this, d);
                    next.down = DailyCounts.floorsDown(this, d);
                    floors = next;
                    day = d;
                }
                floors.pressure(t, e.values[0]);
                int before = floors.up, beforeDown = floors.down;
                floors.flush(now);
                if (floors.up != before || floors.down != beforeDown) DailyCounts.setFloors(this, day, floors.up, floors.down);
                break;
            default:
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        if (sensors != null) sensors.unregisterListener(this);
        if (floors != null) {
            floors.flushAll();
            DailyCounts.setFloors(this, day, floors.up, floors.down);
        }
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "All day (steps and floors)", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Counting steps and floors")
                .setContentText("All day, on this phone.")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }
}
