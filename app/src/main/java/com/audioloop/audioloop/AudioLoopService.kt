package com.audioloop.audioloop

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

// Assuming you'll add Hilt annotations if this service needs injected dependencies
// For now, keeping it as a standard Service.
class AudioLoopService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var notificationManager: NotificationManager? = null

    // Placeholder for actual audio capture logic
    private fun startLoopbackAudio() {
        Log.d(TAG, "Loopback audio (supposedly) started.")
        // TODO: Implement actual audio capture using currentMediaProjection
        // This is where you would create AudioRecord with AudioPlaybackCaptureConfiguration
        if (currentMediaProjection == null) {
            Log.w(TAG, "Attempted to start loopback audio, but MediaProjection is not available.")
            // Consider stopping the service or a part of it if projection is essential
            return
        }
        // Example:
        // val config = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
        //   .addMatchingUsage(AudioAttributes.USAGE_MEDIA) // Capture media like music, games
        //   .build()
        // val audioFormat = AudioFormat.Builder()...build()
        // audioRecord = AudioRecord.Builder()
        //   .setAudioFormat(audioFormat)
        //   .setAudioPlaybackCaptureConfig(config)
        //   .build()
        // audioRecord.startRecording()
        // Start a thread to read from audioRecord
    }

    private fun stopLoopbackAudio() {
        Log.d(TAG, "Loopback audio (supposedly) stopped.")
        // TODO: Stop AudioRecord and release resources
        // audioRecord?.stop()
        // audioRecord?.release()
        // audioRecord = null
    }

    private fun startForegroundNotification() {
        val channelId = "AudioLoopServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Audio Loop Service",
                NotificationManager.IMPORTANCE_LOW // Use LOW or MIN to avoid sound/vibration if not critical
            )
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AudioLoop Service Active")
            .setContentText(if (isProjectionSetup()) "Capturing selected app" else "Ready to select app")
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
            .setOngoing(true) // Makes the notification non-dismissable
            .build()
        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
            Log.d(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
             // This can happen if the service is started from background without proper permissions on Android 12+ for foreground service launch
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        _isRunning.postValue(false) // Initial state
        clearProjection() // Ensure projection is clear on service creation
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")

        when (action) {
            ACTION_START_SERVICE -> {
                _isRunning.postValue(true)
                clearProjection() // Clear any existing projection when explicitly starting
                startForegroundNotification()
                startLoopbackAudio() // Or prepare for it
            }
            ACTION_STOP_SERVICE -> {
                _isRunning.postValue(false)
                stopLoopbackAudio()
                clearProjection()
                stopForeground(true)
                stopSelf()
            }
            ACTION_SETUP_PROJECTION -> {
                if (!_isRunning.value!!) {
                     Log.w(TAG, "ACTION_SETUP_PROJECTION received but service is not running. Starting service first.")
                    _isRunning.postValue(true)
                    startForegroundNotification() // Ensure service is foreground before getMediaProjection
                } else {
                    // Refresh notification if already running to update text potentially
                    startForegroundNotification()
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.i(TAG, "MediaProjection obtained from MainActivity's result and stored.")
                            // Potentially start audio capture now if it wasn't started or needs reconfiguring
                            // startLoopbackAudio() // if it depends on having currentMediaProjection
                        } else {
                             Log.e(TAG, "getMediaProjection returned null despite RESULT_OK.")
                             clearProjection()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException when trying to get MediaProjection. This should not happen if service is in foreground.", e)
                        clearProjection()
                        // This indicates a deeper issue, perhaps service wasn't truly foreground
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception obtaining MediaProjection", e)
                        clearProjection()
                    }
                } else {
                    Log.w(TAG, "ACTION_SETUP_PROJECTION: Failed to get valid data from intent. ResultCode: $resultCode")
                    clearProjection()
                }
                // Update notification text after attempting projection setup
                startForegroundNotification()
                // Update LiveData for UI
                 _isRunning.postValue(_isRunning.value) // Force re-notify to update UI
            }
        }
        return START_NOT_STICKY
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection session stopped (onStop callback).")
            clearProjection()
            // Potentially stop audio capture or the service itself if projection is critical
            // stopLoopbackAudio()
             _isRunning.postValue(_isRunning.value) // Update UI
            startForegroundNotification() // Update notification text
            // if (/* projection is essential */) { stopSelf(); }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopLoopbackAudio()
        clearProjection()
        _isRunning.postValue(false)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    companion object {
        private const val TAG = "AudioLoopService"
        private const val SERVICE_NOTIFICATION_ID = 12345

        const val ACTION_START_SERVICE = "com.audioloop.audioloop.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.audioloop.audioloop.ACTION_STOP_SERVICE"
        const val ACTION_SETUP_PROJECTION = "com.audioloop.audioloop.ACTION_SETUP_PROJECTION"

        const val EXTRA_RESULT_CODE = "com.audioloop.audioloop.EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "com.audioloop.audioloop.EXTRA_DATA_INTENT"

        private var currentMediaProjection: MediaProjection? = null
        private val _isRunning = MutableLiveData<Boolean>()
        val isRunning: LiveData<Boolean> = _isRunning

        fun isProjectionSetup(): Boolean {
            return currentMediaProjection != null
        }

        fun clearProjection() {
            Log.d(TAG, "Clearing MediaProjection.")
            currentMediaProjection?.stop()
            currentMediaProjection = null
        }
    }
}

