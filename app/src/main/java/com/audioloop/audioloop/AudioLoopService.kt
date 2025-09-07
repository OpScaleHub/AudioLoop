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
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder // For AudioSource.MIC
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
    private lateinit var audioManager: AudioManager

    // App Audio Capture & Playback
    private var appAudioRecord: AudioRecord? = null // Renamed for clarity
    private var audioTrack: AudioTrack? = null
    private var appAudioCaptureThread: Thread? = null // Renamed for clarity
    @Volatile private var isCapturingAppAudio: Boolean = false // Renamed for clarity

    // Microphone Audio Capture
    private var micAudioRecord: AudioRecord? = null
    private var micCaptureThread: Thread? = null
    @Volatile private var isCapturingMicAudio: Boolean = false

    @Volatile private var hasAudioFocus: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener

    // Common Audio Parameters (can be adjusted)
    private val SAMPLE_RATE = 44100
    private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // App Audio Specific
    private val APP_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    private val PLAYBACK_CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO

    // Mic Audio Specific
    private val MIC_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO


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
                    if (isCapturingAppAudio && audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
                        audioTrack?.play()
                        Log.d(TAG, "Resuming playback due to focus gain.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS: Focus lost permanently. Stopping loopback.")
                    hasAudioFocus = false
                    clearProjectionAndAudioInstance()
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
                else -> Log.d(TAG, "Unknown audio focus change: $focusChange")
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener)
                .build()
            result = audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            result = audioManager.requestAudioFocus(
                onAudioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
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
        if (!hasAudioFocus) return
        Log.d(TAG, "Abandoning audio focus.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startLoopbackAudio() {
        Log.d(TAG, "Attempting to start loopback audio operations...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.")
            return
        }
        if (currentMediaProjection == null) {
            Log.e(TAG, "MediaProjection is not available.")
            return
        }
        if (isCapturingAppAudio || isCapturingMicAudio) { // Check both flags
            Log.w(TAG, "Audio capture is already in progress.")
            return
        }
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to obtain audio focus. Cannot start loopback.")
            return
        }

        // Start App Audio Capture and Playback
        startAppAudioCaptureAndPlayback()

        // Start Microphone Audio Capture
        startMicAudioCapture()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAppAudioCaptureAndPlayback() {
        // Configure App AudioRecord
        val recordConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val recordAudioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT_ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(APP_CHANNEL_CONFIG_IN)
            .build()
        val appRecordMinBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING)
        if (appRecordMinBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for App AudioRecord.")
            abandonAudioFocus() // Clean up focus if setup fails
            return
        }
        val appRecordBufferSizeInBytes = appRecordMinBufferSize * 2

        try {
            appAudioRecord = AudioRecord.Builder()
                .setAudioFormat(recordAudioFormat)
                .setAudioPlaybackCaptureConfig(recordConfig)
                .setBufferSizeInBytes(appRecordBufferSizeInBytes)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating App AudioRecord: ${e.message}", e)
            abandonAudioFocus()
            return
        }
        if (appAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "App AudioRecord failed to initialize.")
            appAudioRecord?.release()
            appAudioRecord = null
            abandonAudioFocus()
            return
        }
        Log.d(TAG, "App AudioRecord initialized.")

        // Configure AudioTrack for playback
        val trackMinBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING)
        if (trackMinBufferSize == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for AudioTrack.")
            appAudioRecord?.release()
            appAudioRecord = null
            abandonAudioFocus()
            return
        }
        val trackBufferSizeInBytes = trackMinBufferSize * 2
        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(playbackAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT_ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating AudioTrack: ${e.message}", e)
            appAudioRecord?.release()
            appAudioRecord = null
            abandonAudioFocus()
            return
        }
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize.")
            appAudioRecord?.release()
            appAudioRecord = null
            audioTrack?.release()
            audioTrack = null
            abandonAudioFocus()
            return
        }
        Log.d(TAG, "AudioTrack initialized.")

        isCapturingAppAudio = true
        appAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "App AudioRecord and AudioTrack started.")

        appAudioCaptureThread = Thread {
            val audioBuffer = ByteArray(appRecordBufferSizeInBytes)
            while (isCapturingAppAudio && hasAudioFocus &&
                   appAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                   audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val bytesRead = appAudioRecord?.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead != null && bytesRead > 0) {
                    audioTrack?.write(audioBuffer, 0, bytesRead)
                } else if (bytesRead != null && bytesRead < 0) {
                    Log.e(TAG, "Error reading app audio data: $bytesRead")
                }
            }
            Log.d(TAG, "App audio capture/playback thread finished.")
        }
        appAudioCaptureThread?.name = "AppAudioPlaybackThread"
        appAudioCaptureThread?.start()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startMicAudioCapture() {
        Log.d(TAG, "Attempting to start Mic audio capture...")
        val micRecordMinBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING)
        if (micRecordMinBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for Mic AudioRecord.")
            // Don't abandon focus here as app audio might still want it,
            // but log failure. Or decide if mic failure is critical.
            return
        }
        val micRecordBufferSizeInBytes = micRecordMinBufferSize * 2

        try {
            micAudioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT_ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(MIC_CHANNEL_CONFIG_IN)
                        .build()
                )
                .setBufferSizeInBytes(micRecordBufferSizeInBytes)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating Mic AudioRecord: ${e.message}", e)
            return
        }

        if (micAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mic AudioRecord failed to initialize.")
            micAudioRecord?.release()
            micAudioRecord = null
            return
        }
        Log.d(TAG, "Mic AudioRecord initialized.")

        isCapturingMicAudio = true
        micAudioRecord?.startRecording()
        Log.d(TAG, "Mic AudioRecord started.")

        micCaptureThread = Thread {
            val micAudioBuffer = ByteArray(micRecordBufferSizeInBytes)
            while (isCapturingMicAudio && micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = micAudioRecord?.read(micAudioBuffer, 0, micAudioBuffer.size)
                if (bytesRead != null && bytesRead > 0) {
                    // TODO: Process micAudioBuffer - e.g., mix it or save it
                    Log.v(TAG, "Mic audio data read: $bytesRead bytes") // Verbose, enable if needed
                } else if (bytesRead != null && bytesRead < 0) {
                    Log.e(TAG, "Error reading mic audio data: $bytesRead")
                }
            }
            Log.d(TAG, "Mic audio capture thread finished.")
        }
        micCaptureThread?.name = "MicAudioCaptureThread"
        micCaptureThread?.start()
    }


    private fun stopLoopbackAudio() {
        Log.d(TAG, "Stopping all loopback audio operations...")

        // Stop app audio capture and playback
        val wasCapturingApp = isCapturingAppAudio
        isCapturingAppAudio = false
        if (appAudioCaptureThread?.isAlive == true) {
            try { appAudioCaptureThread?.join(500) }
            catch (e: InterruptedException) { Log.w(TAG, "Interrupted joining app audio thread", e); Thread.currentThread().interrupt() }
        }
        appAudioCaptureThread = null
        appAudioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) try { stop() } catch (e: IllegalStateException) { Log.e(TAG, "App AudioRecord stop ex", e) }
            release()
        }
        appAudioRecord = null
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING || playState == AudioTrack.PLAYSTATE_PAUSED) try { pause(); flush(); stop() } catch (e: IllegalStateException) { Log.e(TAG, "AudioTrack stop ex", e) }
            release()
        }
        audioTrack = null
        Log.d(TAG, "App audio capture and playback stopped and released.")

        // Stop microphone audio capture
        stopMicAudioCapture()

        if (wasCapturingApp || hasAudioFocus) { // If app audio was active or we thought we had focus
            abandonAudioFocus()
        }
        Log.d(TAG, "All audio operations stopped, resources released, and audio focus abandoned (if held).")
    }

    private fun stopMicAudioCapture() {
        if (!isCapturingMicAudio && micAudioRecord == null) return // Already stopped or never started

        Log.d(TAG, "Stopping Mic audio capture...")
        isCapturingMicAudio = false
        if (micCaptureThread?.isAlive == true) {
            try { micCaptureThread?.join(500) }
            catch (e: InterruptedException) { Log.w(TAG, "Interrupted joining mic audio thread", e); Thread.currentThread().interrupt() }
        }
        micCaptureThread = null
        micAudioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) try { stop() } catch (e: IllegalStateException) { Log.e(TAG, "Mic AudioRecord stop ex", e) }
            release()
        }
        micAudioRecord = null
        Log.d(TAG, "Mic audio capture stopped and released.")
    }


    private fun startForegroundNotification() {
        val channelId = "AudioLoopServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AudioLoop Service Active")
            .setContentText(if (isProjectionSetup()) "Capturing selected app" else "Ready to select app")
            .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build()
        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
            Log.d(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

     private fun clearProjectionAndAudioInstance() {
        Log.d(TAG, "Clearing MediaProjection and stopping audio instance.")
        stopLoopbackAudio() // Stops both app and mic audio, and handles focus
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
                Log.d(TAG, "ACTION_START_SERVICE received. Cleaning up.")
                stopLoopbackAudio() // Clear previous state including mic
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
                stopSelf(); Log.d(TAG,"Service stopped and self-terminated.")
            }
            ACTION_SETUP_PROJECTION -> {
                if (_isRunning.value != true) {
                     Log.w(TAG, "ACTION_SETUP_PROJECTION: service not marked as running, starting implicitly.")
                    _isRunning.postValue(true)
                }
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT) }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.i(TAG, "MediaProjection obtained and stored.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLoopbackAudio() // This now starts app audio, playback, AND mic capture
                            } else {
                                Log.w(TAG, "AudioPlaybackCaptureConfiguration requires Android Q (API 29)+")
                                clearProjectionAndAudioInstance()
                            }
                        } else {
                             Log.e(TAG, "getMediaProjection returned null despite RESULT_OK.")
                             clearProjectionAndAudioInstance()
                        }
                    } catch (e: Exception) { // Broader catch for projection setup
                        Log.e(TAG, "Exception obtaining/setting up MediaProjection", e)
                        clearProjectionAndAudioInstance()
                    }
                } else {
                    Log.w(TAG, "ACTION_SETUP_PROJECTION: Failed. ResultCode: $resultCode")
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

    override fun onBind(intent: Intent?): IBinder? = null

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
        fun isProjectionSetup(): Boolean = currentMediaProjection != null
    }
}

