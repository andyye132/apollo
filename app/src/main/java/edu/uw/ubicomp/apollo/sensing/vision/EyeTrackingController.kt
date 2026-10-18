package edu.uw.ubicomp.apollo.sensing.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Front camera -> MediaPipe Face Landmarker (iris landmarks + blendshapes) -> [EyeMetrics].
 * Kotlin port of prototypes/eyetrack/.../MainActivity.java's camera + MediaPipe glue, minus the
 * debug overlay (this app shows live blink/gaze-shift numbers instead — see EyeTrackingScreen).
 */
class EyeTrackingController(private val context: Context) {

    data class State(
        val faceDetected: Boolean = false,
        val blinkCount: Int = 0,
        val shiftCount: Int = 0,
        val blinksPerMinute: Double = 0.0,
        val shiftsPerMinute: Double = 0.0,
        val fps: Double = 0.0,
        val error: String? = null,
    )

    private val metrics = EyeMetrics()
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastFrameT = -1L
    private var fps = 0.0

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView, onState: (State) -> Unit) {
        try {
            landmarker = FaceLandmarker.createFromOptions(
                context,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath("face_landmarker.task")
                            .setDelegate(Delegate.CPU)
                            .build(),
                    )
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setOutputFaceBlendshapes(true)
                    .setResultListener { result, input -> onResult(result, input, onState) }
                    .setErrorListener { e -> onState(State(error = "MediaPipe error: ${e.message}")) }
                    .build(),
            )
        } catch (e: RuntimeException) {
            onState(State(error = "Could not load the eye-tracking model. Run app/fetch_eyetrack_model.sh, then reinstall."))
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                onState(State(error = "Camera error: ${e.message}"))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        landmarker?.close()
        landmarker = null
        analysisExecutor.shutdown()
    }

    /** Camera thread: rotate upright and mirror (selfie view), then hand to MediaPipe. */
    private fun analyze(image: ImageProxy) {
        val raw = image.toBitmap()
        val m = Matrix()
        m.postRotate(image.imageInfo.rotationDegrees.toFloat())
        m.postScale(-1f, 1f)
        image.close()
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        landmarker?.detectAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
    }

    /** MediaPipe thread: one result per processed frame. */
    private fun onResult(result: FaceLandmarkerResult, input: MPImage, onState: (State) -> Unit) {
        val t = result.timestampMs()
        if (lastFrameT > 0) fps += 0.1 * (1000.0 / max(1L, t - lastFrameT) - fps)
        lastFrameT = t
        val w = input.width
        val h = input.height

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
        if (!face) metrics.noFace()

        val snapshot = State(
            faceDetected = face,
            blinkCount = metrics.blinkCount,
            shiftCount = metrics.shiftCount,
            blinksPerMinute = metrics.blinksPerMinute(t),
            shiftsPerMinute = metrics.shiftsPerMinute(t),
            fps = fps,
        )
        mainHandler.post { onState(snapshot) }
    }
}
