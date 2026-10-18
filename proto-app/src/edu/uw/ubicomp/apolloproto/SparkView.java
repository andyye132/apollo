package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** A small coral sparkline (recent readings on a Today card), with a soft fade under the line. */
final class SparkView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), area = new Path();
    private double[] values = new double[0];

    SparkView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Ui.CORAL);
    }

    void setValues(double[] v) {
        values = v == null ? new double[0] : v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (values.length < 2) return;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : values) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (hi - lo < 6) {   // keep small wobbles small
            double mid = (hi + lo) / 2;
            lo = mid - 3;
            hi = mid + 3;
        }
        float w = getWidth(), h = getHeight(), pad = line.getStrokeWidth();
        path.reset();
        area.reset();
        for (int i = 0; i < values.length; i++) {
            float x = pad + (w - 2 * pad) * i / (values.length - 1);
            float y = (float) (pad + (h - 2 * pad) * (1 - (values[i] - lo) / (hi - lo)));
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, h);
                area.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        area.lineTo(w - pad, h);
        area.close();
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x33E8705E, 0x00E8705E, Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        c.drawPath(path, line);
    }
}
