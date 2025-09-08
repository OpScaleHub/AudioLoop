package com.audioloop.audioloop

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.audioloop.audioloop.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
// import dagger.hilt.android.AndroidEntryPoint // Assuming Hilt is setup, keep if used

// @AndroidEntryPoint // Assuming Hilt is setup, keep if used
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isAudioLoopServiceRunning = false // Tracks AudioLoopService's LiveData state (processing or not)
    private var isFloatingControlsServiceActive = false // Tracks if FloatingControlsService is supposed to be active

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val requiredSdkPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // Launcher for standard permissions (RECORD_AUDIO, POST_NOTIFICATIONS)
    private val requestSdkPermissionsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                Log.d(TAG, "All SDK permissions granted.")
                // After SDK permissions, check for overlay permission to show controls
                checkAndRequestOverlayPermissionForControls()
            } else {
                Log.w(TAG, "Not all SDK permissions were granted.")
                Snackbar.make(binding.root, "Record Audio and Notification permissions are required.", Snackbar.LENGTH_LONG).show()
            }
            updateUI()
        }

    // Launcher for Overlay Permission
    private val requestOverlayPermissionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> // Result isn'''t directly used, we re-check Settings.canDrawOverlays
            if (Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted after returning from settings.")
                startFloatingControlsService()
            } else {
                Log.w(TAG, "Overlay permission was not granted after returning from settings.")
                Snackbar.make(binding.root, "Overlay permission is needed to show floating controls.", Snackbar.LENGTH_SHORT).show()
            }
            updateUI()
        }

    // Launcher for MediaProjection permission
    private val mediaProjectionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "MediaProjection permission result. Code: ${result.resultCode}")
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i(TAG, "MediaProjection obtained. Sending to AudioLoopService.")
                val serviceIntent = Intent(this, AudioLoopService::class.java).apply {
                    action = AudioLoopService.ACTION_SETUP_PROJECTION
                    putExtra(AudioLoopService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(AudioLoopService.EXTRA_DATA_INTENT, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied or cancelled.")
                Snackbar.make(binding.root, "Screen capture permission denied or cancelled.", Snackbar.LENGTH_SHORT).show()
                // If projection fails, ensure AudioLoopService state reflects it'''s not running processing.
                 val stopProcessingIntent = Intent(this, AudioLoopService::class.java).apply { 
                    action = AudioLoopService.ACTION_PROCESS_AUDIO_STOP 
                }
                ContextCompat.startForegroundService(this, stopProcessingIntent)
            }
            updateUI() // UI should reflect projection status via AudioLoopService.isProjectionSetup()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Start AudioLoopService in a "standby" mode to show notification and be ready for projection
        // This ensures the service is active for projection setup even if controls are not yet shown.
        val initialServiceIntent = Intent(this, AudioLoopService::class.java).apply {
            action = AudioLoopService.ACTION_START_SERVICE
        }
        ContextCompat.startForegroundService(this, initialServiceIntent)

        setupUIListeners()
        observeServiceStatus()
        updateUI()
    }

    private fun setupUIListeners() {
        // This button will now toggle the FloatingControlsService
        binding.buttonToggleService.setOnClickListener {
            if (isFloatingControlsServiceActive) {
                stopFloatingControlsService()
            } else {
                checkAndRequestSdkPermissionsForControls() // This will then chain to overlay permission
            }
        }

        // This button is for initiating screen capture selection
        binding.buttonSelectApp.setOnClickListener {
            if (!AudioLoopService.isProjectionSetup()) { // Or if you want to allow re-selection
                 Log.d(TAG, "Requesting MediaProjection.")
                 mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                // Optionally allow re-selection or inform user it'''s already set up
                 Snackbar.make(binding.root, "Screen capture already set up. Restart gadget to re-select.", Snackbar.LENGTH_LONG).show()
            }
        }
    }
    
    private fun checkAndRequestSdkPermissionsForControls() {
        val allSdkGranted = requiredSdkPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allSdkGranted) {
            Log.d(TAG, "SDK permissions already granted. Checking overlay for controls.")
            checkAndRequestOverlayPermissionForControls()
        } else {
            Log.d(TAG, "Requesting SDK permissions for controls.")
            requestSdkPermissionsLauncher.launch(requiredSdkPermissions)
        }
    }

    private fun checkAndRequestOverlayPermissionForControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission not granted. Requesting...")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        } else {
            Log.d(TAG, "Overlay permission already granted or not required. Starting floating controls.")
            startFloatingControlsService()
        }
    }

    private fun startFloatingControlsService() {
        if (!Settings.canDrawOverlays(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.w(TAG, "Attempted to start FloatingControlsService without overlay permission.")
            Snackbar.make(binding.root, "Overlay permission is required first.", Snackbar.LENGTH_LONG).show()
            return
        }
        Log.d(TAG, "Starting FloatingControlsService.")
        val intent = Intent(this, FloatingControlsService::class.java)
        startService(intent)
        isFloatingControlsServiceActive = true
        updateUI()
    }

    private fun stopFloatingControlsService() {
        Log.d(TAG, "Stopping FloatingControlsService.")
        val intent = Intent(this, FloatingControlsService::class.java)
        stopService(intent)
        isFloatingControlsServiceActive = false
        // Also stop audio processing if floating controls are hidden
        val stopProcessingIntent = Intent(this, AudioLoopService::class.java).apply { 
            action = AudioLoopService.ACTION_PROCESS_AUDIO_STOP 
        }
        ContextCompat.startForegroundService(this, stopProcessingIntent)
        updateUI()
    }

    private fun observeServiceStatus() {
        AudioLoopService.isRunning.observe(this) { running ->
            isAudioLoopServiceRunning = running
            Log.d(TAG, "AudioLoopService.isRunning state updated via LiveData: $isAudioLoopServiceRunning")
            updateUI()
        }
    }

    private fun updateUI() {
        val projectionReady = AudioLoopService.isProjectionSetup()
        Log.d(TAG, "Updating UI: AudioLoop Running: $isAudioLoopServiceRunning, Projection Ready: $projectionReady, Controls Active: $isFloatingControlsServiceActive")

        binding.buttonToggleService.text = if (isFloatingControlsServiceActive) "Hide Floating Controls" else "Show Floating Controls"
        
        var statusText = "Gadget: ${if (isFloatingControlsServiceActive) "Shown" else "Hidden"}\n"
        statusText += "Screen Capture: ${if (projectionReady) "Set" else "Not Set"}\n"
        statusText += "Audio Loop: ${if (isAudioLoopServiceRunning) "ACTIVE" else "INACTIVE"}"
        
        binding.textViewStatus.text = statusText
        binding.buttonSelectApp.isEnabled = !projectionReady // Enable if projection not yet set
    }

    override fun onResume() {
        super.onResume()
        // Reflect the current state accurately, esp. if returning from settings for overlay perm
        isAudioLoopServiceRunning = AudioLoopService.isRunning.value ?: false 
        // isFloatingControlsServiceActive // This is maintained by start/stopFloatingControlsService logic
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy.")
        // Option: if floating controls are active, maybe stop them to avoid leaks if not intended to persist beyond MainActivity.
        // However, typically a floating gadget is meant to persist, so user explicitly hides it.
        // If you want to ensure AudioLoopService fully stops when MainActivity is destroyed:
        // val serviceIntent = Intent(this, AudioLoopService::class.java)
        // serviceIntent.action = AudioLoopService.ACTION_STOP_SERVICE
        // ContextCompat.startForegroundService(this, serviceIntent)
        // stopFloatingControlsService() // also hide controls
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
