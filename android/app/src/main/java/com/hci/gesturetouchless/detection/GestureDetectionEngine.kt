package com.hci.gesturetouchless.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.hci.gesturetouchless.ml.GestureClassifier
import com.hci.gesturetouchless.utils.LandmarkUtils
import com.hci.gesturetouchless.utils.PreferencesManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the complete camera -> MediaPipe -> landmark -> classifier pipeline.
 *
 * This class deliberately contains detection only. Gesture-to-action dispatch
 * remains the responsibility of the detection service for this phase.
 */
class GestureDetectionEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onGestureDetected: (gesture: String, confidence: Float) -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = PreferencesManager(appContext)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var handClassifier: GestureClassifier? = null
    private var handLandmarker: HandLandmarker? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var started = false
    private var destroyed = false

    /** Attach/detach the Activity's PreviewView surface without giving the Activity camera ownership. */
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        preview?.setSurfaceProvider(surfaceProvider)
    }

    fun start() {
        if (started || destroyed) return
        if (!hasCameraPermission()) {
            Log.w(TAG, "Camera permission is not granted")
            return
        }

        started = true
        handClassifier = GestureClassifier(appContext, "hand_model.tflite", "hand_labels.json")
        handClassifier?.resetHistory()
        initializeMediaPipe()
        startCamera()
    }

    private fun initializeMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                val firstHand = result.landmarks().firstOrNull() ?: return@setResultListener
                val flat = LandmarkUtils.flatten(firstHand)
                val rotated = LandmarkUtils.rotateAroundCenter(flat, -90)

                val (gesture, confidence) = handClassifier?.classifyWithSmoothing(rotated)
                    ?: return@setResultListener
                val threshold = prefs.getDetectionConfidence()

                if (gesture.isNotEmpty() && confidence >= threshold) {
                    onGestureDetected(gesture, confidence)
                }
            }
            .build()

        handLandmarker = try {
            HandLandmarker.createFromOptions(appContext, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe", e)
            null
        }
    }

    private fun startCamera() {
        if (handLandmarker == null) {
            Log.e(TAG, "Camera start skipped because MediaPipe is unavailable")
            stop()
            return
        }

        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            if (destroyed || !started) return@addListener

            try {
                cameraProvider = future.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to obtain camera provider", e)
                stop()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val cameraPreview = Preview.Builder().build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        try {
            provider.unbindAll()
            handClassifier?.resetHistory()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                cameraPreview,
                analysis
            )
            preview = cameraPreview
            imageAnalysis = analysis
            Log.d(TAG, "Detection camera pipeline started")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
            analysis.clearAnalyzer()
            stop()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        buffer.rewind()
        return Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        ).apply {
            copyPixelsFromBuffer(buffer)
        }
    }

    fun stop() {
        if (!started && handLandmarker == null && handClassifier == null) return
        started = false

        runCatching { imageAnalysis?.clearAnalyzer() }
        imageAnalysis = null
        runCatching { preview?.setSurfaceProvider(null) }
        preview = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        runCatching { handLandmarker?.close() }
        handLandmarker = null
        runCatching { handClassifier?.close() }
        handClassifier = null
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stop()
        cameraExecutor.shutdownNow()
    }

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "GestureDetectionEngine"
    }
}
