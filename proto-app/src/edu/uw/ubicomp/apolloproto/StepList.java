package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Numbered steps for a screen: finished steps get a tick, the current one is highlighted, later ones are muted. */
final class StepList extends LinearLayout {
    private final List<TextView> dots = new ArrayList<>(), labels = new ArrayList<>();
    private final int accent;

    StepList(Context c, int accent, String... steps) {
        super(c);
        this.accent = accent;
        setOrientation(VERTICAL);
        setBackground(Ui.rounded(Ui.CARD, Ui.dp(c, 14)));
        setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 12));
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView dot = Ui.text(c, String.valueOf(i + 1), 13, Ui.TEXT, true);
            dot.setGravity(Gravity.CENTER);
            int size = Ui.dp(c, 24);
            row.addView(dot, new LayoutParams(size, size));
            TextView label = Ui.text(c, steps[i], 15, Ui.TEXT, false);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = Ui.dp(c, 12);
            row.addView(label, lp);
            row.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            addView(row);
            dots.add(dot);
            labels.add(label);
        }
        setCurrent(0);
    }

    /** Highlights step `index` (0-based); a value past the end marks every step done. */
    void setCurrent(int index) {
        Context c = getContext();
        for (int i = 0; i < dots.size(); i++) {
            TextView dot = dots.get(i), label = labels.get(i);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            if (i < index) {
                bg.setColor(Ui.GOOD);
                dot.setText("✓");
                dot.setTextColor(Ui.BG);
                label.setTextColor(Ui.MUTED);
                label.setTypeface(null, android.graphics.Typeface.NORMAL);
            } else if (i == index) {
                bg.setColor(accent);
                dot.setText(String.valueOf(i + 1));
                dot.setTextColor(Ui.BG);
                label.setTextColor(Ui.TEXT);
                label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            } else {
                bg.setColor(0x00000000);
                bg.setStroke(Ui.dp(c, 1.5f), Ui.MUTED);
                dot.setText(String.valueOf(i + 1));
                dot.setTextColor(Ui.MUTED);
                label.setTextColor(Ui.MUTED);
                label.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
            dot.setBackground(bg);
        }
    }
}
