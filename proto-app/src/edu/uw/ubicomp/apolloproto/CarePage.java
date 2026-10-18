package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Care tab. "Meds & vitamins": shelves picked by CareModel from the user's readings, chats with Apollo,
 * searches and browsing (remembered in CareSignals), a catalog, and search, all
 * with real product photos and recent prices (UPCitemdb), a product sheet with the FDA label (openFDA), and
 * hand-offs to DoorDash and Uber Eats (neither offers a public shopping API, so Apollo opens a search for the
 * product in their app or site). "Services & hospitals": real care near the phone or a ZIP code, with hours,
 * phone numbers and a map (OpenStreetMap). Readings never leave the phone; searches and a rounded location do.
 */
final class CarePage {
    interface Host {
        void refresh();

        void openCategory(String id);   // shows category(); Back returns to the tab
    }

    static final int REQ_LOCATION = 3;

    private final Activity a;
    private final Host host;
    int tab;   // 0 meds, 1 services
    private LinearLayout page;   // the column last built here: async answers only redraw while it's on screen
    private Runnable toCare;     // from a category page back to the tab

    // Services: where to search (the phone, or a ZIP), what was found, and the filters
    private boolean openNow, notNow, asked, locating, locFailed, geocoding;
    private CareData.PlaceType type = CareData.PlaceType.ALL;
    private String zip = "", zipError;
    private double[] spot;
    private boolean spotIsPhone;
    private String spotName;
    private List<Nearby.Place> places;
    private boolean placesDone, placesOk = true;
    private LinearLayout placesBox;
    private Nearby.MapView map;
    private TextView spotLabel;

    CarePage(Activity a, Host host) {
        this.a = a;
        this.host = host;
    }

    /** Re-render, but only if this tab is what's on screen (an answer can land after the user moved on). */
    private void redraw() {
        if (page != null && page.isAttachedToWindow()) host.refresh();
    }

    // ---- The tab --------------------------------------------------------------------------------

    void build(LinearLayout col) {
        page = col;
        toCare = null;
        LinearLayout head = Ui.add(col, Ui.row(a), 0);
        TextView t = Ui.title(a, "Care");
        t.setTextSize(34);
        head.addView(t, weighted(0));
        head.addView(Ui.mascot(a, "cares", 76));   // Apollo holding his heart, on the page's ivory
        Ui.add(col, Ui.segmented(a, new String[]{"Meds & vitamins", "Services & hospitals"}, tab, i -> {
            tab = i;
            host.refresh();
        }), 12);
        if (tab == 0) meds(col);
        else services(col);
    }

    // ---- Meds & vitamins ------------------------------------------------------------------------

