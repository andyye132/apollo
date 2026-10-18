package edu.uw.ubicomp.apolloproto;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Feeds Bluetooth packets captured on the Mac (arrival_ms,hex) through the app's WatchDecoder. */
public class WatchCheck {
    public static void main(String[] args) throws IOException {
        WatchDecoder dec = new WatchDecoder();
        int packets = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
            r.readLine();
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split(",");
                byte[] b = new byte[p[1].length() / 2];
                for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(p[1].substring(2 * i, 2 * i + 2), 16);
                dec.onPacket(b, Long.parseLong(p[0]));
                packets++;
            }
        }
        double[][] s = dec.between(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
        int n = s[0].length;
        long off = dec.offsetMs();
        System.out.printf("java:   %d packets, %d samples; first (%d, %.0f, %.0f, %.0f); last (%d, %.0f, %.0f, %.0f)%n", packets, n,
                (long) s[0][0] - off, s[1][0], s[2][0], s[3][0], (long) s[0][n - 1] - off, s[1][n - 1], s[2][n - 1], s[3][n - 1]);
        System.out.printf("java:   offset %d ms, %.1f%% lost in transit%n", off, 100 * dec.dropFraction());
    }
}
