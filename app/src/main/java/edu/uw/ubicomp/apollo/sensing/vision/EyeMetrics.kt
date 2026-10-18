package edu.uw.ubicomp.apollo.sensing.vision

import java.util.ArrayDeque
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns per-frame face landmarks into eye metrics: a gaze position, blinks, and "gaze shifts".
 * Pure Kotlin, no Android types, so it unit-tests the same way on recorded landmark CSVs — ported
 * line for line from prototypes/eyetrack/app/.../EyeMetrics.java (see docs/DESIGN.md, Vision: eye tracking).
 *
 * Gaze is the iris centre relative to the eye corners, in units of eye width: x runs 0 (left corner in the
 * image) to 1 (right corner); y is the offset from the corner-to-corner midline, positive downward. Both eyes
 * are averaged. This is a relative signal, not a screen position: there is no per-user calibration.
 *
 * A gaze shift is the gaze moving more than [shiftThreshold] within [shiftWindowMs] while the eyes are open.
 * At 30 fps a saccade (20-200 ms) spans only a few frames, so this counts shifts; it can't measure their speed.
 *
 * The timing constants are constructor parameters, not statics, because the README's passive mode runs at
 * 15 fps instead of the foreground prototype's 30 fps: "thresholds scale with frame rate... at 15 fps a frame
 * is 67 ms, so set SHIFT_WINDOW_MS >= 2 frames (about 150 ms)." The defaults match the foreground prototype.
 */
class EyeMetrics(
    private val blinkClose: Double = BLINK_CLOSE,
    private val blinkOpen: Double = BLINK_OPEN,
    private val shiftThreshold: Double = SHIFT_THRESHOLD,
    private val shiftWindowMs: Long = SHIFT_WINDOW_MS,
    private val shiftRefractoryMs: Long = SHIFT_REFRACTORY_MS,
    private val rateWindowMs: Long = RATE_WINDOW_MS,
    private val smoothing: Double = SMOOTHING,
) {
    companion object {
        // MediaPipe Face Landmarker indices (478-point mesh with irises)
        const val R_CORNER_A = 33
        const val R_CORNER_B = 133
        const val R_IRIS = 468
        const val L_CORNER_A = 362
        const val L_CORNER_B = 263
        const val L_IRIS = 473
        val EYE_POINTS = intArrayOf(33, 133, 159, 145, 160, 144, 158, 153, 362, 263, 386, 374, 387, 373, 385, 380)
        val IRIS_POINTS = intArrayOf(468, 469, 470, 471, 472, 473, 474, 475, 476, 477)

        // Starting values (30 fps foreground prototype); tune on recordings
        const val BLINK_CLOSE = 0.5
        const val BLINK_OPEN = 0.3
        const val SHIFT_THRESHOLD = 0.10 // eye widths
        const val SHIFT_WINDOW_MS = 100L
        const val SHIFT_REFRACTORY_MS = 150L
        const val RATE_WINDOW_MS = 60_000L
        const val SMOOTHING = 0.5 // EMA weight of the newest frame

        /** 15 fps passive-mode tuning per prototypes/eyetrack/README.md ("Next: passive tracking"). */
        fun passive() = EyeMetrics(shiftWindowMs = 150L)
    }

    var gazeX: Double = Double.NaN
        private set
    var gazeY: Double = Double.NaN
        private set
    var eyesClosed: Boolean = false
        private set
    var blinkCount: Int = 0
        private set
    var shiftCount: Int = 0
        private set
    var blinkEvent: Boolean = false
        private set
    var shiftEvent: Boolean = false
        private set

    private val history = ArrayDeque<DoubleArray>() // {t, x, y}
    private val blinkTimes = ArrayDeque<Long>()
    private val shiftTimes = ArrayDeque<Long>()
    private var firstT = -1L
    private var lastShiftT = Long.MIN_VALUE / 2

    /** xs/ys are landmark positions in pixels; blinkScore is the larger eyeBlink blendshape, or NaN. */
    fun update(t: Long, xs: FloatArray, ys: FloatArray, blinkScore: Double) {
        if (firstT < 0) firstT = t
        blinkEvent = false
        shiftEvent = false

        if (!blinkScore.isNaN()) {
            if (!eyesClosed && blinkScore > blinkClose) {
                eyesClosed = true
            } else if (eyesClosed && blinkScore < blinkOpen) {
                eyesClosed = false
                blinkCount++
                blinkEvent = true
                blinkTimes.add(t)
            }
        }
        if (eyesClosed) { // iris landmarks are guesses while the lids are shut
            history.clear()
            return
        }

        val r = eye(xs, ys, R_CORNER_A, R_CORNER_B, R_IRIS)
        val l = eye(xs, ys, L_CORNER_A, L_CORNER_B, L_IRIS)
        val x = (r[0] + l[0]) / 2
        val y = (r[1] + l[1]) / 2
        if (gazeX.isNaN()) {
            gazeX = x
            gazeY = y
        } else {
            gazeX += smoothing * (x - gazeX)
            gazeY += smoothing * (y - gazeY)
        }

        history.add(doubleArrayOf(t.toDouble(), gazeX, gazeY))
        while (history.size > 1 && t - history.peekFirst()[0] > shiftWindowMs) history.pollFirst()
        val old = history.peekFirst()
        if (old != null && t - old[0] >= shiftWindowMs / 2 &&
            hypot(gazeX - old[1], gazeY - old[2]) > shiftThreshold &&
            t - lastShiftT > shiftRefractoryMs
        ) {
            shiftCount++
            shiftEvent = true
            shiftTimes.add(t)
            lastShiftT = t
            history.clear()
            history.add(doubleArrayOf(t.toDouble(), gazeX, gazeY))
        }
    }

    /** No face this frame: drop the shift history so reacquiring a face doesn't count as a shift. */
    fun noFace() {
        blinkEvent = false
        shiftEvent = false
        history.clear()
    }

    fun blinksPerMinute(now: Long): Double = perMinute(blinkTimes, now)

    fun shiftsPerMinute(now: Long): Double = perMinute(shiftTimes, now)

    private fun perMinute(times: ArrayDeque<Long>, now: Long): Double {
        val it = times.iterator()
        while (it.hasNext()) {
            if (now - it.next() > rateWindowMs) it.remove() else break
        }
        if (firstT < 0) return 0.0
        val span = min(rateWindowMs, max(now - firstT, 1))
        return times.size * 60_000.0 / span
    }

    /** Iris position for one eye, in eye widths: {x along the corner line from the left corner, y below it}. */
    private fun eye(xs: FloatArray, ys: FloatArray, a: Int, b: Int, iris: Int): DoubleArray {
        val lo = if (xs[a] <= xs[b]) a else b
        val hi = if (lo == a) b else a
        val ex = (xs[hi] - xs[lo]).toDouble()
        val ey = (ys[hi] - ys[lo]).toDouble()
        val w = hypot(ex, ey)
        if (w < 1e-6) return doubleArrayOf(Double.NaN, Double.NaN)
        val ix = (xs[iris] - xs[lo]).toDouble()
        val iy = (ys[iris] - ys[lo]).toDouble()
        // project onto the corner line (x) and its perpendicular (y), so head roll doesn't leak into gaze
        return doubleArrayOf((ix * ex + iy * ey) / (w * w), (-ix * ey + iy * ex) / (w * w))
    }
}