    private void meds(LinearLayout col) {
        searchBar(col, "", 18);
        if (!Net.online(a)) offline(col, "Offline · showing saved products. Search needs a connection.");

        List<CareModel.Signal> signals = CareSignals.load(a);
        CareModel.Readings readings = CareSignals.readings(a);
        LinearLayout pfy = Ui.add(col, Ui.row(a), 26);
        pfy.addView(Ui.styled(a, "Picked for you", 21, Ui.TEXT, 700), weighted(0));
        pfy.addView(pickedInfo(signals, readings));
        List<CareModel.Shelf> shelves = CareSignals.model().rank(signals, readings, SkinToCore.FEVER_FLAG_C, 3);
        java.util.Set<String> viewed = new java.util.HashSet<>();
        for (CareModel.Signal sg : signals) if (sg.kind == CareModel.Kind.VIEW) viewed.add(sg.text);
        boolean kids = CareModel.aboutChildren(signals);
        for (CareModel.Shelf sh : shelves) shelf(col, sh, kids, viewed);

        Ui.add(col, Ui.styled(a, "Shop by need", 21, Ui.TEXT, 700), 30);
        LinearLayout grid = Ui.add(col, Ui.column(a), 12);
        LinearLayout row = null;
        for (int i = 0; i < CareData.CATALOG.size(); i++) {
            CareData.Category cat = CareData.CATALOG.get(i);
            if (i % 2 == 0) {
                row = Ui.add(grid, Ui.row(a), i == 0 ? 0 : 12);
                row.setBaselineAligned(false);
            }
            LinearLayout tile = Ui.card(a);
            Ui.tappable(tile, v -> browse(cat.id));
            tile.addView(Ui.iconBadge(a, cat.icon, i % 4 == 0 || i % 4 == 3 ? Ui.CORAL : Ui.LEAF_DARK, 38));
            TextView name = Ui.add(tile, Ui.styled(a, cat.title, 15, Ui.TEXT, 600), 12);
            name.setMinLines(2);
            TextView hint = Ui.add(tile, Ui.text(a, cat.hint, 12.5f, Ui.MUTED, false), 2);
            hint.setSingleLine(true);
            hint.setEllipsize(TextUtils.TruncateAt.END);
            tile.setContentDescription(cat.title + ": " + cat.hint);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i % 2 == 1) lp.leftMargin = Ui.dp(a, 12);
            row.addView(tile, lp);
        }
    }

    /** The (i) beside "Picked for you": how the picks are made, what they've learned this week, and a way to forget it. */
    private View pickedInfo(List<CareModel.Signal> signals, CareModel.Readings r) {
        IconView i = Ui.icon(a, "info", Ui.MUTED, 32);
        int p = Ui.dp(a, 6);
        i.setPadding(p, p, p, p);
        i.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 16)));
        i.setContentDescription("How Apollo picks these");
        Ui.tappable(i, v -> {
            Info.Sheet sheet = new Info.Sheet(a).title("How Apollo picks these").body("Apollo learns on this phone from your "
                    + "readings, your chats with him, what you search and what you open. Each shelf says what it's based on; hide "
                    + "one and he learns from that too. Ideas, not medical advice.\n\nPhotos and recent online prices come from "
                    + "UPCitemdb, drug facts from the FDA's labels. Only what you search for leaves the phone, never your readings. "
                    + "Apollo doesn't sell anything: DoorDash or Uber Eats handle an order.");
            String[] learned = learned(signals, r);
            LinearLayout row = Ui.add(sheet.col, Ui.row(a), 16);
            row.setBackground(Ui.rounded(Ui.LEAF_TINT, Ui.dp(a, 14)));
            row.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
            row.addView(Ui.icon(a, "leaf", Ui.LEAF_DARK, 16));
            TextView t = Ui.styled(a, learned[0], 14, Ui.LEAF_DARK, 600);
            t.setPadding(Ui.dp(a, 8), 0, 0, 0);
            row.addView(t, weighted(0));
            if (learned[1] != null) {
                Ui.add(sheet.col, Ui.button(a, "Reset what Apollo learned", Ui.MUTED, false, b -> {
                    CareSignals.clear(a);
                    sheet.dismiss();
                    host.refresh();
                }), 12);
            }
            sheet.show();
        });
        return i;
    }

    /** {"Learning from 3 chat messages, 1 search and 2 readings this week", non-null if there's anything to reset}. */
    private static String[] learned(List<CareModel.Signal> signals, CareModel.Readings r) {
        int chats = 0, searches = 0, browsing = 0, readings = 0;
        for (CareModel.Signal s : signals) {
            if (s.ageH > 168) continue;
            if (s.kind == CareModel.Kind.CHAT) chats++;
            else if (s.kind == CareModel.Kind.SEARCH) searches++;
            else if (s.kind == CareModel.Kind.VIEW || s.kind == CareModel.Kind.OPEN || s.kind == CareModel.Kind.DELIVER) browsing++;
        }
        if (r.tempAgeH < 72) readings++;
        if (r.bpmAgeH < 72) readings++;
        if (r.breathAgeH < 72) readings++;
        if (r.steps > 0) readings++;
        if (r.screenMin >= 20) readings++;
        List<String> parts = new ArrayList<>();
        if (chats > 0) parts.add(chats + (chats == 1 ? " chat message" : " chat messages"));
        if (searches > 0) parts.add(searches + (searches == 1 ? " search" : " searches"));
        if (browsing > 0) parts.add(browsing + (browsing == 1 ? " product viewed" : " products viewed"));
        if (readings > 0) parts.add(readings + (readings == 1 ? " reading" : " readings"));
        String list = parts.isEmpty() ? "" : parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        return new String[]{parts.isEmpty() ? "Nothing learned yet. Chat with Apollo or search, and these adapt."
                : "Learning from " + list + " this week.", chats + searches + browsing > 0 ? "reset" : null};
    }

    /** Open a category, remembering the interest. */
    private void browse(String categoryId) {
        CareSignals.log(a, CareModel.Kind.OPEN, null, categoryId);
        host.openCategory(categoryId);
    }

    /**
     * A need's shelf, the way a delivery app lays out a store: a white box with the need's name, what it's based
     * on in a few words, a way to hide it (the model learns from that too), and a sideways row of labelled products.
     */
    private void shelf(LinearLayout col, CareModel.Shelf sh, boolean kids, java.util.Set<String> viewed) {
        CareData.Need n = sh.need;
        LinearLayout box = Ui.add(col, Ui.card(a), 14);
        box.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 16));
        box.setClipToOutline(true);   // tiles scroll out under the rounded edge
        LinearLayout head = Ui.add(box, Ui.row(a), 0);
        head.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 14), 0);
        head.addView(Ui.styled(a, n.title, 18, Ui.TEXT, 700), weighted(0));
        TextView all = Ui.styled(a, "See all  ›", 14, Ui.CORAL_DARK, 600);
        all.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        all.setOnClickListener(v -> browse(n.categoryId));
        head.addView(all);
        LinearLayout sub = Ui.add(box, Ui.row(a), 2);
        sub.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 14), 0);
        sub.addView(Ui.text(a, because(sh), 13.5f, Ui.MUTED, false), weighted(0));
        if (!sh.reasons.isEmpty()) {
            TextView hide = Ui.styled(a, "Hide", 13, Ui.FAINT, 600);
            hide.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
            hide.setContentDescription("Hide " + n.title + " for a few days");
            hide.setOnClickListener(v -> {
                CareSignals.log(a, CareModel.Kind.DISMISS, null, n.id);
                host.refresh();
            });
            sub.addView(hide);
        }

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(Ui.dp(a, 18), 0, Ui.dp(a, 6), 0);
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.TOP);
        hs.addView(row);
        Ui.add(box, hs, 14);
        for (int k = 0; k < 3; k++) row.addView(skeleton(148, 196), cardParams());
        CareApi.shelf(a, sh.queries, 3, (items, complete, ok) -> {
            row.removeAllViews();
            List<CareApi.Item> shown = new ArrayList<>(items);
            if (!kids) shown.sort(java.util.Comparator.comparingInt(it -> CareModel.forChildren(it.name) ? 1 : 0));   // grown-ups' first
            String[] labels = labels(shown, viewed, sh.reasons.isEmpty());
            for (int k = 0; k < shown.size(); k++) row.addView(productCard(shown.get(k), n.id, labels[k]), cardParams());
            if (!complete) row.addView(skeleton(148, 196), cardParams());
            else if (items.isEmpty()) row.addView(shelfMessage(ok ? "Nothing to show here yet." : "Can't reach the store list right now."));
        });
    }

    /** What a shelf rests on, in a few words: "Based on your temperature and your chat with Apollo". */
    private static String because(CareModel.Shelf sh) {
        if (sh.reasons.isEmpty()) return "Worth having at home";
        List<String> words = new ArrayList<>();
        for (String src : sh.sources(0.15)) {
            String w = src.equals("temperature") ? "your temperature" : src.equals("heart") ? "your pulse"
                    : src.equals("steps") ? "your steps today" : src.equals("breath") ? "your breath strength"
                    : src.equals("eyes") ? "your screen time" : src.equals("chat") ? "your chat with Apollo"
                    : src.equals("apollo") ? "what Apollo suggested" : src.equals("search") ? "your searches" : "what you viewed";
            if (!words.contains(w) && words.size() < 2) words.add(w);
        }
        if (words.isEmpty()) words.add(sh.reasons.get(0).source.equals("browse") ? "what you viewed" : "your readings");
        return "Based on " + String.join(" and ", words);
    }

    /** One label per tile, delivery-app style: the shelf's top pick, ones you opened, the cheapest, children's. */
    private static String[] labels(List<CareApi.Item> items, java.util.Set<String> viewed, boolean basic) {
        String[] out = new String[items.size()];
        int cheapest = -1;
        for (int k = 0; k < items.size(); k++) {
            if (items.get(k).price > 0 && (cheapest < 0 || items.get(k).price < items.get(cheapest).price)) cheapest = k;
            if (viewed.contains(items.get(k).name)) out[k] = "You viewed";
        }
        if (!basic && !items.isEmpty() && out[0] == null) out[0] = "#1 pick";
        if (cheapest >= 0 && out[cheapest] == null && items.size() > 1) out[cheapest] = "Best price";
        for (int k = 0; k < items.size(); k++) if (out[k] == null && CareModel.forChildren(items.get(k).name)) out[k] = "Kids'";
        return out;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 148), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(a, 12);
        return lp;
    }

    private View shelfMessage(String s) {
        TextView t = Ui.text(a, s, 13.5f, Ui.MUTED, false);
        t.setPadding(Ui.dp(a, 4), Ui.dp(a, 20), Ui.dp(a, 4), Ui.dp(a, 20));
        return t;
    }

    /** A calm amber pill: no connection, and what still works. */
    private void offline(LinearLayout col, String s) {
        LinearLayout r = Ui.wrap(col, Ui.row(a), 12);
        r.setBackground(Ui.rounded(Ui.WARN_TINT, Ui.dp(a, 100)));
        r.setPadding(Ui.dp(a, 12), Ui.dp(a, 7), Ui.dp(a, 14), Ui.dp(a, 7));
        r.addView(Ui.icon(a, "info", Ui.WARN, 16));
        TextView t = Ui.styled(a, s, 13, Ui.WARN, 600);
        t.setPadding(Ui.dp(a, 7), 0, 0, 0);
        r.addView(t);
    }

    /** A shelf product, delivery-app style: the photo with its ＋, the name, price and size, and a label. */
    private View productCard(CareApi.Item it, String needId, String label) {
        LinearLayout card = Ui.column(a);
        card.addView(photoBox(it, 148, 128, true), new LinearLayout.LayoutParams(Ui.dp(a, 148), Ui.dp(a, 128)));
        TextView name = Ui.add(card, Ui.styled(a, it.name, 14, Ui.TEXT, 700), 8);
        name.setMaxLines(2);
        name.setMinLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        String money = it.price > 0 ? CareLogic.price(it.price) : "Price varies";
        SpannableString line = new SpannableString(money + (it.size.isEmpty() ? "" : "  ·  " + it.size));
        line.setSpan(new ForegroundColorSpan(Ui.MUTED), it.price > 0 ? money.length() : 0, line.length(), 0);
        TextView price = Ui.add(card, Ui.styled(a, "", 13.5f, Ui.TEXT, 600), 3);
        price.setText(line);
        price.setSingleLine(true);
        price.setEllipsize(TextUtils.TruncateAt.END);
        if (label != null) {
            TextView l = Ui.styled(a, label, 11.5f, label.startsWith("#") ? Ui.CORAL_DARK : Ui.TEXT, 700);
            l.setBackground(Ui.rounded(label.startsWith("#") ? Ui.CORAL_TINT : Ui.CARD_2, Ui.dp(a, 6)));
            l.setPadding(Ui.dp(a, 7), Ui.dp(a, 3), Ui.dp(a, 7), Ui.dp(a, 3));
            Ui.wrap(card, l, 6);
        }
        card.setPadding(0, 0, 0, Ui.dp(a, 4));
        card.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 16)));
        Ui.tappable(card, v -> sheet(it, needId));
        card.setContentDescription(it.name + (it.price > 0 ? ", about " + CareLogic.price(it.price) : "") + (label != null ? ", " + label : ""));
        return card;
    }

    /** The product photo on white, a faint pill while it loads; optionally the round ＋ of a delivery app. */
    private FrameLayout photoBox(CareApi.Item it, int widthDp, int heightDp, boolean plus) {
        FrameLayout f = new FrameLayout(a);
        GradientDrawable bg = Ui.rounded(Ui.CARD, Ui.dp(a, 16));
        bg.setStroke(Ui.dp(a, 1), Ui.LINE);
        f.setBackground(bg);
        f.addView(Ui.icon(a, "pill", Ui.LINE, 30), new FrameLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30), Gravity.CENTER));
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int m = Ui.dp(a, heightDp > 150 ? 16 : 10);
        ip.setMargins(m, m, m, m);
        f.addView(img, ip);
        Net.photo(img, it.photo, Ui.dp(a, Math.max(widthDp, heightDp)));
        if (plus) {
            IconView add = new IconView(a, "plus", Ui.CORAL_DARK);
            add.setBackground(Ui.rounded(Ui.CARD, Ui.dp(a, 16)));
            int pad = Ui.dp(a, 7);
            add.setPadding(pad, pad, pad, pad);
            Ui.lift(add, 3);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32), Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, Ui.dp(a, 7), Ui.dp(a, 7));
            f.addView(add, lp);
        }
        return f;
    }

    /** A soft grey block that breathes while its content loads. */
    private View skeleton(int widthDp, int heightDp) {
        View v = new View(a);
        v.setBackground(Ui.rounded(Ui.CARD_2, Ui.dp(a, 16)));
        v.setMinimumHeight(Ui.dp(a, heightDp));
        v.setMinimumWidth(Ui.dp(a, widthDp));
        ObjectAnimator pulse = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.45f);
        pulse.setDuration(800);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
        return v;
    }

    /** "Search Tylenol, Pedialyte, eye drops…": a live product search. */
    private void searchBar(LinearLayout col, String prefill, int topDp) {
        LinearLayout bar = Ui.add(col, Ui.row(a), topDp);
        GradientDrawable bg = Ui.rounded(Ui.CARD, Ui.dp(a, 100));
        bg.setStroke(Ui.dp(a, 1), Ui.LINE);
        bar.setBackground(bg);
        Ui.lift(bar, 1);
        bar.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 8), 0);
        bar.addView(Ui.icon(a, "search", Ui.MUTED, 20));
        EditText in = Ui.textInput(a, "Search Tylenol, Pedialyte, eye drops…");
        in.setBackground(null);
        in.setTextSize(15.5f);
        in.setText(prefill);
        in.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        in.setPadding(Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14));
        in.setOnEditorActionListener((v, action, e) -> {
            String q = in.getText().toString().trim();
            if (q.length() < 2) return true;
            a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(in.getWindowToken(), 0);
            CareSignals.log(a, CareModel.Kind.SEARCH, q, null);
            host.openCategory("q:" + q);
            return true;
        });
        bar.addView(in, weighted(0));
    }

    /** A category's products (or a search's, for "q:<words>"), as a delivery app's store list. */
    void category(LinearLayout col, String id, Runnable back) {
        page = col;
        toCare = back;
        boolean search = id.startsWith("q:");
        CareData.Category cat = search ? null : CareData.category(id);
        String q = search ? id.substring(2) : null;
        TextView link = Ui.add(col, Ui.styled(a, "‹  Care", 15, Ui.TEXT, 500), 0);
        link.setPadding(0, Ui.dp(a, 10), Ui.dp(a, 24), Ui.dp(a, 10));
        link.setOnClickListener(v -> back.run());
        TextView title = Ui.add(col, Ui.title(a, search ? "“" + q + "”" : cat != null ? cat.title : "Products"), 6);
        title.setMaxLines(2);
        if (cat != null) Ui.add(col, Ui.text(a, cat.about, 15, Ui.MUTED, false), 6);
        searchBar(col, search ? q : "", 16);

        LinearLayout list = Ui.add(col, Ui.card(a), 14);
        list.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 4));
        for (int i = 0; i < 4; i++) Ui.add(list, skeleton(0, 96), i == 0 ? 12 : 10);
        List<String> queries = search ? java.util.Collections.singletonList(q) : cat != null ? cat.queries : new ArrayList<>();
        String needId = search ? CareSignals.model().text.top(q) : id;
        CareApi.shelf(a, queries, 8, (items, complete, ok) -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) list.addView(divider());
                list.addView(productRow(items.get(i), needId));
            }
            if (!complete) Ui.add(list, skeleton(0, 96), 12);
            else if (items.isEmpty()) emptyList(list, ok, q);
        });
    }

    private void emptyList(LinearLayout list, boolean ok, String q) {
        LinearLayout box = Ui.add(list, Ui.column(a), 0);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(a, 18), 0, Ui.dp(a, 18));
        box.addView(Ui.mascot(a, ok ? "thinking" : "wink", 80));
        Ui.add(box, Ui.styled(a, ok ? (q != null ? "No matches for “" + q + "”" : "Nothing here yet") : "Can't reach the store list",
                17, Ui.TEXT, 600), 10).setGravity(Gravity.CENTER);
        Ui.add(box, Ui.text(a, ok ? "Try a brand name, like Tylenol, Advil or Claritin." : "Check the connection, then try again.",
                14, Ui.MUTED, false), 4).setGravity(Gravity.CENTER);
        if (!ok) Ui.add(box, Ui.button(a, "Try again", Ui.CORAL, true, v -> host.refresh()), 14);
    }

    private View divider() {
        View d = new View(a);
        d.setBackgroundColor(Ui.LINE);
        d.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return d;
    }

    /** Name, price and size on the left, the photo with its ＋ on the right. */
    private View productRow(CareApi.Item it, String needId) {
        LinearLayout r = Ui.row(a);
        r.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 14));
        LinearLayout t = Ui.column(a);
        TextView name = Ui.styled(a, it.name, 15.5f, Ui.TEXT, 600);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name);
        if (it.price > 0) {
            SpannableString s = new SpannableString(CareLogic.price(it.price) + "  ·  " + it.store);
            s.setSpan(new ForegroundColorSpan(Ui.MUTED), CareLogic.price(it.price).length(), s.length(), 0);
            Ui.add(t, Ui.styled(a, "", 14.5f, Ui.TEXT, 600), 6).setText(s);
        } else {
            Ui.add(t, Ui.styled(a, "Price varies by store", 14, Ui.MUTED, 500), 6);
        }
        String sub = it.size.isEmpty() ? it.brand : it.size;
        if (!sub.isEmpty()) Ui.add(t, Ui.text(a, sub, 13, Ui.MUTED, false), 3);
        r.addView(t, weighted(0));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(a, 96), Ui.dp(a, 96));
        pl.leftMargin = Ui.dp(a, 14);
        r.addView(photoBox(it, 96, 96, true), pl);
        r.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 12)));
        Ui.tappable(r, v -> sheet(it, needId));
        return r;
    }

    // ---- The product sheet ----------------------------------------------------------------------

    /** A bottom sheet: the big photo, price, what the FDA label says, and ways to get it. Opening it is remembered. */
    private void sheet(CareApi.Item it, String needId) {
        CareSignals.log(a, CareModel.Kind.VIEW, it.name, null);
        String need = CareData.need(needId) != null ? needId : null;
        Info.Sheet d = new Info.Sheet(a);
        LinearLayout col = d.col;
        FrameLayout pic = photoBox(it, 320, 220, false);
        Ui.add(col, pic, 14).getLayoutParams().height = Ui.dp(a, 220);
        Ui.add(col, Ui.styled(a, it.name, 22, Ui.TEXT, 700), 16);
        String sub = it.size.isEmpty() ? it.brand : (it.brand.isEmpty() ? it.size : it.brand + " · " + it.size);
        if (!sub.isEmpty()) Ui.add(col, Ui.text(a, sub, 14, Ui.MUTED, false), 4);
        LinearLayout price = Ui.add(col, Ui.row(a), 12);
        price.setGravity(Gravity.BOTTOM);
        price.addView(Ui.styled(a, it.price > 0 ? CareLogic.price(it.price) : "Price varies", 24, it.price > 0 ? Ui.TEXT : Ui.MUTED, 700));
        if (it.price > 0) {
            TextView at = Ui.styled(a, "recent price at " + it.store, 13.5f, Ui.MUTED, 500);
            at.setPadding(Ui.dp(a, 8), 0, 0, Ui.dp(a, 3));
            price.addView(at);
        }

        Ui.add(col, divider(), 18);
        LinearLayout facts = Ui.add(col, Ui.column(a), 16);
        Ui.add(facts, Ui.text(a, "Reading the FDA label…", 14, Ui.FAINT, false), 0);
        CareApi.label(a, it, l -> {
            facts.removeAllViews();
            if (l == null) {
                Ui.add(facts, Ui.text(a, Net.online(a) ? "No FDA drug label matches this exact product. Check the package for directions."
                        : "The FDA label needs a connection. The package has the same directions.", 13.5f, Ui.MUTED, false), 0);
                return;
            }
            Ui.add(facts, Ui.label(a, "From the FDA label · " + l.brand), 0);
            fact(facts, "What it's for", l.purpose.isEmpty() ? l.uses : l.purpose + (l.uses.isEmpty() ? "" : ". " + l.uses));
            fact(facts, "Active ingredient", l.active);
            fact(facts, "Warnings", l.warnings);
            if (!l.setId.isEmpty()) {
                TextView full = Ui.add(facts, Ui.styled(a, "Read the full label  ›", 14, Ui.CORAL_DARK, 600), 10);
                full.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
                full.setOnClickListener(v -> open("https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=" + l.setId));
            }
        });

        LinearLayout note = Ui.add(col, Ui.tintCard(a, Ui.WARN_TINT), 16);
        note.addView(Ui.styled(a, "Ask a pharmacist first if you take other medicines, are pregnant, or are buying for a child.",
                13.5f, Ui.TEXT, 500));

        Ui.add(col, Ui.label(a, "Get it delivered"), 22);
        String q = CareLogic.deliveryQuery(it.name);
        LinearLayout two = Ui.add(col, Ui.row(a), 10);
        two.addView(small("DoorDash", null, true, v -> {
            CareSignals.log(a, CareModel.Kind.DELIVER, it.name, need);
            deliver(true, q);
        }), weighted(0));
        two.addView(small("Uber Eats", null, false, v -> {
            CareSignals.log(a, CareModel.Kind.DELIVER, it.name, need);
            deliver(false, q);
        }), weighted(10));
        Ui.add(col, Ui.text(a, "Opens " + (installed("com.dd.doordash") || installed("com.ubercab.eats") ? "the app" : "their store in Apollo")
                + " searching for “" + q + "”. They handle the order.", 12.5f, Ui.FAINT, false), 8);
        TextView near = Ui.add(col, small("Find a pharmacy near you", "pin", false, v -> {
            d.dismiss();
            tab = 1;
            type = CareData.PlaceType.PHARMACY;
            if (toCare != null) toCare.run();
            else host.refresh();
        }), 12);
        near.setContentDescription("Find a pharmacy near you");

        d.show();
    }

    private void fact(LinearLayout facts, String head, String body) {
        if (body == null || body.isEmpty()) return;
        Ui.add(facts, Ui.styled(a, head, 14.5f, Ui.TEXT, 600), 12);
        Ui.add(facts, Ui.text(a, body, 14, Ui.MUTED, false), 3);
    }

    /**
     * DoorDash or Uber Eats, searching for the product. Their app if it's installed (straight to the search when it
     * takes the link; else opened, with the product's name copied to paste), otherwise their store inside Apollo.
     * Neither offers a public ordering API, so the order itself happens with them.
     */
    private void deliver(boolean doordash, String q) {
        String pkg = doordash ? "com.dd.doordash" : "com.ubercab.eats", name = doordash ? "DoorDash" : "Uber Eats";
        String url = doordash ? "https://www.doordash.com/search/store/" + Uri.encode(q) + "/"
                : "https://www.ubereats.com/search?q=" + Uri.encode(q);
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        Intent launch = a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            android.content.ClipboardManager clip = a.getSystemService(android.content.ClipboardManager.class);
            clip.setPrimaryClip(android.content.ClipData.newPlainText("product", q));
            android.widget.Toast.makeText(a, "Opening " + name + ": search for “" + q + "” (copied)", android.widget.Toast.LENGTH_LONG).show();
            a.startActivity(launch);
            return;
        }
        inApp(url);
    }

    private boolean installed(String pkg) {
        return a.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /** A page shown inside Apollo: a Chrome Custom Tab over this screen in Apollo's ivory (Back returns), else the browser. */
    private void inApp(String url) {
        Intent tab = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBinder("android.support.customtabs.extra.SESSION", null);   // this extra is what makes it a Custom Tab
        tab.putExtras(extras);
        tab.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", Ui.BG);
        tab.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
        tab.putExtra("androidx.browser.customtabs.extra.SHARE_STATE", 2);
        tab.setPackage("com.android.chrome");
        try {
            a.startActivity(tab);
        } catch (ActivityNotFoundException e) {
            open(url);
        }
    }

    // ---- Services & hospitals -------------------------------------------------------------------

    private boolean located() {
        return a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** From MainActivity.onRequestPermissionsResult. */
    void locationResult(boolean granted) {
        asked = true;
        notNow = false;
        locFailed = false;
        if (granted) startLocating();
        host.refresh();
    }

    private void startLocating() {
        locating = true;
        Nearby.locate(a, loc -> {
            locating = false;
            if (loc == null) locFailed = true;
            else setSpot(loc.getLatitude(), loc.getLongitude(), true);
            redraw();
        });
    }

    /** Search around here: name the area, then find care (the list fills in as each search lands). */
    private void setSpot(double lat, double lon, boolean phone) {
        spot = new double[]{lat, lon};
        spotIsPhone = phone;
        spotName = null;
        List<Nearby.Place> known = Nearby.known(lat, lon);
        places = known;
        placesDone = known != null;
        placesOk = true;
        Nearby.name(a, lat, lon, n -> {
            spotName = n;
            if (spotLabel != null && n != null) spotLabel.setText(spotIsPhone ? "Near " + n : n);
        });
        Nearby.search(a, lat, lon, (list, complete, ok) -> {
            places = list;
            placesDone = complete;
            placesOk = ok;
            if (placesBox != null && placesBox.isAttachedToWindow()) fillPlaces(placesBox);
        });
    }

    private void services(LinearLayout col) {
        placesBox = null;
        map = null;
        spotLabel = null;
        sos(col);   // emergencies don't wait for filters: pinned at the bottom of the screen
        if (!Net.online(a)) offline(col, "Offline · finding places needs a connection.");
        if (spot == null && !locating && located() && !notNow && !locFailed) startLocating();
        if (spot != null) {
            results(col);
        } else if (locating) {
            LinearLayout wait = Ui.add(col, Ui.card(a), 14);
            wait.setGravity(Gravity.CENTER_HORIZONTAL);
            wait.addView(Ui.mascot(a, "thinking", 84));
            Ui.add(wait, Ui.styled(a, "Finding care near you…", 17, Ui.TEXT, 600), 10).setGravity(Gravity.CENTER);
            Ui.add(wait, Ui.text(a, "Hospitals, urgent care, clinics and pharmacies within a few miles.", 14, Ui.MUTED, false), 4)
                    .setGravity(Gravity.CENTER);
            for (int i = 0; i < 2; i++) Ui.add(col, skeleton(0, 150), 12);
        } else if (notNow || locFailed) {
            manualSearch(col);
        } else if (asked) {
            LinearLayout off = Ui.add(col, Ui.card(a), 14);
            off.setGravity(Gravity.CENTER_HORIZONTAL);
            off.addView(Ui.mascot(a, "thinking", 84));
            Ui.add(off, Ui.styled(a, "Location is off, turn it on to see nearby places", 16, Ui.TEXT, 600), 10).setGravity(Gravity.CENTER);
            Ui.add(off, Ui.button(a, "Try again", Ui.CORAL, true, v -> askLocation()), 14);
            TextView z = Ui.add(off, Ui.styled(a, "Search by ZIP or city instead", 14, Ui.MUTED, 500), 10);
            z.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            z.setOnClickListener(v -> {
                notNow = true;
                host.refresh();
            });
        } else {
            // The pre-permission ask, inline: Apollo on the same white as his card.
            LinearLayout ask = Ui.add(col, Ui.card(a), 14);
            ask.setGravity(Gravity.CENTER_HORIZONTAL);
            ask.addView(Ui.mascot(a, "hello", 96));
            Ui.add(ask, Ui.styled(a, "Find care near you", 22, Ui.TEXT, 700), 10).setGravity(Gravity.CENTER);
            Ui.add(ask, Ui.text(a, "Allow Apollo to use your approximate location to show nearby hospitals, clinics and pharmacies. "
                    + "It's only used while the app is open.", 14.5f, Ui.MUTED, false), 6).setGravity(Gravity.CENTER);
            Ui.add(ask, Ui.button(a, "Use my location", Ui.CORAL, true, v -> askLocation()), 16);
            Ui.add(ask, Ui.button(a, "Search by ZIP or city", Ui.MUTED, false, v -> {
                notNow = true;
                host.refresh();
            }), 10);
        }
        View room = new View(a);   // room to scroll the last card out from under the pinned emergency bar
        Ui.add(col, room, 0).getLayoutParams().height = Ui.dp(a, 84);
    }

    /**
     * "Emergency? Call 911", pinned above the tab bar for as long as this Services page is on screen: added to the
     * activity's root over the scrolling page, and removed when the page goes.
     */
    private void sos(LinearLayout col) {
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) return;
        if (!(((ViewGroup) content).getChildAt(0) instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) ((ViewGroup) content).getChildAt(0);
        View nav = root.getChildAt(root.getChildCount() - 1);   // Home's floating tab bar, added last
        LinearLayout bar = Ui.row(a);
        bar.setBackground(Ui.rounded(Ui.CORAL_TINT, Ui.dp(a, 22)));
        bar.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));
        Ui.lift(bar, 4);
        LinearLayout words = Ui.column(a);
        words.addView(Ui.styled(a, "Emergency?", 15, Ui.CORAL_DARK, 700));
        words.addView(Ui.text(a, "Chest pain, trouble breathing, stroke signs", 12.5f, Ui.TEXT, false));
        bar.addView(words, weighted(0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(a, 10);
        bar.addView(small("Call 911", "call", true, v -> dial("911")), cl);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 110));
        root.addView(bar, lp);
        bar.post(() -> {   // just above the tab bar, wherever it sits
            if (nav.getHeight() == 0) return;
            lp.bottomMargin = root.getHeight() - nav.getTop() + Ui.dp(a, 10);
            bar.setLayoutParams(lp);
        });
        col.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                root.removeView(bar);
                col.removeOnAttachStateChangeListener(this);
            }
        });
    }

    private void askLocation() {
        a.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    private void manualSearch(LinearLayout col) {
        LinearLayout card = Ui.add(col, Ui.card(a), 14);
        card.addView(Ui.styled(a, "Search by ZIP or city", 17, Ui.TEXT, 600));
        if (locFailed) {
            Ui.add(card, Ui.text(a, "Couldn't get this phone's location. Check that location is on, or search instead.", 13.5f,
                    Ui.MUTED, false), 4);
        }
        EditText in = Ui.add(card, Ui.textInput(a, "ZIP code or city, e.g. 98105"), 12);
        in.setText(zip);
        in.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        Runnable go = () -> {
            zip = in.getText().toString().trim();
            if (zip.isEmpty() || geocoding) return;
            a.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(in.getWindowToken(), 0);
            geocoding = true;
            zipError = null;
            host.refresh();
            Nearby.geocode(a, zip, ll -> {
                geocoding = false;
                if (ll == null) zipError = "Couldn't find “" + zip + "”. Try a 5-digit ZIP code.";
                else {
                    notNow = false;
                    locFailed = false;
                    setSpot(ll[0], ll[1], false);
                }
                redraw();
            });
        };
        in.setOnEditorActionListener((v, action, e) -> {
            go.run();
            return true;
        });
        Ui.add(card, Ui.button(a, geocoding ? "Searching…" : "Search", Ui.CORAL, true, v -> go.run()), 12);
        if (zipError != null) Ui.add(card, Ui.text(a, zipError, 13.5f, Ui.BAD, false), 8);
        TextView use = Ui.add(card, Ui.styled(a, "Use my location instead", 14.5f, Ui.CORAL_DARK, 600), 10);
        use.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 2));
        use.setOnClickListener(v -> {
            notNow = false;
            locFailed = false;
            if (located()) {
                startLocating();
                host.refresh();
            } else {
                askLocation();
            }
        });
    }

    /** Where we're searching, the map, the filters and the places (the last three refill as answers land). */
    private void results(LinearLayout col) {
        LinearLayout where = Ui.add(col, Ui.row(a), 16);
        where.addView(Ui.icon(a, "pin", Ui.LEAF_DARK, 18));
        String area = spotName != null ? spotName : spotIsPhone ? "your location" : zip;
        spotLabel = Ui.styled(a, spotIsPhone ? "Near " + area : area, 14.5f, Ui.TEXT, 600);
        spotLabel.setSingleLine(true);
        spotLabel.setEllipsize(TextUtils.TruncateAt.END);
        where.addView(spotLabel, weighted(8));
        TextView change = Ui.styled(a, "Change", 14, Ui.CORAL_DARK, 600);
        change.setPadding(Ui.dp(a, 12), Ui.dp(a, 6), 0, Ui.dp(a, 6));
        change.setOnClickListener(v -> {
            spot = null;
            notNow = true;
            host.refresh();
        });
        where.addView(change);

        map = new Nearby.MapView(a);
        Ui.add(col, map, 12).getLayoutParams().height = Ui.dp(a, 320);
        Ui.lift(map, 1.5f);
        map.setOnClickListener(v -> mapsSearch(type.mapsQuery));
        map.setContentDescription("Map of care nearby. Tap to open it in Maps.");

        placesBox = Ui.add(col, Ui.column(a), 0);
        fillPlaces(placesBox);
    }

    private void fillPlaces(LinearLayout box) {
        box.removeAllViews();
        List<Nearby.Place> all = places == null ? new ArrayList<>() : places;
        List<CareData.PlaceType> present = new ArrayList<>();
        for (Nearby.Place p : all) if (!present.contains(p.type)) present.add(p.type);
        if (type != CareData.PlaceType.ALL && !present.contains(type) && placesDone) type = CareData.PlaceType.ALL;

        LinearLayout chips = Ui.row(a);
        for (CareData.PlaceType t : CareData.PlaceType.values()) {
            if (t != CareData.PlaceType.ALL && !present.contains(t) && t != type) continue;
            Ui.inRow(chips, Ui.choice(a, t.label, type == t, v -> {
                type = t;
                host.refresh();
            }), 8);
        }
        Ui.bleed(box, chips, 14);

        LinearLayout open = Ui.add(box, Ui.row(a), 2);
        open.addView(Ui.styled(a, "Open now", 15, Ui.TEXT, 500), weighted(0));
        Switch sw = new Switch(a);
        sw.setChecked(openNow);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{Ui.CORAL, 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{0x66E8705E, Ui.LINE}));
        sw.setOnCheckedChangeListener((b, on) -> {
            openNow = on;
            host.refresh();
        });
        open.addView(sw);
        Ui.tappable(open, v -> sw.toggle());
        open.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));

        Ui.wrap(box, Info.link(a, "Which should I choose?", "Which should I choose?", WHICH), 2);

        List<Nearby.Place> shown = new ArrayList<>();
        for (Nearby.Place p : all) {
            if (type != CareData.PlaceType.ALL && p.type != type) continue;
            if (openNow && !Boolean.TRUE.equals(p.openNow())) continue;
            shown.add(p);
        }
        if (map != null) map.show(spot[0], spot[1], spotIsPhone, shown);
        int n = Math.min(shown.size(), 25);
        for (int i = 0; i < n; i++) place(box, shown.get(i));
        if (!placesDone) {
            Ui.add(box, skeleton(0, 150), 12);
        } else if (shown.isEmpty()) {
            Ui.add(box, Ui.text(a, !placesOk ? "Can't reach the map service right now. Check the connection and try again."
                    : all.isEmpty() ? "No hospitals, clinics or pharmacies found within a few miles."
                    : "Nothing matches these filters. Try “All” or turn off “Open now”.", 14, Ui.MUTED, false), 16);
            if (!placesOk) Ui.add(box, Ui.button(a, "Try again", Ui.CORAL, true, v -> {
                setSpot(spot[0], spot[1], spotIsPhone);
                host.refresh();
            }), 12);
        }
    }

    private static final CharSequence WHICH;

    static {
        String[][] lines = {
                {"ER", "life-threatening or severe symptoms, any time of day."},
                {"Urgent care", "same-day help for things that can't wait for an appointment."},
                {"Clinic", "check-ups, ongoing care and follow-ups."},
                {"Pharmacy", "over-the-counter advice, prescriptions and many vaccines."}};
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        for (String[] l : lines) {
            if (b.length() > 0) b.append("\n\n");
            int at = b.length();
            b.append(l[0]).append(": ").append(l[1]);
            b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + l[0].length() + 1, 0);
        }
        b.append("\n\nPlaces, hours and phone numbers come from OpenStreetMap; hours change, so call ahead. Your location is "
                + "rounded to about a kilometre before it's used to search.");
        WHICH = b;
    }

    private void place(LinearLayout col, Nearby.Place p) {
        LinearLayout card = Ui.add(col, Ui.card(a), 12);
        LinearLayout top = Ui.add(card, Ui.row(a), 0);
        top.setGravity(Gravity.TOP);
        boolean urgent = p.type == CareData.PlaceType.HOSPITAL || p.type == CareData.PlaceType.URGENT_CARE;
        top.addView(Ui.iconBadge(a, p.type == CareData.PlaceType.PHARMACY ? "pill" : p.type == CareData.PlaceType.LABS ? "drop"
                : p.type == CareData.PlaceType.PEDIATRIC ? "heart" : "care", urgent ? Ui.CORAL : Ui.LEAF_DARK, 42));
        LinearLayout t = Ui.column(a);
        TextView name = Ui.styled(a, p.name, 16, Ui.TEXT, 600);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name);
        LinearLayout meta = Ui.add(t, Ui.row(a), 6);
        Ui.inRow(meta, Ui.badge(a, p.type.label, urgent ? Ui.CORAL : Ui.LEAF), 8);
        meta.addView(Ui.styled(a, CareLogic.miles(p.km), 13, Ui.MUTED, 500));
        top.addView(t, weighted(12));

        Boolean open = p.openNow();
        TextView hours = Ui.add(card, Ui.chip(a, p.hoursText(), Boolean.TRUE.equals(open) ? Ui.GOOD : open == null ? Ui.LINE : Ui.FAINT), 12);
        hours.setBackground(null);
        hours.setPadding(0, 0, 0, 0);
        if (!p.address.isEmpty()) Ui.add(card, Ui.text(a, p.address, 13, Ui.MUTED, false), 4);
        LinearLayout two = Ui.add(card, Ui.row(a), 14);
        if (!p.phone.isEmpty()) two.addView(small("Call", "call", true, v -> dial(p.phone)), weighted(0));
        two.addView(small("Directions", "pin", false, v -> directions(p)), weighted(p.phone.isEmpty() ? 0 : 10));
        card.setContentDescription(p.name + ", " + p.type.label + ", " + CareLogic.miles(p.km) + ", " + p.hoursText());
    }

    // ---- Hand-offs ------------------------------------------------------------------------------

    private void dial(String number) {
        open(Intent.ACTION_DIAL, "tel:" + number.replaceAll("[^0-9+]", ""));
    }

    private void directions(Nearby.Place p) {
        String at = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon);
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + at + "?q=" + at + "(" + Uri.encode(p.name) + ")")));
        } catch (ActivityNotFoundException e) {
            open("https://www.google.com/maps/dir/?api=1&destination=" + at);
        }
    }

    private void mapsSearch(String q) {
        String near = spot == null ? "0,0" : String.format(Locale.US, "%.4f,%.4f", spot[0], spot[1]);
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + near + "?q=" + Uri.encode(q))));
        } catch (ActivityNotFoundException e) {
            open("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q));
        }
    }

    private void open(String url) {
        open(Intent.ACTION_VIEW, url);
    }

    private void open(String action, String uri) {
        try {
            a.startActivity(new Intent(action, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(a, "No app on this phone can open that.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Pieces ---------------------------------------------------------------------------------

    private LinearLayout.LayoutParams weighted(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(a, leftDp);
        return lp;
    }

    /** A compact 44 dp button: coral filled, or white outlined; optional leading icon. */
    private TextView small(String label, String icon, boolean filled, View.OnClickListener onClick) {
        TextView b = Ui.button(a, label, filled ? Ui.CORAL : Ui.MUTED, false, onClick);
        b.setTextSize(14.5f);
        b.setMinHeight(Ui.dp(a, 44));
        b.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        GradientDrawable bg = Ui.rounded(filled ? Ui.CORAL : Ui.CARD, Ui.dp(a, 14));
        if (!filled) bg.setStroke(Ui.dp(a, 1), Ui.LINE);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(filled ? 0x33FFFFFF : 0x14000000), bg, null));
        b.setTextColor(filled ? 0xFFFFFFFF : Ui.TEXT);
        if (icon != null) {   // the icon rides in the text, so icon and label centre together
            SpannableString t = new SpannableString("   " + label);
            t.setSpan(new android.text.style.ImageSpan(new IconDrawable(a, icon, filled ? 0xFFFFFFFF : Ui.TEXT, 18),
                    android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, 0);
            b.setText(t);
        }
        return b;
    }

    /** An IconView glyph as a Drawable, for buttons. */
    private static final class IconDrawable extends android.graphics.drawable.Drawable {
        private final IconView icon;
        private final int size;

        IconDrawable(Context c, String name, int color, int sizeDp) {
            icon = new IconView(c, name, color);
            size = Ui.dp(c, sizeDp);
            icon.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            icon.layout(0, 0, size, size);
            setBounds(0, 0, size, size);
        }

        @Override
        public void draw(Canvas c) {
            c.save();
            c.translate(getBounds().left, getBounds().top);
            icon.draw(c);
            c.restore();
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
