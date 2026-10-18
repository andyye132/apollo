package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.ImageFormat;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

/**
 * Rear camera + torch for fingertip/thumb PPG (from ppg-probe). Delivers, per frame, the centre Y/U/V
 * means and a 6x4 grid of tile means. Once skin covers half the view for 2 s it switches to manual
 * exposure, steps exposure down until the skin tiles' red isn't clipped (auto-exposure leaves a red
 * scene clipped on the Pixel 4), then fixes it; `fixed` marks frames after that.
 */
final class PpgCamera {
    static final int COLS = 6, ROWS = 4, TILES = COLS * ROWS;          // analysis + CSV grid
    static final int FINE_COLS = 16, FINE_ROWS = 12, FINE = FINE_COLS * FINE_ROWS;   // coverage grid
    static final int PREVIEW_STEP = 4;                                   // preview = every 4th pixel

    static final class Frame {
        long sensorNs, epochMs, exposureNs;
        float gyroMax = Float.NaN, accelMax = Float.NaN;   // phone motion since the previous frame, filled in by the screen
        int iso, sensorOrientation, previewW, previewH;
        double y, u, v, liveRed;
        double coverage;          // fraction of FINE cells that are (smoothed) skin
        double[] ty, tv;          // COLS x ROWS tile means
        boolean[] fineSkin, fineBright;
        int[] preview;            // ARGB, sensor orientation, previewW x previewH
        boolean fixed;
    }

    interface Listener {
        void onFrame(Frame f);

        void onCameraError(String message);
    }

    private final Context context;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest.Builder request;
    private Range<Long> expRange;
    private int sensorOrientation = 90;
    private volatile long exposureNs;
    private volatile int iso;
    private final boolean[] skinMask = new boolean[TILES];
    private final double[] skinEma = new double[FINE], brightEma = new double[FINE];
    private boolean emaSeen;
    private boolean manual;
    private ExposureControl exposure;
    private long fullSinceNs = -1, partialSinceNs = -1;
    private int manualIso, adjustFrames;

