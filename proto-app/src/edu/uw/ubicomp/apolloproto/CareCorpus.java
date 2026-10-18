package edu.uw.ubicomp.apolloproto;

/**
 * Labelled phrases that train the on-device need classifier (CareModel.Text): how people describe what's
 * wrong, what they search for, and how Apollo talks about it. "none" teaches it to stay quiet on small talk
 * and on questions about readings or places. Plain Java; CareTune measures the classifier on phrases it
 * never saw.
 */
final class CareCorpus {
    private CareCorpus() {
    }

    static final String[][] PHRASES = {
            {"pain_fever",
                    "I have a fever", "I feel feverish", "running a temperature", "I think I'm running hot", "my head hurts", "burning up", "throbbing head", "everything hurts", "aching all over", "high temp",
                    "I have a headache", "pounding headache", "migraine again", "my body aches", "my back hurts", "sore muscles",
                    "achy and hot", "I have chills", "chills and sweats", "cramps are bad today", "toothache", "my knee hurts",
                    "tylenol", "advil", "ibuprofen", "acetaminophen", "aleve", "motrin", "pain reliever", "fever reducer",
                    "something for the pain", "thermometer", "a bit above typical, rest and recheck", "fever care",
                    "pain and fever", "my temperature is high"},
            {"cold_cough",
                    "I have a cough", "coughing all night", "dry cough", "chesty cough", "I think I have a cold", "coming down with a cold", "feel a cold coming on",
                    "you may have a cold", "caught a cold", "stuffy nose", "my nose is blocked", "congested", "congestion",
                    "sore throat", "scratchy throat", "runny nose and cough", "flu symptoms", "I think it's the flu",
                    "phlegm", "mucus", "sinus pressure", "can't stop coughing", "dayquil", "nyquil", "mucinex", "robitussin",
                    "cough drops", "cough syrup", "cold medicine", "throat lozenges", "cold and cough", "chest congestion"},
            {"allergy",
                    "allergies", "my allergies are acting up", "hay fever", "I keep sneezing", "sneezing a lot",
                    "itchy eyes and sneezing", "watery eyes from pollen", "pollen is bad", "seasonal allergies", "allergic reaction",
                    "itchy skin rash", "hives", "cat allergy", "dust makes me sneeze", "runny nose from pollen", "claritin",
                    "zyrtec", "allegra", "benadryl", "antihistamine", "flonase", "allergy medicine", "allergy relief",
                    "itchy nose", "spring allergies", "my eyes itch outside"},
            {"stomach",
                    "my stomach hurts", "upset stomach", "stomach ache", "tummy ache", "I feel nauseous", "nausea", "might throw up", "feel sick to my stomach",
                    "I threw up", "vomiting", "diarrhea", "heartburn", "acid reflux", "indigestion", "bloated", "gassy",
                    "constipated", "ate something bad", "food poisoning", "stomach bug", "queasy", "pepto bismol", "tums",
                    "antacid", "imodium", "gas relief", "my stomach is upset after dinner", "stomach cramps"},
            {"electrolytes",
                    "I feel dehydrated", "dehydration", "I'm so thirsty", "really thirsty", "it's so hot today", "heat", "hungover", "parched", "boiling hot outside", "so hot outside",
                    "hot day", "sweating a lot", "lost a lot of fluids", "after a workout", "long run today", "hangover",
                    "lightheaded and thirsty", "dizzy from the heat", "need fluids", "electrolytes", "hydration",
                    "pedialyte", "liquid iv", "gatorade", "rehydrate", "sports drink", "drink more water", "dry mouth",
                    "fluids and electrolytes", "my pulse is high"},
            {"vitamins",
                    "vitamins", "vitamin d", "vitamin c", "multivitamin", "supplements", "immune support", "boost my immune system",
                    "I never see the sun", "low energy all winter", "always tired lately", "run down", "zinc", "emergen-c",
                    "airborne", "fish oil", "iron supplement", "b12", "gummies", "daily vitamin", "winter blues",
                    "staying healthy", "low on vitamin d", "nature made", "immunity"},
            {"sleep",
                    "I can't sleep", "can't fall asleep", "trouble sleeping", "insomnia", "up all night", "awake at 3am", "melatonin gummies",
                    "restless nights", "jet lag", "my sleep is bad", "poor sleep", "tossing and turning", "need to sleep better",
                    "wake up a lot at night", "melatonin", "zzzquil", "sleep aid", "sleep support", "help me sleep",
                    "night time", "bedtime routine", "too wired to sleep", "sleepless", "tired but can't sleep", "unisom"},
            {"first_aid",
                    "I cut my finger", "small cut", "scraped my knee", "a scrape", "blister on my heel", "blisters from walking",
                    "my feet hurt from walking", "new shoes rubbed", "burned my hand", "minor burn", "bug bite", "splinter",
                    "bleeding a little", "bandage", "band-aid", "bandaids", "first aid kit", "neosporin", "antibiotic ointment",
                    "gauze", "wound care", "skinned my elbow", "paper cut", "sore feet after a long walk"},
            {"eyes",
                    "my eyes are dry", "dry eyes", "tired eyes", "eye strain", "my eyes hurt from the screen", "screen all day",
                    "staring at my laptop", "eyes are burning", "red eyes", "gritty eyes", "contacts feel dry", "blurry and tired eyes",
                    "eye drops", "systane", "visine", "lubricating drops", "artificial tears", "too much screen time",
                    "computer vision", "my eyes feel heavy", "not blinking enough", "eyes sore after gaming"},
            {"none",
                    "hello", "hi there", "hey apollo", "thanks", "thank you so much", "good morning", "good evening",
                    "how many steps today", "how's my heart rate", "what's my heart rate", "show me my trends", "how am i doing",
                    "find urgent care nearby", "where is the nearest hospital", "call 911", "how does the temperature check work",
                    "how does this app work", "what can you do", "who are you", "tell me a joke", "ok", "cool",
                    "never mind", "what time is it", "calibrate", "take a reading", "I feel fine", "I'm doing great",
                    "all good today", "nothing's wrong", "I feel stressed", "I'm anxious", "breath strength test"},
    };
}
