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
import android.media.AudioFocusRequest // Added
import android.media.AudioManager // Added
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class AudioLoopService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var notificationManager: NotificationManager? = null
    private lateinit var audioManager: AudioManager // Added

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioCaptureThread: Thread? = null
    @Volatile private var isCapturingAudio: Boolean = false
    @Volatile private var hasAudioFocus: Boolean = false // Track audio focus state

    // Audio Focus
    private var audioFocusRequest: AudioFocusRequest? = null // For API 26+
    private lateinit var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // Define AudioAttributes for playback, used by AudioTrack and AudioFocusRequest
    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _isRunning.postValue(false)
        setupAudioFocusListener()
    }

    private fun setupAudioFocusListener() {
        onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.d(TAG, "AUDIOFOCUS_GAIN: Focus gained.")
                    hasAudioFocus = true
                    if (isCapturingAudio && audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
                        audioTrack?.play()
                        Log.d(TAG, "Resuming playback due to focus gain.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS: Focus lost permanently. Stopping loopback.")
                    hasAudioFocus = false
                    // This will trigger stopLoopbackAudio which abandons focus
                    clearProjectionAndAudioInstance() // Stop everything if focus is permanently lost
                    // Consider also calling stopSelf() if appropriate for your app logic
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT: Focus lost temporarily.")
                    hasAudioFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        Log.d(TAG, "Paused playback due to transient focus loss.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Ducking not implemented, pausing.")
                    hasAudioFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        Log.d(TAG, "Paused playback due to transient focus loss (duck).")
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown audio focus change: $focusChange")
                }
            }
        }
    }


    private fun requestAudioFocus(): Boolean {
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false) // Modify if needed
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener)
                .build()
            result = audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            result = audioManager.requestAudioFocus(
                onAudioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus request granted.")
            hasAudioFocus = true
            true
        } else {
            Log.e(TAG, "Audio focus request failed. Result: $result")
            hasAudioFocus = false
            false
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return // Don't try to abandon if we don't think we have it

        Log.d(TAG, "Abandoning audio focus.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
            audioFocusRequest = null // Clear the request
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        }
        hasAudioFocus = false
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startLoopbackAudio() {
        Log.d(TAG, "Attempting to start loopback audio capture and playback...")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // ... (permission check as before)
            return
        }
        if (currentMediaProjection == null) {
            // ... (projection check as before)
            return
        }
        if (isCapturingAudio) {
            // ... (already running check as before)
            return
        }

        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to obtain audio focus. Cannot start loopback.")
            // Clean up any partial setup if necessary, though AudioRecord/Track aren't created yet
            return
        }

        // Configure AudioRecord (as before)
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
            abandonAudioFocus()
            return
        }
        val recordBufferSizeInBytes = recordMinBufferSize * 2

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(recordAudioFormat)
                .setAudioPlaybackCaptureConfig(recordConfig)
                .setBufferSizeInBytes(recordBufferSizeInBytes)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating AudioRecord: ${e.message}", e)
            abandonAudioFocus()
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.")
            audioRecord?.release()
            audioRecord = null
            abandonAudioFocus()
            return
        }
        Log.d(TAG, "AudioRecord initialized.")

        // Configure AudioTrack (as before, but using playbackAttributes)
        val trackMinBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING)
        if (trackMinBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioTrack getMinBufferSize.")
            audioRecord?.release()
            audioRecord = null
            abandonAudioFocus()
            return
        }
        val trackBufferSizeInBytes = trackMinBufferSize * 2

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(playbackAttributes) // Use defined playbackAttributes
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
            abandonAudioFocus()
            return
        }

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize.")
            audioRecord?.release()
            audioRecord = null
            audioTrack?.release()
            audioTrack = null
            abandonAudioFocus()
            return
        }
        Log.d(TAG, "AudioTrack initialized.")

        isCapturingAudio = true
        audioRecord?.startRecording()
        audioTrack?.play() // Start playing only if everything is fine and focus is granted
        Log.d(TAG, "AudioRecord and AudioTrack started.")

        audioCaptureThread = Thread {
            val audioBuffer = ByteArray(recordBufferSizeInBytes)
            Log.d(TAG, "Audio capture thread started with buffer size: ${audioBuffer.size}")
            while (isCapturingAudio && hasAudioFocus && // Also check for audio focus in the loop
                   audioRecord != null && audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                   audioTrack != null && audioTrack!!.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead != null && bytesRead > 0) {
                    audioTrack?.write(audioBuffer, 0, bytesRead)
                } else if (bytesRead != null && bytesRead < 0) {
                    Log.e(TAG, "Error reading audio data from AudioRecord: $bytesRead")
                }
            }
            Log.d(TAG, "Audio capture/playback thread finished. isCapturingAudio: $isCapturingAudio, hasAudioFocus: $hasAudioFocus")
        }
        audioCaptureThread?.name = "AudioPlaybackThread"
        audioCaptureThread?.start()
    }

    private fun stopLoopbackAudio() {
        Log.d(TAG, "Stopping loopback audio capture and playback...")
        // Order of operations:
        // 1. Signal thread to stop
        // 2. Join thread
        // 3. Stop AudioRecord
        // 4. Stop AudioTrack
        // 5. Abandon AudioFocus
        // 6. Release resources

        val wasCapturing = isCapturingAudio
        isCapturingAudio = false // Signal thread to stop FIRST

        if (audioCaptureThread?.isAlive == true) {
            try {
                audioCaptureThread?.join(500) // Wait for thread to finish
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while joining audio capture thread", e)
                Thread.currentThread().interrupt()
            }
        }
        audioCaptureThread = null

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
        // Log.d(TAG, "AudioRecord released.") // Moved log for clarity

        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING || audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
            try {
                audioTrack?.pause() // Ensure it's paused before stop
                audioTrack?.flush()
                audioTrack?.stop()
                Log.d(TAG, "AudioTrack stopped.")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException stopping AudioTrack: ${e.message}", e)
            }
        }
        audioTrack?.release()
        audioTrack = null
        // Log.d(TAG, "AudioTrack released.") // Moved log for clarity
        
        if (wasCapturing || hasAudioFocus) { // Only abandon if we might have had it
             abandonAudioFocus()
        }

        Log.d(TAG, "AudioRecord, AudioTrack released, and audio focus abandoned (if held).")
    }

    private fun startForegroundNotification() {
        // ... (as before)
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
        stopLoopbackAudio() // This now handles audio focus abandonment too
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop()
        currentMediaProjection = null
        _isRunning.postValue(isProjectionSetup())
        startForegroundNotification()
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
                    _isRunning.postValue(true)
                }
                startForegroundNotification()

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
                            Log.i(TAG, "MediaProjection obtained and stored.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLoopbackAudio() // Starts loopback which includes audio focus request
                            } else {
                                Log.w(TAG, "AudioPlaybackCaptureConfiguration requires Android Q (API 29)+")
                                clearProjectionAndAudioInstance()
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
        clearProjectionAndAudioInstance() // This will also handle abandoning audio focus
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

