package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Care tab's plain logic, kept free of Android so CareCheck can run it on the Mac: tidying product
 * listings, choosing a price, reading OpenStreetMap opening hours, distances, and sorting places into kinds
 * of care. ("Picked for you" is CareModel.)
 */
final class CareLogic {
    private CareLogic() {
    }

    /** "tylenol extra strength" -> "tylenol_extra_strength": the cache and snapshot name for a search. */
    static String slug(String q) {
        return q.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    // ---- Products -------------------------------------------------------------------------------

    /** "Tylenol Extra Strength Pain Reliever … Caplets - Acetaminophen - 24ct" -> the part before the first " - ". */
    static String shortName(String title) {
        String t = cleanTitle(title);
        String n = t.split(" [-–—|/] ")[0].replaceAll("(?i)\\s+size:.*$", "").trim();
        if (n.length() < 4) n = t;
        int letters = n.replaceAll("[^A-Za-z]", "").length(), upper = n.replaceAll("[^A-Z]", "").length();
        if (letters >= 3 && upper > letters * 0.6) n = titleCase(n);   // mostly shouting
        return n;
    }

    private static final Set<String> KEEP_UPPER = new HashSet<>(Arrays.asList("IU", "PM", "AM", "XL", "HCL", "D3", "B12", "IV", "I.V.", "ER"));

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.US).split(" ")) {
            if (b.length() > 0) b.append(' ');
            if (KEEP_UPPER.contains(w.toUpperCase(Locale.US))) b.append(w.toUpperCase(Locale.US));
            else if (w.matches("\\d.*|mg|ml|oz|fl")) b.append(w);
            else if (!w.isEmpty()) {
                StringBuilder part = new StringBuilder(w);   // each piece of "pepto-bismol"
                for (int i = 0; i < part.length(); i++) {
                    if (i == 0 || part.charAt(i - 1) == '-') part.setCharAt(i, Character.toUpperCase(part.charAt(i)));
                }
                b.append(part);
            }
        }
        return b.toString();
    }

    private static final Pattern SIZE = Pattern.compile("(?i)(?<![\\d.])(\\d*\\.?\\d+)\\s*(ct|count|caplets?|tablets?|softgels?|gummies|"
            + "capsules?|packets?|sticks?|stickpacks|fl\\.? ?oz|oz|ml|liters?|pieces?|strips?|bandages?|tb|ea)\\b\\.?");

    /** The listing's size if it reads as one, else one from the title: "24ct" -> "24 count", "100.00 Tablets" -> "100 tablets". */
    static String size(String sizeField, String title) {
        Matcher m = SIZE.matcher(sizeField == null ? "" : sizeField);
        if (!m.find()) m = SIZE.matcher(cleanTitle(title));
        else if (!sizeField.trim().matches("\\.?\\d.*")) return "";   // "Pack of 2", "6 Count (Pack of 1)": not a size alone
        else m = SIZE.matcher(sizeField);
        if (!m.find()) return "";
        String unit = m.group(2).toLowerCase(Locale.US).replace(".", "");
        unit = unit.equals("ct") ? "count" : unit.equals("pk") ? "pack" : unit.equals("tb") ? "tablets" : unit;
        String n = m.group(1).replaceAll("\\.0+$", "");
        return (n.startsWith(".") ? "0" + n : n) + " " + unit;
    }

    /** UPCitemdb titles, tidied: no "(3 pack)" lead-ins, no mis-encoded stars, "Children s" mended. */
    static String cleanTitle(String title) {
        String t = title == null ? "" : title;
        t = t.replaceAll("[ÃÂâ][\\u0080-\\u02FF\\u2000-\\u21FF]+", " ").replace('★', ' ');
        t = t.replaceAll("(?i)^\\(?\\d+\\s*(pack|pk|x)\\)?\\s*-?\\s*", "");
        t = t.replaceAll("\\b(Children|Kid|Women|Men|Adult)\\s+s\\b", "$1's");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static final Pattern JUNK = Pattern.compile("(?i)\\b(case of|counter unit|display|purifier|humidifier|levofloxacin|"
            + "costume|t-shirt|toy)\\b");

    /** A listing to leave off the shelf: off-topic (an air purifier under Claritin) or a wholesale case. */
    static boolean junk(String title, double price) {
        return JUNK.matcher(title == null ? "" : title).find() || price > 60;
    }

    private static final Set<String> VAGUE = new HashSet<>(Arrays.asList("digital", "extra", "strength", "eye", "drops", "the",
            "kids", "childrens", "adult", "best", "for", "cheap", "brand"));

    /** Does the listing answer the search? Its first telling word ("digital thermometer" -> "thermometer") starts a word. */
    static boolean relevant(String query, String title, String brand) {
        String text = " " + plain(title + " " + brand) + " ";
        for (String w : plain(query).split(" ")) {
            if (w.isEmpty() || VAGUE.contains(w)) continue;
            return text.contains(" " + w);
        }
        return true;
    }

    private static String plain(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    private static final List<String> STORES = Arrays.asList("walgreens", "cvs", "target", "walmart", "amazon", "kroger",
            "rite aid", "costco", "safeway", "fred meyer", "qfc", "albertsons");

    /**
     * The offer worth showing: priced, seen in the last two years, a household-name store before others,
     * then the newest. Returns its index, or -1 when none qualifies.
     */
    static int bestOffer(String[] merchants, double[] prices, long[] updatedSec, long nowSec) {
        int best = -1;
        boolean bestKnown = false;
        for (int i = 0; i < prices.length; i++) {
            if (!(prices[i] > 0.5) || nowSec - updatedSec[i] > 2L * 365 * 86400) continue;
            String m = merchants[i] == null ? "" : merchants[i].toLowerCase(Locale.US);
            boolean known = false;
            for (String s : STORES) known |= m.replaceAll("[^a-z ]", "").startsWith(s);
            if (best < 0 || known && !bestKnown || known == bestKnown && updatedSec[i] > updatedSec[best]) {
                best = i;
                bestKnown = known;
            }
        }
        return best;
    }

    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList("pain", "reliever", "relief", "fever", "reducer",
            "caplets", "caplet", "tablets", "tablet", "softgels", "softgel", "capsules", "gummies", "electrolyte", "electrolytes",
            "solution", "hydration", "cold", "flu", "cough", "brand", "adhesive", "bandages", "for", "with", "and", "&", "oral",
            "powder", "drink", "mix", "dietary", "supplement", "chewable", "antacid", "allergy", "medicine", "relieves", "plus",
            "liquid", "size", "flavor", "flavored", "cherry", "berry", "grape", "orange", "strawberry", "lemon", "mint", "peppermint"));

    /** What to type into DoorDash or Uber Eats: the brand words, without the description ("Tylenol Extra Strength"). */
    static String deliveryQuery(String name) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String w : shortName(name).split("[ ,]+")) {
            String bare = w.toLowerCase(Locale.US).replaceAll("[®™]", "");
            if (n > 0 && (GENERIC.contains(bare.replace(":", "")) || bare.matches("\\d.*") || bare.endsWith(":"))) break;
            if (b.length() > 0) b.append(' ');
            b.append(w.replaceAll("[®™]", ""));
            if (++n == 4) break;
        }
        return b.toString();
    }

    /** Brand names to look up on the drug label (openFDA), most specific first. */
    static List<String> labelNames(String name, String brand) {
        List<String> out = new ArrayList<>();
        String[] w = deliveryQuery(name).toLowerCase(Locale.US).replaceAll("[^a-z0-9 -]", "").trim().split(" +");
        for (int k = Math.min(3, w.length); k >= 1; k--) {
            String s = String.join(" ", Arrays.copyOf(w, k)).trim();
            if (s.length() > 2 && !out.contains(s)) out.add(s);
        }
        String b = brand == null ? "" : brand.toLowerCase(Locale.US).replaceAll("[^a-z0-9 -]", "").trim();
        if (b.length() > 2 && !out.contains(b)) out.add(b);
        return out;
    }

    /** Is this FDA label's brand the one we looked up? Spelling-blind ("Pepto-Bismol" = "pepto bismol"), but whole: "Advil PM" isn't "Advil". */
    static boolean sameBrand(String labelBrand, String wanted) {
        String l = plain(labelBrand).replace(" ", ""), w = plain(wanted).replace(" ", "");
        return !w.isEmpty() && l.equals(w);
    }

    /** "Wal-Mart.com" -> "Walmart", "WalMart Canada" -> "Walmart", "Office Depot" stays. */
    static String store(String merchant) {
        String m = merchant == null ? "" : merchant.trim();
        String l = m.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
        if (l.startsWith("walmart")) return "Walmart";
        if (l.startsWith("walgreens")) return "Walgreens";
        if (l.startsWith("target")) return "Target";
        if (l.startsWith("cvs")) return "CVS";
        if (l.startsWith("amazon")) return "Amazon";
        return m.replaceAll("(?i)\\.com$", "");
    }

    /** A drug-label section as plain text: its heading dropped ("Purpose Pain reliever" -> "Pain reliever"), cut to `max`. */
    static String labelText(String raw, int max) {
        if (raw == null) return "";
        String t = raw.replace('■', '•').replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("(?i)^(purposes?|uses?|indications? (and|&) usage|active ingredients?( \\([^)]*\\))?|warnings?|"
                + "directions?|do not use)\\s*:?\\s*", "");
        if (t.length() > max) {
            int cut = t.lastIndexOf(". ", max);
            t = cut > max / 3 ? t.substring(0, cut + 1) : t.substring(0, max).trim() + "…";
        }
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static String price(double dollars) {
        return String.format(Locale.US, "$%.2f", dollars);
    }

    // ---- Places ---------------------------------------------------------------------------------

    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371.0 * Math.asin(Math.sqrt(h));
    }

    /** OpenStreetMap's "+1-206-598-3300" (or several, ";"-separated) -> "(206) 598-3300"; other numbers as written. */
    static String phone(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String first = raw.split("[;,]")[0].trim(), digits = first.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        return digits.length() == 10 ? "(" + digits.substring(0, 3) + ") " + digits.substring(3, 6) + "-" + digits.substring(6) : first;
    }

    static String miles(double km) {
        double mi = km * 0.621371;
        return mi < 0.1 ? "nearby" : String.format(Locale.US, mi < 10 ? "%.1f mi" : "%.0f mi", mi);
    }

    /**
     * What kind of care an OpenStreetMap place is, from its tags, or null to leave it out (dentists, vets,
     * university "laboratories"). Pharmacies first; then anything with an emergency department.
     */
    static CareData.PlaceType classify(String category, String type, String name, String healthcare, String speciality,
                                       String emergency, String urgentCare) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        String hc = healthcare == null ? "" : healthcare;
        String sp = speciality == null ? "" : speciality;
        boolean medical = "amenity".equals(category) && ("hospital".equals(type) || "clinic".equals(type) || "doctors".equals(type)
                || "pharmacy".equals(type)) || "healthcare".equals(category);
        if (!medical || n.isEmpty() || hc.equals("dentist") || sp.contains("dentistry") || sp.equals("veterinary")) return null;
        if ("pharmacy".equals(type) || hc.equals("pharmacy")) return CareData.PlaceType.PHARMACY;
        if ("hospital".equals(type) || hc.equals("hospital") || "yes".equals(emergency)) return CareData.PlaceType.HOSPITAL;
        if ("yes".equals(urgentCare) || n.contains("urgent care")) return CareData.PlaceType.URGENT_CARE;
        if (sp.contains("paediatric") || sp.contains("pediatric") || n.matches(".*\\b(pediatric|paediatric|children's)\\b.*")) {
            return CareData.PlaceType.PEDIATRIC;
        }
        if (hc.equals("laboratory") || hc.equals("sample_collection")) return CareData.PlaceType.LABS;
        return CareData.PlaceType.CLINIC;
    }

    /** OpenStreetMap opening_hours ("Mo-Fr 08:00-20:00; Sa 09:00-17:00; Su off", "24/7"), the common forms. */
    static final class Hours {
        private static final String D = "(?:Mo|Tu|We|Th|Fr|Sa|Su)";
        private static final Pattern RULE = Pattern.compile("^(" + D + "(?:-" + D + ")?(?:," + D + "(?:-" + D + ")?)*)?\\s*(.*)$");
        private static final Pattern SPAN = Pattern.compile("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\+?$");
        private static final List<String> NAMES = Arrays.asList("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");
        private static final String[] SHORT = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

        /** Per day (Monday = 0), open spans in minutes from that midnight; an end past 1440 runs into the next day. */
        private final List<List<int[]>> week;

        private Hours(List<List<int[]>> week) {
            this.week = week;
        }

        /** null when the text isn't in a form this reads (then the card says "Hours not listed"). */
        static Hours parse(String s) {
            if (s == null || s.trim().isEmpty()) return null;
            List<List<int[]>> week = new ArrayList<>();
            for (int d = 0; d < 7; d++) week.add(new ArrayList<>());
            boolean any = false;
            for (String rule : s.trim().split(";|(?<=\\d),\\s*(?=(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)\\b)")) {   // "…21:00, Sa-Su …" too
                rule = rule.trim();
                if (rule.isEmpty() || rule.startsWith("PH") || rule.startsWith("SH")) continue;   // holidays: not modelled
                if (rule.equals("24/7")) rule = "Mo-Su 00:00-24:00";
                Matcher m = RULE.matcher(rule);
                if (!m.matches()) return null;
                boolean[] on = days(m.group(1));
                String times = m.group(2).trim().toLowerCase(Locale.US);
                List<int[]> spans = new ArrayList<>();
                if (!times.equals("off") && !times.equals("closed")) {
                    for (String t : times.split(",")) {
                        Matcher tm = SPAN.matcher(t.trim());
                        if (!tm.matches()) return null;
                        int a = Integer.parseInt(tm.group(1)) * 60 + Integer.parseInt(tm.group(2));
                        int b = Integer.parseInt(tm.group(3)) * 60 + Integer.parseInt(tm.group(4));
                        if (b <= a) b += 1440;   // "22:00-02:00": past midnight
                        spans.add(new int[]{a, b});
                    }
                }
                for (int d = 0; d < 7; d++) if (on[d]) week.set(d, spans);   // a later rule replaces an earlier one
                any = true;
            }
            return any ? new Hours(week) : null;
        }

        private static boolean[] days(String spec) {
            boolean[] on = new boolean[7];
            if (spec == null) {
                Arrays.fill(on, true);
                return on;
            }
            for (String part : spec.split(",")) {
                String[] ends = part.split("-");
                int a = NAMES.indexOf(ends[0]), b = ends.length > 1 ? NAMES.indexOf(ends[1]) : a;
                for (int d = a; ; d = (d + 1) % 7) {   // "Fr-Mo" wraps
                    on[d] = true;
                    if (d == b) break;
                }
            }
            return on;
        }

        /** Day d's spans as seen from its own midnight: its own (cut at midnight) plus the night before's overrun. */
        private List<int[]> on(int d) {
            List<int[]> out = new ArrayList<>();
            for (int[] r : week.get((d + 6) % 7)) if (r[1] > 1440) out.add(new int[]{0, r[1] - 1440});
            for (int[] r : week.get(d)) out.add(new int[]{r[0], Math.min(r[1], 1440)});
            return out;
        }

        boolean allDay() {
            for (int d = 0; d < 7; d++) {
                boolean full = false;
                for (int[] r : on(d)) full |= r[0] == 0 && r[1] >= 1440;
                if (!full) return false;
            }
            return true;
        }

        /** Open at minute `min` of day `day` (Monday = 0)? */
        boolean isOpen(int day, int min) {
            for (int[] r : on(day)) if (r[0] <= min && min < r[1]) return true;
            return false;
        }

        /** "Open 24 hours", "Open · until 9 PM", "Closed · opens 8 AM", "Closed · opens tomorrow 9 AM", "Closed · opens Mon 9 AM". */
        String describe(int day, int min) {
            if (allDay()) return "Open 24 hours";
            for (int[] r : on(day)) {
                if (r[0] <= min && min < r[1]) {
                    int end = r[1];
                    if (end == 1440) {   // through midnight into tomorrow's first span?
                        for (int[] n : on((day + 1) % 7)) if (n[0] == 0 && n[1] < 1440) return "Open · until " + clock(n[1]);
                    }
                    return "Open · until " + clock(end);
                }
            }
            int next = Integer.MAX_VALUE;
            for (int[] r : on(day)) if (r[0] > min) next = Math.min(next, r[0]);
            if (next != Integer.MAX_VALUE) return "Closed · opens " + clock(next);
            for (int k = 1; k <= 7; k++) {
                int d = (day + k) % 7;
                int first = Integer.MAX_VALUE;
                for (int[] r : on(d)) first = Math.min(first, r[0]);
                if (first != Integer.MAX_VALUE) return "Closed · opens " + (k == 1 ? "tomorrow " : SHORT[d] + " ") + clock(first);
            }
            return "Closed";
        }

        /** 540 -> "9 AM", 1230 -> "8:30 PM", 1440 -> "midnight", 720 -> "noon". */
        static String clock(int min) {
            min %= 1440;
            if (min == 0) return "midnight";
            if (min == 720) return "noon";
            int h = min / 60, m = min % 60, h12 = h % 12 == 0 ? 12 : h % 12;
            return (m == 0 ? String.valueOf(h12) : String.format(Locale.US, "%d:%02d", h12, m)) + (h < 12 ? " AM" : " PM");
        }
    }

    /** "within the hour", "3 hours ago", "yesterday", "4 days ago". */
    static String ago(double hours) {
        if (hours < 1) return "within the hour";
        if (hours < 24) return Math.round(hours) == 1 ? "an hour ago" : Math.round(hours) + " hours ago";
        if (hours < 48) return "yesterday";
        return Math.round(hours / 24) + " days ago";
    }
}
