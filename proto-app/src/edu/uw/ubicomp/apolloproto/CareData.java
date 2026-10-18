package edu.uw.ubicomp.apolloproto;

import java.util.Arrays;
import java.util.List;

/**
 * What the Care tab offers: the catalog's categories (each one a few live product searches) and the kinds
 * of nearby care. The listings themselves come from the internet: products from UPCitemdb (CareApi),
 * places from OpenStreetMap (Nearby). Pure Java, so CareCheck can test it on the Mac.
 */
final class CareData {
    private CareData() {
    }

    static final class Category {
        final String id, title, icon, about, hint;
        final List<String> queries;   // product searches, in shelf order; keep in step with tools/care_seed.py

        Category(String id, String title, String icon, String about, String hint, String... queries) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.about = about;
            this.hint = hint;
            this.queries = Arrays.asList(queries);
        }
    }

    static final List<Category> CATALOG = Arrays.asList(
            new Category("pain_fever", "Pain & fever", "thermo",
                    "Everyday pain relievers and fever reducers, plus a thermometer to track a reading.",
                    "Tylenol, Advil, thermometers", "tylenol extra strength", "advil ibuprofen", "digital thermometer"),
            new Category("cold_cough", "Cold & cough", "waves",
                    "Day and night relief for a stuffy nose, a cough and the aches that come with a cold.",
                    "DayQuil, Mucinex", "dayquil", "mucinex"),
            new Category("allergy", "Allergy", "leaf",
                    "Non-drowsy antihistamines for sneezing, itchy eyes and a runny nose.",
                    "Claritin, Zyrtec", "claritin", "zyrtec"),
            new Category("stomach", "Stomach & digestion", "care",
                    "For heartburn, an upset stomach and indigestion.",
                    "Pepto-Bismol, Tums", "pepto bismol", "tums"),
            new Category("electrolytes", "Electrolytes & hydration", "drop",
                    "Replaces the salts and fluids lost to sweat, heat or a stomach bug.",
                    "Pedialyte, Liquid I.V.", "pedialyte", "liquid iv"),
            new Category("vitamins", "Vitamins & supplements", "pill",
                    "Vitamin D for low-sun months, and vitamin C packs.",
                    "Vitamin D3, Emergen-C", "vitamin d3", "emergen-c"),
            new Category("sleep", "Sleep", "moon",
                    "Gentle, drug-free sleep support.",
                    "Melatonin", "melatonin"),
            new Category("first_aid", "First aid & eye care", "care",
                    "Bandages for scrapes and blisters, and drops for dry, tired eyes.",
                    "Band-Aid, eye drops", "band-aid", "systane eye drops"));

    static Category category(String id) {
        for (Category c : CATALOG) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * What "Picked for you" recommends for: a need, its shelf title, the catalog category behind "See all", and
     * the searches that fill it. Mostly one per category; eye care is split from first aid because a reading
     * (blink rate) points at it alone.
     */
    static final class Need {
        final String id, title, categoryId, icon;
        final List<String> queries;

        Need(String id, String title, String categoryId, String icon, String... queries) {
            this.id = id;
            this.title = title;
            this.categoryId = categoryId;
            this.icon = icon;
            this.queries = Arrays.asList(queries);
        }
    }

    static final List<Need> NEEDS = Arrays.asList(
            new Need("pain_fever", "Fever & pain relief", "pain_fever", "thermo", "tylenol extra strength", "advil ibuprofen", "digital thermometer"),
            new Need("cold_cough", "Cold & cough relief", "cold_cough", "waves", "dayquil", "mucinex"),
            new Need("allergy", "Allergy relief", "allergy", "leaf", "claritin", "zyrtec"),
            new Need("stomach", "For an upset stomach", "stomach", "care", "pepto bismol", "tums"),
            new Need("electrolytes", "Fluids & electrolytes", "electrolytes", "drop", "liquid iv", "pedialyte"),
            new Need("vitamins", "Vitamins & immunity", "vitamins", "pill", "vitamin d3", "emergen-c"),
            new Need("sleep", "Sleep support", "sleep", "moon", "melatonin"),
            new Need("first_aid", "Blisters & scrapes", "first_aid", "care", "band-aid"),
            new Need("eyes", "Tired, dry eyes", "first_aid", "eye", "systane eye drops"));

    static Need need(String id) {
        for (Need n : NEEDS) if (n.id.equals(id)) return n;
        return null;
    }

    /** {title, whatItsFor} for a category id (the chat's "Open …" buttons use it too). */
    static String[] detail(String id) {
        Category c = category(id);
        return c == null ? new String[]{"Products", "Search for what you need."} : new String[]{c.title, c.about};
    }

    enum PlaceType {
        ALL("All", "medical clinic"), HOSPITAL("Hospitals / ER", "emergency room"), URGENT_CARE("Urgent care", "urgent care"),
        CLINIC("Clinics", "medical clinic"), PEDIATRIC("Pediatric", "pediatrician"), PHARMACY("Pharmacies", "pharmacy"),
        LABS("Labs", "medical lab");

        final String label, mapsQuery;

        PlaceType(String label, String mapsQuery) {
            this.label = label;
            this.mapsQuery = mapsQuery;
        }
    }

    /** The place searches run near you (OpenStreetMap's own words for them), most urgent first. */
    static final List<String> PLACE_SEARCHES = Arrays.asList("hospital", "urgent care", "clinic", "doctors", "pharmacy");
}
