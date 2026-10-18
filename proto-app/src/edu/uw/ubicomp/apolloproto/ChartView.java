package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal line chart: series, dot markers, shaded x-bands, three labelled gridlines. */
final class ChartView extends View {
    static final class Series {
        final double[] x, y;
        final int color;
        final boolean dots;
        final String label;

        Series(double[] x, double[] y, int color, boolean dots, String label) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.dots = dots;
            this.label = label;
        }
    }

    private final List<Series> series = new ArrayList<>();
    private final List<double[]> bands = new ArrayList<>();   // {x0, x1, color}
    private final List<String> bandLabels = new ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String title = "", unit = "", xUnit = "s";
    private double minSpan = 0;
    private boolean hideY;
    private final float dp;

    ChartView(Context c, int heightDp) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setMinimumHeight(Ui.dp(c, heightDp));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(Ui.LINE);
        grid.setStrokeWidth(dp);
        label.setColor(Ui.MUTED);
        label.setTextSize(11 * dp);
    }

    ChartView title(String t, String unit) {
        this.title = t;
        this.unit = unit;
        return this;
    }

    ChartView xUnit(String u) {
        xUnit = u;
        return this;
    }

    ChartView minSpan(double s) {
        minSpan = s;
        return this;
    }

    ChartView hideYAxis() {
        hideY = true;
        return this;
    }

    ChartView add(Series s) {
        series.add(s);
        return this;
    }

    ChartView band(double x0, double x1, int color, String text) {
        bands.add(new double[]{x0, x1, color});
        bandLabels.add(text);
        return this;
    }

    void clear() {
        series.clear();
        bands.clear();
        bandLabels.clear();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), getSuggestedMinimumHeight());
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float left = hideY ? 4 * dp : 44 * dp, right = w - 6 * dp, top = 26 * dp, bottom = h - 20 * dp;
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(Ui.MUTED);
        c.drawText(title + (unit.isEmpty() ? "" : "  (" + unit + ")"), 0, 14 * dp, label);

        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Series s : series) {
            for (int i = 0; i < s.x.length; i++) {
                if (Double.isNaN(s.y[i]) || Double.isInfinite(s.y[i])) continue;
                x0 = Math.min(x0, s.x[i]);
                x1 = Math.max(x1, s.x[i]);
                y0 = Math.min(y0, s.y[i]);
                y1 = Math.max(y1, s.y[i]);
            }
        }
        if (x0 > x1) {
            label.setTextAlign(Paint.Align.CENTER);
            c.drawText("no data", w / 2, h / 2, label);
            return;
        }
        if (x1 == x0) x1 = x0 + 1;
        double span = Math.max(y1 - y0, minSpan);
        if (span == 0) span = 1;
        double mid = (y0 + y1) / 2;
        y0 = mid - span * 0.56;
        y1 = mid + span * 0.56;

        for (int i = 0; i < bands.size(); i++) {
            double[] b = bands.get(i);
            fill.setColor((int) b[2]);
            float bx0 = (float) (left + (b[0] - x0) / (x1 - x0) * (right - left));
            float bx1 = (float) (left + (b[1] - x0) / (x1 - x0) * (right - left));
            c.drawRect(Math.max(left, bx0), top, Math.min(right, bx1), bottom, fill);
            label.setTextAlign(Paint.Align.CENTER);
            c.drawText(bandLabels.get(i), (Math.max(left, bx0) + Math.min(right, bx1)) / 2, top + 13 * dp, label);
        }
        label.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i < 3; i++) {
            double v = y0 + (y1 - y0) * (0.1 + 0.4 * i);
            float y = (float) (bottom - (v - y0) / (y1 - y0) * (bottom - top));
            c.drawLine(left, y, right, y, grid);
            if (!hideY) c.drawText(fmt(v, span), left - 6 * dp, y + 4 * dp, label);
        }
        label.setTextAlign(Paint.Align.LEFT);
        c.drawText(String.format(Locale.US, "%.0f %s", x0, xUnit), left, h - 4 * dp, label);
        label.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(Locale.US, "%.0f %s", x1, xUnit), right, h - 4 * dp, label);

        float legendX = right;
        for (int si = series.size() - 1; si >= 0; si--) {
            Series s = series.get(si);
            line.setColor(s.color);
            fill.setColor(s.color);
            if (s.dots) {
                for (int i = 0; i < s.x.length; i++) {
                    if (Double.isNaN(s.y[i])) continue;
                    c.drawCircle(px(s.x[i], x0, x1, left, right), py(s.y[i], y0, y1, top, bottom), 2.6f * dp, fill);
                }
            } else {
                line.setStrokeWidth(2 * dp);
                path.reset();
                boolean started = false;
                for (int i = 0; i < s.x.length; i++) {
                    if (Double.isNaN(s.y[i])) {
                        started = false;
                        continue;
                    }
                    float x = px(s.x[i], x0, x1, left, right), y = py(s.y[i], y0, y1, top, bottom);
                    if (!started) path.moveTo(x, y);
                    else path.lineTo(x, y);
                    started = true;
                }
                c.drawPath(path, line);
            }
            if (s.label != null) {
                label.setTextAlign(Paint.Align.RIGHT);
                label.setColor(s.color);
                c.drawText(s.label, legendX, 14 * dp, label);
                legendX -= label.measureText(s.label) + 12 * dp;
                label.setColor(Ui.MUTED);
            }
        }
    }

    private static float px(double x, double x0, double x1, float l, float r) {
        return (float) (l + (x - x0) / (x1 - x0) * (r - l));
    }

    private static float py(double y, double y0, double y1, float t, float b) {
        return (float) (b - (y - y0) / (y1 - y0) * (b - t));
    }

    private static String fmt(double v, double span) {
        return span < 2 ? String.format(Locale.US, "%.2f", v) : span < 20 ? String.format(Locale.US, "%.1f", v)
                : String.format(Locale.US, "%.0f", v);
    }
}
