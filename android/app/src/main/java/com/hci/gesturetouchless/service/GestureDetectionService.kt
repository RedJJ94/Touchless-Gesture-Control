package com.hci.gesturetouchless.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.hci.gesturetouchless.R
import com.hci.gesturetouchless.detection.GestureDetectionEngine
import com.hci.gesturetouchless.models.GestureAction
import com.hci.gesturetouchless.models.GestureMapping

/**
 * Owns the application-wide gesture detection session.
 *
 * Camera and MediaPipe resources live in GestureDetectionEngine so there is
 * only one detection pipeline in the app.
 */
class GestureDetectionService : LifecycleService() {

    private var detectionEngine: GestureDetectionEngine? = null
    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun service(): GestureDetectionService = this@GestureDetectionService
    }

    // Preserve the Phase 1 trigger behavior. Gesture state/repeat semantics
    // will be redesigned separately in Phase 3.
    private var lastDetectedGesture: String? = null
    private var lastActionTime: Long = 0L
    private val actionCooldownMs = 5_000L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForegroundService()

        detectionEngine = GestureDetectionEngine(
            context = this,
            lifecycleOwner = this,
            onGestureDetected = ::handleGesture
        ).also { it.start() }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setPreviewSurfaceProvider(surfaceProvider: androidx.camera.core.Preview.SurfaceProvider?) {
        detectionEngine?.setPreviewSurfaceProvider(surfaceProvider)
    }

    private fun handleGesture(gesture: String, confidence: Float) {
        val now = System.currentTimeMillis()
        val isNewGesture = gesture != lastDetectedGesture
        val isCooldownExpired = (now - lastActionTime) >= actionCooldownMs
        if (!isNewGesture && !isCooldownExpired) return

        val action = GestureMapping.getAction(gesture)
        if (action == GestureAction.NONE) return

        Log.d(
            TAG,
            "Gesture: $gesture (${(confidence * 100).toInt()}%) → ${action.name}"
        )

        val intent = Intent(ACTION_PERFORM_GESTURE).apply {
            putExtra(EXTRA_GESTURE_ACTION, action.name)
            putExtra(EXTRA_GESTURE_NAME, gesture)
            putExtra(EXTRA_CONFIDENCE, confidence)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        lastDetectedGesture = gesture
        lastActionTime = now
    }

    private fun startAsForegroundService() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gesture Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Runs gesture detection in background"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gesture Control Active")
            .setContentText("Detecting hand gestures")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        detectionEngine?.destroy()
        detectionEngine = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GestureDetectionService"
        private const val CHANNEL_ID = "GestureDetectionChannel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PERFORM_GESTURE = "com.hci.gesturetouchless.PERFORM_GESTURE"
        const val EXTRA_GESTURE_ACTION = "gesture_action"
        const val EXTRA_GESTURE_NAME = "gesture_name"
        const val EXTRA_CONFIDENCE = "confidence"
    }
}
