package com.audioloop.audioloop

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager // Only MediaProjectionManager is needed here
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.audioloop.audioloop.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false // Tracks the service's LiveData state

    private lateinit var mediaProjectionManager: MediaProjectionManager // Correct

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allPermissionsGranted = true
            permissions.entries.forEach {
                if (!it.value) {
                    allPermissionsGranted = false
                }
            }

            if (allPermissionsGranted) {
                Log.d("MainActivity", "All permissions granted.")
                if (!isServiceRunning) {
                    toggleService() 
                }
            } else {
                Snackbar.make(
                    binding.root,
                    "Permissions are required. Please grant them in app settings.",
                    Snackbar.LENGTH_LONG
                ).setAction("Settings") {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                }.show()
            }
            updateUI()
        }

    // Launcher for MediaProjection permission
    private val mediaProjectionLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d("MainActivity", "MediaProjection permission result received. Result code: ${result.resultCode}")
            // THIS IS THE CORRECTED LOGIC:
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.i("MainActivity", "MediaProjection permission granted. Sending data to service.")
                val serviceIntent = Intent(this, AudioLoopService::class.java).apply {
                    action = AudioLoopService.ACTION_SETUP_PROJECTION
                    putExtra(AudioLoopService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(AudioLoopService.EXTRA_DATA_INTENT, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Log.w("MainActivity", "MediaProjection permission denied or cancelled.")
                Snackbar.make(binding.root, "App selection cancelled or denied.", Snackbar.LENGTH_SHORT).show()
            }
            updateUI() 
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupUIListeners()
        observeServiceStatus()
        updateUI() 
    }

    private fun setupUIListeners() {
        binding.buttonToggleService.setOnClickListener {
            if (!checkPermissions()) {
                Log.d("MainActivity", "Requesting audio/notification permissions for toggle service.")
                requestPermissionsLauncher.launch(requiredPermissions)
            } else {
                toggleService()
            }
        }

        binding.buttonSelectApp.setOnClickListener {
            Log.d("MainActivity", "Select App button clicked.")
            if (!isServiceRunning) {
                 Snackbar.make(binding.root, "Please start the loopback service first.", Snackbar.LENGTH_SHORT).show()
                 return@setOnClickListener
            }
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun toggleService() {
        val serviceIntent = Intent(this, AudioLoopService::class.java)
        if (isServiceRunning) {
            Log.d("MainActivity", "Attempting to stop service.")
            serviceIntent.action = AudioLoopService.ACTION_STOP_SERVICE
            startService(serviceIntent)
        } else {
            Log.d("MainActivity", "Attempting to start service.")
            serviceIntent.action = AudioLoopService.ACTION_START_SERVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun observeServiceStatus() {
        AudioLoopService.isRunning.observe(this) { running ->
            isServiceRunning = running
            Log.d("MainActivity", "Service running state updated via LiveData: $isServiceRunning")
            updateUI()
        }
    }

    private fun updateUI() {
        val projectionActive = AudioLoopService.isProjectionSetup() 
        Log.d("MainActivity", "Updating UI. Service running: $isServiceRunning, Projection Active in Service: $projectionActive")
        binding.buttonToggleService.text = if (isServiceRunning) "Stop Loopback" else "Start Loopback"

        var statusText = "Status: ${if (isServiceRunning) "Active" else "Inactive"}"
        if (isServiceRunning && projectionActive) {
            statusText += " (App Selected for Capture)"
        } else if (isServiceRunning && !projectionActive){
            statusText += " (App Selection Pending)"
        }
        binding.textViewStatus.text = statusText

        binding.buttonSelectApp.isEnabled = isServiceRunning
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onResume() {
        super.onResume()
        isServiceRunning = AudioLoopService.isRunning.value ?: false
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy called.")
    }
}

