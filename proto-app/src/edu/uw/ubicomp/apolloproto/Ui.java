package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Apollo's design system in code (no layout XML): warm ivory canvas, white cards with a hairline
 * border, living-coral actions, leaf/sage for good states, Inter type with tight display tracking, and
 * the apple mascot. Tokens follow the design boards ("Apollo images/") and the team's UI guide
 * (apollo_health_ui_implementation_guide.md).
 */
final class Ui {
    // Canvas and surfaces
    static final int BG = 0xFFF6F3EE, CARD = 0xFFFFFFFF, CARD_2 = 0xFFF0ECE5, LINE = 0xFFE9E4DC;
    // Text
    static final int TEXT = 0xFF262421, MUTED = 0xFF7A746C, FAINT = 0xFFA9A39A;
    // Accents: one coral for focus, leaf/sage for good, amber for wait, a deeper coral for problems
    static final int CORAL = 0xFFE8705E, CORAL_DARK = 0xFFCF5847, CORAL_TINT = 0xFFFBE9E3;
    static final int LEAF = 0xFF8C9D77, LEAF_DARK = 0xFF5E7150, LEAF_TINT = 0xFFECF0E4, GOOD = 0xFF4B8F5F, GOOD_TINT = 0xFFE7F1E3;
    static final int WARN = 0xFFB27A22, WARN_TINT = 0xFFF8EDD6, BAD = 0xFFC9503F;
    static final int INK = 0xFF1F1D1B;   // the dark "hold still" screens
    // Older names, kept so every screen picks up the new palette
    static final int HEART = CORAL, TEMP = CORAL, ACCENT = CORAL, STEPS = LEAF;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ---- Type -----------------------------------------------------------------------------------

    private static final Map<Integer, Typeface> FONTS = new HashMap<>();

