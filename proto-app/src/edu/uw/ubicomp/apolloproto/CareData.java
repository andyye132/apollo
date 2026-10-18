package edu.uw.ubicomp.apolloproto;

import java.util.Arrays;
import java.util.List;

/**
 * Care sample data, ported verbatim from the team's Compose app (app/.../care/CareSampleData.kt on the
 * ui-retheme branch). All fictional: no inventory, prices, addresses or live wait times.
 */
final class CareData {
    private CareData() {
    }

    enum Retailer {
        CVS("CVS Health", 0.4), WALGREENS("Walgreens", 0.9), WALMART("Walmart (Equate)", 1.2),
        TARGET("Target (up&up)", 2.0), COSTCO("Costco (Kirkland Signature)", 5.4);

        final String displayName;
        final double distanceMi;

        Retailer(String displayName, double distanceMi) {
            this.displayName = displayName;
            this.distanceMi = distanceMi;
        }
    }

    static final class Product {
        final String name, sizeOrCount, inStockLabel;
        final Retailer retailer;

        Product(String name, Retailer retailer, String sizeOrCount, String inStockLabel) {
            this.name = name;
            this.retailer = retailer;
            this.sizeOrCount = sizeOrCount;
            this.inStockLabel = inStockLabel;
        }
    }

    static final class Pick {
        final String icon, title, whyThis, reading, categoryId;

        Pick(String icon, String title, String whyThis, String reading, String categoryId) {
            this.icon = icon;
            this.title = title;
            this.whyThis = whyThis;
            this.reading = reading;
            this.categoryId = categoryId;
        }
    }

    static final class Category {
        final String id, title, icon;
        final int itemCount;

