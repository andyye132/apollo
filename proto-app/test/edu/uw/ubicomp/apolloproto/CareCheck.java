package edu.uw.ubicomp.apolloproto;

/**
 * Known-answer tests for the Care tab's plain logic (CareLogic), on the Mac:
 *   javac -d /tmp/care proto-app/src/edu/uw/ubicomp/apolloproto/{CareData,CareLogic,SkinToCore}.java \
 *         proto-app/test/edu/uw/ubicomp/apolloproto/CareCheck.java && java -cp /tmp/care edu.uw.ubicomp.apolloproto.CareCheck
 *  - Listings: real UPCitemdb titles tidy into a name, a size and a delivery search; the fresh Walgreens price wins.
 *  - Drug labels: openFDA section headings are dropped.
 *  - Opening hours: weekday/weekend rules, split days, past-midnight spans, 24/7, and forms it can't read.
 *  - Places: OpenStreetMap tags from Seattle sort into kinds of care; dentists and physics labs are left out.
 *  - Ages: "within the hour" to "4 days ago". ("Picked for you" has its own measure: CareTune.)
 */
public class CareCheck {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }

    static void eq(Object got, Object want, String what) {
        check(want.equals(got), what + (want.equals(got) ? "" : "  (got \"" + got + "\", want \"" + want + "\")"));
    }

    public static void main(String[] args) {
        listings();
        labels();
        hours();
        places();
        ago();
        System.out.println(failures == 0 ? "all care checks passed" : failures + " care check(s) FAILED");
        if (failures > 0) System.exit(1);
    }

    static void listings() {
        System.out.println("listings");
        String tylenol = "Tylenol Extra Strength Pain Reliever and Fever Reducer Caplets - Acetaminophen - 24ct";
        eq(CareLogic.slug("Tylenol Extra-Strength "), "tylenol_extra_strength", "slug");
        eq(CareLogic.shortName(tylenol), "Tylenol Extra Strength Pain Reliever and Fever Reducer Caplets", "name before the first dash");
        eq(CareLogic.shortName("ZYRTEC 24 HR ALLERGY RELIEF TABLETS 10 MG"), "Zyrtec 24 Hr Allergy Relief Tablets 10 mg", "shouting titles calmed");
        eq(CareLogic.size("24 Caplets", tylenol), "24 caplets", "size from the listing");
        eq(CareLogic.size("", tylenol), "24 count", "size from the title");
        eq(CareLogic.size("Gold", "Pedialyte Electrolyte Solution 33.8 fl oz"), "33.8 fl oz", "a colour isn't a size");
        eq(CareLogic.size("50 TB", ""), "50 tablets", "\"50 TB\" reads as tablets");
        eq(CareLogic.size("100.00 Tablets", ""), "100 tablets", "trailing zeros dropped");
        eq(CareLogic.size(".33 OZ", ""), "0.33 oz", "a leading decimal point");
        eq(CareLogic.size("Pack of 2", "Tylenol Extra Strength Caplets - 24ct"), "24 count", "\"Pack of 2\" isn't a size: the title's is used");
        eq(CareLogic.size("Tablets", "Advil Tablets 200mg"), "", "no size anywhere");
        eq(CareLogic.cleanTitle("(3 pack) Band-Aid Brand Flexible Fabric Bandages"), "Band-Aid Brand Flexible Fabric Bandages", "pack lead-in dropped");
        eq(CareLogic.cleanTitle("3 Pack - Pedialyte Oral Electrolyte Solution"), "Pedialyte Oral Electrolyte Solution", "pack lead-in with a dash");
        eq(CareLogic.cleanTitle("Vitamin D3 K2 MK-7 Supplements \u00e2\u02dc\u2026 New \u00e2\u02dc\u2026 Full"), "Vitamin D3 K2 MK-7 Supplements New Full", "mis-encoded stars dropped");
        eq(CareLogic.cleanTitle("Zyrtec 24 Hr Children s Allergy Relief Syrup"), "Zyrtec 24 Hr Children's Allergy Relief Syrup", "apostrophe mended");
        eq(CareLogic.shortName("Tylenol Extra Strength Caplet / ACETAMINOPHEN / TABLET"), "Tylenol Extra Strength Caplet", "name before a slash");
        check(CareLogic.junk("Claritin Tower Air Purifier", 104.99), "an air purifier isn't allergy medicine");
        check(CareLogic.junk("Handy Solutions Pepto Bismol Chewable Tablets - 4 Tablets - CASE OF 14", 331.95), "wholesale cases left out");
        check(!CareLogic.junk("Tylenol Extra Strength 24ct", 4.49), "a normal listing stays");
        check(CareLogic.relevant("tums", "Glaxosmithkline - Tums Extra Strength Berry 48ct", ""), "Tums is Tums");
        check(!CareLogic.relevant("tums", "Tum-E Yummies Fruit Flavored Drink", ""), "Tum-E Yummies isn't Tums");
        check(!CareLogic.relevant("zyrtec", "Levofloxacin / SOLUTION", "Zyrtek"), "an antibiotic isn't Zyrtec");
        check(CareLogic.relevant("digital thermometer", "STANDARD DIGITAL THERMOMETER-N-GRN-BX", ""), "the telling word, not \"digital\"");
        check(CareLogic.relevant("liquid iv", "HYDRATION MULTIPLIER POWDER DRINK MIX", "Liquid I.V."), "the brand counts");
        check(CareLogic.relevant("band-aid", "(3 pack) BAND-AID Brand Large Pro Heal Bandages", ""), "hyphenated brands");
        eq(CareLogic.deliveryQuery(tylenol), "Tylenol Extra Strength", "delivery search: Tylenol");
        eq(CareLogic.deliveryQuery("Liquid I.V. Hydration Multiplier, Lemon Lime"), "Liquid I.V.", "delivery search: Liquid I.V.");
        eq(CareLogic.deliveryQuery("Pedialyte Electrolyte Solution, Grape"), "Pedialyte", "delivery search: Pedialyte");
        eq(CareLogic.deliveryQuery("Nature Made Vitamin D3 2000 IU Softgels"), "Nature Made Vitamin D3", "delivery search: stops at the dose");
        eq(CareLogic.deliveryQuery("Band-Aid® Brand Flexible Fabric Adhesive Bandages"), "Band-Aid", "delivery search: trademark dropped");
        eq(String.join("|", CareLogic.labelNames(tylenol, "Tylenol")), "tylenol extra strength|tylenol extra|tylenol", "label names, specific first");
        // From the real "Tylenol Extra Strength 24ct" listing: an old Staples price, a fresh Walgreens one, a cheap stale Sears one.
        String[] m = {"Staples", "Walgreens", "Newegg.com", "Sears", "FSAstore.com"};
        double[] p = {7.49, 6.99, 8.31, 3.83, 5.52};
        long[] t = {1579871478L, 1791951265L, 1648608557L, 1481111020L, 1606664444L};
        eq(CareLogic.bestOffer(m, p, t, 1792300000L), 1, "fresh household-name price chosen");
        eq(CareLogic.bestOffer(m, p, t, 1892300000L), -1, "no price once every offer is stale");
        eq(CareLogic.bestOffer(new String[]{"Shop A", "Shop B"}, new double[]{4.0, 0}, new long[]{1792000000L, 1792200000L}, 1792300000L), 0,
                "a priced offer beats a newer unpriced one");
        eq(CareLogic.price(6.99), "$6.99", "price format");
        eq(CareLogic.shortName("PEPTO-BISMOL LIQUID CHERRY Size: 8 OZ"), "Pepto-Bismol Liquid Cherry", "mostly-capitals title calmed, size dropped");
        eq(CareLogic.deliveryQuery("PEPTO-BISMOL LIQUID CHERRY Size: 8 OZ"), "Pepto-Bismol", "delivery search: flavour dropped");
        check(CareLogic.sameBrand("Pepto-Bismol", "pepto bismol") && CareLogic.sameBrand("ADVIL", "advil"), "label brands match spelling-blind");
        check(!CareLogic.sameBrand("Advil Dual Action with Acetaminophen", "advil") && !CareLogic.sameBrand("Band Aid Antiseptic Cleansing", "band-aid"),
                "a different product under the same brand doesn't match");
        eq(CareLogic.store("Wal-Mart.com"), "Walmart", "Wal-Mart.com");
        eq(CareLogic.store("WalMart Canada"), "Walmart", "WalMart Canada");
        eq(CareLogic.store("UnbeatableSale.com"), "UnbeatableSale", "a .com dropped");
        eq(CareLogic.bestOffer(new String[]{"UnbeatableSale.com", "Wal-Mart.com"}, new double[]{23.04, 22.99}, new long[]{1792000000L, 1736000000L}, 1792300000L),
                1, "Wal-Mart.com counts as a household name");
    }

    static void labels() {
        System.out.println("drug labels");
        eq(CareLogic.labelText("Purpose Pain reliever/fever reducer", 200), "Pain reliever/fever reducer", "purpose heading dropped");
        eq(CareLogic.labelText("Active ingredient (in each tablet) Acetaminophen 500 mg", 200), "Acetaminophen 500 mg", "ingredient heading dropped");
        eq(CareLogic.labelText("Uses temporarily relieves minor aches ■ headache ■ backache", 200),
                "Temporarily relieves minor aches • headache • backache", "uses: bullets kept");
        String cut = CareLogic.labelText("Warnings Liver warning: This product contains acetaminophen. Severe liver damage may occur "
                + "if you take more than 4,000 mg in 24 hours. With other drugs containing acetaminophen. 3 or more alcoholic drinks.", 120);
        check(cut.startsWith("Liver warning") && cut.endsWith(".") && cut.length() <= 121, "warnings cut at a sentence: " + cut);
    }

    static void hours() {
        System.out.println("opening hours");
        CareLogic.Hours h = CareLogic.Hours.parse("Mo-Fr 08:00-20:00; Sa 09:00-17:00; Su off");
        check(h != null, "weekday and weekend rules read");
        eq(h.describe(0, 10 * 60), "Open · until 8 PM", "Monday morning");
        eq(h.describe(0, 21 * 60), "Closed · opens tomorrow 8 AM", "Monday night");
        eq(h.describe(4, 21 * 60), "Closed · opens tomorrow 9 AM", "Friday night: Saturday hours");
        eq(h.describe(5, 18 * 60), "Closed · opens Mon 8 AM", "Saturday evening: closed Sunday");
        eq(h.describe(0, 7 * 60), "Closed · opens 8 AM", "Monday before opening");
        check(h.isOpen(2, 8 * 60) && !h.isOpen(6, 12 * 60), "open Wednesday 8 AM, shut Sunday noon");
        eq(CareLogic.Hours.parse("24/7").describe(3, 3 * 60), "Open 24 hours", "24/7");
        eq(CareLogic.Hours.parse("Mo-Su 00:00-24:00").describe(6, 23 * 60), "Open 24 hours", "round the clock spelled out");
        CareLogic.Hours split = CareLogic.Hours.parse("Mo-Tu,Th 08:00-19:00; We 08:00-17:30; Fr 08:30-17:30; Sa 08:00-16:00");
        eq(split.describe(2, 17 * 60), "Open · until 5:30 PM", "UW Ravenna clinic, Wednesday");
        eq(split.describe(3, 18 * 60), "Open · until 7 PM", "UW Ravenna clinic, Thursday");
        CareLogic.Hours lunch = CareLogic.Hours.parse("Mo-Fr 09:00-12:00,13:00-17:00");
        eq(lunch.describe(0, 12 * 60 + 30), "Closed · opens 1 PM", "closed over lunch");
        CareLogic.Hours late = CareLogic.Hours.parse("Fr-Sa 18:00-02:00");
        eq(late.describe(5, 60), "Open · until 2 AM", "Saturday 1 AM: Friday's late night");
        eq(late.describe(6, 60), "Open · until 2 AM", "Sunday 1 AM: Saturday's late night");
        eq(late.describe(6, 3 * 60), "Closed · opens Fri 6 PM", "Sunday 3 AM: next Friday");
        CareLogic.Hours comma = CareLogic.Hours.parse("Mo-Fr 07:00-21:00, Sa-Su 09:00-17:00");
        eq(comma.describe(6, 10 * 60), "Open · until 5 PM", "rules split by a comma");
        check(CareLogic.Hours.parse("Mo-Tu,Th-Fr 08:00-17:30; We \"varies\"") == null, "\"varies\" isn't read");
        check(CareLogic.Hours.parse("") == null && CareLogic.Hours.parse(null) == null, "no hours, no answer");
        eq(CareLogic.Hours.parse("Mo-Fr 08:00-24:00").describe(0, 23 * 60), "Open · until midnight", "until midnight");
        eq(CareLogic.Hours.clock(720), "noon", "noon");
    }

    static void places() {
        System.out.println("places");
        eq(CareLogic.classify("amenity", "hospital", "Seattle Children's Hospital", "hospital", "paediatrics", "yes", null),
                CareData.PlaceType.HOSPITAL, "children's hospital is a hospital");
        eq(CareLogic.classify("amenity", "doctors", "University of Washington Medical Center - Montlake", "doctor", null, "yes", null),
                CareData.PlaceType.HOSPITAL, "a doctors' office with an ER counts as one");
        eq(CareLogic.classify("amenity", "clinic", "Indigo Urgent Care", "clinic", null, null, null), CareData.PlaceType.URGENT_CARE, "urgent care by name");
        eq(CareLogic.classify("amenity", "clinic", "Zoom Care", "clinic", null, null, "yes"), CareData.PlaceType.URGENT_CARE, "urgent care by tag");
        eq(CareLogic.classify("amenity", "doctors", "Ballard Pediatric Clinic", "doctor", "paediatrics", null, null), CareData.PlaceType.PEDIATRIC, "pediatric");
        eq(CareLogic.classify("amenity", "pharmacy", "QFC Pharmacy #807", null, null, null, null), CareData.PlaceType.PHARMACY, "pharmacy");
        eq(CareLogic.classify("amenity", "clinic", "UW Neighborhood Ravenna Clinic", "clinic", null, null, null), CareData.PlaceType.CLINIC, "clinic");
        check(CareLogic.classify("amenity", "dentist", "Ballard Pediatric Dentistry", "dentist", "paediatric_dentistry", null, null) == null, "dentists left out");
        check(CareLogic.classify("building", "university", "North Physics Laboratory", null, null, null, null) == null, "physics labs left out");
        double km = CareLogic.distanceKm(47.6553, -122.3035, 47.6205, -122.3493);   // UW to the Space Needle
        check(km > 4.8 && km < 5.4, String.format("UW to the Space Needle %.2f km", km));
        eq(CareLogic.miles(km), "3.2 mi", "in miles (5.18 km)");
        eq(CareLogic.phone("+1-206-598-3300"), "(206) 598-3300", "phone, US style");
        eq(CareLogic.phone("+1 206 987 2000;+1 206 987 2001"), "(206) 987-2000", "the first of several");
        eq(CareLogic.phone("+44 20 7946 0958"), "+44 20 7946 0958", "other countries as written");
    }


    static void ago() {
        System.out.println("ages");
        eq(CareLogic.ago(0.4), "within the hour", "minutes");
        eq(CareLogic.ago(2.2), "2 hours ago", "hours");
        eq(CareLogic.ago(30), "yesterday", "yesterday");
        eq(CareLogic.ago(100), "4 days ago", "days");
    }
}
