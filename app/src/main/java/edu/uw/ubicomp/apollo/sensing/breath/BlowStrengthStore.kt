package edu.uw.ubicomp.apollo.sensing.breath

import android.content.Context
import android.os.Build

/**
 * Past blow-strength results, on this phone only. A stand-in until the app has the design doc's Room database
 * and HealthRepository: swap the body of this class, keep its API.
 *
 * Values are kept per phone model, because absolute pascals depend on the phone's glass and barometer and
 * aren't comparable across models (tools/barotest/README.md).
 */
class BlowStrengthStore(context: Context) {
    data class Comparison(val todayPa: Double, val baselinePa: Double?, val sessionsBefore: Int) {
        /** Today as a percentage of the personal baseline, once one exists. */
        val percentOfBaseline: Int? get() = baselinePa?.let { Math.round(100 * todayPa / it).toInt() }
    }

    companion object {
        const val BASELINE_SESSIONS = 5   // baseline = median of the last 5 good sessions
        const val MIN_FOR_BASELINE = 3
        private const val KEEP = 50
    }

    private val prefs = context.getSharedPreferences("blow_strength", Context.MODE_PRIVATE)
    private val key = "sessions_" + Build.MODEL

    fun sessions(): List<Double> =
        prefs.getString(key, "").orEmpty().split(",").mapNotNull { it.toDoubleOrNull() }

    /** Baseline from previous sessions only, so today is compared with the past, not with itself. */
    fun baseline(previous: List<Double> = sessions()): Double? {
        if (previous.size < MIN_FOR_BASELINE) return null
        val recent = previous.takeLast(BASELINE_SESSIONS).sorted()
        val m = recent.size / 2
        return if (recent.size % 2 == 1) recent[m] else (recent[m - 1] + recent[m]) / 2
    }

    /** Records a good or OK session and returns how it compares with the baseline from before it. */
    fun record(strengthPa: Double): Comparison {
        val previous = sessions()
        val comparison = Comparison(strengthPa, baseline(previous), previous.size)
        val kept = (previous + strengthPa).takeLast(KEEP)
        prefs.edit().putString(key, kept.joinToString(",") { "%.2f".format(java.util.Locale.US, it) }).apply()
        return comparison
    }

    /** One line for the Today card: null when there is no result yet. */
    fun lastResultLabel(): String? {
        val all = sessions()
        val last = all.lastOrNull() ?: return null
        val base = baseline(all.dropLast(1)) ?: return "Building baseline (${all.size}/$MIN_FOR_BASELINE)"
        return "Last: ${Math.round(100 * last / base)}% of baseline"
    }
}
