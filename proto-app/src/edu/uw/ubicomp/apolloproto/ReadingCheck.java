package edu.uw.ubicomp.apolloproto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Did anything go wrong during the 90 s on the forehead? A reading is trusted unless the run itself shows a
 * problem: the phone barely warmed (it wasn't on skin), it came away from the forehead, it was moving, or it
 * was bumped. Pre-start conditions are judged elsewhere.
 *
 * Thresholds from the Oct 17 Pixel 4 runs: after the first 5 s of contact (placing the phone, up to 2.3 rad/s),
 * a hand-held forehead run never went above 0.06 rad/s (1 s median), and the screen-side temperature never fell
 * more than 0.09 °C below its running maximum. Forehead runs rose at least 0.7 °C; a phone left on the table
 * rose 0.08 at most.
 */
final class ReadingCheck {
    enum Verdict { GOOD, ROUGH, REDO }

    static final double SETTLE_S = 5;              // placing the phone: motion ignored
    static final double MOVING_RAD_S = 0.15;       // 1 s median gyroscope: the phone is moving
    static final double JOLT_RAD_S = 0.8, JOLT_PEAK_RAD_S = 3.0;   // a bump or a drop
    static final int ROUGH_MOVING_S = 3, REDO_MOVING_S = 15;
    static final double NO_WARMING_C = 0.2;        // rise over the whole contact
    static final double CONTACT_LOST_C = 0.25;     // fall below the running maximum after 10 s
    static final int MIN_SECONDS = 75, REDO_SECONDS = 45;   // of 90

    final Verdict verdict;
    final String reason;   // null when GOOD
    final int movingS, seconds;
    final double rise, drop, worstRadS;

    private ReadingCheck(Verdict v, String reason, int movingS, int seconds, double rise, double drop, double worst) {
        this.verdict = v;
        this.reason = reason;
        this.movingS = movingS;
        this.seconds = seconds;
        this.rise = rise;
        this.drop = drop;
        this.worstRadS = worst;
    }

    /**
     * @param t     seconds from the start of contact, ascending (per-second screen-side temperature)
     * @param front screen-side temperature at each t (the two front sensors, averaged)
     * @param dur   length of the contact, s
     * @param motion {t, 1 s median rad/s, 1 s peak rad/s (NaN if not logged)} per second of contact
     */
    static ReadingCheck of(double[] t, double[] front, double dur, List<double[]> motion) {
        int seconds = 0;
        double start = Double.NaN, end = Double.NaN, max = Double.NEGATIVE_INFINITY, drop = 0;
        for (int i = 0; i < t.length; i++) {
            if (t[i] < 0 || t[i] > dur) continue;
            seconds++;
            if (Double.isNaN(start)) start = front[i];
            end = front[i];
            max = Math.max(max, front[i]);
            if (t[i] >= 10) drop = Math.max(drop, max - front[i]);
        }
        int moving = 0;
        double worst = 0;
        boolean jolt = false;
        for (double[] m : motion) {
            if (m[0] < SETTLE_S || m[0] > dur) continue;
            if (m[1] > MOVING_RAD_S) moving++;
            worst = Math.max(worst, Math.max(m[1], Double.isNaN(m[2]) ? 0 : m[2]));
            if (m[1] > JOLT_RAD_S || (!Double.isNaN(m[2]) && m[2] > JOLT_PEAK_RAD_S)) jolt = true;
        }
        double rise = end - start;
        Verdict v = Verdict.GOOD;
        String why = null;
        if (seconds < REDO_SECONDS) {
            v = Verdict.REDO;
            why = "The phone stopped reading partway through. Keep Apollo open for the whole 90 seconds.";
        } else if (Double.isNaN(rise) || rise < NO_WARMING_C) {
            v = Verdict.REDO;
            why = "The phone barely warmed, so it wasn't against your forehead. Rest your forehead on the middle of the screen and try again.";
        } else if (drop > CONTACT_LOST_C) {
            v = Verdict.REDO;
            why = "The phone came away from your forehead partway through. Keep it in contact for the full 90 seconds.";
        } else if (moving >= REDO_MOVING_S) {
            v = Verdict.REDO;
            why = String.format(Locale.US, "The phone was moving for %d seconds. Rest your head and keep still, then try again.", moving);
        } else if (jolt) {
            v = Verdict.ROUGH;
            why = "The phone was bumped partway through, so this reading is rough.";
        } else if (moving >= ROUGH_MOVING_S) {
            v = Verdict.ROUGH;
            why = String.format(Locale.US, "The phone moved for %d seconds, so this reading is rough.", moving);
        } else if (seconds < MIN_SECONDS) {
            v = Verdict.ROUGH;
            why = "Some seconds of the reading were missed, so it's rough.";
        }
        return new ReadingCheck(v, why, moving, seconds, rise, drop, worst);
    }

    /**
     * The same from a run's raw CSV (front sensors plus the _gyro / _gyropeak rows logged during contact). Null when
     * the file doesn't have this phone model's front sensors (other phones name them differently): no judgement then.
     */
    static ReadingCheck of(File csv) throws IOException {
        FeverForest.Run run = FeverForest.Run.read(csv);
        return run == null ? null : of(run.t, run.front, run.dur, run.motion);
    }

    /** Merges a pre-start problem (the phone still warming by itself) into a good reading. */
    ReadingCheck orRough(String startProblem) {
        if (verdict != Verdict.GOOD || startProblem == null) return this;
        return new ReadingCheck(Verdict.ROUGH, startProblem, movingS, seconds, rise, drop, worstRadS);
    }

}
