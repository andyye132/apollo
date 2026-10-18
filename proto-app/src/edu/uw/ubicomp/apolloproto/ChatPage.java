package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Ask Apollo": the chat tab (the design boards' companion screen). Replies come from ApolloBrain, on
 * the phone; action buttons under a reply open the screen it suggests. The conversation lasts while the
 * app is open.
 */
final class ChatPage {
    interface Host {
        void act(String action);

        long steps();
    }

    private static final List<ApolloBrain.Msg> HISTORY = new ArrayList<>();

    private final Activity a;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout thread;
    private ScrollView scroll;
    private EditText input;
    private boolean thinking;

    ChatPage(Activity a, Host host) {
        this.a = a;
        this.host = host;
        if (HISTORY.isEmpty()) HISTORY.add(ApolloBrain.greeting());
    }

    void build(LinearLayout col, ScrollView scroll) {
        this.scroll = scroll;
        LinearLayout head = Ui.add(col, Ui.row(a), 6);
        head.addView(Ui.mascot(a, "hello", 64));
        LinearLayout names = Ui.column(a);
        TextView t = Ui.styled(a, "A little perspective.", 26, Ui.TEXT, 700);
        names.addView(t);
        Ui.add(names, Ui.text(a, "Apollo · on this phone", 14.5f, Ui.MUTED, false), 3);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nl.leftMargin = Ui.dp(a, 12);
        head.addView(names, nl);

        LinearLayout note = Ui.add(col, Ui.tintCard(a, Ui.CORAL_TINT), 18);
        note.addView(Ui.text(a, "Apollo can see your latest heart rate, temperature and steps. Nothing leaves this phone.",
                14, Ui.CORAL_DARK, false));

        thread = Ui.add(col, Ui.column(a), 6);
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

        Ui.add(col, Ui.text(a, "Apollo helps you reflect on your data; it does not diagnose. In an emergency, call 911.",
                12.5f, Ui.MUTED, false), 12);
        if (HISTORY.size() > 1) toBottom(60);
    }

    private void send(String raw) {
        String q = raw.trim();
        if (q.isEmpty() || thinking) return;
        input.setText("");
        ApolloBrain.Msg mine = new ApolloBrain.Msg(true, q, null);
        HISTORY.add(mine);
        thread.addView(bubble(mine));
        // A short pause with a typing bubble, so the reply reads as a reply.
        thinking = true;
        View dots = bubble(new ApolloBrain.Msg(false, "…", null));
        thread.addView(dots);
        toBottom(40);
        ui.postDelayed(() -> {
            thinking = false;
            thread.removeView(dots);
            ApolloBrain.Msg r = ApolloBrain.reply(a, q, host.steps());
            HISTORY.add(r);
            thread.addView(bubble(r));
            toBottom(40);
        }, 650);
    }

    /** Scrolls to the end, so the newest reply and the input box sit above the floating bar. */
    private void toBottom(long delayMs) {
        ui.postDelayed(() -> {
            if (scroll != null) scroll.smoothScrollTo(0, scroll.getChildAt(0).getHeight());
        }, delayMs);
    }

    private View bubble(ApolloBrain.Msg m) {
        int max = (int) (a.getResources().getDisplayMetrics().widthPixels * (m.mine ? 0.78f : 0.86f));
        LinearLayout holder = Ui.column(a);
        holder.setGravity(m.mine ? Gravity.END : Gravity.START);
        holder.setPadding(0, Ui.dp(a, 12), 0, 0);
        LinearLayout b = Ui.column(a);
        float r = Ui.dp(a, 22), tail = Ui.dp(a, 6);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(m.mine ? Ui.CORAL_TINT : Ui.CARD);
        bg.setCornerRadii(m.mine ? new float[]{r, r, r, r, tail, tail, r, r} : new float[]{r, r, r, r, r, r, tail, tail});
        if (!m.mine) bg.setStroke(Ui.dp(a, 1), Ui.LINE);
        b.setBackground(bg);
        b.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        if (!m.mine) Ui.lift(b, 1);
        TextView t = Ui.styled(a, "", 15.5f, Ui.TEXT, 400);
        t.setText(rich(m.text));
        t.setMaxWidth(max - Ui.dp(a, 36));
        t.setLineSpacing(0, 1.3f);
        b.addView(t);
        if (m.basedOn != null) {
            View div = new View(a);
            div.setBackgroundColor(Ui.LINE);
            Ui.add(b, div, 12).getLayoutParams().height = 1;
            Ui.add(b, Ui.text(a, "Based on: " + m.basedOn, 12.5f, Ui.MUTED, false), 10);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        holder.addView(b, bl);
        for (String[] act : m.actions) {
            TextView btn = Ui.button(a, act[0], Ui.MUTED, false, v -> host.act(act[1]));
            btn.setMinHeight(Ui.dp(a, 48));
            btn.setTextSize(15);
            Ui.add(holder, btn, 10);
        }
        return holder;
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
