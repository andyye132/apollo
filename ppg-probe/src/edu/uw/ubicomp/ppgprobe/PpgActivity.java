package edu.uw.ubicomp.ppgprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;

/**
 * Fingertip PPG smoke test: rear camera + torch, logs the mean Y/U/V of the centre of every frame
 * plus a 6x4 grid of tile means (Y and V), with the sensor timestamp, exposure and ISO, to
 * ppg_<ms>.csv in the app's external files dir. Shows the live pulse waveform with a rough
 * autocorrelation heart rate. The grid shows how much of the view is skin (coverage), so partial
 * covers and other sites (thumb, palm) can be analysed on just the skin tiles.
 */
public class PpgActivity extends Activity {
    private static final int COLS = 6, ROWS = 4, TILES = COLS * ROWS;
    private static final int RING = 2048;          // ~68 s at 30 fps
    private static final double FS = 30;           // resample rate for the live estimate
    private static final double ESTIMATE_S = 10;   // live estimate window
    private static final double SHOW_S = 6;        // waveform shown on screen

    private final Object lock = new Object();
    private final double[] ringT = new double[RING];   // seconds, sensor clock
    private final double[] ringR = new double[RING];   // approximate red
    private int ringHead, ringCount;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder request;
    private PrintWriter out;
    private boolean running;

