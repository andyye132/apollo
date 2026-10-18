package edu.uw.ubicomp.apollo.eyetrack;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.WindowInsets;

/** Draws the analysed camera frame (already upright and mirrored), the eye landmarks, and the metrics. */
final class OverlayView extends View {
    private final Paint eyePaint = paint(Color.rgb(80, 220, 120)), irisPaint = paint(Color.rgb(255, 80, 80));
    private final Paint text = paint(Color.WHITE), panel = paint(Color.argb(160, 0, 0, 0));
    private final Paint boxPaint = paint(Color.WHITE), gazePaint = paint(Color.rgb(255, 200, 0));

    private Bitmap frame;
    private float[] xs, ys;
    private String[] lines = {"starting camera..."};
    private double gazeX = Double.NaN, gazeY = Double.NaN;
    private boolean closed;
    private int insetTop, insetBottom;   // status and navigation bars (the app draws edge to edge)

    OverlayView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        text.setTextSize(42);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
        insetTop = bars.top;
        insetBottom = bars.bottom;
        return insets;
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    /** Called from the MediaPipe thread with a matching frame and landmarks (xs/ys may be null: no face). */
    synchronized void show(Bitmap frame, float[] xs, float[] ys, String[] lines, double gazeX, double gazeY, boolean closed) {
        this.frame = frame;
        this.xs = xs;
        this.ys = ys;
        this.lines = lines;
        this.gazeX = gazeX;
        this.gazeY = gazeY;
        this.closed = closed;
        postInvalidate();
    }

    synchronized void message(String... lines) {
        this.lines = lines;
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        float vw = getWidth(), vh = getHeight();
        if (frame != null) {
            float s = Math.min(vw / frame.getWidth(), vh / frame.getHeight());
            float dx = (vw - frame.getWidth() * s) / 2, dy = (vh - frame.getHeight() * s) / 2;
            c.drawBitmap(frame, null, new RectF(dx, dy, dx + frame.getWidth() * s, dy + frame.getHeight() * s), null);
            if (xs != null) {
                for (int i : EyeMetrics.EYE_POINTS) c.drawCircle(dx + xs[i] * s, dy + ys[i] * s, 5, eyePaint);
                for (int i : EyeMetrics.IRIS_POINTS) c.drawCircle(dx + xs[i] * s, dy + ys[i] * s, i == 468 || i == 473 ? 8 : 4, irisPaint);
            }
        }

        float lh = text.getTextSize() * 1.25f;
        float top = insetTop + 12;
        c.drawRect(0, 0, vw, top + 24 + lh * lines.length, panel);
        for (int i = 0; i < lines.length; i++) c.drawText(lines[i], 24, top + 24 + lh * (i + 0.8f), text);

        // gaze box: x 0..1 across, y -0.25..0.25 down (eye widths); the dot freezes while the eyes are closed
        float bw = vw * 0.35f, bh = bw * 0.5f, bx = vw - bw - 24, by = vh - bh - 48 - insetBottom;
        c.drawRect(bx, by, bx + bw, by + bh, panel);
        c.drawRect(bx, by, bx + bw, by + bh, boxPaint);
        if (!Double.isNaN(gazeX)) {
            float gx = bx + (float) clamp(gazeX, 0, 1) * bw;
            float gy = by + (float) clamp((gazeY + 0.25) / 0.5, 0, 1) * bh;
            gazePaint.setAlpha(closed ? 90 : 255);
            c.drawCircle(gx, gy, 16, gazePaint);
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
