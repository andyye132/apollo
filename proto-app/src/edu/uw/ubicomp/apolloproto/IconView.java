package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Line icons drawn as paths on a 24-unit grid (1.8-unit round strokes), in the style of the design
 * boards: home, measure, journal, trends, heart, thermo, steps, layers, gear, chevron, arrow, watch,
 * waves, play, check, plus, camera, phone, info, moon, back, care, pill, drop, eye, leaf, pin, search, send, call.
 */
final class IconView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private String name;

    IconView(Context c, String name, int color) {
        super(c);
        this.name = name;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidate();
    }

    void setIcon(String name) {
        this.name = name;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight(), h = getHeight() - getPaddingTop() - getPaddingBottom();
        float s = Math.min(w, h) / 24f;
        c.save();
        c.translate(getPaddingLeft() + (w - 24 * s) / 2, getPaddingTop() + (h - 24 * s) / 2);
        c.scale(s, s);
        p.setStrokeWidth(1.8f);
        path.reset();
        drawIcon(c);
        c.restore();
    }

    private void line(float... xy) {
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
    }

    private void circle(Canvas c, float x, float y, float rad) {
        c.drawCircle(x, y, rad, p);
    }

    private void dot(Canvas c, float x, float y, float rad) {
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.STROKE);
    }

    private void box(Canvas c, float l, float t, float rt, float b, float rad) {
        r.set(l, t, rt, b);
        c.drawRoundRect(r, rad, rad, p);
    }

    private void drawIcon(Canvas c) {
        switch (name) {
            case "home":
                line(4, 11, 12, 4.5f, 20, 11);
                line(6, 9.6f, 6, 19.5f, 18, 19.5f, 18, 9.6f);
                line(10, 19.5f, 10, 14.5f, 14, 14.5f, 14, 19.5f);
                break;
            case "measure":
                line(4, 8, 4, 5, 7, 5);
                line(17, 5, 20, 5, 20, 8);
                line(20, 16, 20, 19, 17, 19);
                line(7, 19, 4, 19, 4, 16);
                line(7.5f, 12, 10, 12, 11.5f, 9, 13, 15, 14.5f, 12, 16.5f, 12);
                break;
            case "journal":
                box(c, 5.5f, 3.5f, 18.5f, 20.5f, 2.4f);
                line(9, 8.5f, 15, 8.5f);
                line(9, 12, 15, 12);
                line(9, 15.5f, 12.5f, 15.5f);
                break;
            case "trends":
                line(4, 4, 4, 20, 20, 20);
                line(7.5f, 15, 10.5f, 11, 13.5f, 13.5f, 19, 7.5f);
                break;
            case "heart":
                path.moveTo(12, 19.5f);
                path.cubicTo(7, 15.6f, 3.5f, 12.6f, 3.5f, 9.1f);
                path.cubicTo(3.5f, 6.6f, 5.4f, 4.8f, 7.8f, 4.8f);
                path.cubicTo(9.6f, 4.8f, 11.1f, 5.9f, 12, 7.4f);
                path.cubicTo(12.9f, 5.9f, 14.4f, 4.8f, 16.2f, 4.8f);
                path.cubicTo(18.6f, 4.8f, 20.5f, 6.6f, 20.5f, 9.1f);
                path.cubicTo(20.5f, 12.6f, 17, 15.6f, 12, 19.5f);
                path.close();
                break;
            case "thermo":
                path.moveTo(10, 14.2f);
                path.lineTo(10, 5);
                path.arcTo(new RectF(10, 3, 14, 7), 180, 180);
                path.lineTo(14, 14.2f);
                path.arcTo(new RectF(8.4f, 13.7f, 15.6f, 20.9f), -57.2f, 294.4f);
                path.close();
                line(12, 15.5f, 12, 9.5f);
                break;
            case "steps":
                r.set(5, 5.5f, 9.5f, 13);
                c.drawOval(r, p);
                circle(c, 7.3f, 15.8f, 1.5f);
                r.set(14.5f, 8.5f, 19, 16);
                c.drawOval(r, p);
                circle(c, 16.8f, 18.8f, 1.5f);
                break;
            case "layers":
                line(12, 4, 20, 8.5f, 12, 13, 4, 8.5f, 12, 4);
                line(4, 12.5f, 12, 17, 20, 12.5f);
                line(4, 16.3f, 12, 20.8f, 20, 16.3f);
                break;
            case "gear": {
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * 2 * i / 16 + Math.PI / 16;
                    float rad = i % 2 == 0 ? 8.6f : 6.7f;
                    float x = 12 + (float) (rad * Math.cos(a)), y = 12 + (float) (rad * Math.sin(a));
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                circle(c, 12, 12, 2.8f);
                break;
            }
            case "chevron":
                line(9, 5.5f, 15.5f, 12, 9, 18.5f);
                break;
            case "back":
                line(15, 5.5f, 8.5f, 12, 15, 18.5f);
                break;
            case "arrow":
                line(5, 12, 19, 12);
                line(13, 6, 19, 12, 13, 18);
                break;
            case "watch":
                box(c, 6.5f, 6.5f, 17.5f, 17.5f, 3.2f);
                line(9, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f, 15, 6.5f);
                line(9, 17.5f, 9.5f, 20.5f, 14.5f, 20.5f, 15, 17.5f);
                line(12, 9.5f, 12, 12, 13.8f, 13.2f);
                break;
            case "waves":
                path.addArc(new RectF(2, 8, 10, 16), -50, 100);
                path.addArc(new RectF(-1.5f, 4.5f, 13.5f, 19.5f), -45, 90);
                path.addArc(new RectF(-5, 1, 17, 23), -40, 80);
                break;
            case "play":   // Ball rescue: a ball over gravel
                circle(c, 12, 8.5f, 3.4f);
                for (float x = 5; x <= 19; x += 3.5f) dot(c, x, 17 + (x % 2 == 0 ? 0 : 1.6f), 1.1f);
                break;
            case "check":
                line(5, 12.5f, 10, 17, 19, 7.5f);
                break;
            case "plus":
                line(12, 5, 12, 19);
                line(5, 12, 19, 12);
                break;
            case "camera":
                box(c, 3.5f, 7, 20.5f, 19, 2.6f);
                line(8.5f, 7, 10, 4.5f, 14, 4.5f, 15.5f, 7);
                circle(c, 12, 13, 3.3f);
                break;
            case "phone":
                box(c, 7, 3, 17, 21, 2.6f);
                line(10.5f, 18, 13.5f, 18);
                break;
            case "info":
                circle(c, 12, 12, 8.5f);
                line(12, 11, 12, 16.2f);
                dot(c, 12, 7.9f, 1.1f);
                break;
            case "moon":
                path.addArc(new RectF(4, 4, 20, 20), 60, 260);
                path.moveTo(4 + 8 + 8 * (float) Math.cos(Math.toRadians(60)), 12 + 8 * (float) Math.sin(Math.toRadians(60)));
                path.quadTo(9, 15, 14.5f, 4.4f);
                break;
            case "care":   // a soft first-aid cross
                line(9.5f, 4.5f, 14.5f, 4.5f, 14.5f, 9.5f, 19.5f, 9.5f, 19.5f, 14.5f, 14.5f, 14.5f, 14.5f, 19.5f,
                        9.5f, 19.5f, 9.5f, 14.5f, 4.5f, 14.5f, 4.5f, 9.5f, 9.5f, 9.5f, 9.5f, 4.5f);
                break;
            case "pill":
                c.save();
                c.rotate(-45, 12, 12);
                box(c, 7.5f, 3.5f, 16.5f, 20.5f, 4.5f);
                c.drawLine(7.5f, 12, 16.5f, 12, p);
                c.restore();
                break;
            case "drop":
                path.moveTo(12, 3.5f);
                path.cubicTo(12, 3.5f, 5.5f, 10.5f, 5.5f, 14.5f);
                path.cubicTo(5.5f, 18.2f, 8.4f, 20.5f, 12, 20.5f);
                path.cubicTo(15.6f, 20.5f, 18.5f, 18.2f, 18.5f, 14.5f);
                path.cubicTo(18.5f, 10.5f, 12, 3.5f, 12, 3.5f);
                path.close();
                break;
            case "eye":
                path.moveTo(2.8f, 12);
                path.quadTo(12, 2.5f, 21.2f, 12);
                path.quadTo(12, 21.5f, 2.8f, 12);
                path.close();
                circle(c, 12, 12, 3);
                break;
            case "leaf":
                path.moveTo(5, 19);
                path.cubicTo(5, 10, 10, 5, 19, 5);
                path.cubicTo(19, 14, 14, 19, 5, 19);
                path.close();
                line(5, 19, 13, 11);
                break;
            case "pin":
                path.moveTo(12, 21);
                path.cubicTo(12, 21, 5.5f, 14.8f, 5.5f, 9.8f);
                path.cubicTo(5.5f, 6.2f, 8.4f, 3.3f, 12, 3.3f);
                path.cubicTo(15.6f, 3.3f, 18.5f, 6.2f, 18.5f, 9.8f);
                path.cubicTo(18.5f, 14.8f, 12, 21, 12, 21);
                path.close();
                circle(c, 12, 9.8f, 2.4f);
                break;
            case "search":
                circle(c, 10.5f, 10.5f, 6);
                line(15, 15, 19.5f, 19.5f);
                break;
            case "send":
                line(12, 19, 12, 5.5f);
                line(6.5f, 11, 12, 5.5f, 17.5f, 11);
                break;
            case "call":
                path.moveTo(6.6f, 4);
                path.lineTo(9.4f, 4);
                path.lineTo(10.6f, 8.2f);
                path.lineTo(8.8f, 9.6f);
                path.cubicTo(9.9f, 11.9f, 12.1f, 14.1f, 14.4f, 15.2f);
                path.lineTo(15.8f, 13.4f);
                path.lineTo(20, 14.6f);
                path.lineTo(20, 17.4f);
                path.cubicTo(20, 18.8f, 18.8f, 20, 17.4f, 20);
                path.cubicTo(10.6f, 19.4f, 4.6f, 13.4f, 4, 6.6f);
                path.cubicTo(4, 5.2f, 5.2f, 4, 6.6f, 4);
                path.close();
                break;
            default:
                circle(c, 12, 12, 7);
        }
        c.drawPath(path, p);
    }
}
