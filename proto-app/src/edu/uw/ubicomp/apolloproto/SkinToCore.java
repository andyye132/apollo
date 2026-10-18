package edu.uw.ubicomp.apolloproto;

/**
 * Forehead skin temperature -> estimated oral (core) temperature, a linear model fitted by
 * tools/skin_to_core.py on the FDA facial/oral temperature dataset (Wang et al., PhysioNet 2023, CC0):
 * 1,020 people, IR-camera forehead centre, oral thermometer in monitor mode, room 20-29 °C.
 *
 *   oral = 23.495 + 0.4214 * skin - 0.0430 * room
 *
 * 5-fold cross-validated: mean error 0.31 °C (always guessing 36.94 gives 0.32), fever AUC 0.83. The
 * estimates bunch toward 37 like any regression's, so fever is flagged at an estimate of 37.24 °C, which
 * caught 70% of fevers with 10% false alarms in that data; the plain 37.5 cutoff would catch 33%.
 * The slope of 0.42 also shrinks errors in the skin reading: 0.3 °C off in skin is 0.13 °C in core.
 * Room temperature barely matters (2 °C off moves the estimate 0.09 °C), so the dataset's median is
 * the default.
 */
final class SkinToCore {
    static final double A = 23.495169, B_SKIN = 0.421421, C_ROOM = -0.042999;
    static final double FEVER_FLAG_C = 37.24, ROOM_DEFAULT_C = 24.0, TYPICAL_ERROR_C = 0.31;

    private SkinToCore() {
    }

    static double coreC(double skinC, double roomC) {
        return A + B_SKIN * skinC + C_ROOM * (Double.isNaN(roomC) ? ROOM_DEFAULT_C : roomC);
    }

    static double coreC(double skinC) {
        return coreC(skinC, ROOM_DEFAULT_C);
    }

    /**
     * The forehead skin temperature at which this model gives back `coreC`: lets a body-temperature
     * thermometer calibrate the phone, with the person's readings then centred on their thermometer.
     */
    static double skinFor(double coreC, double roomC) {
        return (coreC - A - C_ROOM * (Double.isNaN(roomC) ? ROOM_DEFAULT_C : roomC)) / B_SKIN;
    }

    static double skinFor(double coreC) {
        return skinFor(coreC, ROOM_DEFAULT_C);
    }

    static boolean possibleFever(double coreC) {
        return coreC >= FEVER_FLAG_C;
    }
}