    PpgCamera(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void start() {
        thread = new HandlerThread("ppg-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            open();
        } catch (CameraAccessException | SecurityException e) {
            listener.onCameraError("Camera error: " + e.getMessage());
        }
    }

    void stop() {
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (reader != null) reader.close();
        session = null;
        camera = null;
        reader = null;
        if (thread != null) thread.quitSafely();
    }

    private void open() throws CameraAccessException {
        CameraManager cm = context.getSystemService(CameraManager.class);
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
            listener.onCameraError("This phone has no rear camera with a flash");
            return;
        }
        expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (so != null) sensorOrientation = so;
        Size size = pickSize(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
        Range<Integer> fps = pickFps(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
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
                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                    device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession s) {
                                    session = s;
                                    repeat();
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession s) {
                                    listener.onCameraError("Camera session failed");
                                }
                            }, handler);
                } catch (CameraAccessException e) {
                    listener.onCameraError("Camera error: " + e.getMessage());
                }
            }

            @Override
            public void onDisconnected(CameraDevice device) {
                device.close();
            }

            @Override
            public void onError(CameraDevice device, int error) {
                device.close();
                listener.onCameraError("Camera error " + error);
            }
        }, handler);
    }

    private static Size pickSize(StreamConfigurationMap map) {
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (s.getWidth() < 320) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : map.getOutputSizes(ImageFormat.YUV_420_888)[0];
    }

    private static Range<Integer> pickFps(Range<Integer>[] ranges) {
        Range<Integer> best = ranges[0];
        for (Range<Integer> r : ranges) {
            if (r.getLower() == 30 && r.getUpper() == 30) return r;
            if (r.getUpper() > best.getUpper() || (r.getUpper().equals(best.getUpper()) && r.getLower() > best.getLower())) best = r;
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
            listener.onCameraError("Camera error: " + e.getMessage());
        }
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) return;
        Frame f = new Frame();
        try {
            f.sensorNs = img.getTimestamp();
            f.epochMs = System.currentTimeMillis();
            int w = img.getWidth(), h = img.getHeight();
            Image.Plane[] p = img.getPlanes();
            f.y = centreMean(p[0], w, h);
            f.u = centreMean(p[1], w / 2, h / 2);
            f.v = centreMean(p[2], w / 2, h / 2);
            f.ty = gridMeans(p[0], w, h, COLS, ROWS);
            f.tv = gridMeans(p[2], w / 2, h / 2, COLS, ROWS);
            double[] fy = gridMeans(p[0], w, h, FINE_COLS, FINE_ROWS), fv = gridMeans(p[2], w / 2, h / 2, FINE_COLS, FINE_ROWS);
            smoothCoverage(f, fy, fv);
            preview(f, p, w, h);
        } finally {
            img.close();
        }

        // Exposure is tuned only on a (90%) covered lens, so a half-covered start can't set it wrong.
        if (f.coverage >= 0.9) {
            partialSinceNs = -1;
            if (fullSinceNs < 0) fullSinceNs = f.sensorNs;
            if (!manual && f.sensorNs - fullSinceNs > 500_000_000L) {
                for (int k = 0; k < TILES; k++) skinMask[k] = isSkin(f.ty[k], f.tv[k]);
                startManual(f.sensorNs);
            }
        } else {
            fullSinceNs = -1;
            if (f.coverage < 0.6) {
                if (partialSinceNs < 0) partialSinceNs = f.sensorNs;
                if (manual && f.sensorNs - partialSinceNs > 1_000_000_000L) stopManual();
            } else {
                partialSinceNs = -1;
            }
        }
        if (manual) adjustExposure(f);

        f.liveRed = f.y + 1.402 * (f.v - 128);
        if (manual) {
            double s = 0;
            int n = 0;
            for (int k = 0; k < TILES; k++) {
                if (skinMask[k]) {
                    s += f.ty[k] + 1.402 * (f.tv[k] - 128);
                    n++;
                }
            }
            if (n > 0) f.liveRed = s / n;
        }
        f.exposureNs = exposureNs;
        f.iso = iso;
        f.sensorOrientation = sensorOrientation;
        f.fixed = manual && !exposure.adjusting;
        listener.onFrame(f);
    }

    static boolean isSkin(double y, double v) {
        return ExposureControl.isSkin(y, v);
    }

    /** Per-cell skin / too-bright flags, smoothed over ~3 frames so edge cells don't flicker. */
    private void smoothCoverage(Frame f, double[] fy, double[] fv) {
        f.fineSkin = new boolean[FINE];
        f.fineBright = new boolean[FINE];
        int skin = 0;
        for (int k = 0; k < FINE; k++) {
            double s = isSkin(fy[k], fv[k]) ? 1 : 0, b = s > 0 && fv[k] > PpgAnalysis.V_CLIPPED ? 1 : 0;
            skinEma[k] = emaSeen ? 0.7 * skinEma[k] + 0.3 * s : s;
            brightEma[k] = emaSeen ? 0.7 * brightEma[k] + 0.3 * b : b;
            f.fineSkin[k] = skinEma[k] >= 0.5;
            f.fineBright[k] = brightEma[k] >= 0.5;
            if (f.fineSkin[k]) skin++;
        }
        emaSeen = true;
        f.coverage = (double) skin / FINE;
    }

    /** Small ARGB preview (every PREVIEW_STEP-th pixel), YUV -> RGB, still in sensor orientation. */
    private static void preview(Frame f, Image.Plane[] p, int w, int h) {
        int pw = w / PREVIEW_STEP, ph = h / PREVIEW_STEP;
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yrs = p[0].getRowStride(), yps = p[0].getPixelStride();
        int crs = p[1].getRowStride(), cps = p[1].getPixelStride(), vrs = p[2].getRowStride(), vps = p[2].getPixelStride();
        int[] out = new int[pw * ph];
        for (int j = 0; j < ph; j++) {
            int sy = j * PREVIEW_STEP;
            for (int i = 0; i < pw; i++) {
                int sx = i * PREVIEW_STEP;
                int yy = yb.get(sy * yrs + sx * yps) & 0xFF;
                int uu = (ub.get((sy / 2) * crs + (sx / 2) * cps) & 0xFF) - 128;
                int vv = (vb.get((sy / 2) * vrs + (sx / 2) * vps) & 0xFF) - 128;
                int r = clamp(yy + (int) (1.402 * vv)), g = clamp(yy - (int) (0.344 * uu + 0.714 * vv)), b = clamp(yy + (int) (1.772 * uu));
                out[j * pw + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        f.preview = out;
        f.previewW = pw;
        f.previewH = ph;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private void startManual(long ts) {
        manual = true;
        long lo = expRange != null ? expRange.getLower() : 100_000L;
        long hi = Math.min(expRange != null ? expRange.getUpper() : 30_000_000L, 30_000_000L);
        exposure = new ExposureControl(lo, hi);
        exposure.start(exposureNs > 0 ? exposureNs : 5_000_000L, ts);
        manualIso = iso > 0 ? iso : 100;
        adjustFrames = 0;
        applyManual();
    }

    private void applyManual() {
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exp);
        request.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso);
        request.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L);
        request.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        repeat();
    }

    private void stopManual() {
        manual = false;
        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        request.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        repeat();
    }

    /** ~5x a second, also after locking (see ExposureControl); the skin tiles are re-read whenever it locks. */
    private void adjustExposure(Frame f) {
        if (++adjustFrames % 6 != 0) return;
        boolean wasAdjusting = exposure.adjusting;
        if (exposure.check(f.ty, f.tv, f.sensorNs)) applyManual();
        if (wasAdjusting && !exposure.adjusting) {
            for (int k = 0; k < TILES; k++) skinMask[k] = isSkin(f.ty[k], f.tv[k]);
        }
    }

    private static double centreMean(Image.Plane plane, int w, int h) {
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

    /** Means of a cols x rows grid over the whole plane, every 2nd pixel; row-major, sensor orientation. */
    private static double[] gridMeans(Image.Plane plane, int w, int h, int cols, int rows) {
        ByteBuffer b = plane.getBuffer();
        int rs = plane.getRowStride(), ps = plane.getPixelStride();
        long[] sum = new long[cols * rows];
        int[] n = new int[cols * rows];
        for (int yy = 0; yy < h; yy += 2) {
            int row = yy * rows / h;
            for (int xx = 0; xx < w; xx += 2) {
                int k = row * cols + xx * cols / w;
                sum[k] += b.get(yy * rs + xx * ps) & 0xFF;
                n[k]++;
            }
        }
        double[] out = new double[cols * rows];
        for (int k = 0; k < out.length; k++) out[k] = n[k] > 0 ? (double) sum[k] / n[k] : 0;
        return out;
    }
}
