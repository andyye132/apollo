package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private boolean openNow, explain, notNow, asked, locating, locFailed, geocoding;
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
        Ui.add(col, Ui.label(a, spotName != null ? spotName : "Meds · clinics · delivery"), 0);
        TextView t = Ui.add(col, Ui.title(a, "Care"), 6);
        t.setTextSize(34);
        Ui.add(col, Ui.text(a, "Find what you need, and where to get it.", 15, Ui.MUTED, false), 6);
        Ui.add(col, Ui.segmented(a, new String[]{"Meds & vitamins", "Services & hospitals"}, tab, i -> {
            tab = i;
            host.refresh();
        }), 20);
        if (tab == 0) meds(col);
        else services(col);
    }

    // ---- Meds & vitamins ------------------------------------------------------------------------

    private void meds(LinearLayout col) {
        searchBar(col, "", 18);
        if (!Net.online(a)) offline(col, "Offline · showing saved products. Search needs a connection.");

        Ui.add(col, Ui.styled(a, "Picked for you", 21, Ui.TEXT, 700), 26);
        Ui.add(col, Ui.text(a, "Learned on this phone from your readings, your chats with Apollo and what you search. "
                + "Ideas, not medical advice.", 14, Ui.MUTED, false), 3);
        List<CareModel.Signal> signals = CareSignals.load(a);
        CareModel.Readings readings = CareSignals.readings(a);
        learned(col, signals, readings);
        List<CareModel.Shelf> shelves = CareSignals.model().rank(signals, readings, SkinToCore.FEVER_FLAG_C, 3);
        boolean firstBasic = true, evidence = false, kids = CareModel.aboutChildren(signals);
        for (CareModel.Shelf sh : shelves) evidence |= !sh.reasons.isEmpty();
        for (CareModel.Shelf sh : shelves) {
            String basic = !sh.reasons.isEmpty() ? null : !firstBasic ? "Also worth having at home."
                    : evidence ? "Worth having at home." : "Nothing in your readings or chats stands out. These are worth having at home.";
            shelf(col, sh, basic, kids);
            if (sh.reasons.isEmpty()) firstBasic = false;
        }

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
        Ui.add(col, Ui.text(a, "Photos and recent prices from UPCitemdb, drug facts from the FDA label. Apollo doesn't sell "
                + "anything. Only what you search for leaves the phone, never your readings.", 12, Ui.FAINT, false), 18).setGravity(Gravity.CENTER);
    }

    /** "Learning from 3 chat messages, 1 search and 2 readings · Reset": what the shelves rest on, and a way to forget it. */
    private void learned(LinearLayout col, List<CareModel.Signal> signals, CareModel.Readings r) {
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
        LinearLayout row = Ui.add(col, Ui.row(a), 8);
        row.addView(Ui.icon(a, "leaf", Ui.LEAF_DARK, 16));
        String list = parts.isEmpty() ? "" : parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        TextView t = Ui.styled(a, parts.isEmpty() ? "Nothing learned yet. Chat with Apollo or search, and these adapt."
                : "Learning from " + list + " this week", 13, Ui.LEAF_DARK, 600);
        t.setPadding(Ui.dp(a, 6), 0, 0, 0);
        row.addView(t, weighted(0));
        if (chats + searches + browsing > 0) {
            TextView reset = Ui.styled(a, "Reset", 13, Ui.CORAL_DARK, 600);
            reset.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), 0, Ui.dp(a, 6));
            reset.setContentDescription("Forget what Care has learned from chats, searches and browsing");
            reset.setOnClickListener(v -> {
                CareSignals.clear(a);
                host.refresh();
            });
            row.addView(reset);
        }
    }

    /** Open a category, remembering the interest. */
    private void browse(String categoryId) {
        CareSignals.log(a, CareModel.Kind.OPEN, null, categoryId);
        host.openCategory(categoryId);
    }

    /**
     * A need's shelf: why it's here (the strongest reason, and tags for each kind of evidence behind it), a way to
     * hide it (the model learns from that too), and a sideways row of products, like a delivery app's shelves.
     */
    private void shelf(LinearLayout col, CareModel.Shelf sh, String basic, boolean kids) {
        CareData.Need n = sh.need;
        LinearLayout head = Ui.add(col, Ui.row(a), 22);
        head.setGravity(Gravity.TOP);
        head.addView(Ui.iconBadge(a, n.icon, sh.reasons.isEmpty() ? Ui.LEAF_DARK : Ui.CORAL, 36));
        LinearLayout t = Ui.column(a);
        t.addView(Ui.styled(a, n.title, 16, Ui.TEXT, 700));
        String reason = basic != null ? basic : sh.reasons.get(0).text;
        Ui.add(t, Ui.text(a, reason, 13.5f, Ui.MUTED, false), 2);
        head.addView(t, weighted(12));
        TextView all = Ui.styled(a, "See all", 14, Ui.CORAL_DARK, 600);
        all.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), 0, Ui.dp(a, 6));
        all.setOnClickListener(v -> browse(n.categoryId));
        head.addView(all);

        LinearLayout tags = Ui.add(col, Ui.row(a), 8);
        tags.setPadding(Ui.dp(a, 48), 0, 0, 0);
        LinearLayout pills = Ui.row(a);
        for (String src : sh.sources(0.15)) Ui.inRow(pills, sourcePill(src), 6);
        tags.addView(pills, weighted(0));
        if (!sh.reasons.isEmpty()) {
            TextView hide = Ui.styled(a, "Hide", 13, Ui.MUTED, 600);
            hide.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), 0, Ui.dp(a, 4));
            hide.setContentDescription("Hide " + n.title + " for a few days");
            hide.setOnClickListener(v -> {
                CareSignals.log(a, CareModel.Kind.DISMISS, null, n.id);
                host.refresh();
            });
            tags.addView(hide);
        }

        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.TOP);
        Ui.bleed(col, row, 10);
        for (int i = 0; i < 3; i++) row.addView(skeleton(150, 196), cardParams());
        CareApi.shelf(a, sh.queries, 3, (items, complete, ok) -> {
            row.removeAllViews();
            List<CareApi.Item> shown = new ArrayList<>(items);
            if (!kids) shown.sort(java.util.Comparator.comparingInt(it -> CareModel.forChildren(it.name) ? 1 : 0));   // grown-ups' first
            for (CareApi.Item it : shown) row.addView(productCard(it, n.id), cardParams());
            if (!complete) row.addView(skeleton(150, 196), cardParams());
            else if (items.isEmpty()) row.addView(shelfMessage(ok ? "Nothing to show here yet." : "Can't reach the store list right now."));
        });
    }

    /** A small leaf-green tag naming one kind of evidence behind a shelf. */
    private View sourcePill(String source) {
        String[] li = source.equals("chat") ? new String[]{"Your chat", "send"} : source.equals("apollo") ? new String[]{"Apollo", null}
                : source.equals("search") ? new String[]{"Your searches", "search"} : source.equals("browse") ? new String[]{"What you viewed", "layers"}
                : source.equals("temperature") ? new String[]{"Temperature", "thermo"} : source.equals("heart") ? new String[]{"Pulse", "heart"}
                : source.equals("steps") ? new String[]{"Steps", "steps"} : source.equals("breath") ? new String[]{"Breath", "waves"}
                : new String[]{"Blink rate", "eye"};
        LinearLayout pill = Ui.row(a);
        pill.setBackground(Ui.rounded(Ui.LEAF_TINT, Ui.dp(a, 100)));
        pill.setPadding(Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
        if (li[1] != null) pill.addView(Ui.icon(a, li[1], Ui.LEAF_DARK, 14));
        else pill.addView(Ui.mascot(a, "hello", 16));   // Apollo himself, on the pill's green
        TextView t = Ui.styled(a, li[0], 12, Ui.LEAF_DARK, 600);
        t.setPadding(Ui.dp(a, 5), 0, 0, 0);
        pill.addView(t);
        return pill;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 150), ViewGroup.LayoutParams.WRAP_CONTENT);
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

    /** A shelf product: photo with a ＋, price, name, size. */
    private View productCard(CareApi.Item it, String needId) {
        LinearLayout card = Ui.column(a);
        card.addView(photoBox(it, 150, 136, true), new LinearLayout.LayoutParams(Ui.dp(a, 150), Ui.dp(a, 136)));
        Ui.add(card, Ui.styled(a, it.price > 0 ? CareLogic.price(it.price) : "Price varies", 15, it.price > 0 ? Ui.TEXT : Ui.MUTED, 700), 8);
        TextView name = Ui.add(card, Ui.styled(a, it.name, 13.5f, Ui.TEXT, 500), 2);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView size = Ui.add(card, Ui.text(a, it.size.isEmpty() ? it.store : it.size, 12.5f, Ui.MUTED, false), 2);
        size.setSingleLine(true);
        size.setEllipsize(TextUtils.TruncateAt.END);
        card.setPadding(0, 0, 0, Ui.dp(a, 6));
        card.setBackground(Ui.rounded(0x00000000, Ui.dp(a, 16)));
        Ui.tappable(card, v -> sheet(it, needId));
        card.setContentDescription(it.name + (it.price > 0 ? ", about " + CareLogic.price(it.price) : ""));
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
        Ui.add(col, Ui.label(a, search ? "Search" : "Meds & vitamins"), 8);
        TextView title = Ui.add(col, Ui.title(a, search ? "“" + q + "”" : cat != null ? cat.title : "Products"), 6);
        title.setMaxLines(2);
        if (cat != null) Ui.add(col, Ui.text(a, cat.about, 15, Ui.MUTED, false), 6);
        searchBar(col, search ? q : "", 16);

        LinearLayout note = Ui.add(col, Ui.tintCard(a, Ui.WARN_TINT), 14);
        LinearLayout nr = Ui.add(note, Ui.row(a), 0);
        nr.addView(Ui.icon(a, "info", Ui.WARN, 20));
        nr.addView(Ui.styled(a, "Ask a pharmacist first if you take other medicines, are pregnant, or are buying for a child.",
                14, Ui.TEXT, 500), weighted(10));

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
        Ui.add(col, Ui.text(a, "Photos and recent online prices from UPCitemdb; what's on the shelf near you can differ. "
                + "Delivery opens DoorDash or Uber Eats, which handle the order.", 12, Ui.FAINT, false), 14).setGravity(Gravity.CENTER);
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
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.CARD);
        float rad = Ui.dp(a, 28);
        bg.setCornerRadii(new float[]{rad, rad, rad, rad, 0, 0, 0, 0});
        sv.setBackground(bg);
        LinearLayout col = Ui.column(a);
        col.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 28));
        sv.addView(col);

        View handle = new View(a);
        handle.setBackground(Ui.rounded(Ui.LINE, Ui.dp(a, 3)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(handle, hp);
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
        Ui.add(col, Ui.text(a, "Opens a search for “" + q + "”. The app shows what nearby stores carry and handles the order.",
                12.5f, Ui.FAINT, false), 8);
        TextView near = Ui.add(col, small("Find a pharmacy near you", "pin", false, v -> {
            d.dismiss();
            tab = 1;
            type = CareData.PlaceType.PHARMACY;
            if (toCare != null) toCare.run();
            else host.refresh();
        }), 12);
        near.setContentDescription("Find a pharmacy near you");

        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setDimAmount(0.35f);
        }
        d.show();
    }

    private void fact(LinearLayout facts, String head, String body) {
        if (body == null || body.isEmpty()) return;
        Ui.add(facts, Ui.styled(a, head, 14.5f, Ui.TEXT, 600), 12);
        Ui.add(facts, Ui.text(a, body, 14, Ui.MUTED, false), 3);
    }

    /** DoorDash or Uber Eats, searching for the product: their app when installed, else their site. */
    private void deliver(boolean doordash, String q) {
        String url = doordash ? "https://www.doordash.com/search/store/" + Uri.encode(q) + "/"
                : "https://www.ubereats.com/search?q=" + Uri.encode(q);
        Intent app = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(doordash ? "com.dd.doordash" : "com.ubercab.eats");
        try {
            a.startActivity(app);
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
        // Emergencies don't wait for filters.
        LinearLayout sos = Ui.add(col, Ui.tintCard(a, Ui.CORAL_TINT), 18);
        LinearLayout sr = Ui.add(sos, Ui.row(a), 0);
        LinearLayout st = Ui.column(a);
        st.addView(Ui.styled(a, "Emergency?", 16, Ui.CORAL_DARK, 700));
        Ui.add(st, Ui.text(a, "Chest pain, trouble breathing, or signs of a stroke: call 911 now.", 13.5f, Ui.TEXT, false), 2);
        sr.addView(st, weighted(0));
        TextView call = small("Call 911", "call", true, v -> dial("911"));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Ui.dp(a, 12);
        sr.addView(call, cl);

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
        Ui.add(col, map, 12).getLayoutParams().height = Ui.dp(a, 180);
        Ui.lift(map, 1.5f);
        map.setOnClickListener(v -> mapsSearch(type.mapsQuery));
        map.setContentDescription("Map of care nearby. Tap to open it in Maps.");

        placesBox = Ui.add(col, Ui.column(a), 0);
        fillPlaces(placesBox);
        Ui.add(col, Ui.text(a, "Places, hours and phone numbers from OpenStreetMap contributors; hours change, so call ahead. "
                + "Your location is rounded to about a kilometre before it's used to search.", 12, Ui.FAINT, false), 16).setGravity(Gravity.CENTER);
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

        LinearLayout which = Ui.add(box, Ui.tintCard(a, Ui.LEAF_TINT), 10);
        LinearLayout wr = Ui.add(which, Ui.row(a), 0);
        wr.addView(Ui.icon(a, "info", Ui.LEAF_DARK, 20));
        wr.addView(Ui.styled(a, "Which should I choose?", 15, Ui.TEXT, 600), weighted(10));
        IconView chev = Ui.icon(a, "chevron", Ui.LEAF_DARK, 18);
        chev.setRotation(explain ? -90 : 90);
        wr.addView(chev);
        which.setContentDescription("Which should I choose? " + (explain ? "Tap to hide" : "Tap to show"));
        if (explain) {
            String[][] lines = {
                    {"ER: ", "life-threatening or severe symptoms, any time of day."},
                    {"Urgent care: ", "same-day help for things that can't wait for an appointment."},
                    {"Clinic: ", "check-ups, ongoing care and follow-ups."},
                    {"Pharmacy: ", "over-the-counter advice, prescriptions and many vaccines."}};
            for (String[] l : lines) {
                SpannableString s = new SpannableString(l[0] + l[1]);
                s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, l[0].length(), 0);
                Ui.add(which, Ui.text(a, "", 14, Ui.TEXT, false), 10).setText(s);
            }
        }
        Ui.tappable(which, v -> {
            explain = !explain;
            host.refresh();
        });

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