        Category(String id, String title, String icon, int itemCount) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.itemCount = itemCount;
        }
    }

    enum PlaceType {
        ALL("All"), ER("Hospitals / ER"), URGENT_CARE("Urgent care"), WALK_IN("Walk-in clinics"), PEDIATRIC("Pediatric"),
        LABS("Labs"), VACCINES("Vaccines"), VIRTUAL("Virtual visits");

        final String label;

        PlaceType(String label) {
            this.label = label;
        }
    }

    static final class Place {
        final String name, hoursText, phone;
        final PlaceType type;
        final double distanceMi;
        final boolean isOpenNow, isVirtual;
        final Integer waitMinutes;

        Place(String name, PlaceType type, double distanceMi, boolean isOpenNow, String hoursText, Integer waitMinutes,
              String phone, boolean isVirtual) {
            this.name = name;
            this.type = type;
            this.distanceMi = distanceMi;
            this.isOpenNow = isOpenNow;
            this.hoursText = hoursText;
            this.waitMinutes = waitMinutes;
            this.phone = phone;
            this.isVirtual = isVirtual;
        }
    }

    static final boolean TRAVEL_MODE = true;
    static final String TRAVEL_CITY = "Lisbon";

    static final List<Pick> PICKED_FOR_YOU = Arrays.asList(
            new Pick("drop", "Electrolytes", "Your pulse has been a bit elevated on a hot day", "Heart rate 104 BPM", "electrolytes"),
            new Pick("thermo", "Thermometer", "Your last check-in read slightly above normal", "Temperature 99.4°F", "pain_fever"),
            new Pick("eye", "Eye drops", "Screen time has been high in low light this evening", "Low-light screen time 3.1 hrs", "first_aid"));

    static final List<Category> CATALOG = Arrays.asList(
            new Category("vitamins", "Vitamins & supplements", "pill", 42),
            new Category("pain_fever", "Pain & fever", "thermo", 18),
            new Category("cold_cough", "Cold & cough", "waves", 24),
            new Category("allergy", "Allergy", "leaf", 15),
            new Category("stomach", "Stomach & digestion", "care", 20),
            new Category("electrolytes", "Electrolytes & hydration", "drop", 9),
            new Category("sleep", "Sleep", "moon", 11),
            new Category("first_aid", "First aid & supplies", "care", 33));

    private static final String IN = "In stock nearby", CHECK = "Check store";

    static final List<Product> VITAMIN_D = Arrays.asList(
            new Product("CVS Health Vitamin D3 2000 IU", Retailer.CVS, "100 softgels", IN),
            new Product("Equate Vitamin D3 2000 IU", Retailer.WALMART, "250 softgels", IN),
            new Product("up&up Vitamin D3 1000 IU", Retailer.TARGET, "150 softgels", CHECK),
            new Product("Walgreens Vitamin D3 2000 IU", Retailer.WALGREENS, "120 softgels", IN),
            new Product("Kirkland Signature Vitamin D3 2000 IU", Retailer.COSTCO, "600 softgels", CHECK));

    static final List<Product> ELECTROLYTES = Arrays.asList(
            new Product("Equate Electrolyte Powder, Mixed Berry", Retailer.WALMART, "20 packets", IN),
            new Product("up&up Electrolyte Drink Mix", Retailer.TARGET, "16 packets", IN),
            new Product("CVS Health Rehydration Solution", Retailer.CVS, "1 L", CHECK),
            new Product("Walgreens Electrolyte Freezer Pops", Retailer.WALGREENS, "16 pops", IN),
            new Product("Kirkland Signature Electrolyte Drink", Retailer.COSTCO, "40 pack", CHECK));

    static final List<Product> PAIN_FEVER = Arrays.asList(
            new Product("Digital Thermometer, 60-second read", Retailer.CVS, "1 count", IN),
            new Product("Equate Infrared Thermometer", Retailer.WALMART, "1 count", IN),
            new Product("up&up Pain Reliever, Acetaminophen 500 mg", Retailer.TARGET, "100 caplets", CHECK),
            new Product("Walgreens Pain Reliever, Ibuprofen 200 mg", Retailer.WALGREENS, "150 tablets", IN),
            new Product("Kirkland Signature Pain Reliever, Acetaminophen", Retailer.COSTCO, "500 caplets", CHECK));

    static final List<Product> FIRST_AID = Arrays.asList(
            new Product("CVS Health Lubricant Eye Drops", Retailer.CVS, "0.5 fl oz", IN),
            new Product("up&up Dry Eye Relief Drops", Retailer.TARGET, "2 x 0.5 fl oz", IN),
            new Product("Equate Adhesive Bandages", Retailer.WALMART, "100 count", IN),
            new Product("Walgreens First Aid Kit", Retailer.WALGREENS, "140 pieces", CHECK),
            new Product("Kirkland Signature Eye Drops, Twin Pack", Retailer.COSTCO, "2 x 1 fl oz", CHECK));

    static final List<Product> GENERIC = Arrays.asList(
            new Product("Store-brand item, option A", Retailer.CVS, "1 count", IN),
            new Product("Store-brand item, option B", Retailer.WALGREENS, "1 count", IN),
            new Product("Store-brand item, option C", Retailer.WALMART, "1 count", CHECK),
            new Product("Store-brand item, option D", Retailer.TARGET, "1 count", CHECK),
            new Product("Store-brand item, option E", Retailer.COSTCO, "1 count", CHECK));

    /** {title, whatItsFor} and the products for a category. */
    static String[] detail(String id) {
        switch (id) {
            case "vitamins":
                return new String[]{"Vitamin D", "Supports bone, muscle, and immune health, especially when sun exposure is low."};
            case "electrolytes":
                return new String[]{"Electrolytes & hydration", "Replaces salts and minerals lost through sweat, heat, or a stomach bug."};
            case "pain_fever":
                return new String[]{"Pain & fever", "Everyday pain relievers and fever reducers, plus thermometers to track a reading."};
            case "first_aid":
                return new String[]{"First aid & supplies", "Basics for small scrapes, dry or tired eyes, and stocking a medicine cabinet."};
            default:
                String title = "Products";
                for (Category c : CATALOG) if (c.id.equals(id)) title = c.title;
                return new String[]{title, "A starting point for this category. Sample data for prototyping."};
        }
    }

    static List<Product> products(String id) {
        switch (id) {
            case "vitamins":
                return VITAMIN_D;
            case "electrolytes":
                return ELECTROLYTES;
            case "pain_fever":
                return PAIN_FEVER;
            case "first_aid":
                return FIRST_AID;
            default:
                return GENERIC;
        }
    }

    static final List<Place> PLACES = Arrays.asList(
            new Place("Riverside General Hospital — ER", PlaceType.ER, 2.1, true, "Open 24 hours", 42, "(555) 010-0199", false),
            new Place("Maple Street Urgent Care", PlaceType.URGENT_CARE, 0.8, true, "Open until 9:00 PM", 15, "(555) 010-0144", false),
            new Place("Harbor Walk-In Clinic", PlaceType.WALK_IN, 1.3, true, "Open until 8:00 PM", 22, "(555) 010-0177", false),
            new Place("Sunnyvale Pediatric Care", PlaceType.PEDIATRIC, 3.4, false, "Opens 8:00 AM tomorrow", null, "(555) 010-0133", false),
            new Place("QuickDraw Labs — Downtown", PlaceType.LABS, 1.0, true, "Open until 6:00 PM", 10, "(555) 010-0166", false),
            new Place("Northside Pharmacy Vaccines", PlaceType.VACCINES, 0.6, true, "Open until 7:00 PM", 5, "(555) 010-0122", false),
            new Place("Apollo Virtual Visit", PlaceType.VIRTUAL, 0.0, true, "Available now", 3, "(555) 010-0100", true),
            new Place("East End Urgent Care", PlaceType.URGENT_CARE, 4.6, false, "Opens 7:00 AM tomorrow", null, "(555) 010-0155", false));
}