    private volatile long exposureNs;
    private volatile int iso;
    private volatile double coverage;
    private final boolean[] skinMask = new boolean[TILES];   // frozen when exposure goes manual; drives the live signal
    // Manual exposure: auto-exposure aims at average brightness, which leaves a red-only scene clipped in
    // red (seen on the Pixel 4: V ~240). Once skin covers the lens we take over and step exposure down.
    private Range<Long> expRange;
    private boolean manual, adjusting;
    private long manualExp, manualSinceNs;
    private int manualIso, adjustFrames, steadyChecks;
    private long fingerSinceNs = -1, noFingerSinceNs = -1;
    private int framesSinceEstimate;
    private volatile String status = "Starting camera";
    private volatile double bpm = Double.NaN, fps = Double.NaN, lastY, lastV;
    private WaveView wave;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        wave = new WaveView(this);
        setContentView(wave);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else status = "Camera permission denied";
    }

    @Override
    protected void onPause() {
        stop();
        super.onPause();
    }

    private void start() {
        if (running) return;
        running = true;
        try {
            File f = new File(getExternalFilesDir(null), "ppg_" + System.currentTimeMillis() + ".csv");
            out = new PrintWriter(new FileWriter(f), true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        StringBuilder header = new StringBuilder("sensor_ns,epoch_ms,y,u,v,exposure_ns,iso,ae_locked,coverage");
        for (int k = 0; k < TILES; k++) header.append(",ty").append(k);
        for (int k = 0; k < TILES; k++) header.append(",tv").append(k);
        out.println(header);
        thread = new HandlerThread("ppg-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            openCamera();
        } catch (CameraAccessException | SecurityException e) {
            status = "Camera error: " + e.getMessage();
        }
    }

    private void stop() {
        if (!running) return;
        running = false;
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (reader != null) reader.close();
        session = null;
        camera = null;
        reader = null;
        thread.quitSafely();
        out.close();
    }

    private void openCamera() throws CameraAccessException {
        CameraManager cm = getSystemService(CameraManager.class);
        String id = null;
        CameraCharacteristics chars = null;
        for (String c : cm.getCameraIdList()) {
            CameraCharacteristics ch = cm.getCameraCharacteristics(c);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                    && Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                id = c;
                chars = ch;
                break;
            }
        }
        if (id == null) {
            status = "No rear camera with a flash";
            return;
        }
        expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Size size = pickSize(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
        Range<Integer> fpsRange = pickFps(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 4);
        reader.setOnImageAvailableListener(this::onImage, handler);

        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                camera = device;
                try {
                    request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    request.addTarget(reader.getSurface());
                    request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                    device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession s) {
                                    session = s;
                                    status = String.format(Locale.US, "Camera %s, %dx%d, %s fps",
                                            device.getId(), size.getWidth(), size.getHeight(), fpsRange);
                                    repeat();
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession s) {
                                    status = "Camera session failed";
                                }
                            }, handler);
                } catch (CameraAccessException e) {
                    status = "Camera error: " + e.getMessage();
                }
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                device.close();
            }

            @Override
            public void onError(CameraDevice device, int error) {
                status = "Camera error " + error;
                device.close();
            }
        }, handler);
    }

    /** Smallest YUV size at least 320 px wide: plenty for a mean, cheap to process. */
    private static Size pickSize(StreamConfigurationMap map) {
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (s.getWidth() < 320) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : map.getOutputSizes(ImageFormat.YUV_420_888)[0];
    }

    /** A fixed 30 fps range if offered, otherwise the range with the highest ceiling (and highest floor). */
    private static Range<Integer> pickFps(Range<Integer>[] ranges) {
        Range<Integer> best = ranges[0];
        for (Range<Integer> r : ranges) {
            if (r.getLower() == 30 && r.getUpper() == 30) return r;
            if (r.getUpper() > best.getUpper()
                    || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower())) best = r;
        }
        return best;
    }

    private void repeat() {
        if (session == null) return;
        try {
            session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    Long e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Integer i = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    if (e != null) exposureNs = e;
                    if (i != null) iso = i;
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            status = "Camera error: " + e.getMessage();
        }
    }

    /** Skin has covered the lens for 2 s: switch to manual exposure, starting from what AE chose. */
    private void startManual(long ts) {
        manual = true;
        adjusting = true;
        manualSinceNs = ts;
        manualExp = exposureNs > 0 ? exposureNs : 5_000_000L;
        manualIso = iso > 0 ? iso : 100;
        adjustFrames = 0;
        steadyChecks = 0;
        applyManual();
    }

    private void applyManual() {
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExp);
        request.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso);
        request.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L);
        request.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        repeat();
    }

    private void stopManual() {
        manual = false;
        adjusting = false;
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        repeat();
    }

    /**
     * While adjusting, about 5 times a second: darken if the skin tiles' red is clipped (V > 222),
     * brighten if it's dim (red < 130), stop once it's been in range for 3 checks or after 4 s.
     * Exposure is then fixed, so the recording has no steps in it.
     */
    private void adjustExposure(long ts, double[] ty, double[] tv) {
        if (!adjusting || ++adjustFrames % 6 != 0) return;
        double[] v = new double[TILES], red = new double[TILES];
        int n = 0;
        for (int k = 0; k < TILES; k++) {
            if (!skinMask[k]) continue;
            v[n] = tv[k];
            red[n] = ty[k] + 1.402 * (tv[k] - 128);
            n++;
        }
        if (n == 0) return;
        java.util.Arrays.sort(v, 0, n);
        java.util.Arrays.sort(red, 0, n);
        double medV = v[n / 2], medRed = red[n / 2];
        long lo = expRange != null ? expRange.getLower() : 100_000L;
        long hi = Math.min(expRange != null ? expRange.getUpper() : 30_000_000L, 30_000_000L);
        if (medV > 222 && manualExp > lo) {
            manualExp = Math.max(lo, (long) (manualExp * 0.6));
            steadyChecks = 0;
            applyManual();
        } else if (medRed < 130 && manualExp < hi) {
            manualExp = Math.min(hi, (long) (manualExp * 1.5));
            steadyChecks = 0;
            applyManual();
        } else if (++steadyChecks >= 3 || ts - manualSinceNs > 4_000_000_000L) {
            adjusting = false;
        }
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        try {
            long ts = img.getTimestamp();
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane[] p = img.getPlanes();
            double y = planeMean(p[0], w, h), u = planeMean(p[1], w / 2, h / 2), v = planeMean(p[2], w / 2, h / 2);
            double[] ty = new double[TILES], tv = new double[TILES];
            tileMeans(p[0], w, h, ty);
            tileMeans(p[2], w / 2, h / 2, tv);
            // Torch-lit skin is deep red: strong V (Cr), not black.
            int skin = 0;
            for (int k = 0; k < TILES; k++) if (isSkin(ty[k], tv[k])) skin++;
            coverage = (double) skin / TILES;
            if (out != null) {
                // ae_locked = exposure fixed (manual and done adjusting): the analysis starts after this flips to 1.
                StringBuilder line = new StringBuilder(String.format(Locale.US, "%d,%d,%.3f,%.3f,%.3f,%d,%d,%d,%.3f",
                        ts, System.currentTimeMillis(), y, u, v, exposureNs, iso, manual && !adjusting ? 1 : 0, coverage));
                for (double t : ty) line.append(String.format(Locale.US, ",%.2f", t));
                for (double t : tv) line.append(String.format(Locale.US, ",%.2f", t));
                out.println(line);
            }
            lastY = y;
            lastV = v;

            boolean finger = coverage >= 0.5;
            if (finger) {
                noFingerSinceNs = -1;
                if (fingerSinceNs < 0) fingerSinceNs = ts;
                if (!manual && ts - fingerSinceNs > 2_000_000_000L) {
                    for (int k = 0; k < TILES; k++) skinMask[k] = isSkin(ty[k], tv[k]);
                    startManual(ts);
                }
            } else {
                fingerSinceNs = -1;
                if (noFingerSinceNs < 0) noFingerSinceNs = ts;
                if (manual && ts - noFingerSinceNs > 1_000_000_000L) stopManual();
            }
            if (manual) adjustExposure(ts, ty, tv);
            double fingerS = finger ? (ts - fingerSinceNs) / 1e9 : 0;
            status = !finger ? String.format(Locale.US, "Cover the camera and the flash (%.0f%% covered)", 100 * coverage)
                    : fingerS < 6 ? "Skin detected, hold still" : "Measuring";

            // Live signal: the skin tiles frozen at AE lock, so the tile set doesn't jump between frames.
            double red = y + 1.402 * (v - 128);
            if (manual) {
                double sum = 0;
                int n = 0;
                for (int k = 0; k < TILES; k++) {
                    if (skinMask[k]) {
                        sum += ty[k] + 1.402 * (tv[k] - 128);
                        n++;
                    }
                }
                if (n > 0) red = sum / n;
            }
            synchronized (lock) {
                ringT[ringHead] = ts / 1e9;
                ringR[ringHead] = red;
                ringHead = (ringHead + 1) % RING;
                ringCount = Math.min(ringCount + 1, RING);
            }
            if (++framesSinceEstimate >= 15) {
                framesSinceEstimate = 0;
                bpm = fingerS >= 6 ? estimateBpm(Math.min(fingerS, ESTIMATE_S)) : Double.NaN;
            }
            wave.postInvalidate();
        } finally {
            img.close();
        }
    }

    /** Mean of the centre half of a plane, every 2nd pixel. */
    private static double planeMean(Image.Plane plane, int w, int h) {
        ByteBuffer b = plane.getBuffer();
        int rs = plane.getRowStride(), ps = plane.getPixelStride();
        long sum = 0;
        int n = 0;
        for (int yy = h / 4; yy < h * 3 / 4; yy += 2) {
            for (int xx = w / 4; xx < w * 3 / 4; xx += 2) {
                sum += b.get(yy * rs + xx * ps) & 0xFF;
                n++;
            }
        }
        return (double) sum / n;
    }

    private static boolean isSkin(double y, double v) {
        return v - 128 > 15 && y > 15;
    }

    /** Means of a COLS x ROWS grid over the whole plane, every 2nd pixel. */
    private static void tileMeans(Image.Plane plane, int w, int h, double[] out) {
        ByteBuffer b = plane.getBuffer();
        int rs = plane.getRowStride(), ps = plane.getPixelStride();
        long[] sum = new long[TILES];
        int[] n = new int[TILES];
        for (int yy = 0; yy < h; yy += 2) {
            int row = yy * ROWS / h;
            for (int xx = 0; xx < w; xx += 2) {
                int k = row * COLS + xx * COLS / w;
                sum[k] += b.get(yy * rs + xx * ps) & 0xFF;
                n[k]++;
            }
        }
        for (int k = 0; k < TILES; k++) out[k] = n[k] > 0 ? (double) sum[k] / n[k] : 0;
    }

    /** Copies the last `seconds` of the ring, oldest first: {t, r}. */
    private double[][] snapshot(double seconds) {
        synchronized (lock) {
            int n = ringCount;
            if (n == 0) return new double[][]{new double[0], new double[0]};
            double tEnd = ringT[(ringHead - 1 + RING) % RING];
            int take = 0;
            while (take < n && tEnd - ringT[(ringHead - 1 - take + 2 * RING) % RING] <= seconds) take++;
            double[] t = new double[take], r = new double[take];
            for (int i = 0; i < take; i++) {
                int k = (ringHead - take + i + 2 * RING) % RING;
                t[i] = ringT[k];
                r[i] = ringR[k];
            }
            return new double[][]{t, r};
        }
    }

    /** Resample to 30 Hz, remove the 1 s moving average, flip so blood volume points up. */
    private static double[] pulse(double[] t, double[] r) {
        int n = t.length;
        if (n < 3) return new double[0];
        int m = (int) ((t[n - 1] - t[0]) * FS);
        double[] x = new double[m];
        for (int i = 0, j = 0; i < m; i++) {
            double ti = t[0] + i / FS;
            while (j < n - 2 && t[j + 1] < ti) j++;
            double a = (ti - t[j]) / (t[j + 1] - t[j]);
            x[i] = r[j] + a * (r[j + 1] - r[j]);
        }
        double[] cum = new double[m + 1];
        for (int i = 0; i < m; i++) cum[i + 1] = cum[i] + x[i];
        int half = (int) (FS / 2);
        double[] d = new double[m];
        for (int i = 0; i < m; i++) {
            int lo = Math.max(0, i - half), hi = Math.min(m, i + half + 1);
            d[i] = -(x[i] - (cum[hi] - cum[lo]) / (hi - lo));
        }
        return d;
    }

    /** Autocorrelation peak between 40 and 200 bpm; NaN when the signal isn't periodic enough. */
    private double estimateBpm(double seconds) {
        double[][] s = snapshot(seconds);
        double[] t = s[0];
        if (t.length > 1) fps = (t.length - 1) / (t[t.length - 1] - t[0]);
        double[] d = pulse(t, s[1]);
        int m = d.length, minLag = (int) (FS * 60 / 200), maxLag = (int) (FS * 60 / 40);
        if (m < 2 * maxLag) return Double.NaN;
        double e0 = 0;
        for (double v : d) e0 += v * v;
        if (e0 == 0) return Double.NaN;
        double[] ac = new double[maxLag + 2];
        for (int lag = minLag - 1; lag <= maxLag + 1; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < m; i++) sum += d[i] * d[i + lag];
            ac[lag] = sum / e0;
        }
        int best = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1] && (best < 0 || ac[lag] > ac[best])) best = lag;
        }
        if (best < 0 || ac[best] < 0.3) return Double.NaN;
        double y0 = ac[best - 1], y1 = ac[best], y2 = ac[best + 1], den = y0 - 2 * y1 + y2;
        double shift = den == 0 ? 0 : 0.5 * (y0 - y2) / den;
        return 60 * FS / (best + shift);
    }

    private class WaveView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        WaveView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            float dp = getResources().getDisplayMetrics().density;
            line.setColor(Color.rgb(240, 90, 70));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3 * dp);
            big.setColor(Color.WHITE);
            big.setTextSize(72 * dp);
            big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            small.setColor(Color.rgb(170, 180, 190));
            small.setTextSize(15 * dp);
        }

        @Override
        protected void onDraw(Canvas c) {
            float dp = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight();
            double b = bpm;
            c.drawText(Double.isNaN(b) ? "-- bpm" : String.format(Locale.US, "%.0f bpm", b), 24 * dp, h * 0.22f, big);
            c.drawText(status, 24 * dp, h * 0.22f + 32 * dp, small);
            c.drawText(String.format(Locale.US, "%.1f fps · exposure %.2f ms · ISO %d · %s · skin %.0f%%",
                    fps, exposureNs / 1e6, iso, !manual ? "auto exposure" : adjusting ? "adjusting" : "exposure fixed",
                    100 * coverage), 24 * dp, h - 32 * dp, small);

            double[][] s = snapshot(SHOW_S);
            double[] d = pulse(s[0], s[1]);
            if (d.length < 2) return;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double v : d) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            double span = Math.max(hi - lo, 1e-6);
            float top = h * 0.40f, bottom = h * 0.80f;
            path.reset();
            for (int i = 0; i < d.length; i++) {
                float x = (float) (w * i / (SHOW_S * FS));
                float y = (float) (bottom - (d[i] - lo) / span * (bottom - top));
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            c.drawPath(path, line);
        }
    }
}
