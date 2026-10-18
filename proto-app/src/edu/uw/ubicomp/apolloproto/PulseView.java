package edu.uw.ubicomp.apolloproto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * The pulse, drawn calmly: the last few seconds of the fingertip signal as a smooth coral line over a soft
 * fill, a small dot on each beat, no axes. It draws itself in from left to right when shown.
 */
final class PulseView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            dot = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), area = new Path();
    private double[] t = new double[0], y = new double[0];
    private int[] beats = new int[0];
    private float reveal = 1;

    PulseView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2.5f));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Ui.CORAL);
        dot.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        ring.setColor(Ui.CORAL_DARK);
    }

    /** The signal (times in seconds, values in any unit) and the indices of its beats; shows the last `seconds`. */
    void set(double[] times, double[] values, int[] peaks, double seconds) {
        int n = times.length, from = 0;
        double end = n > 0 ? times[n - 1] : 0;
        while (from < n && times[from] < end - seconds) from++;
        t = new double[n - from];
        y = new double[n - from];
        System.arraycopy(times, from, t, 0, t.length);
        System.arraycopy(values, from, y, 0, y.length);
        int k = 0;
        int[] keep = new int[peaks.length];
        for (int p : peaks) if (p >= from) keep[k++] = p - from;
        beats = java.util.Arrays.copyOf(keep, k);
        ValueAnimator in = ValueAnimator.ofFloat(0, 1).setDuration(1100);
        in.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        in.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t.length < 2) return;
        float w = getWidth(), h = getHeight(), pad = Ui.dp(getContext(), 8);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : y) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        double span = Math.max(1e-9, hi - lo), t0 = t[0], t1 = t[t.length - 1];
        path.reset();
        area.reset();
        int last = Math.max(1, (int) ((t.length - 1) * reveal));
        for (int i = 0; i <= last; i++) {
            float x = (float) (pad + (w - 2 * pad) * (t[i] - t0) / (t1 - t0));
            float yy = (float) (pad + (h - 2 * pad) * (1 - (y[i] - lo) / span));
            if (i == 0) {
                path.moveTo(x, yy);
                area.moveTo(x, h);
                area.lineTo(x, yy);
            } else {
                path.lineTo(x, yy);
                area.lineTo(x, yy);
            }
            if (i == last) area.lineTo(x, h);
        }
        area.close();
        fill.setShader(new LinearGradient(0, pad, 0, h, 0x40E8705E, 0x00E8705E, Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        c.drawPath(path, line);
        float r = Ui.dp(getContext(), 3.5f);
        for (int b : beats) {
            if (b > last) continue;
            float x = (float) (pad + (w - 2 * pad) * (t[b] - t0) / (t1 - t0));
            float yy = (float) (pad + (h - 2 * pad) * (1 - (y[b] - lo) / span));
            c.drawCircle(x, yy, r, dot);
            c.drawCircle(x, yy, r, ring);
        }
    }
}
