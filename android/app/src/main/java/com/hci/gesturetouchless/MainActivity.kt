package com.hci.gesturetouchless

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hci.gesturetouchless.databinding.ActivityMainBinding
import com.hci.gesturetouchless.services.GestureDetectionService

/**
 * UI entry point. Detection is owned exclusively by GestureDetectionService.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var detectionService: GestureDetectionService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? GestureDetectionService.LocalBinder ?: return
            detectionService = binder.service()
            serviceBound = true
            detectionService?.setPreviewSurfaceProvider(binding.previewView.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detectionService = null
            serviceBound = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateStatusUi()

        if (!allPermissionsGranted()) {
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        updateStatusUi()
        if (allPermissionsGranted()) {
            startGestureService()
            bindDetectionService()
        }
        if (!isAccessibilityEnabled()) {
            promptEnableAccessibilityService()
        }
    }

    override fun onStop() {
        detectionService?.setPreviewSurfaceProvider(null)
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
            detectionService = null
        }
        super.onStop()
    }

    private fun bindDetectionService() {
        if (serviceBound) return
        val intent = Intent(this, GestureDetectionService::class.java)
        serviceBound = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun setupUI() {
        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Enable 'Gesture Touchless Control' to perform actions",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateStatusUi() {
        binding.statusText.text = if (allPermissionsGranted()) {
            "✓ Gesture detection service active"
        } else {
            "Camera permission required"
        }
        binding.serviceStatusText.text = "Background service: active"
        binding.serviceStatusIndicator.setBackgroundColor(Color.parseColor("#4CAF50"))
        binding.handGestureText.text = "Hand: service detection"
        binding.handConfidenceText.text = "Confidence: —"
    }

    private fun startGestureService() {
        if (!allPermissionsGranted()) return
        val intent = Intent(this, GestureDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissions() {
        requestPermissions(REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && allPermissionsGranted()) {
            startGestureService()
            bindDetectionService()
            updateStatusUi()
        } else if (requestCode == PERMISSIONS_REQUEST_CODE) {
            Toast.makeText(
                this,
                "Camera permission is required for gesture detection",
                Toast.LENGTH_LONG
            ).show()
            updateStatusUi()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun promptEnableAccessibilityService() {
        AlertDialog.Builder(this)
            .setTitle("Enable Gesture Actions")
            .setMessage(
                "To perform gestures (volume/media/screenshot), enable this app in Accessibility settings."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
