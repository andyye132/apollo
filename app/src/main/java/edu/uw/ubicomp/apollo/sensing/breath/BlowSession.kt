package edu.uw.ubicomp.apollo.sensing.breath

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * One guided blow-strength test: the barometer at its fastest rate (25 Hz on a Pixel 5) for [DURATION_S],
 * with visual "blow now" cues, plus the accelerometer to check that the phone lies flat and still.
 * Cues are visual only: sound and vibration move the air or the glass, which the barometer would see.
 * Sensor callbacks arrive on the main thread; no permission is needed for either sensor.
 */
class BlowSession(context: Context) : SensorEventListener {
    enum class Phase { IDLE, RUNNING, DONE }

    data class Live(
        val phase: Phase = Phase.IDLE,
        val elapsedS: Double = 0.0,
        val deviationPa: Double = 0.0,
        val peakPa: Double = 0.0,
        val blowsSoFar: Int = 0,
        val cue: String = "",
        val flat: Boolean = false,
        val movedDuringTest: Boolean = false,
        val touchSeen: Boolean = false,
        val summary: BlowDetector.Summary? = null,
    )

    companion object {
        const val DURATION_S = 36.0
        val CUES_S = listOf(3.0, 12.0, 21.0, 30.0)   // four blows, 9 s apart
        const val CUE_LENGTH_S = 2.5
    }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val barometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val hasBarometer: Boolean get() = barometer != null

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val t = ArrayList<Double>()
    private val p = ArrayList<Double>()
    private var t0Ns = -1L
    private var aboveSinceS = -1.0
    private var lastAboveS = -10.0

    /** Watch the accelerometer so the screen can show "lay the phone flat" before the test starts. */
    fun monitor() {
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun start() {
        val baro = barometer ?: return
        t.clear()
        p.clear()
        t0Ns = -1L
        aboveSinceS = -1.0
        lastAboveS = -10.0
        _live.value = Live(phase = Phase.RUNNING, flat = _live.value.flat, cue = "Get ready")
        sensors.registerListener(this, baro, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun release() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            Sensor.TYPE_PRESSURE -> onPressure(e.timestamp, e.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun onAccel(x: Double, y: Double, z: Double) {
        // Screen up on a table: gravity almost entirely on +z.
        val flat = z > 9.3 && hypot(x, y) < 1.5
        val s = _live.value
        _live.value = s.copy(flat = flat, movedDuringTest = s.movedDuringTest || (s.phase == Phase.RUNNING && !flat))
    }

    private fun onPressure(tNs: Long, hpa: Double) {
        val s = _live.value
        if (s.phase != Phase.RUNNING) return
        if (t0Ns < 0) t0Ns = tNs
        val ts = (tNs - t0Ns) / 1e9
        t += ts
        p += hpa * 100

        val dev = if (ts < 1.0) 0.0 else p.last() - BlowDetector.trailingMedian(t, p, p.size - 1)
        var blows = s.blowsSoFar
        if (dev > BlowDetector.EVENT_PA) {
            if (ts - lastAboveS > BlowDetector.MERGE_S) aboveSinceS = ts
            if (aboveSinceS == ts) blows++
            lastAboveS = ts
        }
        val cue = CUES_S.indexOfFirst { ts >= it && ts < it + CUE_LENGTH_S }.let { i ->
            when {
                i >= 0 -> "Blow now! (${i + 1} of ${CUES_S.size})"
                ts < CUES_S.first() -> "Get ready"
                else -> "Breathe in and get ready"
            }
        }
        _live.value = s.copy(
            elapsedS = ts,
            deviationPa = dev,
            peakPa = maxOf(s.peakPa, dev),
            blowsSoFar = blows,
            cue = cue,
            touchSeen = s.touchSeen || dev > BlowDetector.TOUCH_PA,
        )
        if (ts >= DURATION_S) finish()
    }

    private fun finish() {
        barometer?.let { sensors.unregisterListener(this, it) }
        val summary = BlowDetector.analyze(t.toDoubleArray(), DoubleArray(p.size) { p[it] / 100 })
        _live.value = _live.value.copy(phase = Phase.DONE, cue = "", summary = summary)
    }
}
