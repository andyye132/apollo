package edu.uw.ubicomp.apolloproto;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * Decodes the WILD watch's Bluetooth notifications and keeps the last few minutes of samples. Plain Java
 * (no Android types), so proto-app/check.sh can feed it packets captured on the Mac.
 *
 * Protocol (firmware/wild_zephyr/src/main.c): 200 samples/s, each sent as two 20-byte notifications.
 * Even count = IMU packet [count u16][watch uptime ms u32][accel xyz, gyro xyz s16], odd count = PPG
 * packet [count u16][red, ir, green u24 for sensor 0, then sensor 1], all big-endian; 0xFFFFFF = missing.
 *
 * Clock: each sample keeps the watch's own time. Phone time = watch time + the smallest
 * (arrival - watch) difference seen on this connection, i.e. the radio's minimum delivery delay, so
 * Bluetooth's delivery jitter (hundreds of ms) doesn't move the samples.
 */
final class WatchDecoder {
    static final int CAPACITY = 200 * 240;   // 4 minutes of samples

    private final long[] watchMs = new long[CAPACITY];
    private final int[] red = new int[CAPACITY], ir = new int[CAPACITY], green = new int[CAPACITY];
    private final short[] az = new short[CAPACITY];
    private int head, size;
    private long offsetMs = Long.MAX_VALUE;
    private int pendingCount = -1, lastImuCount = -1;
    private long pendingWatchMs, pendingArrival;
    private short pendingAz;
    private long received, dropped;

    static int u16(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    static long u32(byte[] d, int i) {
        return ((long) u16(d, i) << 16) | u16(d, i + 2);
    }

    static int u24(byte[] d, int i) {
        int v = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
        return v == 0xFFFFFF ? -1 : v;
    }

    /** A new connection: new clock offset and loss counters (old samples stay in the buffer). */
    synchronized void newConnection() {
        offsetMs = Long.MAX_VALUE;
        pendingCount = -1;
        lastImuCount = -1;
        received = 0;
        dropped = 0;
    }

    synchronized void onPacket(byte[] d, long arrivalMs) {
        if (d == null || d.length < 20) return;
        int count = u16(d, 0);
        if ((count & 1) == 0) {
            if (lastImuCount >= 0) {
                int gap = ((count - lastImuCount) & 0xFFFF) / 2 - 1;   // samples missing since the last one
                if (gap > 0 && gap < 2000) dropped += gap;
            }
            lastImuCount = count;
            pendingCount = count;
            pendingWatchMs = u32(d, 2);
            pendingAz = (short) u16(d, 10);
            pendingArrival = arrivalMs;
        } else if (pendingCount >= 0 && count == ((pendingCount + 1) & 0xFFFF)) {
            offsetMs = Math.min(offsetMs, pendingArrival - pendingWatchMs);
            watchMs[head] = pendingWatchMs;
            red[head] = u24(d, 2);
            ir[head] = u24(d, 5);
            green[head] = u24(d, 8);
            az[head] = pendingAz;
            head = (head + 1) % CAPACITY;
            size = Math.min(size + 1, CAPACITY);
            received++;
            pendingCount = -1;
        }
    }

    /** Share of samples lost in transit on this connection, from gaps in the packet counter. */
    synchronized double dropFraction() {
        long total = received + dropped;
        return total == 0 ? 0 : (double) dropped / total;
    }

    synchronized long received() {
        return received;
    }

    synchronized long offsetMs() {
        return offsetMs;
    }

    /** Samples between two phone-clock times: {phoneMs, red, ir, green} as parallel arrays, time-ordered. */
    synchronized double[][] between(long fromPhoneMs, long toPhoneMs) {
        int n = 0;
        int[] idx = new int[size];
        for (int k = 0; k < size; k++) {
            int i = (head - size + k + CAPACITY) % CAPACITY;
            long t = watchMs[i] + offsetMs;
            if (t >= fromPhoneMs && t <= toPhoneMs) idx[n++] = i;
        }
        double[][] out = new double[4][n];
        for (int k = 0; k < n; k++) {
            int i = idx[k];
            out[0][k] = watchMs[i] + offsetMs;
            out[1][k] = red[i];
            out[2][k] = ir[i];
            out[3][k] = green[i];
        }
        return out;
    }

    /** Samples between two phone-clock times as CSV, for the Mac-side report. */
    synchronized void writeCsv(File f, long fromPhoneMs, long toPhoneMs) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(f))) {
            w.println("phone_ms,watch_ms,ppg0_red,ppg0_ir,ppg0_green,accel_z_raw");
            for (int k = 0; k < size; k++) {
                int i = (head - size + k + CAPACITY) % CAPACITY;
                long t = watchMs[i] + offsetMs;
                if (t < fromPhoneMs || t > toPhoneMs) continue;
                w.println(String.format(Locale.US, "%d,%d,%d,%d,%d,%d", t, watchMs[i], red[i], ir[i], green[i], az[i]));
            }
        }
    }
}
