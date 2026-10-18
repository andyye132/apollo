package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Colours and tiny view builders, so screens can be written in code without layout XML. */
final class Ui {
    static final int BG = 0xFF0E1318, CARD = 0xFF172029, CARD_2 = 0xFF1E2831, LINE = 0xFF2A3541;
    static final int TEXT = 0xFFE8EDF2, MUTED = 0xFF93A1AF;
    static final int HEART = 0xFFF0604A, TEMP = 0xFFF2A33A, STEPS = 0xFF4CC2A9, ACCENT = 0xFF7EAAF5;
    static final int GOOD = 0xFF4CC27A, WARN = 0xFFE8B03A, BAD = 0xFFE5534B;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static ScrollView page(Context c, LinearLayout content) {
        ScrollView s = new ScrollView(c);
        s.setBackgroundColor(BG);
        s.setFillViewport(true);
        content.setPadding(dp(c, 18), dp(c, 28), dp(c, 18), dp(c, 32));
        s.addView(content);
        return s;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** "‹ Home" link for the top of a sub-screen; closes it and returns to the home screen. */
    static TextView homeLink(android.app.Activity a) {
        TextView t = text(a, "‹  Home", 16, ACCENT, true);
        t.setPadding(0, dp(a, 6), dp(a, 24), dp(a, 10));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> a.finish());
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        GradientDrawable bg = rounded(CARD, dp(c, 16));
        bg.setStroke(dp(c, 1), LINE);
        l.setBackground(bg);
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16));
        return l;
    }

    /** A card that is itself the button (ripple on tap), laid out as a row: put an iconDisc first. */
    static LinearLayout tile(Context c, View.OnClickListener onClick) {
        LinearLayout l = row(c);
        GradientDrawable bg = rounded(CARD, dp(c, 16));
        bg.setStroke(dp(c, 1), LINE);
        l.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
        l.setPadding(dp(c, 14), dp(c, 14), dp(c, 12), dp(c, 14));
        l.setClickable(true);
        l.setFocusable(true);
        l.setOnClickListener(onClick);
        return l;
    }

    /** Round tinted disc with a glyph, the visual anchor of a tile. */
    static TextView iconDisc(Context c, String glyph, int color, int sizeDp) {
        TextView t = text(c, glyph, sizeDp * 0.45f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor((color & 0x00FFFFFF) | 0x2E000000);
        t.setBackground(d);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return t;
    }

    /** Pill with a coloured dot: "● Phone ready". */
    static TextView chip(Context c, String label, int color) {
        TextView t = text(c, "●  " + label, 12.5f, TEXT, false);
        GradientDrawable bg = rounded(CARD, dp(c, 20));
        bg.setStroke(dp(c, 1), LINE);
        t.setBackground(bg);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 12), dp(c, 5));
        setChip(t, label, color);
        return t;
    }

    static void setChip(TextView t, String label, int color) {
        android.text.SpannableString s = new android.text.SpannableString("●  " + label);
        s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(s);
    }

    /** Small grey heading between groups of cards. */
    static TextView section(Context c, String s) {
        TextView t = label(c, s);
        t.setPadding(dp(c, 4), 0, 0, 0);
        return t;
    }

    /** Right-pointing chevron for tappable rows. */
    static TextView chevron(Context c) {
        TextView t = text(c, "›", 26, MUTED, false);
        t.setPadding(dp(c, 8), 0, dp(c, 2), dp(c, 2));
        return t;
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 28, TEXT, true);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11.5f, MUTED, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    static TextView button(Context c, String label, int color, boolean filled, View.OnClickListener onClick) {
        TextView b = text(c, label, 16, filled ? 0xFF0E1318 : color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 18), dp(c, 13), dp(c, 18), dp(c, 13));
        GradientDrawable bg = rounded(filled ? color : 0x00000000, dp(c, 12));
        if (!filled) bg.setStroke(dp(c, 1.5f), color);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    static TextView badge(Context c, String label, int color) {
        TextView t = text(c, label, 12, color, true);
        GradientDrawable bg = rounded((color & 0x00FFFFFF) | 0x26000000, dp(c, 6));
        t.setBackground(bg);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    static EditText numberInput(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setBackground(rounded(CARD_2, dp(c, 10)));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    static EditText textInput(Context c, String hint) {
        EditText e = numberInput(c, hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setSingleLine(true);
        return e;
    }

    /** Adds `v` to `parent` with a top margin in dp, full width. */
    static <T extends View> T add(LinearLayout parent, T v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), topDp);
        parent.addView(v, lp);
        return v;
    }

    /** "Label   value" line used in detail lists. */
    static LinearLayout kv(Context c, String k, String v) {
        LinearLayout r = row(c);
        TextView key = text(c, k, 14, MUTED, false);
        TextView val = text(c, v, 14, TEXT, false);
        val.setGravity(Gravity.END);
        r.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        r.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return r;
    }

    static int verdictColor(String verdict) {
        if (verdict == null) return MUTED;
        switch (verdict) {
            case "GOOD":
            case "READY":
                return GOOD;
            case "WEAK":
            case "SETTLING":
                return WARN;
            default:
                return BAD;
        }
    }
}
