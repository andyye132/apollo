package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Picked for you": an on-device recommender. Each need (CareData.NEEDS) collects evidence:
 *  - readings: a warm temperature estimate, a fast pulse, a long walk, weaker breath than usual, a low blink
 *    rate over long screen time;
 *  - words: what the user tells Apollo, what Apollo answers (and the shelves it points to), and searches, each
 *    read by a Naive Bayes classifier trained on CareCorpus (with negation: "no fever" isn't a fever);
 *  - behaviour: categories browsed, products opened, delivery tapped (more of that) and shelves hidden (less).
 * Every piece is weighted by its kind and fades with its own half-life; the best-supported needs become shelves,
 * each with the reasons behind it. The weights come from CareTune (a labelled scenario set, tuned on part of it
 * and checked on the rest). Plain Java: it runs on the Mac too.
 */
final class CareModel {

    // ---- Words: a Naive Bayes need classifier -------------------------------------------------------

    /** Multinomial Naive Bayes over word and word-pair features, trained on labelled phrases ({class, phrase…}). */
    static final class Text {
        final List<String> classes = new ArrayList<>();
        private final Map<String, double[]> logLik = new HashMap<>();
        private final double temper;

        Text(String[][] corpus, double alpha, double temper) {
            this.temper = temper;
            for (String[] row : corpus) classes.add(row[0]);
            int k = classes.size();
            Map<String, double[]> counts = new HashMap<>();
            double[] totals = new double[k];
            for (int c = 0; c < k; c++) {
                for (int i = 1; i < corpus[c].length; i++) {
                    for (String f : features(corpus[c][i])) {
                        counts.computeIfAbsent(f, x -> new double[k])[c]++;
                        totals[c]++;
                    }
                }
            }
            int v = counts.size();
            for (Map.Entry<String, double[]> e : counts.entrySet()) {
                double[] l = new double[k];
                for (int c = 0; c < k; c++) l[c] = Math.log((e.getValue()[c] + alpha) / (totals[c] + alpha * v));
                logLik.put(e.getKey(), l);
            }
        }

        /** P(class | text) for each class, in `classes` order. Unseen words carry no vote; no known word at all: all "none". */
        double[] posterior(String text) {
            int k = classes.size();
            double[] s = new double[k];
            boolean any = false;
            for (String f : features(text)) {
                double[] l = logLik.get(f);
                if (l == null) continue;
                any = true;
                for (int c = 0; c < k; c++) s[c] += l[c];
            }
            double[] p = new double[k];
            if (!any) {
                p[classes.indexOf("none")] = 1;
                return p;
            }
            double max = Double.NEGATIVE_INFINITY, sum = 0;
            for (double x : s) max = Math.max(max, x);
            for (int c = 0; c < k; c++) sum += p[c] = Math.exp((s[c] - max) / temper);   // tempered: NB is overconfident
            for (int c = 0; c < k; c++) p[c] /= sum;
            return p;
        }

        /** The most likely class. */
        String top(String text) {
            double[] p = posterior(text);
            int best = 0;
            for (int c = 1; c < p.length; c++) if (p[c] > p[best]) best = c;
            return classes.get(best);
        }

        private static final Pattern WORD = Pattern.compile("[a-z0-9]+(?:['’\\-][a-z0-9]+)*|[.,!?;]");
        private static final Set<String> NEGATORS = new HashSet<>(Arrays.asList("no", "not", "dont", "never", "without", "isnt",
                "arent", "havent", "hasnt", "didnt", "doesnt", "wasnt", "nor"));
        private static final Set<String> STOP = new HashSet<>(Arrays.asList("i", "im", "me", "my", "a", "an", "the", "and", "or", "to",
                "of", "is", "it", "its", "so", "for", "on", "in", "at", "have", "ha", "had", "be", "been", "am", "are", "was", "thi",
                "that", "with", "from", "just", "really", "very", "today", "some", "any", "ive", "feel", "think", "maybe", "bit",
                "lot", "keep", "again", "all", "too", "up", "out", "get", "got", "after", "you", "your", "do", "can", "what", "how",
                "need", "want", "some", "something", "since", "like", "much", "lately", "now", "still", "help", "please", "should"));

        /**
         * Lower-cased, lightly stemmed words (coughing, coughs -> cough) minus filler, then each adjacent pair. A
         * negation marks the next three words until a clause ends: "no fever, just a stuffy nose" gives not_fever.
         */
        static List<String> features(String text) {
            Matcher m = WORD.matcher(text.toLowerCase(Locale.US));
            List<String> words = new ArrayList<>();
            int negate = 0;
            while (m.find()) {
                String w = m.group();
                if (w.length() == 1 && ".,!?;".contains(w) || w.equals("but") || w.equals("just")) {
                    negate = 0;
                    words.add("|");   // a clause break: no pair across it
                    continue;
                }
                w = w.replaceAll("['’\\-]", "");
                if (NEGATORS.contains(w)) {
                    negate = 3;
                    continue;
                }
                String s = stem(w);
                if (STOP.contains(s) || STOP.contains(w)) continue;
                words.add(negate-- > 0 ? "not_" + s : s);
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                if (words.get(i).equals("|")) continue;
                out.add(words.get(i));
                if (i + 1 < words.size() && !words.get(i + 1).equals("|")) out.add(words.get(i) + "_" + words.get(i + 1));
            }
            return out;
        }

        /** Just enough stemming to join word forms: allergies -> allergy -> allerg, sneezing, sneeze -> sneez, dehydration -> dehydrat. */
        static String stem(String w) {
            if (w.length() > 4 && w.endsWith("ies")) w = w.substring(0, w.length() - 3) + "y";
            else if (w.length() > 5 && w.endsWith("ing")) w = w.substring(0, w.length() - 3);
            else if (w.length() > 6 && w.endsWith("ion")) w = w.substring(0, w.length() - 3);
            else if (w.length() > 4 && w.endsWith("ed")) w = w.substring(0, w.length() - 2);
            else if (w.length() > 4 && w.endsWith("es")) w = w.substring(0, w.length() - 2);
            else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
            if (w.length() > 3 && w.endsWith("e") || w.length() > 4 && w.endsWith("y")) w = w.substring(0, w.length() - 1);
            return w;
        }
    }

    // ---- Inputs ---------------------------------------------------------------------------------

    /** The latest readings (NaN = none; ages in hours). */
    static final class Readings {
        double tempC = Double.NaN, tempAgeH = Double.NaN, bpm = Double.NaN, bpmAgeH = Double.NaN;
        double breathPct = Double.NaN, breathAgeH = Double.NaN, blinkRate = Double.NaN, screenMin;
        long steps;
    }

    enum Kind { CHAT, APOLLO, APOLLO_PICK, SEARCH, OPEN, VIEW, DELIVER, DISMISS }

    /**
     * Something the user said, searched or did. `text` for words (chat, Apollo's reply, a search, a product's
     * name); `need` when it names one outright (a shelf Apollo pointed to, a category opened, a shelf hidden).
     */
    static final class Signal {
        final Kind kind;
        final double ageH;
        final String text, need;

        Signal(Kind kind, double ageH, String text, String need) {
            this.kind = kind;
            this.ageH = ageH;
            this.text = text == null ? "" : text;
            this.need = need;
        }
    }

    /** The model's knobs: how much each kind of evidence counts and how fast it fades (half-lives in hours). */
    static final class Params {
        double wChat = 1.0, wApollo = 0.5, wApolloPick = 0.8, wSearch = 0.9, wOpen = 0.4, wView = 0.5, wDeliver = 0.8, wDismiss = -2.0;
        double hlChat = 36, hlSearch = 72, hlBrowse = 120, hlDismiss = 72, hlReading = 24;
        double wFever = 1.2, wPulse = 0.9, wSteps = 0.7, wBreath = 0.6, wEyes = 0.9;
        double alpha = 0.5, temper = 1.0;

        Params copy() {
            Params q = new Params();
            q.wChat = wChat;
            q.wApollo = wApollo;
            q.wApolloPick = wApolloPick;
            q.wSearch = wSearch;
            q.wOpen = wOpen;
            q.wView = wView;
            q.wDeliver = wDeliver;
            q.wDismiss = wDismiss;
            q.hlChat = hlChat;
            q.hlSearch = hlSearch;
            q.hlBrowse = hlBrowse;
            q.hlDismiss = hlDismiss;
            q.hlReading = hlReading;
            q.wFever = wFever;
            q.wPulse = wPulse;
            q.wSteps = wSteps;
            q.wBreath = wBreath;
            q.wEyes = wEyes;
            q.alpha = alpha;
            q.temper = temper;
            return q;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "wChat=%.2f wApollo=%.2f wApolloPick=%.2f wSearch=%.2f wOpen=%.2f wView=%.2f wDeliver=%.2f "
                            + "wDismiss=%.2f hlChat=%.0f hlSearch=%.0f hlBrowse=%.0f hlDismiss=%.0f hlReading=%.0f wFever=%.2f wPulse=%.2f "
                            + "wSteps=%.2f wBreath=%.2f wEyes=%.2f alpha=%.2f temper=%.2f", wChat, wApollo, wApolloPick, wSearch, wOpen, wView,
                    wDeliver, wDismiss, hlChat, hlSearch, hlBrowse, hlDismiss, hlReading, wFever, wPulse, wSteps, wBreath, wEyes, alpha, temper);
        }
    }

    /**
     * The shipped knobs. CareTune (Oct 18 2026, 42 labelled scenarios): the old fixed rules scored NDCG@3 0.17 on
     * the tune set and 0.44 held out; these weights 0.98 and 0.985, after one round of error analysis (corpus gaps,
     * filler words, a normal temperature counting as weak fever evidence). A 3,000-draw random search over all 20
     * knobs, checked by nested 3-fold cross-validation, did not beat them on unseen scenarios (0.973 vs 0.980), so
     * the hand-set values stay. The classifier gets 88% of a fresh test set of phrases right (chance: 10%).
     */
    static Params tuned() {
        return new Params();
    }

    // ---- Output ---------------------------------------------------------------------------------

    /** Why a shelf is there: where it came from ("chat", "apollo", "search", "browse", "temperature", "heart", "steps", "breath", "eyes") and a sentence. */
    static final class Reason {
        final String source, text;
        final double weight;

        Reason(String source, String text, double weight) {
            this.source = source;
            this.text = text;
            this.weight = weight;
        }
    }

    static final class Shelf {
        final CareData.Need need;
        final double score;
        final List<Reason> reasons;   // strongest first; empty for the medicine-cabinet basics
        List<String> queries;

        Shelf(CareData.Need need, double score, List<Reason> reasons) {
            this.need = need;
            this.score = score;
            this.reasons = reasons;
        }

        /** The kinds of evidence that carry at least `share` of this shelf's support, strongest first ("chat", "temperature"…). */
        List<String> sources(double share) {
            Map<String, Double> by = new LinkedHashMap<>();
            for (Reason r : reasons) by.merge(r.source, r.weight, Double::sum);
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Double> e : by.entrySet()) if (e.getValue() >= share * score && !out.contains(e.getKey())) out.add(e.getKey());
            return out;
        }
    }

    /** Needs worth having at home, shown (weakly) when nothing points elsewhere. */
    private static final Map<String, Double> BASICS = new LinkedHashMap<>();

    static {
        BASICS.put("pain_fever", 0.06);
        BASICS.put("first_aid", 0.05);
        BASICS.put("vitamins", 0.04);
    }

    final Params p;
    final Text text;

    CareModel(Params p) {
        this.p = p;
        text = new Text(CareCorpus.PHRASES, p.alpha, p.temper);
    }

    /** P(need | words), for the needs only. */
    Map<String, Double> needs(String words) {
        double[] post = text.posterior(words);
        Map<String, Double> out = new LinkedHashMap<>();
        for (int c = 0; c < post.length; c++) if (!text.classes.get(c).equals("none")) out.put(text.classes.get(c), post[c]);
        return out;
    }

    private static double fade(double ageH, double halfLifeH) {
        return Math.pow(0.5, Math.max(0, ageH) / halfLifeH);
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    private static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    /** The top `k` shelves from these readings and signals, best first. */
    List<Shelf> rank(List<Signal> signals, Readings r, double feverFlagC, int k) {
        Map<String, Double> score = new LinkedHashMap<>();
        Map<String, List<Reason>> why = new HashMap<>();
        for (CareData.Need n : CareData.NEEDS) {
            score.put(n.id, BASICS.getOrDefault(n.id, 0.0));
            why.put(n.id, new ArrayList<>());
        }

        // Readings
        if (r.tempC >= feverFlagC - 0.15 && r.tempAgeH < 72) {   // a normal reading is no evidence at all
            double x = sigmoid((r.tempC - feverFlagC) / 0.2) * fade(r.tempAgeH, p.hlReading);
            if (x > 0.05) {
                add(score, why, "pain_fever", p.wFever * x, "temperature", String.format(Locale.US,
                        "Your temperature check %s read %.1f °C, warmer than usual.", CareLogic.ago(r.tempAgeH), r.tempC));
                add(score, why, "electrolytes", 0.6 * p.wFever * x, "temperature", String.format(Locale.US,
                        "A fever takes fluids: your temperature read %.1f °C.", r.tempC));
            }
        }
        if (r.bpm > 0 && r.bpmAgeH < 72) {
            double x = sigmoid((r.bpm - 100) / 5) * fade(r.bpmAgeH, p.hlReading);
            if (x > 0.05) add(score, why, "electrolytes", p.wPulse * x, "heart", String.format(Locale.US,
                    "Your pulse check %s read %.0f bpm. Heat, too little water and caffeine can all raise it.", CareLogic.ago(r.bpmAgeH), r.bpm));
        }
        if (r.steps > 7000) {
            double x = clamp((r.steps - 7000) / 6000.0);
            add(score, why, "first_aid", p.wSteps * x, "steps", String.format(Locale.US, "%,d steps today. Something for blisters.", r.steps));
            add(score, why, "electrolytes", 0.5 * p.wSteps * x, "steps", String.format(Locale.US, "%,d steps today. Something to drink.", r.steps));
        }
        if (r.breathPct > 0 && r.breathPct < 90 && r.breathAgeH < 72) {
            double x = clamp((90 - r.breathPct) / 30) * fade(r.breathAgeH, p.hlReading);
            add(score, why, "cold_cough", p.wBreath * x, "breath", String.format(Locale.US,
                    "Your breath strength was %.0f%% of your usual %s. Congestion can do that.", r.breathPct, CareLogic.ago(r.breathAgeH)));
        }
        if (r.screenMin >= 20 && r.blinkRate < 12) {
            double x = clamp((14 - r.blinkRate) / 8) * clamp(r.screenMin / 60);
            add(score, why, "eyes", p.wEyes * x, "eyes", String.format(Locale.US,
                    "You blinked about %.0f times a minute over %s of screen time today. Screens slow blinking, which dries eyes.",
                    r.blinkRate, r.screenMin >= 90 ? String.format(Locale.US, "%.1f hours", r.screenMin / 60) : Math.round(r.screenMin) + " minutes"));
        }

        // Words and behaviour
        for (Signal s : signals) {
            switch (s.kind) {
                case CHAT:
                    words(score, why, s, p.wChat * fade(s.ageH, p.hlChat), "chat", "You told Apollo “" + quote(s.text) + "”");
                    break;
                case APOLLO:
                    words(score, why, s, p.wApollo * fade(s.ageH, p.hlChat), "apollo", "Apollo said “" + quote(s.text) + "”");
                    break;
                case SEARCH:
                    words(score, why, s, p.wSearch * fade(s.ageH, p.hlSearch), "search", "You searched “" + quote(s.text) + "”");
                    break;
                case VIEW:
                    words(score, why, s, p.wView * fade(s.ageH, p.hlBrowse), "browse", "You looked at " + quote(s.text));
                    break;
                case DELIVER:
                    if (s.need != null) add(score, why, s.need, p.wDeliver * fade(s.ageH, p.hlBrowse), "browse",
                            "You looked into delivery for " + quote(s.text) + " " + CareLogic.ago(s.ageH));
                    break;
                case APOLLO_PICK:
                    if (s.need != null) add(score, why, s.need, p.wApolloPick * fade(s.ageH, p.hlChat), "apollo",
                            "Apollo pointed you here " + CareLogic.ago(s.ageH));
                    break;
                case OPEN:
                    if (s.need != null) add(score, why, s.need, p.wOpen * fade(s.ageH, p.hlBrowse), "browse",
                            "You browsed " + CareData.need(s.need).title.toLowerCase(Locale.US) + " " + CareLogic.ago(s.ageH));
                    break;
                case DISMISS:
                    if (s.need != null) score.merge(s.need, p.wDismiss * fade(s.ageH, p.hlDismiss), Double::sum);
                    break;
            }
        }

        List<Shelf> out = new ArrayList<>();
        for (CareData.Need n : CareData.NEEDS) {
            double sc = score.get(n.id);
            if (sc <= 0.01) continue;
            List<Reason> rs = why.get(n.id);
            rs.sort(Comparator.comparingDouble((Reason x) -> -x.weight));
            Shelf sh = new Shelf(n, sc, rs);
            sh.queries = queries(n, signals);
            out.add(sh);
        }
        out.sort(Comparator.comparingDouble((Shelf s) -> -s.score));
        return out.size() > k ? new ArrayList<>(out.subList(0, k)) : out;
    }

    private void words(Map<String, Double> score, Map<String, List<Reason>> why, Signal s, double w, String source, String text) {
        if (w < 1e-3 || s.text.isEmpty()) return;
        String agoText = " " + CareLogic.ago(s.ageH);
        for (Map.Entry<String, Double> e : needs(s.text).entrySet()) {
            if (e.getValue() < 0.05) continue;
            add(score, why, e.getKey(), w * e.getValue(), source, text + agoText);
        }
    }

    private static void add(Map<String, Double> score, Map<String, List<Reason>> why, String need, double w, String source, String text) {
        if (!score.containsKey(need)) return;
        score.merge(need, w, Double::sum);
        why.get(need).add(new Reason(source, text, w));
    }

    private static String quote(String s) {
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() > 42 ? t.substring(0, 40).trim() + "…" : t;
    }

    /**
     * A shelf's searches, personal first: the user's own recent searches for this need (they asked for that
     * brand), then the shelf's usual searches, the ones mentioned in chat or searches ahead. At most three.
     */
    List<String> queries(CareData.Need n, List<Signal> signals) {
        List<String> out = new ArrayList<>();
        for (Signal s : signals) {
            if (s.kind != Kind.SEARCH || s.ageH > 168 || out.size() >= 2) continue;
            String q = s.text.trim().toLowerCase(Locale.US);
            if (!q.isEmpty() && !out.contains(q) && n.id.equals(text.top(q))) out.add(q);
        }
        List<String> usual = new ArrayList<>(n.queries);
        usual.sort(Comparator.comparingInt((String q) -> -mentions(q, signals)));
        for (String q : usual) if (out.size() < 3 && !out.contains(q)) out.add(q);
        return out;
    }

    private static final Pattern CHILD = Pattern.compile("(?i)\\b(kids?'?|child|children'?s?|junior|infants?|toddlers?|baby|son|daughter)\\b");

    /** A product made for children ("DayQuil Kids'", "Children's Mucinex", "Junior's Claritin")? */
    static boolean forChildren(String productName) {
        return CHILD.matcher(productName).find();
    }

    /** Has the user talked or searched about a child this week? Then children's products needn't wait their turn. */
    static boolean aboutChildren(List<Signal> signals) {
        for (Signal s : signals) {
            if ((s.kind == Kind.CHAT || s.kind == Kind.SEARCH) && s.ageH < 168 && CHILD.matcher(s.text).find()) return true;
        }
        return false;
    }

    private static int mentions(String query, List<Signal> signals) {
        String brand = query.split(" ")[0];
        int n = 0;
        for (Signal s : signals) {
            if ((s.kind == Kind.CHAT || s.kind == Kind.SEARCH || s.kind == Kind.VIEW) && s.text.toLowerCase(Locale.US).contains(brand)) n++;
        }
        return n;
    }
}
