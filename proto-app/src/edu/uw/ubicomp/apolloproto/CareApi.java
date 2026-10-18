package edu.uw.ubicomp.apolloproto;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Products and drug labels for the Care tab, from two keyless public APIs:
 *  - UPCitemdb's product search: names, sizes, photos (from store sites) and recent store prices.
 *    The trial allows 100 searches a day, so answers are cached for a week and the catalog's own
 *    searches ship as a snapshot (tools/care_seed.py).
 *  - openFDA's drug labels: what a medicine is for, its active ingredient and its warnings.
 */
final class CareApi {
    private CareApi() {
    }

    static final class Item {
        final String name, brand, size, photo, store;
        final double price;   // 0 when no recent price

        Item(String name, String brand, String size, String photo, double price, String store) {
            this.name = name;
            this.brand = brand;
            this.size = size;
            this.photo = photo;
            this.price = price;
            this.store = store;
        }

        /** "$4.49 · Target", or "" without a price. */
        String priceLine() {
            return price > 0 ? CareLogic.price(price) + " · " + store : "";
        }
    }

    /** `ok` is false when the search couldn't be answered at all (offline, nothing cached). */
    interface Items {
        void got(List<Item> items, boolean ok);
    }

    private static final Map<String, List<Item>> SEARCHES = new HashMap<>();   // this run's answers, for instant re-renders

    /** Answered already (so a shelf can draw without a loading state)? */
    static List<Item> known(String query) {
        return SEARCHES.get(norm(query));
    }

    static void search(Context c, String query, Items done) {
        String q = norm(query);
        List<Item> hit = SEARCHES.get(q);
        if (hit != null) {
            done.got(hit, true);
            return;
        }
        String url = "https://api.upcitemdb.com/prod/trial/search?s=" + Uri.encode(q) + "&match_mode=0&type=product";
        Net.text(c, url, 7 * Net.DAY, "care/upc_" + CareLogic.slug(q) + ".json", (body, live) -> {
            if (body == null) {
                done.got(Collections.emptyList(), false);
                return;
            }
            List<Item> items = parse(q, body);
            SEARCHES.put(q, items);
            done.got(items, true);
        });
    }

    /** Several searches as one shelf, `each` from each in turn; `done` runs as each answer lands (`complete` on the last). */
    static void shelf(Context c, List<String> queries, int each, Shelf done) {
        List<List<Item>> parts = new ArrayList<>(Collections.nCopies(queries.size(), (List<Item>) null));
        int[] left = {queries.size()};
        boolean[] anyOk = {false};
        for (int i = 0; i < queries.size(); i++) {
            int k = i;
            search(c, queries.get(i), (items, ok) -> {
                parts.set(k, items.size() > each ? items.subList(0, each) : items);
                anyOk[0] |= ok;
                left[0]--;
                List<Item> all = new ArrayList<>();
                for (List<Item> p : parts) if (p != null) all.addAll(p);
                done.got(all, left[0] == 0, anyOk[0]);
            });
        }
    }

    interface Shelf {
        void got(List<Item> items, boolean complete, boolean ok);
    }

    private static String norm(String q) {
        return q.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    private static final List<String> PHOTO_HOSTS = Arrays.asList("pics.walgreens.com", "target.scene7.com", "i5.walmartimages.com",
            "media.officedepot.com");
    private static final List<String> DEAD_HOSTS = Arrays.asList("pics.drugstore.com");   // drugstore.com closed; its photos 404

    private static List<Item> parse(String query, String body) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long now = System.currentTimeMillis() / 1000;
        try {
            JSONArray items = new JSONObject(body).optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String title = it.optString("title"), brand = it.optString("brand");
                String photo = photo(it.optJSONArray("images"));
                if (photo == null || !CareLogic.relevant(query, title, brand)) continue;
                JSONArray offers = it.optJSONArray("offers");
                int n = offers == null ? 0 : offers.length();
                String[] m = new String[n];
                double[] p = new double[n];
                long[] t = new long[n];
                for (int k = 0; k < n; k++) {
                    JSONObject o = offers.getJSONObject(k);
                    m[k] = o.optString("merchant");
                    p[k] = o.optDouble("price", 0);
                    t[k] = o.optLong("updated_t");
                }
                int best = CareLogic.bestOffer(m, p, t, now);
                double price = best >= 0 ? p[best] : 0;
                if (CareLogic.junk(title, price)) continue;
                String name = CareLogic.shortName(title), size = CareLogic.size(it.optString("size"), title);
                if (!seen.add((name + "|" + size).toLowerCase(Locale.US))) continue;
                out.add(new Item(name, brand, size, photo, price, best >= 0 ? CareLogic.store(m[best]) : ""));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** A photo from a store site we know serves https, else any https one that isn't from a dead site. */
    private static String photo(JSONArray images) {
        if (images == null) return null;
        String any = null;
        for (int i = 0; i < images.length(); i++) {
            String u = images.optString(i);
            if (!u.startsWith("https://") || DEAD_HOSTS.contains(Uri.parse(u).getHost())) continue;
            if (any == null) any = u;
            if (PHOTO_HOSTS.contains(Uri.parse(u).getHost())) return u;
        }
        return any;
    }

    // ---- Drug labels ----------------------------------------------------------------------------

    static final class Label {
        final String brand, purpose, uses, active, warnings, setId;

        Label(String brand, String purpose, String uses, String active, String warnings, String setId) {
            this.brand = brand;
            this.purpose = purpose;
            this.uses = uses;
            this.active = active;
            this.warnings = warnings;
            this.setId = setId;
        }
    }

    /** The FDA label for this product's brand, or null (not a medicine, or no exact brand match). Main thread. */
    static void label(Context c, Item item, Consumer<Label> done) {
        tryLabel(c, CareLogic.labelNames(item.name, item.brand), 0, done);
    }

    private static void tryLabel(Context c, List<String> names, int i, Consumer<Label> done) {
        if (i >= names.size()) {
            done.accept(null);
            return;
        }
        String name = names.get(i);
        String url = "https://api.fda.gov/drug/label.json?search=openfda.brand_name:%22" + Uri.encode(name) + "%22&limit=10";
        Net.text(c, url, 7 * Net.DAY, null, (body, live) -> {
            Label l = body == null ? null : pick(body, name);
            if (l != null) done.accept(l);
            else tryLabel(c, names, i + 1, done);
        });
    }

    /** The first label whose brand is exactly `name` (so "Advil" doesn't get "Advil PM"'s label), skipping homeopathic ones. */
    private static Label pick(String body, String name) {
        try {
            JSONArray rs = new JSONObject(body).optJSONArray("results");
            for (int i = 0; rs != null && i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                JSONObject fda = r.optJSONObject("openfda");
                String brand = fda == null ? "" : first(fda, "brand_name");
                String text = r.toString().toLowerCase(Locale.US);
                if (!CareLogic.sameBrand(brand, name) || text.contains("hpus") || text.contains("homeopathic")) continue;
                String warn = first(r, "warnings");
                if (warn.isEmpty()) warn = first(r, "do_not_use");
                return new Label(brand, CareLogic.labelText(first(r, "purpose"), 140),
                        CareLogic.labelText(first(r, "indications_and_usage"), 260), CareLogic.labelText(first(r, "active_ingredient"), 160),
                        CareLogic.labelText(warn, 260), fda == null ? "" : first(fda, "spl_set_id"));
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    private static String first(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        return a != null && a.length() > 0 ? a.optString(0) : "";
    }
}
