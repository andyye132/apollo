package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * The live camera feed with the uncovered parts of a 16 x 12 grid tinted red, and a big "% covered"
 * label; the frame turns green at 90% (enough to record). Kept deliberately plain so it reads at a
 * glance. Rotated by the sensor orientation like a camera preview, so it reads as looking through the
 * phone from the screen side: a gap on the left means slide left.
 */
final class CoverageView extends View {
    private static final int COLS = PpgCamera.FINE_COLS, ROWS = PpgCamera.FINE_ROWS, CELLS = PpgCamera.FINE;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private Bitmap bitmap;
    private boolean[] skin;
    private int orientation = 90;

    CoverageView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
    }

    void update(PpgCamera.Frame f) {
        if (orientation != f.sensorOrientation) {
            orientation = f.sensorOrientation;
            requestLayout();
        }
        skin = f.fineSkin;
        if (f.preview != null) {
            if (bitmap == null || bitmap.getWidth() != f.previewW || bitmap.getHeight() != f.previewH) {
                bitmap = Bitmap.createBitmap(f.previewW, f.previewH, Bitmap.Config.ARGB_8888);
            }
            bitmap.setPixels(f.preview, 0, f.previewW, 0, 0, f.previewW, f.previewH);
        }
        invalidate();
    }

    private boolean portrait() {
        return orientation == 90 || orientation == 270;
    }

    private int dispCols() {
        return portrait() ? ROWS : COLS;
    }

    private int dispRows() {
        return portrait() ? COLS : ROWS;
    }

    /** Sensor cell (col, row) -> display {col, row}, rotating clockwise by the sensor orientation. */
    private int[] toDisplay(int c, int r) {
        switch (orientation) {
            case 90:
                return new int[]{ROWS - 1 - r, c};
            case 180:
                return new int[]{COLS - 1 - c, ROWS - 1 - r};
            case 270:
                return new int[]{r, COLS - 1 - c};
            default:
                return new int[]{c, r};
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        float cell = 15 * dp;
        setMeasuredDimension((int) (dispCols() * cell), (int) (dispRows() * cell));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cell = w / dispCols();
        fill.setColor(0xFF000000);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        if (bitmap != null) {
            // The bitmap is in sensor orientation; rotate it the same way as the grid.
            c.save();
            c.clipRect(0, 0, w, h);
            c.translate(w / 2, h / 2);
            c.rotate(orientation);
            float bw = portrait() ? h : w, bh = portrait() ? w : h;
            r.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            c.drawBitmap(bitmap, null, r, bitmapPaint);
            c.restore();
        }
        int covered = 0;
        for (int k = 0; skin != null && k < CELLS; k++) {
            if (skin[k]) {
                covered++;
                continue;
            }
            int[] d = toDisplay(k % COLS, k / COLS);
            r.set(d[0] * cell, d[1] * cell, (d[0] + 1) * cell, (d[1] + 1) * cell);
            fill.setColor(0x73E5534B);   // not covered
            c.drawRect(r, fill);
        }
        double frac = skin == null ? 0 : (double) covered / CELLS;
        boolean ok = frac >= 0.9;
        stroke.setColor(ok ? Ui.GOOD : Ui.MUTED);   // green frame once it's covered enough to record
        stroke.setStrokeWidth(ok ? 4 * dp : dp);
        r.set(dp, dp, w - dp, h - dp);
        c.drawRoundRect(r, 10 * dp, 10 * dp, stroke);
        if (skin != null) {
            String label = String.format(Locale.US, ok ? "%.0f%% covered ✓" : "%.0f%% covered", Math.floor(100 * frac));
            label(c, label, w / 2, h - 14 * dp, ok ? Ui.GOOD : Ui.TEXT);
        }
    }

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Big centred text on a dark pill, readable over the camera image. */
    private void label(Canvas c, String s, float cx, float baseline, int color) {
        text.setTextSize(20 * dp);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        float tw = text.measureText(s);
        fill.setColor(0xB3000000);
        r.set(cx - tw / 2 - 12 * dp, baseline - 24 * dp, cx + tw / 2 + 12 * dp, baseline + 8 * dp);
        c.drawRoundRect(r, 16 * dp, 16 * dp, fill);
        text.setColor(color);
        c.drawText(s, cx, baseline, text);
    }

    /** Where the gap is, in words, from the centroid of the uncovered cells on the display grid. */
    String hint() {
        if (skin == null) return "Starting the camera…";
        int n = 0;
        double sx = 0, sy = 0;
        for (int k = 0; k < CELLS; k++) {
            if (skin[k]) continue;
            int[] d = toDisplay(k % COLS, k / COLS);
            sx += (d[0] + 0.5) / dispCols() - 0.5;
            sy += (d[1] + 0.5) / dispRows() - 0.5;
            n++;
        }
        if (n <= CELLS * 0.1) return "Good, that's covered enough. Hold still.";   // 90% is enough to record
        if (n > CELLS * 0.8) return "Cover the camera and the flash with your fingertip.";
        double x = sx / n, y = sy / n;
        String v = y < -0.12 ? "top" : y > 0.12 ? "bottom" : "", h = x < -0.12 ? "left" : x > 0.12 ? "right" : "";
        String where = v.isEmpty() && h.isEmpty() ? "around the edges" : "toward the " + v + (v.isEmpty() || h.isEmpty() ? "" : "-") + h;
        String move = (v.isEmpty() ? "" : v.equals("top") ? "up" : "down") + (v.isEmpty() || h.isEmpty() ? "" : " and ")
                + (h.isEmpty() ? "" : "to the " + h);
        return String.format(Locale.US, "%.0f%% uncovered, %s. %s", 100.0 * n / CELLS, where,
                move.isEmpty() ? "Flatten your finger so it covers the whole lens." : "Slide your finger " + move + ".");
    }
}