    /** Inter at a weight (100-900) and optical size (14 text .. 32 display), cached; system sans if the font is missing. */
    static Typeface font(Context c, int weight, int opsz) {
        int key = weight * 100 + opsz;
        Typeface t = FONTS.get(key);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), "fonts/Inter.ttf")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz).build();
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) t = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            FONTS.put(key, t);
        }
        return t;
    }

    /** Inter styled for its size: display optical size and tighter tracking as text grows. */
    static TextView styled(Context c, String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        int opsz = sp >= 28 ? 32 : sp >= 20 ? 24 : sp >= 16 ? 18 : 14;
        t.setTypeface(font(c, weight, opsz));
        t.setLetterSpacing(sp >= 40 ? -0.035f : sp >= 24 ? -0.022f : sp >= 17 ? -0.01f : 0f);
        t.setLineSpacing(0, sp >= 24 ? 1.04f : 1.22f);
        t.setIncludeFontPadding(sp < 40);
        return t;
    }

    /** Keeps a headline on one line, stepping its size down (to `minSp`) until it fits the width it gets. */
    static void fitOneLine(TextView t, float maxSp, float minSp) {
        t.setSingleLine(true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp);
        t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            int avail = r - l - t.getPaddingLeft() - t.getPaddingRight();
            if (avail <= 0 || r - l == or - ol && t.getTag() != null) return;
            float scale = t.getResources().getDisplayMetrics().scaledDensity, sp = maxSp;
            android.text.TextPaint paint = new android.text.TextPaint(t.getPaint());
            String s = t.getText().toString();
            while (sp > minSp) {
                paint.setTextSize(sp * scale);
                if (paint.measureText(s) <= avail) break;
                sp -= 0.5f;
            }
            t.setTag(sp);
            if (Math.abs(t.getTextSize() - sp * scale) > 0.5f) {
                float fsp = sp;
                t.post(() -> t.setTextSize(TypedValue.COMPLEX_UNIT_SP, fsp));
            }
        });
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        return styled(c, s, sp, color, bold ? 600 : 400);
    }

    /** Screen headline: "A gentle temperature check." */
    static TextView title(Context c, String s) {
        return styled(c, s, 32, TEXT, 700);
    }

    /** A large reading: "72", "+0.42". */
    static TextView number(Context c, String s, float sp) {
        TextView t = styled(c, s, sp, TEXT, 600);
        t.setIncludeFontPadding(true);   // without it, the tops of big digits ("36.8") are clipped
        return t;
    }

    /** Small spaced capitals: "SATURDAY, OCTOBER 17". */
    static TextView label(Context c, String s) {
        TextView t = styled(c, s.toUpperCase(), 11, MUTED, 700);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** Section heading between groups of cards: "Choose a measurement". */
    static TextView section(Context c, String s) {
        return styled(c, s, 17, TEXT, 600);
    }

    // ---- Layout ---------------------------------------------------------------------------------

    /**
     * A scrolling page on the ivory canvas with the sunset glow at the top, drawn up under the status bar
     * (every screen shares it, as on Today).
     */
    static ScrollView page(Context c, LinearLayout content) {
        ScrollView s = new ScrollView(c);
        s.setBackgroundColor(BG);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        int top = edgeToEdge(c);
        content.setPadding(dp(c, 20), top + dp(c, 12), dp(c, 20), dp(c, 36));
        content.setClipToPadding(false);
        android.widget.FrameLayout stack = new android.widget.FrameLayout(c);
        stack.addView(new Sunset(c), new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 304)));
        stack.addView(content);
        s.addView(stack);
        fadeStatusBar(c, s);
        return s;
    }

    /** The status bar stays clear over the sunset and turns ivory once the page scrolls under it. */
    static void fadeStatusBar(Context c, ScrollView s) {
        if (!(c instanceof Activity)) return;
        android.view.Window w = ((Activity) c).getWindow();
        w.setStatusBarColor(0x00F6F3EE);
        s.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            int a = (int) (0xF2 * Math.min(1f, y / (float) dp(c, 36)));
            w.setStatusBarColor((a << 24) | 0xF6F3EE);
        });
    }

    /** A sideways-scrolling row (chips, cards), bleeding to the screen edges past the 20 dp page gutter. */
    static android.widget.HorizontalScrollView hscroll(Context c, LinearLayout row) {
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(c);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 8));
        h.addView(row);
        return h;
    }

    /** Adds a full-bleed sideways row to a page column (cancelling the column's 20 dp side padding). */
    static android.widget.HorizontalScrollView bleed(LinearLayout parent, LinearLayout row, int topDp) {
        android.widget.HorizontalScrollView h = hscroll(parent.getContext(), row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), topDp - 4);
        lp.leftMargin = lp.rightMargin = -dp(parent.getContext(), 20);
        parent.addView(h, lp);
        return h;
    }

    /** Lets the window draw under a transparent status bar (dark icons); returns the status bar height to pad by. */
    static int edgeToEdge(Context c) {
        if (c instanceof Activity) {
            Activity a = (Activity) c;
            a.getWindow().setStatusBarColor(0x00000000);
            a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id != 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 28);
    }

    /** The sunset glow: coral to peach to pale gold, fading into the ivory canvas. */
    static final class Sunset extends View {
        private final Paint glow = new Paint(), fade = new Paint();

        Sunset(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = Math.min(getHeight(), dp(getContext(), 260));   // a short glow, whatever height a screen gives it
            glow.setShader(new android.graphics.LinearGradient(0, 0, w * 0.9f, h * 0.75f,
                    new int[]{0xFFF27A64, 0xFFF8A98C, 0xFFFBD9A4}, new float[]{0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, glow);
            fade.setShader(new android.graphics.LinearGradient(0, h * 0.28f, 0, h, 0x00F6F3EE, 0xFFF6F3EE,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fade);
        }
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

    /** Adds `v` to `parent` with a top margin in dp, full width. */
    static <T extends View> T add(LinearLayout parent, T v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), topDp);
        parent.addView(v, lp);
        return v;
    }

    static View space(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    /** Top of a sub-screen: "‹ Back". (The mascot appears in the content where it helps, not as a header badge.) */
    static LinearLayout homeLink(Activity a) {
        LinearLayout r = row(a);
        TextView back = styled(a, "‹  Back", 15, TEXT, 500);
        back.setPadding(0, dp(a, 10), dp(a, 24), dp(a, 10));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> a.finish());
        r.addView(back);
        return r;
    }

    static LinearLayout homeLink(Activity a, String moodUnused) {
        return homeLink(a);
    }

    // ---- Surfaces -------------------------------------------------------------------------------

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static GradientDrawable cardBg(Context c) {
        GradientDrawable bg = rounded(CARD, dp(c, 22));
        bg.setStroke(dp(c, 1), LINE);
        return bg;
    }

    /** A soft warm shadow under white surfaces (Android 9+ tints shadows). */
    static void lift(View v, float dp) {
        v.setElevation(dp(v.getContext(), dp));
        v.setOutlineAmbientShadowColor(0x33B4825A);
        v.setOutlineSpotShadowColor(0x33B4825A);
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(cardBg(c));
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 18));
        lift(l, 1.5f);
        return l;
    }

    /** A tinted note card (no border): CORAL_TINT for guidance, GOOD_TINT for good news, WARN_TINT for a caution. */
    static LinearLayout tintCard(Context c, int tint) {
        LinearLayout l = column(c);
        l.setBackground(rounded(tint, dp(c, 20)));
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 18));
        return l;
    }

    /** A card that is itself the button (ripple on tap), laid out as a row: put an icon first. */
    static LinearLayout tile(Context c, View.OnClickListener onClick) {
        LinearLayout l = row(c);
        l.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000), cardBg(c), null));
        l.setPadding(dp(c, 16), dp(c, 16), dp(c, 14), dp(c, 16));
        l.setClickable(true);
        l.setFocusable(true);
        l.setOnClickListener(onClick);
        lift(l, 1.5f);
        return l;
    }

    /** Makes any view tappable with a soft ripple over its existing background. */
    static <T extends View> T tappable(T v, View.OnClickListener onClick) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000), v.getBackground(), null));
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(onClick);
        return v;
    }

    // ---- Icons, mascot --------------------------------------------------------------------------

    /** A line icon on a tinted rounded square (the anchor of a tile). */
    static View iconBadge(Context c, String icon, int color, int sizeDp) {
        IconView v = new IconView(c, icon, color);
        v.setBackground(rounded((color & 0x00FFFFFF) | 0x1F000000, dp(c, sizeDp * 0.32f)));
        int pad = dp(c, sizeDp * 0.24f);
        v.setPadding(pad, pad, pad, pad);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Older glyph discs map onto line icons where one exists. */
    static View iconDisc(Context c, String glyph, int color, int sizeDp) {
        String icon = "♥".equals(glyph) ? "heart" : "🌡️".equals(glyph) ? "thermo" : "◎".equals(glyph) ? "watch" : "plus";
        return iconBadge(c, icon, color, sizeDp);
    }

    static IconView icon(Context c, String name, int color, int sizeDp) {
        IconView v = new IconView(c, name, color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** The apple mascot: hello, happy, calm, curious, thinking, wink, or wave (full body). */
    static ImageView mascot(Context c, String mood, int sizeDp) {
        ImageView v = new ImageView(c);
        int id = c.getResources().getIdentifier("apollo_" + mood, "drawable", c.getPackageName());
        if (id != 0) v.setImageResource(id);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setAdjustViewBounds(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Right-pointing chevron for tappable rows. */
    static View chevron(Context c) {
        IconView v = icon(c, "chevron", FAINT, 18);
        ((LinearLayout.LayoutParams) v.getLayoutParams()).leftMargin = dp(c, 8);
        return v;
    }

    // ---- Controls -------------------------------------------------------------------------------

    /**
     * Filled: the coral call to action (white text and an arrow). Not filled: a white secondary button
     * with ink text (red text when `color` is BAD, for a destructive action).
     */
    static TextView button(Context c, String label, int color, boolean filled, View.OnClickListener onClick) {
        boolean arrow = filled && !label.contains("→") && !label.endsWith("…");
        TextView b = styled(c, arrow ? label + "   →" : label, 16, filled ? 0xFFFFFFFF : color == BAD ? BAD : TEXT, 600);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 54));
        b.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        GradientDrawable bg = rounded(filled ? (color == MUTED ? TEXT : CORAL) : CARD, dp(c, 16));
        if (!filled) bg.setStroke(dp(c, 1), LINE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(filled ? 0x33FFFFFF : 0x14000000), bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    /** A pill: green for good/ready, leaf for nearby/live, amber for wait, coral for a problem, grey otherwise. */
    static TextView badge(Context c, String label, int color) {
        int tint = color == GOOD ? GOOD_TINT : color == LEAF ? LEAF_TINT : color == WARN ? WARN_TINT
                : color == BAD || color == CORAL ? CORAL_TINT : CARD_2;
        int fg = color == MUTED || color == TEXT ? MUTED : color == LEAF ? LEAF_DARK : color;
        TextView t = styled(c, label, 12.5f, fg, 600);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setBackground(rounded(tint, dp(c, 100)));
        t.setPadding(dp(c, 11), dp(c, 5), dp(c, 11), dp(c, 5));
        return t;
    }

    /** Wraps a view so it keeps its natural width inside a column (badges, chips). */
    static <T extends View> T wrap(LinearLayout parent, T v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), topDp);
        parent.addView(v, lp);
        return v;
    }

    /** Pill with a coloured dot: "● Phone ready". */
    static TextView chip(Context c, String label, int color) {
        TextView t = styled(c, "", 12.5f, TEXT, 500);
        GradientDrawable bg = rounded(0xCCFFFFFF, dp(c, 20));
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

    /** iOS-style segmented control. `onSelect` gets the tapped index. */
    static LinearLayout segmented(Context c, String[] options, int selected, IntConsumer onSelect) {
        LinearLayout track = row(c);
        track.setBackground(rounded(CARD_2, dp(c, 13)));
        track.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            TextView seg = styled(c, options[i], 14, on ? TEXT : MUTED, on ? 600 : 500);
            seg.setGravity(Gravity.CENTER);
            seg.setPadding(dp(c, 6), dp(c, 9), dp(c, 6), dp(c, 9));
            if (on) {
                seg.setBackground(rounded(CARD, dp(c, 10)));
                lift(seg, 1);
            }
            seg.setClickable(true);
            seg.setOnClickListener(v -> onSelect.accept(idx));
            track.addView(seg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        return track;
    }

    /** A selectable filter chip: coral-tinted when on, a white outline when off. */
    static TextView choice(Context c, String label, boolean on, View.OnClickListener onClick) {
        TextView t = styled(c, label, 13.5f, on ? CORAL_DARK : TEXT, on ? 600 : 500);
        t.setSingleLine(true);
        GradientDrawable bg = rounded(on ? CORAL_TINT : CARD, dp(c, 100));
        bg.setStroke(dp(c, 1), on ? 0x66E8705E : LINE);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000), bg, null));
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setClickable(true);
        t.setOnClickListener(onClick);
        return t;
    }

    /** Adds `v` to a row with a right margin (chip rows). */
    static <T extends View> T inRow(LinearLayout row, T v, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(row.getContext(), rightDp);
        row.addView(v, lp);
        return v;
    }

    static EditText numberInput(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        e.setTypeface(font(c, 500, 18));
        GradientDrawable bg = rounded(CARD, dp(c, 14));
        bg.setStroke(dp(c, 1), LINE);
        e.setBackground(bg);
        e.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        return e;
    }

    static EditText textInput(Context c, String hint) {
        EditText e = numberInput(c, hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setSingleLine(true);
        return e;
    }

    /** "Label   value" line used in detail lists. */
    static LinearLayout kv(Context c, String k, String v) {
        LinearLayout r = row(c);
        TextView key = styled(c, k, 14.5f, MUTED, 400);
        TextView val = styled(c, v, 14.5f, TEXT, 500);
        val.setGravity(Gravity.END);
        r.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        r.setPadding(0, dp(c, 7), 0, dp(c, 7));
        return r;
    }

    /** A thin rounded progress bar (steps toward a goal, coverage). */
    static Bar bar(Context c, float fraction, int color) {
        Bar b = new Bar(c, color);
        b.set(fraction);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6)));
        return b;
    }

    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        Bar(Context c, int color) {
            super(c);
            track.setColor(CARD_2);
            fill.setColor(color);
        }

        void set(float f) {
            fraction = Math.max(0, Math.min(1, f));
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {   // a fixed 6 dp track, whatever the parent asks
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 6));
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), rad = h / 2;
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, rad, rad, track);
            if (fraction > 0) {
                r.set(0, 0, Math.max(h, getWidth() * fraction), h);
                c.drawRoundRect(r, rad, rad, fill);
            }
        }
    }

    /** Short, calm words for the phone's readiness (ThermalMonitor verdicts). */
    static String readyLabel(String verdict) {
        if (verdict == null) return "Checking";
        switch (verdict) {
            case "READY":
                return "Ready";
            case "SETTLING":
                return "Settling";
            case "TOO_WARM":
                return "Cooling down";
            case "BLOCKED":
                return "Unplug first";
            case "UNSUPPORTED":
                return "Needs setup";
            default:
                return "Checking";
        }
    }

    /** "Is the phone ready?": a calm status pill, one plain sentence and the small print. */
    static final class Readiness {
        final LinearLayout card;
        final TextView message, detail;
        private final TextView pill;

        Readiness(Context c) {
            card = card(c);
            LinearLayout top = add(card, row(c), 0);
            top.addView(styled(c, "Is the phone ready?", 16, TEXT, 600), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            pill = badge(c, "Checking", MUTED);
            top.addView(pill);
            message = add(card, styled(c, "", 15, TEXT, 400), 8);
            detail = add(card, styled(c, "", 12.5f, FAINT, 400), 6);
        }

        void show(ThermalMonitor.State s, String small) {
            int color = "READY".equals(s.verdict) ? GOOD : "UNSUPPORTED".equals(s.verdict) || s.verdict == null ? MUTED : WARN;
            TextView fresh = badge(card.getContext(), ("READY".equals(s.verdict) ? "✓  " : "") + readyLabel(s.verdict), color);
            pill.setText(fresh.getText());
            pill.setTextColor(fresh.getCurrentTextColor());
            pill.setBackground(fresh.getBackground());
            message.setText(s.message);
            detail.setText(small);
        }
    }

    static int verdictColor(String verdict) {
        if (verdict == null) return MUTED;
        switch (verdict) {
            case "GOOD":
            case "READY":
                return GOOD;
            case "WEAK":
            case "SETTLING":
            case "TOO_WARM":   // waits, not errors
            case "BLOCKED":
                return WARN;
            default:
                return BAD;
        }
    }
}
