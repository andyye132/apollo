package edu.uw.ubicomp.apolloproto;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Flights climbed, counted the way Apple and Google Health do: about 3 m (10 ft) of altitude gained on foot.
 * Altitude comes from the barometer; a climb only counts while the step detector says you are walking, so an
 * elevator ride, a drive up a hill with the phone in a pocket, or the weather moving the pressure don't add
 * flights (the reference level just follows them). Plain Java, so CoreCheck can test it.
 *
 * Pressure samples are processed LAG_MS late, so the step events of the same moment (delivered in batches by
 * the sensor hub) are in by then, and "walking" can look a few seconds ahead as well as back: the first steps of
 * a climb count from the first step, not from when enough steps have piled up.
 */
final class FloorCounter {
    static final double FLOOR_M = 3.0;            // one flight (~10 ft)
    static final double SMOOTH_S = 4.0;           // altitude smoothing time constant (BMP380 noise ~0.13 m)
    static final long WALK_HALF_WINDOW_MS = 10_000;
    static final int WALK_STEPS = 6;              // steps within ±10 s: walking (stairs run ~1.5 steps a second)
    static final double JUMP_M = 0.9;             // altitude change within ~1 s faster than any stair climb: a door, the
                                                  // air conditioning, or the phone in a squeezed pocket; not a flight
    static final long LAG_MS = 40_000;

    int up, down;   // flights climbed and descended

    private double smooth = Double.NaN, anchor = Double.NaN;
    private long lastT = -1;
    private final ArrayDeque<double[]> pending = new ArrayDeque<>();   // {t ms, hPa} waiting for their steps
    private final ArrayDeque<Long> steps = new ArrayDeque<>();
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();    // {t, raw altitude} over the last ~1.5 s

    /** A step from the step detector (wall-clock ms). */
    void step(long tMs) {
        steps.add(tMs);
    }

    /** A barometer reading (wall-clock ms). Nothing is counted until {@link #flush}. */
    void pressure(long tMs, double hpa) {
        pending.add(new double[]{tMs, hpa});
    }

    /** Processes readings older than LAG_MS before {@code nowMs}; returns the flights climbed among them. */
    int flush(long nowMs) {
        int before = up;
        while (!pending.isEmpty() && pending.peekFirst()[0] <= nowMs - LAG_MS) {
            double[] p = pending.pollFirst();
            process((long) p[0], p[1]);
        }
        // Steps older than any reading still to come aren't needed again.
        long keepFrom = (pending.isEmpty() ? nowMs - LAG_MS : (long) pending.peekFirst()[0]) - WALK_HALF_WINDOW_MS;
        while (!steps.isEmpty() && steps.peekFirst() < keepFrom) steps.pollFirst();
        return up - before;
    }

    /** Processes everything now (tests, or when the day ends). */
    int flushAll() {
        return flush(Long.MAX_VALUE);
    }

    private void process(long t, double hpa) {
        double raw = altitude(hpa);
        if (Double.isNaN(smooth)) {
            smooth = anchor = raw;
            lastT = t;
            recent.add(new double[]{t, raw});
            return;
        }
        // A jump faster than a person climbs moves the reference with it, so it can't add or remove a flight.
        recent.add(new double[]{t, raw});
        while (recent.size() > 1 && t - recent.peekFirst()[0] > 1500) recent.pollFirst();
        double[] old = recent.peekFirst();
        if (old != null && t - old[0] >= 500 && Math.abs(raw - old[1]) > JUMP_M) {
            double jump = raw - old[1];
            smooth += jump;
            anchor += jump;
            recent.clear();
            recent.add(new double[]{t, raw});
        }
        double dt = Math.max(0, (t - lastT) / 1000.0);
        lastT = t;
        smooth += (1 - Math.exp(-dt / SMOOTH_S)) * (raw - smooth);

        if (!walking(t)) {   // standing, riding or driving: altitude changes don't count
            anchor = smooth;
            return;
        }
        while (smooth - anchor >= FLOOR_M) {
            up++;
            anchor += FLOOR_M;
        }
        while (anchor - smooth >= FLOOR_M) {
            down++;
            anchor -= FLOOR_M;
        }
    }

    private boolean walking(long t) {
        int n = 0;
        for (Iterator<Long> it = steps.iterator(); it.hasNext(); ) {
            long s = it.next();
            if (s > t + WALK_HALF_WINDOW_MS) break;
            if (s >= t - WALK_HALF_WINDOW_MS && ++n >= WALK_STEPS) return true;
        }
        return false;
    }

    /** Standard-atmosphere altitude, m (only differences matter). */
    static double altitude(double hpa) {
        return 44330.0 * (1 - Math.pow(hpa / 1013.25, 1 / 5.255));
    }
}
