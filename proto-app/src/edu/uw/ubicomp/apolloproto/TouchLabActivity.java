package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Magical Touch (CHI EA '26) "Ball Rescue" on a stock phone. Each finger, thumb or palm on the screen
 * becomes gravel in a 52 x 78 field (rebuilt from Android's touch ellipses: the raw capacitance the paper
 * used needs vendor firmware). Guide the falling balls into the basket and collect stars; a firmer, wider
 * touch makes denser gravel and a harder bounce.
 */
public class TouchLabActivity extends Activity {
    private TextView hud;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.BG);
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 6));
        top.addView(Ui.homeLink(this));
        hud = Ui.text(this, "", 13, Ui.MUTED, false);
        hud.setSingleLine(true);   // a wrapping HUD would resize the board mid-game
        hud.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(hud, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        col.addView(top);
        TextView how = Ui.text(this, "Magical Touch · catch the falling balls with your fingers or palm and steer them "
                + "into the basket. Press harder or wider for a bouncier surface.", 13, Ui.MUTED, false);
        how.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        col.addView(how);
        col.addView(new Board(this), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(col);
    }

    private final class Board extends View {
        private final Map<Integer, float[]> touches = new LinkedHashMap<>();   // id -> x, y, major, minor, orientation, pressure
        private final Paint gravel = new Paint(Paint.ANTI_ALIAS_FLAG), ball = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG), basket = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path starPath = new Path();
        private final RectF rect = new RectF();
        private TouchWorld world;
        private long lastNs;
        private float lastPressure, lastMajor;
        private String hudText = "";

        Board(Context c) {
            super(c);
            setBackgroundColor(Ui.BG);
            ball.setColor(Ui.CORAL);
            shine.setColor(0xAAFFFFFF);
            basket.setColor(Ui.STEPS);
            basket.setStyle(Paint.Style.STROKE);
            basket.setStrokeWidth(Ui.dp(c, 3));
            star.setColor(0xFFE9A93B);   // gold
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(c, 1));
            ring.setColor(0x33000000);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) {
                touches.clear();
                return true;
            }
            for (int i = 0; i < e.getPointerCount(); i++) {
                touches.put(e.getPointerId(i), new float[]{e.getX(i), e.getY(i), e.getTouchMajor(i), e.getTouchMinor(i),
                        e.getOrientation(i), e.getPressure(i)});
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                touches.remove(e.getPointerId(e.getActionIndex()));
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (world == null || world.width != w || world.height != h) {
                world = new TouchWorld(w, h);
                world.minTouch = 8 * getResources().getDisplayMetrics().xdpi / 25.4;   // a fingertip, ~8 mm
                lastNs = SystemClock.elapsedRealtimeNanos();
            }
            long now = SystemClock.elapsedRealtimeNanos();
            double dt = Math.min(0.05, (now - lastNs) / 1e9);
            lastNs = now;

            world.clearField();
            for (float[] t : touches.values()) {
                // Intensity from pressure (Android reports 0..1, often derived from contact size), never below 0.35.
                double intensity = Math.max(0.35, Math.min(1.0, 0.25 + 0.9 * t[5]));
                world.addTouch(t[0], t[1], t[2], t[3], t[4], intensity);
                lastPressure = t[5];
                lastMajor = t[2];
            }
            world.smoothField();
            for (double left = dt; left > 1e-6; left -= 1 / 120.0) world.step(Math.min(left, 1 / 120.0), true);

            // Gravel: dots per cell, more and brighter where the field is denser.
            double cell = world.cell;
            for (int r = 0; r < TouchWorld.ROWS; r++) {
                for (int col = 0; col < TouchWorld.COLS; col++) {
                    float d = world.field[r * TouchWorld.COLS + col];
                    if (d < 0.04f) continue;
                    int dots = 1 + (int) (d * 4);
                    gravel.setColor(blend(0xFFE3D2B5, 0xFF9C7A4E, Math.min(1f, d)));   // light sand to packed sand
                    gravel.setAlpha((int) (90 + 165 * Math.min(1f, d)));
                    for (int k = 0; k < dots; k++) {
                        int hsh = (r * 73856093) ^ (col * 19349663) ^ (k * 83492791);
                        float ox = ((hsh & 0xFF) / 255f), oy = (((hsh >> 8) & 0xFF) / 255f);
                        c.drawCircle((float) ((col + ox) * cell), (float) ((r + oy) * cell), (float) (cell * 0.18 + cell * 0.12 * d), gravel);
                    }
                }
            }
            for (float[] t : touches.values()) c.drawCircle(t[0], t[1], (float) Math.max(t[2], world.minTouch) / 2, ring);

            rect.set((float) world.basketX0, (float) world.basketY - Ui.dp(getContext(), 18), (float) world.basketX1, h - Ui.dp(getContext(), 4));
            c.drawRoundRect(rect, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), basket);
            for (TouchWorld.Star s : world.stars) drawStar(c, (float) s.x, (float) s.y, (float) (cell * 1.4));
            for (TouchWorld.Ball b : world.balls) {
                float fade = (float) Math.max(0.35, 1 - b.freeFall / world.maxFreeFall);   // fades as its lifespan runs out
                ball.setAlpha((int) (255 * fade));
                c.drawCircle((float) b.x, (float) b.y, (float) b.r, ball);
                c.drawCircle((float) (b.x - b.r * 0.35), (float) (b.y - b.r * 0.35), (float) (b.r * 0.3), shine);
            }
            String status = String.format(Locale.US, "saved %d · lost %d · ★ %d · touch %.2f, %.0f px",
                    world.saved, world.lost, world.starsCollected, lastPressure, lastMajor);
            if (!status.equals(hudText)) {   // setText re-lays out the header, so only when it changed
                hudText = status;
                hud.setText(status);
            }
            postInvalidateOnAnimation();
        }

        private void drawStar(Canvas c, float x, float y, float r) {
            starPath.reset();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float rr = i % 2 == 0 ? r : r * 0.45f;
                float px = (float) (x + rr * Math.cos(a)), py = (float) (y + rr * Math.sin(a));
                if (i == 0) starPath.moveTo(px, py);
                else starPath.lineTo(px, py);
            }
            starPath.close();
            c.drawPath(starPath, star);
        }
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
