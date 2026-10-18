package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Ask Apollo": the chat tab (the design boards' companion screen). Replies come from ApolloBrain, on
 * the phone, unless the user turns on Gemini mode (GeminiBrain): then the conversation and a summary of their readings go
 * to Google, and ApolloBrain answers whenever Gemini can't. Emergencies are always answered on the phone, first. Action
 * buttons under a reply open the screen it suggests. Apollo answers in speech bubbles
 * with his face beside them, its expression matched to what he says (mood()). The conversation lasts
 * while the app is open.
 */
final class ChatPage {
    interface Host {
        void act(String action);

        long steps();
    }

    private static final List<ApolloBrain.Msg> HISTORY = new ArrayList<>();
    private static final String PREF_GEMINI = "chat_gemini";   // the user agreed to send chats and readings to Gemini
    private static final Brain GEMINI = new GeminiBrain();
    private static final ExecutorService BRAIN = Executors.newSingleThreadExecutor();

    private final Activity a;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout thread;
    private ScrollView scroll;
    private EditText input;
    private ImageView headFace;
    private LinearLayout infoSlot, modeRow;   // the "What Apollo knows" button and the Gemini line, both follow the mode
    private boolean thinking;

    ChatPage(Activity a, Host host) {
        this.a = a;
        this.host = host;
        if (HISTORY.isEmpty()) HISTORY.add(ApolloBrain.greeting());
    }

    void build(LinearLayout col, ScrollView scroll) {
        this.scroll = scroll;
        LinearLayout head = Ui.add(col, Ui.row(a), 6);
        headFace = null;   // his face is beside each of his messages; the title stands alone
        TextView t = Ui.title(a, "Chat with Apollo");
        Ui.fitOneLine(t, 32, 22);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        infoSlot = Ui.row(a);
        head.addView(infoSlot);
        modeRow = Ui.add(col, Ui.row(a), 4);
        showMode();

        thread = Ui.add(col, Ui.column(a), 12);
        for (ApolloBrain.Msg m : HISTORY) thread.addView(bubble(m));

        Ui.add(col, Ui.label(a, "Try asking"), 20);
        LinearLayout chips = Ui.row(a);
        for (String s : ApolloBrain.SUGGESTIONS) Ui.inRow(chips, Ui.choice(a, s, false, v -> send(s)), 8);
        Ui.bleed(col, chips, 8);

        LinearLayout bar = Ui.add(col, Ui.row(a), 10);
        input = new EditText(a);
        input.setHint("Ask Apollo…");
        input.setHintTextColor(Ui.FAINT);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(16);
        input.setTypeface(Ui.font(a, 400, 18));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable bg = Ui.rounded(Ui.CARD, Ui.dp(a, 100));
        bg.setStroke(Ui.dp(a, 1), Ui.LINE);
        input.setBackground(bg);
        input.setPadding(Ui.dp(a, 20), Ui.dp(a, 14), Ui.dp(a, 20), Ui.dp(a, 14));
        input.setOnEditorActionListener((v, id, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEND || enter) {
                send(input.getText().toString());
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, has) -> {
            if (has) toBottom(350);
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        IconView go = new IconView(a, "send", 0xFFFFFFFF);
        go.setBackground(Ui.rounded(Ui.CORAL, Ui.dp(a, 26)));
        int p = Ui.dp(a, 14);
        go.setPadding(p, p, p, p);
        go.setContentDescription("Send");
        Ui.tappable(go, v -> send(input.getText().toString()));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52));
        gl.leftMargin = Ui.dp(a, 10);
        bar.addView(go, gl);

        Ui.add(col, Ui.text(a, "Apollo doesn't diagnose. In an emergency, call 911.", 12.5f, Ui.MUTED, false), 12);
        if (HISTORY.size() > 1) toBottom(60);
    }

    private static boolean geminiOn(Activity a) {
        return GeminiBrain.configured() && Store.prefs(a).getBoolean(PREF_GEMINI, false);
    }

