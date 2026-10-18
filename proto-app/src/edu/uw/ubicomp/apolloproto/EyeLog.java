package edu.uw.ubicomp.apolloproto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Passive eye tracking's saved history: every one-minute row EyeTrackingService wrote, across all sessions
 * (eyetrack_passive_*.csv in the app's files folder). Plain Java, so it can be checked on the Mac.
 */
final class EyeLog {
    static final String PREFIX = "eyetrack_passive_";

    /** One minute of tracking. Rates are per minute of face time; NaN when the face wasn't seen long enough. */
    static final class Minute {
        long start, end;
        double faceS, blinkRate = Double.NaN, shiftRate = Double.NaN, threshold = Double.NaN;
        int blinks, shifts;
        String read = "";

        boolean rated() {
            return !Double.isNaN(shiftRate) && !Double.isNaN(blinkRate) && faceS >= 20;
        }

        boolean restless() {
            return "Restless".equals(read);
        }
    }

    /** One day's summary. Rates are weighted by face time. */
    static final class Day {
        long dayStart;
        int ratedMinutes, restlessMinutes;
        double faceMinutes, blinkRate = Double.NaN, shiftRate = Double.NaN;
    }

    private EyeLog() {
    }

    /** Every saved minute, oldest first. */
    static List<Minute> load(File dir) {
        List<Minute> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".csv"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try (Reader r = new FileReader(f)) {
                out.addAll(parse(r));
            } catch (IOException ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** Rows of one summary file (header first; the jitter/threshold columns are optional, added Oct 18). */
    static List<Minute> parse(Reader in) throws IOException {
        List<Minute> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(in);
        String header = r.readLine();
        if (header == null) return out;
        List<String> cols = java.util.Arrays.asList(header.split(","));
        int iStart = cols.indexOf("minute_start_ms"), iEnd = cols.indexOf("minute_end_ms"), iFace = cols.indexOf("face_s");
        int iBlinks = cols.indexOf("blinks"), iShifts = cols.indexOf("gaze_shifts");
        int iBr = cols.indexOf("blinks_per_face_min"), iSr = cols.indexOf("shifts_per_face_min");
        int iRead = cols.indexOf("read"), iThr = cols.indexOf("shift_threshold");
        for (String line; (line = r.readLine()) != null; ) {
            String[] c = line.split(",");
            if (c.length < 10) continue;
            try {
                Minute m = new Minute();
                m.start = Long.parseLong(c[iStart]);
                m.end = Long.parseLong(c[iEnd]);
                m.faceS = Double.parseDouble(c[iFace]);
                m.blinks = Integer.parseInt(c[iBlinks]);
                m.shifts = Integer.parseInt(c[iShifts]);
                m.blinkRate = num(c[iBr]);
                m.shiftRate = num(c[iSr]);
                m.read = c[iRead];
                if (iThr >= 0 && iThr < c.length) m.threshold = num(c[iThr]);
                out.add(m);
            } catch (RuntimeException ignored) {   // a half-written last row
            }
        }
        return out;
    }

    static List<Minute> parse(String csv) throws IOException {
        return parse(new StringReader(csv));
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;   // "NaN" parses; anything else becomes NaN too
        }
    }

    /** Midnight of the day containing ms (local time). */
    static long dayStart(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Minutes that started on the same day as {@code dayMs}. */
    static List<Minute> on(List<Minute> all, long dayMs) {
        long d0 = dayStart(dayMs);
        List<Minute> out = new ArrayList<>();
        for (Minute m : all) if (dayStart(m.start) == d0) out.add(m);
        return out;
    }

    static Day summarize(List<Minute> minutes, long dayMs) {
        Day d = new Day();
        d.dayStart = dayStart(dayMs);
        double face = 0, blinks = 0, shifts = 0;
        for (Minute m : minutes) {
            d.faceMinutes += m.faceS / 60;
            if (!m.rated()) continue;
            d.ratedMinutes++;
            if (m.restless()) d.restlessMinutes++;
            face += m.faceS;
            blinks += m.blinks;
            shifts += m.shifts;
        }
        if (face > 0) {
            d.blinkRate = blinks * 60 / face;
            d.shiftRate = shifts * 60 / face;
        }
        return d;
    }

    /** The last {@code days} days, oldest first, today last (days with no tracking have zero rated minutes). */
    static List<Day> days(List<Minute> all, long nowMs, int days) {
        List<Day> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        for (int i = 0; i < days; i++) {
            long d = c.getTimeInMillis();
            out.add(summarize(on(all, d), d));
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    /** Shift rates of the most recent rated minutes, oldest first: the baseline a new session starts from. */
    static List<Double> recentShiftRates(List<Minute> all, int n, long sinceMs) {
        List<Double> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < n; i--) {
            Minute m = all.get(i);
            if (m.start < sinceMs) break;
            // only minutes from the adaptive detector (they log its threshold): the old fixed one counted far fewer shifts
            if (m.rated() && !Double.isNaN(m.threshold)) out.add(0, m.shiftRate);
        }
        return out;
    }
}
