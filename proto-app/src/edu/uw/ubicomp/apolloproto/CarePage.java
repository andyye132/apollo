package edu.uw.ubicomp.apolloproto;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Care tab, ported from the team's Compose app (care/ on the ui-retheme branch) into this design:
 * "Meds & vitamins" (picks, a catalog, and per-category products with find-in-store and delivery links)
 * and "Services & hospitals" (location permission, a sample map, filters, and places to call or visit).
 * All listings are CareData samples; the links hand off to Maps, the dialer, DoorDash and Uber Eats.
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
    private CareData.Retailer retailer;   // null = all stores
    private boolean az, openNow, byWait, explain, notNow, asked;
    private CareData.PlaceType type = CareData.PlaceType.ALL;
    private String query = "";

    CarePage(Activity a, Host host) {
        this.a = a;
        this.host = host;
    }

    // ---- The tab --------------------------------------------------------------------------------

    void build(LinearLayout col) {
        Ui.add(col, Ui.label(a, "Care"), 0);
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
        if (CareData.TRAVEL_MODE) {
            LinearLayout r = Ui.wrap(col, Ui.row(a), 16);
            r.setBackground(Ui.rounded(Ui.LEAF_TINT, Ui.dp(a, 100)));
            r.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6));
            r.addView(Ui.icon(a, "pin", Ui.LEAF_DARK, 16));
            TextView tt = Ui.styled(a, "You're in " + CareData.TRAVEL_CITY + ": showing what's sold nearby", 13, Ui.LEAF_DARK, 600);
            tt.setPadding(Ui.dp(a, 6), 0, 0, 0);
            r.addView(tt);
        }

        Ui.add(col, Ui.section(a, "Picked for you"), 24);
        Ui.add(col, Ui.text(a, "Ideas linked to your recent readings.", 13.5f, Ui.MUTED, false), 2);
        LinearLayout picks = Ui.row(a);
        picks.setGravity(Gravity.TOP);
        for (CareData.Pick p : CareData.PICKED_FOR_YOU) {
            LinearLayout card = Ui.card(a);
            Ui.tappable(card, v -> host.openCategory(p.categoryId));
            card.addView(Ui.iconBadge(a, p.icon, Ui.CORAL, 40));
            Ui.add(card, Ui.styled(a, p.title, 17, Ui.TEXT, 600), 12);
            TextView why = Ui.add(card, Ui.text(a, p.whyThis, 13.5f, Ui.MUTED, false), 4);
            why.setMaxLines(2);
            why.setMinLines(2);
            why.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.wrap(card, Ui.badge(a, p.reading, Ui.MUTED), 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 220), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(a, 12);
            lp.bottomMargin = Ui.dp(a, 4);
            picks.addView(card, lp);
        }
        Ui.bleed(col, picks, 12);

        Ui.add(col, Ui.section(a, "Catalog"), 20);
        LinearLayout grid = Ui.add(col, Ui.column(a), 10);
        LinearLayout row = null;
        for (int i = 0; i < CareData.CATALOG.size(); i++) {
            CareData.Category cat = CareData.CATALOG.get(i);
            if (i % 2 == 0) {
                row = Ui.add(grid, Ui.row(a), i == 0 ? 0 : 12);
                row.setBaselineAligned(false);
            }
            LinearLayout tile = Ui.card(a);
            Ui.tappable(tile, v -> host.openCategory(cat.id));
            tile.addView(Ui.iconBadge(a, cat.icon, i % 3 == 1 ? Ui.CORAL : Ui.LEAF, 38));
            TextView name = Ui.add(tile, Ui.styled(a, cat.title, 15, Ui.TEXT, 600), 12);
            name.setMinLines(2);
            Ui.add(tile, Ui.text(a, cat.itemCount + " items", 13, Ui.MUTED, false), 2);
            tile.setContentDescription(cat.title + ", " + cat.itemCount + " items");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % 2 == 1) lp.leftMargin = Ui.dp(a, 12);
            row.addView(tile, lp);
        }
        Ui.add(col, Ui.text(a, "Sample listings for the prototype. Apollo doesn't sell anything or process payments.",
                12, Ui.FAINT, false), 16).setGravity(Gravity.CENTER);
    }

    /** One category's products, grouped by store. */
    void category(LinearLayout col, String id, Runnable back) {
        TextView link = Ui.add(col, Ui.styled(a, "‹  Care", 15, Ui.TEXT, 500), 0);
        link.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        link.setOnClickListener(v -> back.run());
        String[] d = CareData.detail(id);
        Ui.add(col, Ui.label(a, "Meds & vitamins"), 8);
        Ui.add(col, Ui.title(a, d[0]), 6);
        Ui.add(col, Ui.text(a, d[1], 15, Ui.MUTED, false), 6);

        LinearLayout note = Ui.add(col, Ui.tintCard(a, Ui.WARN_TINT), 16);
        LinearLayout nr = Ui.add(note, Ui.row(a), 0);
        nr.addView(Ui.icon(a, "info", Ui.WARN, 20));
        TextView nt = Ui.styled(a, "Check with a pharmacist if you take other medication.", 14, Ui.TEXT, 500);
        nr.addView(nt, weighted(10));
        Ui.add(col, Ui.text(a, "This is sample data for prototyping — not real inventory or pricing.", 12, Ui.FAINT, false), 8);

        LinearLayout stores = Ui.row(a);
        Ui.inRow(stores, Ui.choice(a, "All stores", retailer == null, v -> {
            retailer = null;
            host.refresh();
        }), 8);
        for (CareData.Retailer r : CareData.Retailer.values()) {
            Ui.inRow(stores, Ui.choice(a, r.displayName, retailer == r, v -> {
                retailer = r;
                host.refresh();
            }), 8);
        }
        Ui.bleed(col, stores, 14);
        LinearLayout sort = Ui.add(col, Ui.row(a), 2);
        sort.addView(Ui.styled(a, "Sort", 13.5f, Ui.MUTED, 500), new LinearLayout.LayoutParams(Ui.dp(a, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.inRow(sort, Ui.choice(a, "Nearest", !az, v -> {
            az = false;
            host.refresh();
        }), 8);
        Ui.inRow(sort, Ui.choice(a, "A–Z", az, v -> {
            az = true;
            host.refresh();
        }), 8);

        List<CareData.Product> list = new ArrayList<>();
        for (CareData.Product p : CareData.products(id)) if (retailer == null || p.retailer == retailer) list.add(p);
        list.sort(az ? Comparator.comparing(p -> p.name) : Comparator.comparingDouble(p -> p.retailer.distanceMi));
        if (list.isEmpty()) Ui.add(col, Ui.text(a, "No products match these filters.", 14, Ui.MUTED, false), 18);
        // Grouped by store in the store order; within a store, the chosen sort.
        for (CareData.Retailer r : CareData.Retailer.values()) {
            List<CareData.Product> group = new ArrayList<>();
            for (CareData.Product p : list) if (p.retailer == r) group.add(p);
            if (group.isEmpty()) continue;
            LinearLayout head = Ui.add(col, Ui.row(a), 22);
            head.addView(Ui.section(a, r.displayName), weighted(0));
            head.addView(Ui.styled(a, String.format(Locale.US, "%.1f mi", r.distanceMi), 13, Ui.FAINT, 500));
            for (CareData.Product p : group) product(col, p);
        }
        View div = new View(a);
        div.setBackgroundColor(Ui.LINE);
        Ui.add(col, div, 24).getLayoutParams().height = 1;
        Ui.add(col, Ui.text(a, "Availability and delivery are provided by the retailers and delivery apps. Apollo doesn't process payments.",
                12, Ui.FAINT, false), 12);
    }

    private void product(LinearLayout col, CareData.Product p) {
        LinearLayout card = Ui.add(col, Ui.card(a), 10);
        card.addView(Ui.styled(a, p.name, 16, Ui.TEXT, 600));
        Ui.add(card, Ui.text(a, p.retailer.displayName + " · " + p.sizeOrCount, 13, Ui.MUTED, false), 3);
        LinearLayout chips = Ui.add(card, Ui.row(a), 10);
        if (p.retailer.distanceMi <= 1.0) {
            Ui.inRow(chips, Ui.badge(a, String.format(Locale.US, "Nearby • %.1f mi", p.retailer.distanceMi), Ui.LEAF), 6);
        }
        boolean in = "In stock nearby".equals(p.inStockLabel);
        Ui.inRow(chips, Ui.badge(a, (in ? "✓  " : "") + p.inStockLabel, in ? Ui.GOOD : Ui.MUTED), 6);
        Ui.add(card, small("Find in store", "pin", false, v -> findInStore(p)), 14);
        Ui.add(card, Ui.label(a, "Get it delivered"), 14);
        LinearLayout two = Ui.add(card, Ui.row(a), 8);
        two.addView(small("DoorDash", null, false, v -> open("https://www.doordash.com/search/?query=" + Uri.encode(query(p)))), weighted(0));
        two.addView(small("Uber Eats", null, false, v -> open("https://www.ubereats.com/search?q=" + Uri.encode(query(p)))), weighted(10));
    }

    private static String query(CareData.Product p) {
        return p.retailer.displayName + " " + p.name;
    }

    /** Maps search for the store near you (the sample product names aren't real listings). */
    private void findInStore(CareData.Product p) {
        String store = p.retailer.displayName.replaceAll(" \\(.*\\)", "");
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(store))));
        } catch (ActivityNotFoundException e) {
            open("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(store));
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
        host.refresh();
    }

    private void services(LinearLayout col) {
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

        if (located()) {
            places(col, true);
        } else if (notNow) {
            manualSearch(col);
        } else if (asked) {
            LinearLayout off = Ui.add(col, Ui.card(a), 14);
            off.setGravity(Gravity.CENTER_HORIZONTAL);
            off.addView(Ui.mascot(a, "thinking", 84));
            Ui.add(off, Ui.styled(a, "Location is off, turn it on to see nearby places", 16, Ui.TEXT, 600), 10).setGravity(Gravity.CENTER);
            Ui.add(off, Ui.button(a, "Try again", Ui.CORAL, true, v -> askLocation()), 14);
            TextView zip = Ui.add(off, Ui.styled(a, "Search by ZIP or city instead", 14, Ui.MUTED, 500), 10);
            zip.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            zip.setOnClickListener(v -> {
                notNow = true;
                host.refresh();
            });
        } else {
            // The pre-permission ask, inline: Apollo on the same white as his card.
            LinearLayout ask = Ui.add(col, Ui.card(a), 14);
            ask.setGravity(Gravity.CENTER_HORIZONTAL);
            ask.addView(Ui.mascot(a, "hello", 96));
            Ui.add(ask, Ui.styled(a, "Find care near you", 22, Ui.TEXT, 700), 10).setGravity(Gravity.CENTER);
            Ui.add(ask, Ui.text(a, "Allow Apollo to use your location to show nearby hospitals, clinics, and labs. "
                    + "We only use it while the app is open.", 14.5f, Ui.MUTED, false), 6).setGravity(Gravity.CENTER);
            Ui.add(ask, Ui.button(a, "Allow while using the app", Ui.CORAL, true, v -> askLocation()), 16);
            Ui.add(ask, Ui.button(a, "Only this time", Ui.MUTED, false, v -> askLocation()), 10);
            TextView later = Ui.add(ask, Ui.styled(a, "Not now", 14.5f, Ui.MUTED, 600), 10);
            later.setGravity(Gravity.CENTER);
            later.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 4));
            later.setOnClickListener(v -> {
                notNow = true;
                host.refresh();
            });
        }
    }

    private void askLocation() {
        a.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    private void manualSearch(LinearLayout col) {
        LinearLayout card = Ui.add(col, Ui.card(a), 14);
        card.addView(Ui.styled(a, "Search by ZIP or city", 17, Ui.TEXT, 600));
        EditText in = Ui.add(card, Ui.textInput(a, "ZIP code or city"), 10);
        in.setText(query);
        LinearLayout results = Ui.column(a);
        in.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                results.removeAllViews();
                if (!query.isEmpty()) {
                    Ui.add(results, Ui.text(a, "Showing sample results near \"" + query + "\".", 13.5f, Ui.MUTED, false), 16);
                    placesInto(results);
                }
            }
        });
        TextView use = Ui.add(card, Ui.styled(a, "Use my location", 14.5f, Ui.CORAL_DARK, 600), 10);
        use.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 2));
        use.setOnClickListener(v -> {
            notNow = false;
            askLocation();
        });
        Ui.add(col, results, 0);
        if (!query.isEmpty()) {
            Ui.add(results, Ui.text(a, "Showing sample results near \"" + query + "\".", 13.5f, Ui.MUTED, false), 16);
            placesInto(results);
        }
    }

    private void places(LinearLayout col, boolean map) {
        if (map) {
            MapPreview m = new MapPreview(a);
            Ui.add(col, m, 14).getLayoutParams().height = Ui.dp(a, 168);
            Ui.lift(m, 1.5f);
            Ui.tappable(m, v -> mapsSearch(type == CareData.PlaceType.ALL ? "urgent care" : search(type)));
            m.setContentDescription("Sample map. Tap to search real places nearby in Maps.");
        }
        placesInto(col);
    }

    private void placesInto(LinearLayout col) {
        LinearLayout chips = Ui.row(a);
        for (CareData.PlaceType t : CareData.PlaceType.values()) {
            Ui.inRow(chips, Ui.choice(a, t.label, type == t, v -> {
                type = t;
                host.refresh();
            }), 8);
        }
        Ui.bleed(col, chips, 14);

        LinearLayout open = Ui.add(col, Ui.row(a), 2);
        open.addView(Ui.styled(a, "Open now", 15, Ui.TEXT, 500), weighted(0));
        Switch sw = new Switch(a);
        sw.setChecked(openNow);
        sw.setOnCheckedChangeListener((b, on) -> {
            openNow = on;
            host.refresh();
        });
        open.addView(sw);
        Ui.tappable(open, v -> sw.toggle());
        open.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));

        LinearLayout sort = Ui.add(col, Ui.row(a), 6);
        sort.addView(Ui.styled(a, "Sort", 13.5f, Ui.MUTED, 500), new LinearLayout.LayoutParams(Ui.dp(a, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.inRow(sort, Ui.choice(a, "Nearest", !byWait, v -> {
            byWait = false;
            host.refresh();
        }), 8);
        Ui.inRow(sort, Ui.choice(a, "Wait time", byWait, v -> {
            byWait = true;
            host.refresh();
        }), 8);

        LinearLayout which = Ui.add(col, Ui.tintCard(a, Ui.LEAF_TINT), 16);
        LinearLayout wr = Ui.add(which, Ui.row(a), 0);
        wr.addView(Ui.icon(a, "info", Ui.LEAF_DARK, 20));
        wr.addView(Ui.styled(a, "Which should I choose?", 15, Ui.TEXT, 600), weighted(10));
        IconView chev = Ui.icon(a, "chevron", Ui.LEAF_DARK, 18);
        chev.setRotation(explain ? -90 : 90);
        wr.addView(chev);
        which.setContentDescription(explain ? "Collapse" : "Expand");
        if (explain) {
            String[][] lines = {
                    {"ER: ", "life-threatening or severe symptoms, any time of day."},
                    {"Urgent care: ", "non-emergency issues that still need same-day attention."},
                    {"Virtual visit: ", "quick questions or follow-ups you can do from home."}};
            for (String[] l : lines) {
                android.text.SpannableString s = new android.text.SpannableString(l[0] + l[1]);
                s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, l[0].length(), 0);
                TextView tv = Ui.add(which, Ui.text(a, "", 14, Ui.TEXT, false), 10);
                tv.setText(s);
            }
        }
        Ui.tappable(which, v -> {
            explain = !explain;
            host.refresh();
        });

        List<CareData.Place> list = new ArrayList<>();
        for (CareData.Place p : CareData.PLACES) {
            if (type != CareData.PlaceType.ALL && p.type != type) continue;
            if (openNow && !p.isOpenNow) continue;
            list.add(p);
        }
        list.sort(byWait ? Comparator.comparing((CareData.Place p) -> p.waitMinutes == null ? Integer.MAX_VALUE : p.waitMinutes)
                : Comparator.comparingDouble(p -> p.distanceMi));
        if (list.isEmpty()) Ui.add(col, Ui.text(a, "No places found, try widening the filters", 14, Ui.MUTED, false), 16);
        for (CareData.Place p : list) place(col, p);
        Ui.add(col, Ui.text(a, "Sample places for the prototype. Directions open a real search for that kind of care in Maps.",
                12, Ui.FAINT, false), 14).setGravity(Gravity.CENTER);
    }

    private void place(LinearLayout col, CareData.Place p) {
        LinearLayout card = Ui.add(col, Ui.card(a), 12);
        LinearLayout top = Ui.add(card, Ui.row(a), 0);
        top.setGravity(Gravity.TOP);
        boolean urgent = p.type == CareData.PlaceType.ER || p.type == CareData.PlaceType.URGENT_CARE;
        top.addView(Ui.iconBadge(a, p.isVirtual ? "phone" : p.type == CareData.PlaceType.LABS ? "drop"
                : p.type == CareData.PlaceType.VACCINES ? "pill" : "care", urgent ? Ui.CORAL : Ui.LEAF, 42));
        LinearLayout t = Ui.column(a);
        t.addView(Ui.styled(a, p.name, 16, Ui.TEXT, 600));
        LinearLayout meta = Ui.add(t, Ui.row(a), 6);
        Ui.inRow(meta, Ui.badge(a, p.type.label, urgent ? Ui.CORAL : Ui.LEAF), 8);
        if (!p.isVirtual) meta.addView(Ui.styled(a, String.format(Locale.US, "%.1f mi", p.distanceMi), 13, Ui.MUTED, 500));
        top.addView(t, weighted(12));

        TextView hours = Ui.add(card, Ui.chip(a, (p.isOpenNow ? "Open now · " : "Closed · ") + p.hoursText,
                p.isOpenNow ? Ui.GOOD : Ui.FAINT), 12);
        hours.setBackground(null);
        hours.setPadding(0, 0, 0, 0);
        if (p.waitMinutes != null) {
            Ui.add(card, Ui.text(a, "Estimated wait: " + p.waitMinutes + " min", 13.5f, Ui.TEXT, false), 4);
        }
        Ui.add(card, Ui.text(a, p.phone, 13, Ui.FAINT, false), 4);
        if (p.isVirtual) {
            Ui.add(card, small("Start a virtual visit", "phone", true,
                    v -> open("https://www.google.com/search?q=" + Uri.encode(p.name))), 14);
        } else {
            LinearLayout two = Ui.add(card, Ui.row(a), 14);
            two.addView(small("Call", "call", true, v -> dial(p.phone)), weighted(0));
            two.addView(small("Directions", "pin", false, v -> mapsSearch(search(p.type))), weighted(10));
        }
    }

    /** What to search Maps for, per kind of place (the sample names aren't real places). */
    private static String search(CareData.PlaceType t) {
        switch (t) {
            case ER:
                return "emergency room";
            case WALK_IN:
                return "walk-in clinic";
            case PEDIATRIC:
                return "pediatric urgent care";
            case LABS:
                return "medical lab";
            case VACCINES:
                return "pharmacy vaccines";
            default:
                return "urgent care";
        }
    }

    // ---- Hand-offs ------------------------------------------------------------------------------

    private void dial(String number) {
        open(Intent.ACTION_DIAL, "tel:" + number.replaceAll("[^0-9+]", ""));
    }

    private void mapsSearch(String q) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))));
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
        b.setText(label);
        b.setTextSize(14.5f);
        b.setMinHeight(Ui.dp(a, 44));
        b.setMinimumHeight(Ui.dp(a, 44));
        b.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        android.graphics.drawable.GradientDrawable bg = Ui.rounded(filled ? Ui.CORAL : Ui.CARD, Ui.dp(a, 14));
        if (!filled) bg.setStroke(Ui.dp(a, 1), Ui.LINE);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(filled ? 0x33FFFFFF : 0x14000000), bg, null));
        b.setTextColor(filled ? 0xFFFFFFFF : Ui.TEXT);
        if (icon != null) {   // the icon rides in the text, so icon and label centre together
            android.text.SpannableString t = new android.text.SpannableString("\u00A0\u00A0 " + label);
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

    /** A decorative sample map: soft streets and a park, three care pins, and a "you" dot. */
    private static final class MapPreview extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), street = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        MapPreview(Context c) {
            super(c);
            street.setStyle(Paint.Style.STROKE);
            street.setStrokeCap(Paint.Cap.ROUND);
            text.setTypeface(Ui.font(c, 600, 14));
            text.setTextSize(Ui.dp(c, 12));
            text.setColor(Ui.TEXT);
            setClipToOutline(true);
            setBackground(Ui.rounded(0xFFF1ECE3, Ui.dp(c, 22)));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = Ui.dp(getContext(), 1);
            fill.setColor(0xFFDDE5D2);   // a park, in the mascot's leaf green
            r.set(w * 0.52f, h * 0.30f, w * 0.74f, h * 0.68f);
            c.drawRoundRect(r, 18 * d, 18 * d, fill);
            fill.setColor(0xFFD5E3EA);   // a sliver of water
            path.reset();
            path.moveTo(0, h * 0.86f);
            path.quadTo(w * 0.35f, h * 0.78f, w * 0.6f, h);
            path.lineTo(0, h);
            path.close();
            c.drawPath(path, fill);
            street.setColor(0xFFFFFFFF);
            street.setStrokeWidth(9 * d);
            c.drawLine(0, h * 0.3f, w, h * 0.22f, street);
            c.drawLine(0, h * 0.7f, w, h * 0.78f, street);
            c.drawLine(w * 0.3f, 0, w * 0.22f, h, street);
            c.drawLine(w * 0.82f, 0, w * 0.86f, h, street);
            street.setStrokeWidth(4 * d);
            c.drawLine(w * 0.3f, h * 0.5f, w, h * 0.46f, street);
            pin(c, w * 0.16f, h * 0.24f, Ui.CORAL, d);
            pin(c, w * 0.9f, h * 0.36f, Ui.CORAL, d);
            pin(c, w * 0.42f, h * 0.84f, Ui.LEAF, d);
            float cx = w * 0.48f, cy = h * 0.48f;   // you
            fill.setColor(0x33E8705E);
            c.drawCircle(cx, cy, 20 * d, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, 9 * d, fill);
            fill.setColor(Ui.CORAL);
            c.drawCircle(cx, cy, 6 * d, fill);
            String label = "You are here · sample map";
            float tw = text.measureText(label);
            r.set(w - tw - 34 * d, h - 38 * d, w - 10 * d, h - 10 * d);
            fill.setColor(0xF2FFFFFF);
            c.drawRoundRect(r, 14 * d, 14 * d, fill);
            c.drawText(label, r.left + 12 * d, r.centerY() + 4.2f * d, text);
        }

        private void pin(Canvas c, float x, float y, int color, float d) {
            fill.setColor(color);
            path.reset();
            path.moveTo(x, y + 12 * d);
            path.cubicTo(x - 3 * d, y + 7 * d, x - 9 * d, y + 3 * d, x - 9 * d, y - 3 * d);
            path.cubicTo(x - 9 * d, y - 8 * d, x - 5 * d, y - 12 * d, x, y - 12 * d);
            path.cubicTo(x + 5 * d, y - 12 * d, x + 9 * d, y - 8 * d, x + 9 * d, y - 3 * d);
            path.cubicTo(x + 9 * d, y + 3 * d, x + 3 * d, y + 7 * d, x, y + 12 * d);
            path.close();
            c.drawPath(path, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(x, y - 3 * d, 3.2f * d, fill);
        }
    }
}
