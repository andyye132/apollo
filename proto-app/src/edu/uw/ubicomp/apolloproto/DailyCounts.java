package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Today's steps and flights, kept per day in preferences ("steps_yyyy-MM-dd", "floors_up_…", "floors_down_…") for
 * the All day cards and Trends. Written by AllDayService in the background and by Home while it's open.
 */
final class DailyCounts {
    private DailyCounts() {
    }

    static String day(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    static String today() {
        return day(System.currentTimeMillis());
    }

    /**
     * The step counter counts from boot; the first value seen each day is that day's baseline (with the service
     * running, that's within a minute of midnight). Returns today's steps.
     */
    static synchronized long onStepCounter(Context c, long total) {
        SharedPreferences p = Store.prefs(c);
        String today = today();
        long base = p.getLong("steps_base", -1);
        if (!today.equals(p.getString("steps_day", "")) || base < 0 || total < base) {
            base = total;
            p.edit().putString("steps_day", today).putLong("steps_base", base).apply();
        }
        long now = total - base;
        if (now != p.getLong("steps_" + today, -1)) p.edit().putLong("steps_" + today, now).apply();
        return now;
    }

    static int floorsUp(Context c, String day) {
        return Store.prefs(c).getInt("floors_up_" + day, 0);
    }

    static int floorsDown(Context c, String day) {
        return Store.prefs(c).getInt("floors_down_" + day, 0);
    }

    static void setFloors(Context c, String day, int up, int down) {
        Store.prefs(c).edit().putInt("floors_up_" + day, up).putInt("floors_down_" + day, down).apply();
    }
}
