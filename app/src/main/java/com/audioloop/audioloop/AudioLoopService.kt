package com.audioloop.audioloop

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack // Added
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class AudioLoopService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var notificationManager: NotificationManager? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null // Added for playback
    private var audioCaptureThread: Thread? = null
    @Volatile private var isCapturingAudio: Boolean = false

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO // Added for AudioTrack
    private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startLoopbackAudio() {
        Log.d(TAG, "Attempting to start loopback audio capture and playback...")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Cannot start audio capture.")
            return
        }
        if (currentMediaProjection == null) {
            Log.e(TAG, "MediaProjection is not available. Cannot start audio capture.")
            return
        }
        if (isCapturingAudio) {
            Log.w(TAG, "Audio capture/playback is already in progress.")
            return
        }

        // Configure AudioRecord
        val recordConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val recordAudioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT_ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG_IN)
            .build()
        val recordMinBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING)
        if (recordMinBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioRecord getMinBufferSize.")
            return
        }
        val recordBufferSizeInBytes = recordMinBufferSize * 2

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(recordAudioFormat)
                .setAudioPlaybackCaptureConfig(recordConfig)
                .setBufferSizeInBytes(recordBufferSizeInBytes)
                .build()
        } catch (e: Exception) { // Catch broader exceptions for initialization
            Log.e(TAG, "Exception creating AudioRecord: ${e.message}", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.")
            audioRecord?.release()
            audioRecord = null
            return
        }
        Log.d(TAG, "AudioRecord initialized. Buffer size: $recordBufferSizeInBytes bytes")

        // Configure AudioTrack
        val trackMinBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING)
        if (trackMinBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioTrack getMinBufferSize.")
            audioRecord?.release() // Release already acquired record
            audioRecord = null
            return
        }
        val trackBufferSizeInBytes = trackMinBufferSize * 2

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT_ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating AudioTrack: ${e.message}", e)
            audioRecord?.release()
            audioRecord = null
            return
        }

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize.")
            audioRecord?.release()
            audioRecord = null
            audioTrack?.release()
            audioTrack = null
            return
        }
        Log.d(TAG, "AudioTrack initialized. Buffer size: $trackBufferSizeInBytes bytes")

        isCapturingAudio = true // Signal that processing can start
        audioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "AudioRecord and AudioTrack started.")

        audioCaptureThread = Thread {
            // Use the larger of the two buffer sizes for our processing buffer,
            // or stick to record buffer if that's what's driving the read pace.
            val audioBuffer = ByteArray(recordBufferSizeInBytes)
            Log.d(TAG, "Audio capture thread started with buffer size: ${audioBuffer.size}")

            while (isCapturingAudio &&
                   audioRecord != null && audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                   audioTrack != null && audioTrack!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead != null && bytesRead > 0) {
                    audioTrack?.write(audioBuffer, 0, bytesRead)
                } else if (bytesRead != null && bytesRead < 0) {
                    Log.e(TAG, "Error reading audio data from AudioRecord: $bytesRead")
                    // Potentially stop if errors persist, or implement error handling
                }
            }
            Log.d(TAG, "Audio capture/playback thread finished.")
        }
        audioCaptureThread?.name = "AudioPlaybackThread"
        audioCaptureThread?.start()
    }

    private fun stopLoopbackAudio() {
        Log.d(TAG, "Stopping loopback audio capture and playback...")
        if (!isCapturingAudio && audioRecord == null && audioTrack == null) {
            Log.d(TAG, "Audio capture/playback not running or already stopped.")
            return
        }

        isCapturingAudio = false // Signal thread to stop

        if (audioCaptureThread?.isAlive == true) {
            try {
                audioCaptureThread?.join(500) // Wait for thread to finish
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while joining audio capture thread", e)
                Thread.currentThread().interrupt()
            }
        }
        audioCaptureThread = null

        // Stop and release AudioRecord
        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                audioRecord?.stop()
                Log.d(TAG, "AudioRecord stopped.")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException stopping AudioRecord: ${e.message}", e)
            }
        }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "AudioRecord released.")

        // Stop and release AudioTrack
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                audioTrack?.pause() // Pause before stop can be good practice
                audioTrack?.flush() // Flush any pending data
                audioTrack?.stop()
                Log.d(TAG, "AudioTrack stopped.")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException stopping AudioTrack: ${e.message}", e)
            }
        }
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "AudioTrack released.")
    }

    private fun startForegroundNotification() {
        val channelId = "AudioLoopServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Audio Loop Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AudioLoop Service Active")
            .setContentText(if (isProjectionSetup()) "Capturing selected app" else "Ready to select app")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
            Log.d(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

     private fun clearProjectionAndAudioInstance() {
        Log.d(TAG, "Clearing MediaProjection and stopping audio instance.")
        stopLoopbackAudio() // This now stops both record and track
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop()
        currentMediaProjection = null
        _isRunning.postValue(isProjectionSetup())
        startForegroundNotification()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        _isRunning.postValue(false)
        // clearProjectionAndAudioInstance() // Called from onStartCommand or explicitly if needed
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")

        when (action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "ACTION_START_SERVICE received. Cleaning up any existing session.")
                stopLoopbackAudio() 
                if (currentMediaProjection != null) {
                    Log.d(TAG, "Clearing existing MediaProjection.")
                    currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                    currentMediaProjection?.stop()
                    currentMediaProjection = null
                }
                _isRunning.postValue(true) 
                startForegroundNotification() 
            }
            ACTION_STOP_SERVICE -> {
                _isRunning.postValue(false)
                clearProjectionAndAudioInstance()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.d(TAG,"Service stopped and self-terminated.")
            }
            ACTION_SETUP_PROJECTION -> {
                if (_isRunning.value != true) {
                     Log.w(TAG, "ACTION_SETUP_PROJECTION received but service is not marked as running. Starting service first implicitly.")
                    _isRunning.postValue(true) // Ensure service is marked as running
                     // Important: The service should already be in foreground from ACTION_START_SERVICE
                     // If not, mediaProjectionManager.getMediaProjection might fail on Android 10+
                }
                startForegroundNotification() // Update notification (e.g. if service was implicitly started)

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
                            // It might be good to clear any old projection/audio,
                            // though ACTION_START_SERVICE should handle this.
                            // clearProjectionAndAudioInstance() // This was too aggressive here.
                            
                            currentMediaProjection = projection 
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.i(TAG, "MediaProjection obtained and stored.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLoopbackAudio() // Now attempts to start both record and playback
                            } else {
                                Log.w(TAG, "AudioPlaybackCaptureConfiguration requires Android Q (API 29)+")
                                clearProjectionAndAudioInstance() // Clean up if feature not supported
                            }
                        } else {
                             Log.e(TAG, "getMediaProjection returned null despite RESULT_OK.")
                             clearProjectionAndAudioInstance()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException getting MediaProjection. Is service in foreground?", e)
                        clearProjectionAndAudioInstance()
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception obtaining MediaProjection", e)
                        clearProjectionAndAudioInstance()
                    }
                } else {
                    Log.w(TAG, "ACTION_SETUP_PROJECTION: Failed to get valid data. ResultCode: $resultCode")
                    clearProjectionAndAudioInstance()
                }
                startForegroundNotification() 
                _isRunning.postValue(isProjectionSetup())
            }
        }
        return START_NOT_STICKY
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection session stopped (onStop callback).")
            clearProjectionAndAudioInstance()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        clearProjectionAndAudioInstance()
        _isRunning.postValue(false)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val TAG = "AudioLoopService"
        private const val SERVICE_NOTIFICATION_ID = 12345

        const val ACTION_START_SERVICE = "com.audioloop.audioloop.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.audioloop.audioloop.ACTION_STOP_SERVICE"
        const val ACTION_SETUP_PROJECTION = "com.audioloop.audioloop.ACTION_SETUP_PROJECTION"

        const val EXTRA_RESULT_CODE = "com.audioloop.audioloop.EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "com.audioloop.audioloop.EXTRA_DATA_INTENT"

        var currentMediaProjection: MediaProjection? = null
            private set

        private val _isRunning = MutableLiveData<Boolean>()
        val isRunning: LiveData<Boolean> = _isRunning

        fun isProjectionSetup(): Boolean {
            return currentMediaProjection != null
        }

        fun clearStaticProjectionReference(){
            Log.d(TAG, "Clearing static MediaProjection reference.")
            currentMediaProjection?.stop() 
            currentMediaProjection = null
        }
    }
}

