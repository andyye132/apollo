package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The countdown ring from the design boards: a soft track, a coral arc for what's left, and the time
 * in the middle with a caption under it. Works on the light canvas and on the dark "hold still" screen.
 */
final class RingView extends FrameLayout {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final TextView value, caption;
    private float fraction = 1;

    RingView(Context c, boolean dark) {
        super(c);
        setWillNotDraw(false);
        float w = Ui.dp(c, 9);
        for (Paint p : new Paint[]{track, arc}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(dark ? 0x26FFFFFF : Ui.CARD_2);
        arc.setColor(Ui.CORAL);
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER);
        value = Ui.styled(c, "", 60, dark ? 0xFFFFFFFF : Ui.TEXT, 500);
        value.setGravity(Gravity.CENTER);
        caption = Ui.styled(c, "", 13, dark ? 0x99FFFFFF : Ui.MUTED, 400);
        caption.setGravity(Gravity.CENTER);
        col.addView(value);
        col.addView(caption);
        addView(col, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /** `left` of `total`: the arc shows what's left; the value is the time left as m:ss. */
    void setTime(double left, double total, String captionText) {
        fraction = (float) Math.max(0, Math.min(1, left / total));
        int s = (int) Math.ceil(Math.max(0, left));
        value.setText(String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60));
        caption.setText(captionText);
        invalidate();
    }

    /** A plain value instead of a time (e.g. "~8" minutes to settle). */
    void setValue(String v, float fractionLeft, String captionText) {
        fraction = Math.max(0, Math.min(1, fractionLeft));
        value.setText(v);
        caption.setText(captionText);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        int side = Math.min(size, Ui.dp(getContext(), 230));
        super.onMeasure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = track.getStrokeWidth() / 2 + 2;
        r.set(inset, inset, getWidth() - inset, getHeight() - inset);
        c.drawArc(r, 0, 360, false, track);
        if (fraction > 0) c.drawArc(r, -90, 360 * fraction, false, arc);
    }
}