    /**
     * Where replies come from. The info button says what Apollo sees and where it goes; with a Gemini key built in,
     * a line under the title shows the mode and switches it.
     */
    private void showMode() {
        boolean on = geminiOn(a);
        infoSlot.removeAllViews();
        infoSlot.addView(Info.button(a, "What Apollo knows", on
                ? "Apollo can see your latest heart rate, temperature, steps, breath and eye results, and what you tell it here.\n\n"
                + "Gemini mode is on: your messages and a short summary of those readings go to Google's Gemini to write his "
                + "replies. Emergencies are always answered on this phone, and he falls back to his own answers when offline.\n\n"
                + "He doesn't diagnose. In an emergency, call 911."
                : "Apollo can see your latest heart rate, temperature, steps and breath results, and what you tell it here. He "
                + "lives on this phone: nothing you say leaves it.\n\nHe helps you reflect on your readings and points you to "
                + "the right screen. He doesn't diagnose. In an emergency, call 911."));
        modeRow.removeAllViews();
        if (!GeminiBrain.configured()) return;
        modeRow.addView(Ui.text(a, on ? "Replies by Gemini · " : "Replies from this phone · ", 13.5f, Ui.MUTED, false));
        TextView sw = Ui.styled(a, on ? "Turn off" : "Use Gemini", 13.5f, Ui.CORAL_DARK, 600);
        sw.setPadding(0, Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6));
        modeRow.addView(sw);
        Ui.tappable(sw, v -> {
            if (geminiOn(a)) {
                Store.prefs(a).edit().putBoolean(PREF_GEMINI, false).apply();
                showMode();
            } else {
                askGemini();
            }
        });
    }

    /** Consent first: nothing is sent until the user agrees, and they can turn it off any time. */
    private void askGemini() {
        new android.app.AlertDialog.Builder(a)
                .setTitle("Use Gemini for Apollo's replies?")
                .setMessage("Apollo will send your messages and a short summary of your readings (latest heart rate, temperature, "
                        + "breath strength, steps today and eye tracking) to Google's Gemini service to write each reply. "
                        + "Google's terms apply to what is sent. Raw sensor data, photos and your location are never sent, "
                        + "and emergencies are always answered on this phone.\n\nYou can turn this off here any time.")
                .setPositiveButton("Turn on", (d, w) -> {
                    Store.prefs(a).edit().putBoolean(PREF_GEMINI, true).apply();
                    showMode();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void send(String raw) {
        String q = raw.trim();
        if (q.isEmpty() || thinking) return;
        input.setText("");
        ApolloBrain.Msg mine = new ApolloBrain.Msg(true, q, null);
        HISTORY.add(mine);
        thread.addView(bubble(mine));
        // A typing bubble while Apollo works (at least a short pause, so the reply reads as a reply).
        thinking = true;
        View dots = bubble(new ApolloBrain.Msg(false, TYPING, null));
        thread.addView(dots);
        setFace(headFace, "thinking");
        toBottom(40);
        long started = System.currentTimeMillis();
        boolean useGemini = geminiOn(a);
        List<ApolloBrain.Msg> turns = new ArrayList<>(HISTORY);
        long steps = host.steps();
        BRAIN.execute(() -> {
            ApolloBrain.Msg urgent = ApolloBrain.emergency(q);   // never leaves the phone
            ApolloBrain.Msg fromModel = urgent == null && useGemini ? GEMINI.reply(a, turns, steps) : null;
            ApolloBrain.Msg reply = urgent != null ? urgent : fromModel != null ? fromModel : ApolloBrain.reply(a, q, steps);
            ui.postDelayed(() -> showReply(q, reply, dots), Math.max(0, 650 - (System.currentTimeMillis() - started)));
        });
    }

    private void showReply(String q, ApolloBrain.Msg r, View dots) {
        thinking = false;
        thread.removeView(dots);
        // Care's recommender remembers the conversation; if the words clearly name a need Apollo didn't point to, link its picks.
        String need = CareSignals.chat(a, q, r);
        boolean linked = false;
        for (String[] act : r.actions) linked |= act[1].startsWith("category:") || act[1].startsWith("care:");
        if (need != null && !linked) r.act("See picks for " + CareData.need(need).title.toLowerCase(Locale.US), "care:meds");
        HISTORY.add(r);
        thread.addView(bubble(r));
        setFace(headFace, mood(r));
        toBottom(40);
    }

    /** Scrolls to the end, so the newest reply and the input box sit above the floating bar. */
    private void toBottom(long delayMs) {
        ui.postDelayed(() -> {
            if (scroll != null) scroll.smoothScrollTo(0, scroll.getChildAt(0).getHeight());
        }, delayMs);
    }

    private static final String TYPING = "\u2022 \u2022 \u2022";

    /** The newest thing Apollo said (his greeting at first). */
    private static ApolloBrain.Msg lastReply() {
        for (int i = HISTORY.size() - 1; i >= 0; i--) if (!HISTORY.get(i).mine) return HISTORY.get(i);
        return ApolloBrain.greeting();
    }

    /**
     * Apollo's face for a reply, from what it says (the expression board's moods): thinking while he types
     * or when a reading deserves a closer look, happy at good news, curious when there's nothing to go on
     * yet or when it may be an emergency (alert, never smiling), calm for a breathing exercise.
     */
    static String mood(ApolloBrain.Msg m) {
        String t = m.text.toLowerCase(Locale.US);
        boolean dials = false;
        for (String[] act : m.actions) dials |= act[1].startsWith("dial:");
        if (TYPING.equals(m.text)) return "thinking";
        if (dials) return "curious";
        if (t.contains("slow it down") || t.contains("breathe in")) return "calm";
        if (t.contains("above typical") || t.contains("above the usual") || t.contains("weak signal") || t.contains("can't see")) return "thinking";
        if (t.contains("don't have") || t.contains("no readings yet") || t.contains("still building")) return "curious";
        if (t.contains("nice going") || t.contains("typical range") || t.contains("inside the usual") || t.contains("good signal")
                || t.startsWith("anytime") || t.startsWith("hello")) return "happy";
        if (t.startsWith("i'm a small helper")) return "wink";
        if (t.contains("times how fast") || t.contains("barometer")) return "thinking";
        return "hello";
    }

    /** Swap a face to another mood with a little pop. */
    private void setFace(ImageView face, String mood) {
        if (face == null) return;
        int id = a.getResources().getIdentifier("apollo_" + mood, "drawable", a.getPackageName());
        if (id == 0) return;
        face.setImageResource(id);
        face.setScaleX(0.85f);
        face.setScaleY(0.85f);
        face.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
    }

    private View bubble(ApolloBrain.Msg m) {
        int width = a.getResources().getDisplayMetrics().widthPixels;
        int face = Ui.dp(a, 40), gap = Ui.dp(a, 4), tail = Ui.dp(a, 8);
        int max = m.mine ? (int) (width * 0.78f) : (int) (width * 0.86f) - face - gap;
        LinearLayout holder = Ui.column(a);
        holder.setGravity(m.mine ? Gravity.END : Gravity.START);
        holder.setPadding(0, Ui.dp(a, 12), 0, 0);
        LinearLayout b = Ui.column(a);
        b.setBackground(new Bubble(m.mine ? Ui.CORAL_TINT : Ui.CARD, m.mine ? 0 : Ui.LINE, !m.mine, Ui.dp(a, 22), tail, Ui.dp(a, 1)));
        b.setPadding(Ui.dp(a, 18) + (m.mine ? 0 : tail), Ui.dp(a, 14), Ui.dp(a, 18) + (m.mine ? tail : 0), Ui.dp(a, 14));
        if (!m.mine) Ui.lift(b, 1);
        TextView t = Ui.styled(a, "", 15.5f, Ui.TEXT, 400);
        t.setText(rich(m.text));
        t.setMaxWidth(max - Ui.dp(a, 36) - tail);
        t.setLineSpacing(0, 1.3f);
        b.addView(t);
        if (TYPING.equals(m.text)) {   // the typing dots breathe
            t.setTextColor(Ui.MUTED);
            ObjectAnimator pulse = ObjectAnimator.ofFloat(t, View.ALPHA, 1f, 0.3f);
            pulse.setDuration(450);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
        }
        if (m.basedOn != null) {
            View div = new View(a);
            div.setBackgroundColor(Ui.LINE);
            Ui.add(b, div, 12).getLayoutParams().height = 1;
            Ui.add(b, Ui.text(a, "Based on: " + m.basedOn, 12.5f, Ui.MUTED, false), 10);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (m.mine) {
            holder.addView(b, bl);
        } else {
            // Apollo on the page's ivory (no disc behind him), the bubble's tail pointing at him.
            LinearLayout row = Ui.row(a);
            row.setGravity(Gravity.BOTTOM);
            ImageView f = Ui.mascot(a, mood(m), 40);
            f.setContentDescription("Apollo");
            row.addView(f);
            bl.leftMargin = gap;
            row.addView(b, bl);
            holder.addView(row);
        }
        for (String[] act : m.actions) {
            TextView btn = Ui.button(a, act[0], Ui.MUTED, false, v -> host.act(act[1]));
            btn.setMinHeight(Ui.dp(a, 48));
            btn.setTextSize(15);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, 10);
            lp.leftMargin = face + gap;   // under the bubble, not under his face
            holder.addView(btn, lp);
        }
        return holder;
    }

    /** A speech bubble: a rounded body and a small curved tail at its bottom corner, toward Apollo (left) or the edge (right). */
    private static final class Bubble extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF body = new RectF();
        private final boolean left;
        private final float r, tail;

        Bubble(int color, int strokeColor, boolean left, float r, float tail, float strokeWidth) {
            this.left = left;
            this.r = r;
            this.tail = tail;
            fill.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(strokeWidth);
            stroke.setColor(strokeColor);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            float h = b.height(), s = r * 0.27f;
            body.set(b.left + (left ? tail : 0), b.top, b.right - (left ? 0 : tail), b.bottom);
            path.reset();
            path.addRoundRect(body, left ? new float[]{r, r, r, r, r, r, s, s} : new float[]{r, r, r, r, s, s, r, r}, Path.Direction.CW);
            Path t = new Path();   // the tail: out from the body's lower side to a point level with its bottom
            float x = left ? body.left : body.right, dir = left ? -1 : 1, bottom = b.bottom;
            t.moveTo(x, bottom - Math.min(h * 0.5f, tail * 2.2f));
            t.quadTo(x + dir * tail * 0.15f, bottom - tail * 0.35f, x + dir * tail, bottom);
            t.lineTo(x - dir * tail * 1.4f, bottom);
            t.close();
            path.op(t, Path.Op.UNION);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            c.drawPath(path, fill);
            if (stroke.getColor() != 0) c.drawPath(path, stroke);
        }

        @Override
        public void getOutline(Outline o) {   // the soft shadow follows the body
            o.setRoundRect((int) body.left, (int) body.top, (int) body.right, (int) body.bottom, r);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** "**72 bpm**" → semibold Inter. */
    private CharSequence rich(String s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] parts = s.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            int start = out.length();
            out.append(parts[i]);
            if (i % 2 == 1) out.setSpan(new TypefaceSpan(Ui.font(a, 650, 18)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
}
