package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Measures and tunes "Picked for you" (CareModel), on the Mac:
 *   javac -d /tmp/care proto-app/src/edu/uw/ubicomp/apolloproto/{CareData,CareLogic,CareCorpus,CareModel,SkinToCore}.java \
 *         proto-app/test/edu/uw/ubicomp/apolloproto/CareTune.java && java -cp /tmp/care edu.uw.ubicomp.apolloproto.CareTune [tune]
 *
 *  1. The need classifier: 5-fold cross-validation on CareCorpus for each smoothing value, then accuracy on
 *     HELD_OUT (phrases written apart from the corpus, then used to find its gaps) and on TEST (written before
 *     any fix and never used to change anything: the honest number).
 *  2. The ranking: 42 labelled scenarios (readings, chat, Apollo's replies, searches, browsing, hidden shelves),
 *     each with graded answers (2 = should lead, 1 = good to show). Scored by NDCG@3 and "right first" (hit@1),
 *     for the old fixed rules, the hand-set weights, and (with "tune") a seeded random search over all 20 knobs
 *     on two thirds of the scenarios, reported on the held-out third.
 * Without "tune" it checks that the shipped weights (CareModel.tuned()) still clear the bars below.
 */
public class CareTune {
    static final double FLAG = SkinToCore.FEVER_FLAG_C;

    /** Phrases the classifier never trained on: {need, phrase…}. */
    static final String[][] HELD_OUT = {
            {"pain_fever", "I'm burning up", "my temples are throbbing", "everything aches", "need some ibuprofen", "high temp since last night"},
            {"cold_cough", "hacking cough", "my nose is so stuffed up", "throat feels raw", "I think I'm coming down with something cold-ish", "nyquil for tonight"},
            {"allergy", "the pollen is killing me", "sneezy and itchy", "allergic to my friend's dog", "need an antihistamine", "zyrtec or claritin?"},
            {"stomach", "my tummy is upset", "feel like I might throw up", "bad indigestion tonight", "acid reflux again", "something I ate disagreed with me"},
            {"electrolytes", "I'm parched", "sweated through my shirt", "it's boiling outside", "need to rehydrate after soccer", "feeling dehydrated after the run"},
            {"vitamins", "should I take vitamin d", "my immune system needs help", "feeling run down this week", "looking for a multivitamin", "vitamin c packets"},
            {"sleep", "I lie awake for hours", "can't sleep before exams", "my sleep schedule is wrecked", "something to help me fall asleep", "insomnia is back"},
            {"first_aid", "got a blister hiking", "cut myself cooking", "scraped my hand on the sidewalk", "need some bandages", "small burn from the oven"},
            {"eyes", "my eyes are so dry from my monitor", "eyes ache after coding all day", "need eye drops", "my eyes sting after hours on my phone", "contacts are drying out"},
            {"none", "hey there", "thanks so much", "show my steps", "where's the closest urgent care", "I feel great today"},
    };

    /** Written after the first run and before any fix, never used to change the corpus: the honest test. */
    static final String[][] TEST = {
            {"pain_fever", "I've had a temperature all day", "my shoulder is killing me", "splitting headache", "feeling hot and shivery", "what should I take for cramps"},
            {"cold_cough", "I can't stop sniffling and coughing", "blocked sinuses", "my throat hurts when I swallow", "I've got the flu I think", "something for a chesty cough"},
            {"allergy", "hay fever season is here", "my eyes are itchy and I keep sneezing", "allergy pills", "cat dander makes me sneeze", "benadryl"},
            {"stomach", "my stomach is cramping", "I feel sick to my stomach", "bad case of diarrhea", "antacids please", "gas and bloating"},
            {"electrolytes", "I'm really dehydrated", "heat wave today", "drank too much last night", "electrolyte drink", "I sweat a ton at the gym"},
            {"vitamins", "low vitamin d levels", "I want to support my immunity", "a daily multivitamin", "zinc supplements", "always catching everything this winter"},
            {"sleep", "can't get to sleep at night", "I wake up at 4am every day", "need melatonin", "my sleep has been terrible", "jet lag from my flight"},
            {"first_aid", "I nicked my finger with a knife", "blister on my toe", "my elbow is scraped", "a bandage for a cut", "burned my finger on the pan"},
            {"eyes", "my eyes are tired from my screen", "dry eyes at work", "eye drops for dryness", "my eyes feel gritty", "screen time is hurting my eyes"},
            {"none", "good night apollo", "what's my temperature trend", "how do I calibrate", "you're helpful", "I'm feeling good"},
    };

    // ---- Scenarios ------------------------------------------------------------------------------

    static final class Sc {
        final String name;
        final CareModel.Readings r = new CareModel.Readings();
        final List<CareModel.Signal> s = new ArrayList<>();
        final Map<String, Integer> rel = new LinkedHashMap<>();

        Sc(String name) {
            this.name = name;
        }

        Sc chat(double h, String t) {
            s.add(new CareModel.Signal(CareModel.Kind.CHAT, h, t, null));
            return this;
        }

        Sc apollo(double h, String t) {
            s.add(new CareModel.Signal(CareModel.Kind.APOLLO, h, t, null));
            return this;
        }

        Sc pick(double h, String need) {
            s.add(new CareModel.Signal(CareModel.Kind.APOLLO_PICK, h, null, need));
            return this;
        }

        Sc search(double h, String q) {
            s.add(new CareModel.Signal(CareModel.Kind.SEARCH, h, q, null));
            return this;
        }

        Sc open(double h, String need) {
            s.add(new CareModel.Signal(CareModel.Kind.OPEN, h, null, need));
            return this;
        }

        Sc view(double h, String name) {
            s.add(new CareModel.Signal(CareModel.Kind.VIEW, h, name, null));
            return this;
        }

        Sc deliver(double h, String name, String need) {
            s.add(new CareModel.Signal(CareModel.Kind.DELIVER, h, name, need));
            return this;
        }

        Sc dismiss(double h, String need) {
            s.add(new CareModel.Signal(CareModel.Kind.DISMISS, h, null, need));
            return this;
        }

        Sc temp(double c, double h) {
            r.tempC = c;
            r.tempAgeH = h;
            return this;
        }

        Sc bpm(double b, double h) {
            r.bpm = b;
            r.bpmAgeH = h;
            return this;
        }

        Sc steps(long n) {
            r.steps = n;
            return this;
        }

        Sc breath(double pct, double h) {
            r.breathPct = pct;
            r.breathAgeH = h;
            return this;
        }

        Sc eyes(double blink, double minutes) {
            r.blinkRate = blink;
            r.screenMin = minutes;
            return this;
        }

        /** "need", grade, "need", grade… */
        Sc rel(Object... ng) {
            for (int i = 0; i < ng.length; i += 2) rel.put((String) ng[i], (Integer) ng[i + 1]);
            return this;
        }

        Sc basics() {
            return rel("pain_fever", 1, "first_aid", 1, "vitamins", 1);
        }
    }

    static List<Sc> scenarios() {
        List<Sc> l = new ArrayList<>();
        l.add(new Sc("cough in chat").chat(1, "I have a cough and a runny nose").rel("cold_cough", 2, "allergy", 1));
        l.add(new Sc("fever reading").temp(38.3, 2).rel("pain_fever", 2, "electrolytes", 1));
        l.add(new Sc("fever and a headache").temp(37.9, 3).chat(3, "my head is pounding").rel("pain_fever", 2, "electrolytes", 1));
        l.add(new Sc("searched zyrtec, sneezing").search(20, "zyrtec").chat(2, "sneezing all morning").rel("allergy", 2));
        l.add(new Sc("long walk, sore feet").steps(14500).chat(1, "my feet are sore from walking all day").rel("first_aid", 2, "electrolytes", 1));
        l.add(new Sc("fast pulse on a hot day").bpm(112, 1).chat(1, "it's really hot out").rel("electrolytes", 2));
        l.add(new Sc("waking at night").chat(5, "I keep waking up at night and can't get back to sleep").rel("sleep", 2));
        l.add(new Sc("upset stomach").chat(1, "my stomach is upset after dinner").rel("stomach", 2));
        l.add(new Sc("old cough, new stomach").chat(110, "bad cough").chat(2, "now my stomach feels awful").rel("stomach", 2, "cold_cough", 1));
        l.add(new Sc("no fever, stuffy nose").chat(1, "no fever, just a stuffy nose").rel("cold_cough", 2, "allergy", 1));
        l.add(new Sc("Apollo points to cold & cough").chat(1, "what helps a sore throat?")
                .apollo(1, "In Care, Cold & cough has options from nearby stores, with delivery links.").pick(1, "cold_cough").rel("cold_cough", 2));
        l.add(new Sc("low blink rate on screens").eyes(6, 150).rel("eyes", 2).basics());
        l.add(new Sc("dry eyes in chat").chat(2, "my eyes feel dry and tired from staring at my laptop").rel("eyes", 2));
        l.add(new Sc("nothing yet").basics());
        l.add(new Sc("normal readings").temp(36.8, 2).bpm(72, 2).steps(3000).basics());
        l.add(new Sc("fever shelf hidden").temp(37.6, 5).dismiss(1, "pain_fever").rel("electrolytes", 2, "first_aid", 1, "vitamins", 1));
        l.add(new Sc("keeps looking at vitamins").view(30, "Nature Made Vitamin D3 2000 IU").view(10, "Emergen-C Original Formula")
                .open(10, "vitamins").rel("vitamins", 2));
        l.add(new Sc("weak breath and coughing").breath(70, 4).chat(4, "my chest feels tight and I keep coughing").rel("cold_cough", 2));
        l.add(new Sc("weak breath alone").breath(68, 3).rel("cold_cough", 2, "pain_fever", 1, "first_aid", 1));
        l.add(new Sc("searched pedialyte").search(5, "pedialyte").rel("electrolytes", 2));
        l.add(new Sc("old advil search, can't sleep now").search(150, "advil").chat(1, "can't fall asleep").rel("sleep", 2, "pain_fever", 1));
        l.add(new Sc("Apollo says it may be a cold").apollo(2, "That sounds like it could be a cold. Rest and fluids help.")
                .rel("cold_cough", 2, "electrolytes", 1));
        l.add(new Sc("hungover").chat(1, "rough night, hungover and so thirsty").rel("electrolytes", 2, "pain_fever", 1, "stomach", 1));
        l.add(new Sc("burned hand").chat(1, "burned my hand on the stove").rel("first_aid", 2));
        l.add(new Sc("heartburn isn't the heart").chat(1, "heartburn after pizza").rel("stomach", 2));
        l.add(new Sc("itchy watery eyes, sneezing").chat(1, "itchy watery eyes and sneezing since spring").rel("allergy", 2, "eyes", 1));
        l.add(new Sc("fever, then looked at Tylenol delivery").temp(38.1, 6).deliver(5, "Tylenol Extra Strength", "pain_fever")
                .rel("pain_fever", 2, "electrolytes", 1));
        l.add(new Sc("hot day, many steps").steps(11000).chat(2, "hot day, lots of sweating").rel("electrolytes", 2, "first_aid", 1));
        l.add(new Sc("stressed").chat(1, "I feel stressed about exams").rel("sleep", 1).basics());
        l.add(new Sc("small talk").chat(1, "hello").chat(1, "thanks apollo").basics());
        l.add(new Sc("old fever, new sneezing").temp(38.5, 70).chat(1, "sneezing like crazy").rel("allergy", 2, "pain_fever", 1));
        l.add(new Sc("gaming eye strain").eyes(9, 45).chat(3, "eye strain from gaming").rel("eyes", 2));
        l.add(new Sc("fever and a sore throat").temp(38.0, 2).chat(2, "sore throat and congested").rel("pain_fever", 2, "cold_cough", 2, "electrolytes", 1));
        l.add(new Sc("eye shelf hidden").eyes(6, 120).dismiss(2, "eyes").basics());
        l.add(new Sc("searched melatonin").search(30, "melatonin gummies").rel("sleep", 2));
        l.add(new Sc("Apollo on a fast pulse").chat(1, "how's my heart rate").bpm(108, 1)
                .apollo(1, "Your latest check-in was 108 bpm, with a good signal. That's above the usual 60-100 resting range. "
                        + "Movement, caffeine, heat or stress can do that; try again after a few calm minutes.").rel("electrolytes", 2));
        l.add(new Sc("queasy").chat(3, "feeling queasy and nauseous").rel("stomach", 2));
        l.add(new Sc("kid's scraped knee").chat(1, "my kid scraped her knee").rel("first_aid", 2));
        l.add(new Sc("no sun in winter").chat(4, "I never see the sun in winter, always tired").rel("vitamins", 2, "sleep", 1));
        l.add(new Sc("searched nyquil, viewed DayQuil").search(10, "nyquil").view(9, "Vicks DayQuil Cold & Flu Liquid").rel("cold_cough", 2));
        l.add(new Sc("dry contacts").chat(1, "my contacts feel so dry").rel("eyes", 2));
        l.add(new Sc("not a fever, scratchy throat").temp(36.9, 1).chat(1, "I don't have a fever but my throat is scratchy").rel("cold_cough", 2));
        return l;
    }

    // ---- Scoring --------------------------------------------------------------------------------

    static double ndcg3(List<String> ranked, Map<String, Integer> rel) {
        double dcg = 0, ideal = 0;
        for (int i = 0; i < Math.min(3, ranked.size()); i++) dcg += (Math.pow(2, rel.getOrDefault(ranked.get(i), 0)) - 1) / log2(i + 2);
        List<Integer> best = new ArrayList<>(rel.values());
        best.sort((a, b) -> b - a);
        for (int i = 0; i < Math.min(3, best.size()); i++) ideal += (Math.pow(2, best.get(i)) - 1) / log2(i + 2);
        return ideal == 0 ? 1 : dcg / ideal;
    }

    static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    static boolean hit1(List<String> ranked, Map<String, Integer> rel) {
        int top = 0;
        for (int v : rel.values()) top = Math.max(top, v);
        return !ranked.isEmpty() && rel.getOrDefault(ranked.get(0), 0) == top;
    }

    static List<String> ids(List<CareModel.Shelf> shelves) {
        List<String> out = new ArrayList<>();
        for (CareModel.Shelf s : shelves) out.add(s.need.id);
        return out;
    }

    /** The rules "Picked for you" used before the model: readings only, fixed order, basics otherwise. */
    static List<String> oldRules(Sc sc) {
        List<String> out = new ArrayList<>();
        CareModel.Readings r = sc.r;
        if (r.tempC > 0 && r.tempAgeH < 48 && r.tempC >= FLAG) {
            out.add("pain_fever");
            out.add("electrolytes");
        }
        if (r.bpm >= 100 && r.bpmAgeH < 48 && !out.contains("electrolytes")) out.add("electrolytes");
        if (r.steps >= 8000) out.add("first_aid");
        if (out.isEmpty()) out.add("pain_fever");
        return out;
    }

    interface Ranker {
        List<String> rank(Sc sc);
    }

    static double[] score(List<Sc> set, Ranker f) {
        double n = 0, h = 0;
        for (Sc sc : set) {
            List<String> r = f.rank(sc);
            n += ndcg3(r, sc.rel);
            h += hit1(r, sc.rel) ? 1 : 0;
        }
        return new double[]{n / set.size(), h / set.size()};
    }

    static Ranker model(CareModel.Params p) {
        CareModel m = new CareModel(p);
        return sc -> ids(m.rank(sc.s, sc.r, FLAG, 3));
    }

    // ---- The classifier -------------------------------------------------------------------------

    static double cv(double alpha) {
        int right = 0, all = 0;
        for (int fold = 0; fold < 5; fold++) {
            String[][] train = new String[CareCorpus.PHRASES.length][];
            List<String[]> test = new ArrayList<>();
            for (int c = 0; c < CareCorpus.PHRASES.length; c++) {
                List<String> keep = new ArrayList<>();
                keep.add(CareCorpus.PHRASES[c][0]);
                for (int i = 1; i < CareCorpus.PHRASES[c].length; i++) {
                    if (i % 5 == fold) test.add(new String[]{CareCorpus.PHRASES[c][0], CareCorpus.PHRASES[c][i]});
                    else keep.add(CareCorpus.PHRASES[c][i]);
                }
                train[c] = keep.toArray(new String[0]);
            }
            CareModel.Text t = new CareModel.Text(train, alpha, 1);
            for (String[] x : test) {
                all++;
                if (t.top(x[1]).equals(x[0])) right++;
            }
        }
        return (double) right / all;
    }

    static double heldOut(CareModel.Text t, String[][] set, boolean print) {
        int right = 0, all = 0;
        for (String[] row : set) {
            for (int i = 1; i < row.length; i++) {
                all++;
                String got = t.top(row[i]);
                if (got.equals(row[0])) right++;
                else if (print) System.out.printf(Locale.US, "      missed: \"%s\" -> %s (want %s)%n", row[i], got, row[0]);
            }
        }
        return (double) right / all;
    }

    // ---- Main -----------------------------------------------------------------------------------

    public static void main(String[] args) {
        boolean tune = args.length > 0 && args[0].equals("tune");
        System.out.println("need classifier (Naive Bayes, " + CareCorpus.PHRASES.length + " classes)");
        double bestAlpha = 0.5, bestCv = -1;
        for (double a : new double[]{0.1, 0.25, 0.5, 1.0, 2.0}) {
            double acc = cv(a);
            System.out.printf(Locale.US, "  alpha %.2f: 5-fold accuracy %.1f%%%n", a, 100 * acc);
            if (acc > bestCv + 1e-9) {
                bestCv = acc;
                bestAlpha = a;
            }
        }
        CareModel.Params shipped = CareModel.tuned();
        CareModel.Text shippedText = new CareModel.Text(CareCorpus.PHRASES, shipped.alpha, 1);
        double dev = heldOut(shippedText, HELD_OUT, false), ho = heldOut(shippedText, TEST, true);
        System.out.printf(Locale.US, "  shipped alpha %.2f: dev phrases %.1f%% right (used to fix the corpus), fresh test phrases %.1f%% "
                + "(chance %.0f%%)%n", shipped.alpha, 100 * dev, 100 * ho, 100.0 / CareCorpus.PHRASES.length);

        List<Sc> all = scenarios(), train = new ArrayList<>(), test = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) (i % 3 == 2 ? test : train).add(all.get(i));
        System.out.printf(Locale.US, "%nranking: %d scenarios (%d to tune on, %d held out); NDCG@3 and right-first%n", all.size(), train.size(), test.size());
        report("old fixed rules", CareTune::oldRules, train, test);
        report("hand-set weights", model(new CareModel.Params()), train, test);
        report("shipped weights", model(shipped), train, test);

        if (tune) {
            // Nested 3-fold: does tuning beat the hand-set weights on scenarios it didn't see? Only then fit on all 42.
            double handSum = 0, tunedSum = 0;
            for (int f = 0; f < 3; f++) {
                List<Sc> in = new ArrayList<>(), out = new ArrayList<>();
                for (int i = 0; i < all.size(); i++) (i % 3 == f ? out : in).add(all.get(i));
                CareModel.Params p = search(in, bestAlpha);
                double h = score(out, model(new CareModel.Params()))[0], u = score(out, model(p))[0];
                handSum += h;
                tunedSum += u;
                System.out.printf(Locale.US, "  fold %d: hand-set %.3f, tuned on the other two thirds %.3f%n", f + 1, h, u);
            }
            System.out.printf(Locale.US, "  unseen-scenario average: hand-set %.3f, tuned %.3f -> %s%n", handSum / 3, tunedSum / 3,
                    tunedSum >= handSum ? "tuning helps: fit on all 42" : "tuning doesn't help: keep the hand-set weights");
            CareModel.Params best = tunedSum >= handSum ? search(all, bestAlpha) : new CareModel.Params();
            report("final", model(best), train, test);
            System.out.println("\n  " + best);
            Ranker hand = model(new CareModel.Params()), tuned = model(best);
            for (Sc sc : all) {   // where the final weights change an answer, and which way
                double h = ndcg3(hand.rank(sc), sc.rel), u = ndcg3(tuned.rank(sc), sc.rel);
                if (Math.abs(h - u) > 1e-6) System.out.printf(Locale.US, "    %-36s hand %.2f %s -> final %.2f %s%n",
                        sc.name, h, hand.rank(sc), u, tuned.rank(sc));
            }
            misses(tuned, all);
        } else {
            misses(model(shipped), all);
            double[] te = score(test, model(shipped));
            boolean ok = te[0] >= 0.85 && te[0] > score(test, CareTune::oldRules)[0] + 0.2 && ho >= 0.7 & behaviour(new CareModel(shipped));
            System.out.println(ok ? "\ncare model checks passed" : "\ncare model checks FAILED");
            if (!ok) System.exit(1);
        }
    }

    /** Specific promises, beyond the averages. */
    static boolean behaviour(CareModel m) {
        System.out.println("\nbehaviour");
        boolean ok = true;
        ok &= check(CareModel.Text.features("no fever, just a stuffy nose").contains("not_fever")
                && !CareModel.Text.features("no fever, just a stuffy nose").contains("not_stuff"), "negation stops at the clause break");
        Sc cold = new Sc("").apollo(1, "You have a cough and may have a cold.");
        ok &= check(m.rank(cold.s, cold.r, FLAG, 3).get(0).need.id.equals("cold_cough"), "Apollo saying it may be a cold leads with cold medicine");
        Sc zyr = new Sc("").search(3, "zyrtec").chat(2, "sneezing nonstop");
        CareModel.Shelf allergy = m.rank(zyr.s, zyr.r, FLAG, 3).get(0);
        ok &= check(allergy.need.id.equals("allergy") && allergy.queries.get(0).equals("zyrtec"), "a searched brand leads its shelf: " + allergy.queries);
        Sc hid = new Sc("").chat(1, "my stomach hurts").dismiss(0.5, "stomach");
        ok &= check(!ids(m.rank(hid.s, hid.r, FLAG, 3)).contains("stomach"), "a hidden shelf stays hidden");
        Sc fever = new Sc("").temp(38.3, 2);
        CareModel.Shelf f = m.rank(fever.s, fever.r, FLAG, 3).get(0);
        ok &= check(f.reasons.get(0).text.contains("38.3 °C") && f.sources(0.15).contains("temperature"), "a reading explains itself: " + f.reasons.get(0).text);
        Sc both = new Sc("").temp(38.0, 2).chat(2, "sore throat and congested");
        List<String> src = new ArrayList<>();
        for (CareModel.Shelf x : m.rank(both.s, both.r, FLAG, 3)) src.addAll(x.sources(0.15));
        ok &= check(src.contains("temperature") && src.contains("chat"), "readings and chat both show as reasons");
        ok &= check(CareModel.forChildren("DayQuil Kids' Syrup") && CareModel.forChildren("Mucinex Children's Cold Cough")
                && CareModel.forChildren("Junior's Claritin 12 Hour") && !CareModel.forChildren("Tylenol Extra Strength")
                && !CareModel.forChildren("Johnson & Johnson Band-Aid") && CareModel.forChildren("Johnson's Baby Shampoo"), "children's products recognised");
        ok &= check(CareModel.aboutChildren(new Sc("").chat(2, "my kid scraped her knee").s) && !CareModel.aboutChildren(new Sc("").chat(2, "my knee").s),
                "a chat about a child is noticed");
        Sc none = new Sc("").chat(1, "hello").chat(1, "how many steps today");
        for (CareModel.Shelf x : m.rank(none.s, none.r, FLAG, 3)) ok &= check(x.reasons.isEmpty() || x.reasons.get(0).weight < 0.2, "small talk adds nothing: " + x.need.id);
        return ok;
    }

    static boolean check(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        return ok;
    }

    static void report(String name, Ranker f, List<Sc> train, List<Sc> test) {
        double[] a = score(train, f), b = score(test, f);
        System.out.printf(Locale.US, "  %-18s tune set %.3f / %3.0f%%    held out %.3f / %3.0f%%%n", name, a[0], 100 * a[1], b[0], 100 * b[1]);
    }

    static void misses(Ranker f, List<Sc> all) {
        for (Sc sc : all) {
            List<String> r = f.rank(sc);
            double n = ndcg3(r, sc.rel);
            if (n < 0.8) System.out.printf(Locale.US, "    weak: %-36s %.2f  got %s  want %s%n", sc.name, n, r, sc.rel);
        }
    }

    /**
     * Seeded random search: 3,000 draws over all knobs, scored on the tuning scenarios. Scores within TIE count as
     * equal and go to the draw nearest the hand-set weights, so nothing moves without a reason: with only 28
     * scenarios to tune on, a looser tie (0.002 overfit: up on the tune set, down on the held-out one) keeps it honest.
     */
    static final double TIE = 0.01;

    static CareModel.Params search(List<Sc> train, double alpha) {
        Random rnd = new Random(20261018);
        CareModel.Params base = new CareModel.Params(), best = base.copy();
        best.alpha = alpha;
        double bestScore = score(train, model(best))[0], bestDist = 0;
        for (int i = 0; i < 3000; i++) {
            CareModel.Params p = base.copy();
            p.alpha = alpha;
            p.wChat = u(rnd, 0.3, 2);
            p.wApollo = u(rnd, 0, 1.2);
            p.wApolloPick = u(rnd, 0, 1.5);
            p.wSearch = u(rnd, 0.3, 2);
            p.wOpen = u(rnd, 0, 1);
            p.wView = u(rnd, 0, 1);
            p.wDeliver = u(rnd, 0, 1.5);
            p.wDismiss = u(rnd, -4, -0.5);
            p.hlChat = u(rnd, 6, 96);
            p.hlSearch = u(rnd, 24, 240);
            p.hlBrowse = u(rnd, 24, 336);
            p.hlDismiss = u(rnd, 24, 168);
            p.hlReading = u(rnd, 6, 72);
            p.wFever = u(rnd, 0.3, 2);
            p.wPulse = u(rnd, 0.3, 2);
            p.wSteps = u(rnd, 0.2, 1.5);
            p.wBreath = u(rnd, 0.2, 1.5);
            p.wEyes = u(rnd, 0.3, 2);
            p.temper = u(rnd, 1, 4);
            double s = score(train, model(p))[0], d = dist(p, base);
            if (s > bestScore + TIE || Math.abs(s - bestScore) <= TIE && d < bestDist) {
                if (s > bestScore + TIE) bestScore = s;
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    static double u(Random r, double lo, double hi) {
        return lo + r.nextDouble() * (hi - lo);
    }

    /** How far from the hand-set weights, each knob scaled by its range. */
    static double dist(CareModel.Params p, CareModel.Params b) {
        double[] x = vec(p), y = vec(b), span = {1.7, 1.2, 1.5, 1.7, 1, 1, 1.5, 3.5, 90, 216, 312, 144, 66, 1.7, 1.7, 1.3, 1.3, 1.7, 3};
        double d = 0;
        for (int i = 0; i < x.length; i++) d += Math.abs(x[i] - y[i]) / span[i];
        return d;
    }

    static double[] vec(CareModel.Params p) {
        return new double[]{p.wChat, p.wApollo, p.wApolloPick, p.wSearch, p.wOpen, p.wView, p.wDeliver, p.wDismiss, p.hlChat, p.hlSearch,
                p.hlBrowse, p.hlDismiss, p.hlReading, p.wFever, p.wPulse, p.wSteps, p.wBreath, p.wEyes, p.temper};
    }

    static String show(Map<String, Integer> m) {
        return Arrays.toString(m.entrySet().toArray());
    }
}
