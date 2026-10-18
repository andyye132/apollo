package edu.uw.ubicomp.apolloproto;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Saying more without crowding a screen: (i) buttons and "How does this work?" links that open a bottom
 * sheet, "Learn more" sections that unfold in place, the research-prototype disclaimer, and the bottom sheet
 * itself (a close button, a swipe down or a tap outside dismisses it).
 */
final class Info {
    private Info() {
    }

    /** A bottom sheet over the screen: fill `col` (or use title/body/link), then show(). */
    static final class Sheet {
        final Dialog dialog;
        final LinearLayout col;
        private final Activity a;

        Sheet(Activity a) {
            this.a = a;
            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout frame = new FrameLayout(a);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Ui.CARD);
            float r = Ui.dp(a, 28);
            bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            frame.setBackground(bg);
            ScrollView sv = new ScrollView(a);
            sv.setVerticalScrollBarEnabled(false);
            col = Ui.column(a);
            col.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 28));
            sv.addView(col);
            frame.addView(sv);
            View handle = new View(a);
            handle.setBackground(Ui.rounded(Ui.LINE, Ui.dp(a, 3)));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 5));
            hp.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(handle, hp);

            IconView close = new IconView(a, "close", Ui.TEXT);
            close.setBackground(Ui.rounded(Ui.CARD_2, Ui.dp(a, 18)));
            int p = Ui.dp(a, 9);
            close.setPadding(p, p, p, p);
            close.setContentDescription("Close");
            Ui.tappable(close, v -> dialog.dismiss());
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36), Gravity.TOP | Gravity.END);
            cl.setMargins(0, Ui.dp(a, 14), Ui.dp(a, 14), 0);
            frame.addView(close, cl);
            swipeToClose(sv, frame);

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0));
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setGravity(Gravity.BOTTOM);
                w.setWindowAnimations(android.R.style.Animation_InputMethod);
                w.setDimAmount(0.35f);
            }
        }

        /** Dragging down from the top of the content follows the finger; far enough, and the sheet closes. */
        private void swipeToClose(ScrollView sv, View frame) {
            float[] start = {0};
            boolean[] dragging = {false};
            sv.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        start[0] = e.getRawY();
                        dragging[0] = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - start[0];
                        if (!dragging[0] && dy > Ui.dp(a, 12) && sv.getScrollY() == 0) dragging[0] = true;
                        if (!dragging[0]) return false;
                        frame.setTranslationY(Math.max(0, dy));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging[0]) return false;
                        dragging[0] = false;
                        if (frame.getTranslationY() > Ui.dp(a, 110)) dialog.dismiss();
                        else frame.animate().translationY(0).setDuration(160).start();
                        return true;
                    default:
                        return false;
                }
            });
        }

        Sheet title(String t) {
            TextView v = Ui.add(col, Ui.styled(a, t, 22, Ui.TEXT, 700), 14);
            v.setPadding(0, 0, Ui.dp(a, 44), 0);   // clear of the close button
            return this;
        }

        Sheet body(CharSequence text) {
            TextView v = Ui.add(col, Ui.text(a, "", 15, Ui.TEXT, false), 10);
            v.setText(text);
            v.setLineSpacing(0, 1.3f);
            return this;
        }

        /** "Read the paper ›" and the like, opening a page. */
        Sheet link(String label, String url) {
            TextView l = Ui.add(col, Ui.styled(a, label + "  ›", 15, Ui.CORAL_DARK, 600), 14);
            l.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            l.setOnClickListener(v -> open(a, url));
            return this;
        }

        void show() {
            dialog.show();
        }

        void dismiss() {
            dialog.dismiss();
        }
    }

    /** A round (i) that opens a sheet: a title, the words, and optionally a link {label, url}. */
    static View button(Activity a, String title, CharSequence body, String... link) {
        IconView i = new IconView(a, "info", Ui.MUTED);
        int p = Ui.dp(a, 6);
        i.setPadding(p, p, p, p);
        i.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 16)));
        Ui.tappable(i, v -> {
            Sheet s = new Sheet(a).title(title).body(body);
            if (link.length == 2) s.link(link[0], link[1]);
            s.show();
        });
        i.setContentDescription("About: " + title);
        i.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32)));
        return i;
    }

    /** "How does this work?  (i)": the plain-language explainer and the paper behind it. */
    static View howItWorks(Activity a, CharSequence body, String paper, String url) {
        return link(a, "How does this work?", "How it works", body, "Read the paper: " + paper, url);
    }

    /** Coral words with an (i), opening a sheet ("Which should I choose?"), with an optional link {label, url}. */
    static View link(Activity a, String words, String title, CharSequence body, String... link) {
        LinearLayout r = Ui.row(a);
        r.setPadding(Ui.dp(a, 4), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8));
        r.addView(Ui.styled(a, words, 15, Ui.CORAL_DARK, 600));
        IconView i = Ui.icon(a, "info", Ui.CORAL_DARK, 18);
        ((LinearLayout.LayoutParams) i.getLayoutParams()).leftMargin = Ui.dp(a, 6);
        r.addView(i);
        r.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 12)));
        Ui.tappable(r, v -> {
            Sheet s = new Sheet(a).title(title).body(body);
            if (link.length == 2) s.link(link[0], link[1]);
            s.show();
        });
        return r;
    }

    /** A "Learn more" row that unfolds the returned column in place (and folds it again). */
    static LinearLayout learnMore(LinearLayout parent, int topDp) {
        Activity a = (Activity) parent.getContext();
        LinearLayout head = Ui.add(parent, Ui.row(a), topDp);
        head.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        TextView t = Ui.styled(a, "Learn more", 15, Ui.CORAL_DARK, 600);
        head.addView(t);
        IconView chev = Ui.icon(a, "chevron", Ui.CORAL_DARK, 18);
        chev.setRotation(90);
        ((LinearLayout.LayoutParams) chev.getLayoutParams()).leftMargin = Ui.dp(a, 4);
        head.addView(chev);
        LinearLayout body = Ui.add(parent, Ui.column(a), 0);
        body.setVisibility(View.GONE);
        head.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 12)));
        Ui.tappable(head, v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            t.setText(open ? "Show less" : "Learn more");
            chev.animate().rotation(open ? -90 : 90).setDuration(160).start();
        });
        return body;
    }

    /** The research-prototype notice, behind a small "Disclaimer" button. */
    static View disclaimer(Activity a) {
        LinearLayout r = Ui.row(a);
        r.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        r.addView(Ui.icon(a, "info", Ui.FAINT, 16));
        TextView t = Ui.styled(a, "Disclaimer", 13, Ui.MUTED, 600);
        t.setPadding(Ui.dp(a, 6), 0, 0, 0);
        r.addView(t);
        r.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 12)));
        Ui.tappable(r, v -> new Sheet(a).title("A research prototype")
                .body("Apollo is a research prototype from the University of Washington, not a medical device. Its readings are "
                        + "estimates for research and reflection, not a diagnosis.\n\nIf you feel unwell, talk to a doctor. "
                        + "In an emergency, call 911.").show());
        return r;
    }

    /** Puts `v` centred on its own line in `parent`. */
    static void centred(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(parent.getContext(), topDp);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        parent.addView(v, lp);
    }

    static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(a, "No app on this phone can open that.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
