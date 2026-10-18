package edu.uw.ubicomp.apolloproto;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;
import android.view.View;

/**
 * The daily check-in as a ring of four arcs, one per test, filling left to right: temperature top left, heart
 * rate bottom left, calm sensing bottom right, breath top right. Coral when done today, pale coral for the next
 * one, sand for later. The arcs grow in, in that order's direction, when shown. Apollo sits in the middle (a
 * separate view laid over it).
 */
final class CheckInRing extends View {
    private static final int NEXT = 0xFFF6C2B6, LATER = 0xFFEAE3D9;
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int[] colors = {LATER, LATER, LATER, LATER};
    private float grow = 1;

    CheckInRing(Context c) {
        super(c);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Which tests are done today, and the one that's next (-1: none). */
    void set(boolean[] done, int next) {
        for (int i = 0; i < 4; i++) colors[i] = done[i] ? Ui.CORAL : i == next ? NEXT : LATER;
        ValueAnimator in = ValueAnimator.ofFloat(0, 1).setDuration(750);
        in.setInterpolator(new DecelerateInterpolator());
        in.addUpdateListener(a -> {
            grow = (float) a.getAnimatedValue();
            invalidate();
        });
        in.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), stroke = Ui.dp(getContext(), 14), pad = stroke / 2 + Ui.dp(getContext(), 2);
        r.set(pad, pad, w - pad, h - pad);
        arc.setStrokeWidth(stroke);
        float radius = r.width() / 2, cap = (float) Math.toDegrees(stroke / 2 / radius), gap = 14;
        for (int i = 0; i < 4; i++) {   // each quarter less the gap and the round caps' overhang
            arc.setColor(colors[i]);
            float from = 270 - i * 90 - gap / 2 - cap;   // quarter i's leading end: 12, 9, 6, then 3 o'clock
            c.drawArc(r, from, -(90 - gap - 2 * cap) * grow, false, arc);   // sweeping anticlockwise
        }
    }
}
