package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * What passive eye tracking is doing, for the All day card to show. Written by {@link EyeTrackingService}, read by
 * the UI. Rates are per minute of time the face was seen, from the last completed minute; the shift baseline is
 * this session's own recent average.
 *
 * Exploratory: nothing in the project's papers validates an eye-movement measure of stress or distraction. The read
 * only says whether the eyes are busier than usual for this person, this session. It is not a diagnosis.
 */
final class EyeState {
    static volatile boolean running;
    static volatile String status = "Off";
    static volatile int blinks, shifts;                 // this session
    static volatile double blinkRate = Double.NaN, shiftRate = Double.NaN;   // last full minute
    static volatile double faceSecondsLastMinute;
    static volatile String read = "";                   // "", "Settled", "Restless", "Not enough face time"
    static volatile long startedAtMs;
    static volatile double threshold = Double.NaN, jitter = Double.NaN;   // the shift detector's, eye widths (last minute)

    static final String PREF_WANTED = "eye_passive_wanted";

    private EyeState() {
    }

    static boolean hasCamera(Context c) {
        return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** Camera foreground services can only start while the app is visible; call this from the foreground. */
    static void start(Context c) {
        Store.prefs(c).edit().putBoolean(PREF_WANTED, true).apply();
        c.startForegroundService(new Intent(c, EyeTrackingService.class));
    }

    static void stop(Context c) {
        Store.prefs(c).edit().putBoolean(PREF_WANTED, false).apply();
        c.stopService(new Intent(c, EyeTrackingService.class));
    }

    static boolean wanted(Context c) {
        return Store.prefs(c).getBoolean(PREF_WANTED, false);
    }
}
