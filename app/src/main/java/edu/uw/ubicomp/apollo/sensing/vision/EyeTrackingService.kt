package edu.uw.ubicomp.apollo.sensing.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import edu.uw.ubicomp.apollo.MainActivity
import edu.uw.ubicomp.apollo.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Passive eye tracking as a camera foreground service, built from the spec in
 * prototypes/eyetrack/README.md ("Next: passive tracking as a camera foreground service").
 *
 * Must be started from a visible screen (Android 11+ camera while-in-use rule) and never restarts
 * itself after a reboot or being killed — that's expected, not a bug. No images are ever stored or
 * sent; only per-minute blink/shift-rate summaries, written locally.
 */
class EyeTrackingService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "edu.uw.ubicomp.apollo.eyetrack.ACTION_STOP"
        private const val CHANNEL_ID = "eye_tracking"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "EyeTrackingService"
        private const val MINUTE_MS = 60_000L

        /** How far above its own recent baseline the shift rate has to run before we call it "elevated". */
        private const val ELEVATED_MULTIPLIER = 1.5
        private const val MIN_BASELINE_MINUTES = 3
        private const val BASELINE_WINDOW_MINUTES = 10
    }

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val metrics = EyeMetrics.passive()

    private var started = false
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var summaryLog: PrintWriter? = null
    private lateinit var notificationManager: NotificationManager

    private var isPausedForCamera = false
    private var isPausedForScreen = false
    private var sawFaceSinceLastTick = false
    private var minuteStartMs = 0L
    private var secondsWithFaceThisMinute = 0
    private var secondsPausedThisMinute = 0
    private val recentShiftRates = ArrayDeque<Double>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            beginTracking()
        }
        return START_NOT_STICKY // a sticky restart would come from the background and fail; see class doc
    }

    private fun beginTracking() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText = "Tracking blinks and eye movement. Camera stays on this phone."),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
        )
        PassiveEyeTrackingState.update { PassiveEyeTrackingUiState(status = PassiveStatus.RUNNING) }
        minuteStartMs = SystemClock.elapsedRealtime()
        registerScreenReceiver()
        openSummaryLog()
        startCamera()
        startTicking()
    }

    private fun stopTracking() {
        PassiveEyeTrackingState.update { it.copy(status = PassiveStatus.STOPPED) }
        stopCamera()
        unregisterScreenReceiver()
        summaryLog?.close()
        summaryLog = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCamera()
        unregisterScreenReceiver()
        summaryLog?.close()
        landmarker?.close()
        landmarker = null
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Eye tracking", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, EyeTrackingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("Eye tracking")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_stat_eye, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    // ---------------------------------------------------------------- camera + MediaPipe

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        try {
            landmarker = FaceLandmarker.createFromOptions(
                this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build(),
                    )
                    // VIDEO mode + synchronous detectForVideo: "simpler than LIVE_STREAM at 15 fps" (README)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not load the eye-tracking model", e)
            PassiveEyeTrackingState.update {
                it.copy(status = PassiveStatus.ERROR, errorMessage = "Could not load the eye-tracking model.")
            }
            stopTracking()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(Surface.ROTATION_0) // a service has no display; rotate using imageInfo instead
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 15),
                )
                val analysis = builder.build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
                analysisUseCase = analysis

                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                observeCameraState()
            } catch (e: Exception) {
                Log.e(TAG, "Camera error", e)
                PassiveEyeTrackingState.update { it.copy(status = PassiveStatus.ERROR, errorMessage = "Camera error: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        analysisUseCase = null
    }

    private fun observeCameraState() {
        camera?.cameraInfo?.cameraState?.observe(this) { state ->
            val error = state.error
            when {
                error != null &&
                    (error.code == CameraState.ERROR_CAMERA_IN_USE || error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE) ->
                    pauseForCameraInUse()
                state.type == CameraState.Type.OPEN -> resumeFromCameraPause()
                else -> {}
            }
        }
    }

    /** Camera thread: rotate upright and mirror (selfie view), then run MediaPipe synchronously. */
    private fun analyze(image: ImageProxy) {
        if (isPausedForCamera || isPausedForScreen) {
            image.close()
            return
        }
        val raw = image.toBitmap()
        val m = Matrix()
        m.postRotate(image.imageInfo.rotationDegrees.toFloat())
        m.postScale(-1f, 1f)
        image.close()
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val mpImage = BitmapImageBuilder(upright).build()
        val timestampMs = SystemClock.uptimeMillis()
        val result = try {
            landmarker?.detectForVideo(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "detectForVideo failed", e)
            null
        } ?: return
        processResult(result, upright.width, upright.height, timestampMs)
    }

    private fun processResult(result: FaceLandmarkerResult, w: Int, h: Int, t: Long) {
        var face = false
        var blink = Double.NaN
        val faceLandmarks = result.faceLandmarks()
        if (faceLandmarks.isNotEmpty()) {
            val lm = faceLandmarks[0]
            if (lm.size > EyeMetrics.L_IRIS) {
                val xs = FloatArray(lm.size)
                val ys = FloatArray(lm.size)
                for (i in lm.indices) {
                    xs[i] = lm[i].x() * w
                    ys[i] = lm[i].y() * h
                }
                val blendshapes = result.faceBlendshapes()
                if (blendshapes.isPresent && blendshapes.get().isNotEmpty()) {
                    var left = 0.0
                    var right = 0.0
                    for (category in blendshapes.get()[0]) {
                        when (category.categoryName()) {
                            "eyeBlinkLeft" -> left = category.score().toDouble()
                            "eyeBlinkRight" -> right = category.score().toDouble()
                        }
                    }
                    blink = max(left, right)
                }
                face = true
                metrics.update(t, xs, ys, blink)
            }
        }
        if (!face) metrics.noFace() else sawFaceSinceLastTick = true
    }

    // ---------------------------------------------------------------- screen on/off

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> pauseForScreenOff()
                    Intent.ACTION_SCREEN_ON -> resumeFromScreenOff()
                }
            }
        }
        screenReceiver = receiver
        registerReceiver(receiver, filter)
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    private fun pauseForScreenOff() {
        if (isPausedForScreen) return
        isPausedForScreen = true
        analysisUseCase?.let { cameraProvider?.unbind(it) }
        PassiveEyeTrackingState.update { it.copy(status = PassiveStatus.PAUSED_SCREEN_OFF) }
        updateNotification("Paused: screen off")
    }

    private fun resumeFromScreenOff() {
        if (!isPausedForScreen) return
        isPausedForScreen = false
        val provider = cameraProvider
        val analysis = analysisUseCase
        if (provider != null && analysis != null && !isPausedForCamera) {
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            observeCameraState()
        }
        if (!isPausedForCamera) {
            PassiveEyeTrackingState.update { it.copy(status = PassiveStatus.RUNNING) }
            updateNotification("Tracking blinks and eye movement. Camera stays on this phone.")
        }
    }

    private fun pauseForCameraInUse() {
        if (isPausedForCamera) return
        isPausedForCamera = true
        PassiveEyeTrackingState.update { it.copy(status = PassiveStatus.PAUSED_CAMERA_IN_USE) }
        updateNotification("Paused: camera in use by another app")
    }

    private fun resumeFromCameraPause() {
        if (!isPausedForCamera) return
        isPausedForCamera = false
        if (!isPausedForScreen) {
            PassiveEyeTrackingState.update { it.copy(status = PassiveStatus.RUNNING) }
            updateNotification("Tracking blinks and eye movement. Camera stays on this phone.")
        }
    }

    // ---------------------------------------------------------------- per-minute summary + CSV

    private fun startTicking() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (isPausedForCamera || isPausedForScreen) {
            secondsPausedThisMinute++
        } else if (sawFaceSinceLastTick) {
            secondsWithFaceThisMinute++
        }
        sawFaceSinceLastTick = false
        if (now - minuteStartMs >= MINUTE_MS) finalizeMinute(now)
    }

    private fun finalizeMinute(now: Long) {
        val blinkRate = metrics.blinksPerMinute(now)
        val shiftRate = metrics.shiftsPerMinute(now)
        val summary = MinuteSummary(minuteStartMs, blinkRate, shiftRate, secondsWithFaceThisMinute, secondsPausedThisMinute)
        writeSummaryRow(summary)

        val baseline = if (recentShiftRates.size >= MIN_BASELINE_MINUTES) recentShiftRates.average() else null
        val elevated = baseline != null && shiftRate > baseline * ELEVATED_MULTIPLIER
        recentShiftRates.addLast(shiftRate)
        while (recentShiftRates.size > BASELINE_WINDOW_MINUTES) recentShiftRates.removeFirst()

        PassiveEyeTrackingState.update {
            it.copy(lastMinute = summary, sessionBaselineShiftsPerMin = baseline, elevatedEyeMovement = elevated)
        }

        minuteStartMs = now
        secondsWithFaceThisMinute = 0
        secondsPausedThisMinute = 0
    }

    private fun openSummaryLog() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "eyetrack_passive_${System.currentTimeMillis()}.csv")
            val writer = PrintWriter(FileWriter(file))
            writer.println("minute_start_elapsed_ms,blink_rate_per_min,shift_rate_per_min,seconds_with_face,seconds_paused")
            summaryLog = writer
        } catch (e: Exception) {
            Log.e(TAG, "Could not open summary log", e)
        }
    }

    private fun writeSummaryRow(s: MinuteSummary) {
        summaryLog?.println(
            String.format(
                Locale.US, "%d,%.1f,%.1f,%d,%d",
                s.minuteStartElapsedMs, s.blinkRatePerMin, s.shiftRatePerMin, s.secondsWithFace, s.secondsPaused,
            ),
        )
        summaryLog?.flush()
    }
}
