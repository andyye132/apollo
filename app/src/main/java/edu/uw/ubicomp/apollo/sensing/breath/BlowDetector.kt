package edu.uw.ubicomp.apollo.sensing.breath

import kotlin.math.sqrt

/**
 * Finds forced blows in a barometer recording and summarises a session. Pure Kotlin port of
 * tools/barotest/analyze_baro.py; see tools/barotest/README.md for the measurements behind every constant.
 *
 * A blow from 2–3 cm onto a phone lying flat flexes the screen of the sealed (IP68) phone and briefly raises
 * the pressure inside it. On a Pixel 5 (Bosch BMP380, 25 Hz, ~1.6 Pa noise) maximal blows gave 15–90 Pa,
 * repeatable within a person (±7%) but not comparable across people (mouth opening leaks in), so the result
 * is "blow strength against your own baseline", never peak flow.
 */
object BlowDetector {
    const val EVENT_PA = 6.0              // ~4x the noise
    const val MERGE_S = 0.5               // samples above threshold closer than this belong to one blow
    const val TOUCH_PA = 500.0            // a finger press gives ~1,200 Pa; no blow came near 500
    const val TOUCH_DURATION_S = 1.5      // blows last 0.2–1.2 s; a press lasts as long as the press
    const val BASELINE_HALF_WINDOW_S = 2.0
    const val GOOD_SPREAD_PCT = 15.0
    const val OK_SPREAD_PCT = 30.0

    data class Event(val tSec: Double, val peakPa: Double, val areaPaS: Double, val durationS: Double) {
        /** A touch or press on the phone, not a blow. */
        val isTouch: Boolean get() = peakPa > TOUCH_PA || durationS > TOUCH_DURATION_S
    }

    enum class Quality { GOOD, OK, INCONSISTENT, TOO_FEW }

    data class Summary(
        val events: List<Event>,
        /** Mean of the top three blow peaks, Pa; null if there were fewer than three blows. */
        val strengthPa: Double?,
        /** Spread (SD / mean) of the top three peaks, percent. */
        val spreadPct: Double?,
        val quality: Quality,
    ) {
        val blows: List<Event> get() = events.filterNot { it.isTouch }
        val touches: Int get() = events.count { it.isTouch }
    }

    /** [tSec] seconds since the start, [hpa] pressure readings. */
    fun analyze(tSec: DoubleArray, hpa: DoubleArray): Summary {
        require(tSec.size == hpa.size)
        val events = events(tSec, hpa)
        val peaks = events.filterNot { it.isTouch }.map { it.peakPa }.sortedDescending()
        if (peaks.size < 3) return Summary(events, null, null, Quality.TOO_FEW)
        // No absolute floor: a weak-lunged person's maximal blow can be small, and dropping it would bias things.
        val top = peaks.take(3)
        val mean = top.average()
        val sd = sqrt(top.sumOf { (it - mean) * (it - mean) } / top.size)
        val spread = 100 * sd / mean
        val quality = when {
            spread <= GOOD_SPREAD_PCT -> Quality.GOOD
            spread <= OK_SPREAD_PCT -> Quality.OK
            else -> Quality.INCONSISTENT
        }
        return Summary(events, mean, spread, quality)
    }

    fun events(tSec: DoubleArray, hpa: DoubleArray): List<Event> {
        val n = tSec.size
        if (n < 3) return emptyList()
        val pa = DoubleArray(n) { hpa[it] * 100 }
        val dev = DoubleArray(n) { pa[it] - centeredMedian(tSec, pa, it) }
        val dt = (tSec[n - 1] - tSec[0]) / (n - 1)

        val out = mutableListOf<Event>()
        var start = -1
        var last = -1
        fun close() {
            if (start < 0) return
            var k = start
            var area = 0.0
            for (i in start..last) {
                if (dev[i] > dev[k]) k = i
                if (dev[i] > 0) area += dev[i] * dt
            }
            out += Event(tSec[k], dev[k], area, tSec[last] - tSec[start] + dt)
            start = -1
        }
        for (i in 0 until n) {
            if (dev[i] <= EVENT_PA) continue
            if (start >= 0 && tSec[i] - tSec[last] > MERGE_S) close()
            if (start < 0) start = i
            last = i
        }
        close()
        return out
    }

    /** Median of the samples within ±BASELINE_HALF_WINDOW_S: slow drift drops out, a short blow doesn't move it. */
    private fun centeredMedian(t: DoubleArray, v: DoubleArray, i: Int): Double {
        var lo = i
        var hi = i
        while (lo > 0 && t[i] - t[lo - 1] <= BASELINE_HALF_WINDOW_S) lo--
        while (hi < t.size - 1 && t[hi + 1] - t[i] <= BASELINE_HALF_WINDOW_S) hi++
        return median(v.copyOfRange(lo, hi + 1))
    }

    /** Median of the last [windowS] seconds ending at index [i], for the live display (no future samples). */
    fun trailingMedian(t: List<Double>, v: List<Double>, i: Int, windowS: Double = 2 * BASELINE_HALF_WINDOW_S): Double {
        var lo = i
        while (lo > 0 && t[i] - t[lo - 1] <= windowS) lo--
        return median(DoubleArray(i - lo + 1) { v[lo + it] })
    }

    private fun median(a: DoubleArray): Double {
        a.sort()
        val m = a.size / 2
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2
    }
}
