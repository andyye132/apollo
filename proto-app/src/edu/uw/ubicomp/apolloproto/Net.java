package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Care tab's only way onto the internet: cached GETs and product photos. What goes out is search words
 * and a location rounded to about a kilometre; readings and history never do.
 *
 * A text request answers from the disk cache while it's fresh, else the network, else a stale copy, else
 * the snapshot bundled in assets (tools/care_seed.py), so the tab works on bad hackathon Wi-Fi too. Photos
 * likewise come from the disk cache, then the snapshot's bundled copies, then the store's site.
 */
final class Net {
    static final String UA = "ApolloProto/0.2 (UW DubHacks health prototype)";
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    private static final String TAG = "ApolloNet";

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final ExecutorService OSM = Executors.newSingleThreadExecutor();   // Nominatim: one request a second
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastOsm;
    private static final LruCache<String, Bitmap> PHOTOS = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Net() {
    }

    /** A text answer: `body` is null when nothing (network, cache or snapshot) had it; `live` when it just came from the network. */
    interface Text {
        void got(String body, boolean live);
    }

    /** GET `url` as text, answered on the main thread. `seed` names a bundled snapshot in assets (or null). */
    static void text(Context c, String url, long ttlMs, String seed, Text done) {
        fetch(c.getApplicationContext(), url, ttlMs, seed, false, done);
    }

    /** As text(), for OpenStreetMap's Nominatim: its policy allows one request a second from an app. */
    static void osm(Context c, String url, long ttlMs, Text done) {
        fetch(c.getApplicationContext(), url, ttlMs, null, true, done);
    }

    private static void fetch(Context app, String url, long ttlMs, String seed, boolean osm, Text done) {
        (osm ? OSM : IO).execute(() -> {
            File cached = new File(dir(app, "net"), hash(url));
            String body = null;
            boolean live = false;
            if (cached.exists() && System.currentTimeMillis() - cached.lastModified() < ttlMs) body = read(cached);
            String snapshot = body == null && seed != null ? asset(app, seed) : null;
            if (snapshot != null && System.currentTimeMillis() - fetchedMs(snapshot) < ttlMs) body = snapshot;
            if (body == null) {
                try {
                    if (osm) {
                        long wait = lastOsm + 1100 - System.currentTimeMillis();
                        if (wait > 0) Thread.sleep(wait);
                        lastOsm = System.currentTimeMillis();
                    }
                    body = new String(get(url), StandardCharsets.UTF_8);
                    live = true;
                    write(cached, body.getBytes(StandardCharsets.UTF_8));
                } catch (IOException | InterruptedException e) {
                    Log.w(TAG, "offline answer for " + url + ": " + e);
                    if (cached.exists()) body = read(cached);   // stale beats nothing
                    if (body == null) body = snapshot;
                }
            }
            String b = body;
            boolean l = live;
            MAIN.post(() -> done.got(b, l));
        });
    }

    /** A product photo into `v` (fades in), cached in memory and on disk; `v` keeps its placeholder if it fails. */
    static void photo(ImageView v, String url, int sizePx) {
        if (url == null || url.isEmpty()) return;
        v.setTag(url);
        Bitmap hit = PHOTOS.get(url);
        if (hit != null) {
            v.setImageBitmap(hit);
            return;
        }
        Context app = v.getContext().getApplicationContext();
        bitmap(app, url, sizePx, b -> {
            if (b == null || !url.equals(v.getTag())) return;
            v.setImageBitmap(b);
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(180).start();
        });
    }

    /** A picture (photo or map tile) decoded to about `sizePx` across, on the main thread; null if it can't be had. */
    static void bitmap(Context app, String url, int sizePx, Consumer<Bitmap> done) {
        Bitmap hit = PHOTOS.get(url);
        if (hit != null) {
            done.accept(hit);
            return;
        }
        IO.execute(() -> {
            Bitmap b = null;
            try {
                File f = new File(dir(app, "img"), hash(url));
                byte[] bytes = f.exists() ? Files.readAllBytes(f.toPath()) : bundled(app, "care/img/" + hash(url) + ".jpg");
                if (bytes == null) {
                    bytes = get(url);
                    write(f, bytes);
                }
                b = decode(bytes, sizePx);
                if (b != null) PHOTOS.put(url, b);
            } catch (IOException e) {
                Log.w(TAG, "no picture from " + url + ": " + e);
            }
            Bitmap out = b;
            MAIN.post(() -> done.accept(out));
        });
    }

    private static Bitmap decode(byte[] bytes, int sizePx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= sizePx && o.outHeight / (sample * 2) >= sizePx) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(12000);
        http.setRequestProperty("User-Agent", UA);
        http.setRequestProperty("Accept-Language", "en-US");
        try {
            int code = http.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = http.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            http.disconnect();
        }
    }

    // ---- Files ----------------------------------------------------------------------------------

    private static File dir(Context app, String name) {
        File d = new File(app.getCacheDir(), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        } catch (IOException ignored) {
        }
    }

    /** A photo shipped with the app (tools/care_seed.py saves the snapshot's), or null. */
    private static byte[] bundled(Context app, String name) {
        try (InputStream in = app.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Is the phone on a network that reaches the internet? (Assumes so if it can't tell.) */
    static boolean online(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException e) {
            return true;
        }
    }

    private static String asset(Context app, String name) {
        try (InputStream in = app.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static String hash(String s) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** When a snapshot was taken ("fetched_ms"), 0 if it doesn't say. */
    private static long fetchedMs(String json) {
        try {
            return new JSONObject(json).optLong("fetched_ms");
        } catch (Exception e) {
            return 0;
        }
    }
}
