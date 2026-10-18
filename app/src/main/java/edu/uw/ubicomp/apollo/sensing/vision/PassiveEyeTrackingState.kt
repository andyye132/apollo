package edu.uw.ubicomp.apollo.sensing.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PassiveStatus { STOPPED, RUNNING, PAUSED_CAMERA_IN_USE, PAUSED_SCREEN_OFF, ERROR }

/** One completed minute of passive tracking, written to the summary CSV (see prototypes/eyetrack/README.md). */
data class MinuteSummary(
    val minuteStartElapsedMs: Long,
    val blinkRatePerMin: Double,
    val shiftRatePerMin: Double,
    val secondsWithFace: Int,
    val secondsPaused: Int,
)

data class PassiveEyeTrackingUiState(
    val status: PassiveStatus = PassiveStatus.STOPPED,
    val errorMessage: String? = null,
    val lastMinute: MinuteSummary? = null,
    val sessionBaselineShiftsPerMin: Double? = null,
    /**
     * Exploratory only — nothing in Papers/ validates an eye-movement measure of stress or overwhelm
     * (see docs/DESIGN.md, "Vision: eye tracking"). This flags gaze-shift rate well above this session's
     * own recent average, nothing more; it is not a diagnosis.
     */
    val elevatedEyeMovement: Boolean = false,
)

/**
 * Bridges [EyeTrackingService] (which has no UI) to Compose. The real app would route this through
 * HealthRepository/Room; that data layer doesn't exist yet, so this in-memory StateFlow stands in for it.
 */
object PassiveEyeTrackingState {
    private val _state = MutableStateFlow(PassiveEyeTrackingUiState())
    val state: StateFlow<PassiveEyeTrackingUiState> = _state.asStateFlow()

    fun update(transform: (PassiveEyeTrackingUiState) -> PassiveEyeTrackingUiState) {
        _state.value = transform(_state.value)
    }
}
