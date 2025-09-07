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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioLoopService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var notificationManager: NotificationManager? = null
    private lateinit var audioManager: AudioManager

    // App Audio Capture
    private var appAudioRecord: AudioRecord? = null
    @Volatile private var isCapturingAppAudio: Boolean = false

    // Microphone Audio Capture
    private var micAudioRecord: AudioRecord? = null
    @Volatile private var isCapturingMicAudio: Boolean = false // Still useful to track if mic is independently started/stopped

    // Playback (of mixed audio)
    private var audioTrack: AudioTrack? = null
    private var audioProcessingThread: Thread? = null // Renamed from appAudioCaptureThread

    @Volatile private var hasAudioFocus: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener

    // Common Audio Parameters
    private val SAMPLE_RATE = 44100
    private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // App Audio Specific
    private val APP_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO // Stereo
    private val APP_BYTES_PER_FRAME = 4 // Stereo, 16-bit PCM (2 bytes/sample * 2 channels)

    // Mic Audio Specific
    private val MIC_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO // Mono
    private val MIC_BYTES_PER_FRAME = 2 // Mono, 16-bit PCM (2 bytes/sample * 1 channel)

    // Playback Specific
    private val PLAYBACK_CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO // Stereo output
    private val PLAYBACK_BYTES_PER_FRAME = 4 // Stereo, 16-bit PCM

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
                    Log.d(TAG, "AUDIOFOCUS_GAIN.")
                    hasAudioFocus = true
                    if ((isCapturingAppAudio || isCapturingMicAudio) && audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
                        audioTrack?.play()
                        Log.d(TAG, "Resuming playback.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS.")
                    hasAudioFocus = false
                    clearProjectionAndAudioInstance()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT.")
                    hasAudioFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        Log.d(TAG, "Paused playback.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, pausing.")
                    hasAudioFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        Log.d(TAG, "Paused playback (duck).")
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
            Log.d(TAG, "Audio focus granted.")
            hasAudioFocus = true
            true
        } else {
            Log.e(TAG, "Audio focus request failed: $result")
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
    private fun startAudioProcessing() {
        Log.d(TAG, "Attempting to start audio processing...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.")
            return
        }
        if (currentMediaProjection == null && isCapturingAppAudio) { // Check only if app audio is intended
            Log.e(TAG, "MediaProjection is not available for app audio.")
            return
        }
        if (audioProcessingThread?.isAlive == true) {
            Log.w(TAG, "Audio processing thread already running.")
            return
        }
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to obtain audio focus.")
            return
        }

        var appRecordOk = false
        var micRecordOk = false
        var audioTrackOk = false

        // 1. Setup App AudioRecord (if projection exists)
        var appRecordBufferSizeInBytes = 0
        if (currentMediaProjection != null) {
            val recordConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
            val recordAudioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(APP_CHANNEL_CONFIG_IN).build()
            appRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
            if (appRecordBufferSizeInBytes < AudioRecord.ERROR_BAD_VALUE * 2) { // Check for error
                try {
                    appAudioRecord = AudioRecord.Builder()
                        .setAudioFormat(recordAudioFormat).setAudioPlaybackCaptureConfig(recordConfig)
                        .setBufferSizeInBytes(appRecordBufferSizeInBytes).build()
                    if (appAudioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        appRecordOk = true
                        Log.d(TAG, "App AudioRecord initialized. Buffer: $appRecordBufferSizeInBytes")
                    } else { Log.e(TAG, "App AudioRecord failed to initialize state.") }
                } catch (e: Exception) { Log.e(TAG, "Exception creating App AudioRecord", e) }
            } else { Log.e(TAG, "Invalid appRecordMinBufferSize") }
        } else {
            Log.d(TAG, "No MediaProjection, skipping App AudioRecord setup.")
            appRecordOk = true // No app audio to capture, so it's "ok" in terms of proceeding
        }


        // 2. Setup Mic AudioRecord
        val micRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
        if (micRecordBufferSizeInBytes < AudioRecord.ERROR_BAD_VALUE * 2) {
             try {
                micAudioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(MIC_CHANNEL_CONFIG_IN).build())
                    .setBufferSizeInBytes(micRecordBufferSizeInBytes).build()
                if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    micRecordOk = true
                    Log.d(TAG, "Mic AudioRecord initialized. Buffer: $micRecordBufferSizeInBytes")
                } else { Log.e(TAG, "Mic AudioRecord failed to initialize state.") }
            } catch (e: Exception) { Log.e(TAG, "Exception creating Mic AudioRecord", e) }
        } else { Log.e(TAG, "Invalid micRecordMinBufferSize") }


        // 3. Setup AudioTrack (for playback of mixed audio)
        val trackBufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING) * 2
        if (trackBufferSizeInBytes < AudioTrack.ERROR_BAD_VALUE * 2) {
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(playbackAttributes)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT).build())
                    .setBufferSizeInBytes(trackBufferSizeInBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrackOk = true
                    Log.d(TAG, "AudioTrack initialized. Buffer: $trackBufferSizeInBytes")
                } else { Log.e(TAG, "AudioTrack failed to initialize state.") }
            } catch (e: Exception) { Log.e(TAG, "Exception creating AudioTrack", e) }
        } else { Log.e(TAG, "Invalid trackMinBufferSize") }


        if (! ( (appRecordOk || currentMediaProjection == null) && micRecordOk && audioTrackOk) ) {
            Log.e(TAG, "Audio processing setup failed. Cleaning up. AppRec: $appRecordOk, MicRec: $micRecordOk, Track: $audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null
            micAudioRecord?.release(); micAudioRecord = null
            audioTrack?.release(); audioTrack = null
            abandonAudioFocus()
            return
        }

        // Start recording/playback
        isCapturingAppAudio = appAudioRecord != null // Only true if appAudioRecord was successfully initialized
        isCapturingMicAudio = true // Assume mic is intended if setup was ok

        appAudioRecord?.startRecording() // Null safe
        micAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "Audio sources and track started.")

        audioProcessingThread = Thread {
            // Buffer for app audio (stereo). Size based on appAudioRecord buffer.
            // If appAudioRecord is null, this buffer won't be used for app audio.
            val appAudioByteBuffer = if (appAudioRecord != null) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            
            // Buffer for mic audio (mono). Size based on micAudioRecord buffer.
            val micAudioByteBuffer = ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN)
            
            // Output buffer for AudioTrack (stereo). Size matches app audio buffer size,
            // or mic buffer size x2 if no app audio, or a fixed reasonable size.
            // For simplicity, let's make it based on the larger of the input read sizes, adapted for stereo.
            val outputBufferSize = if (appAudioRecord != null) appRecordBufferSizeInBytes else micRecordBufferSizeInBytes * (PLAYBACK_BYTES_PER_FRAME / MIC_BYTES_PER_FRAME)
            val mixedAudioByteBuffer = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.LITTLE_ENDIAN)

            Log.d(TAG, "Audio processing thread started. Output buffer size: $outputBufferSize")

            while ((isCapturingAppAudio || isCapturingMicAudio) && hasAudioFocus &&
                   audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {

                var appBytesRead = 0
                if (isCapturingAppAudio && appAudioRecord != null && appAudioByteBuffer != null) {
                    appAudioByteBuffer.clear()
                    appBytesRead = appAudioRecord!!.read(appAudioByteBuffer, appAudioByteBuffer.capacity())
                    if (appBytesRead < 0) { Log.e(TAG, "App audio read error: $appBytesRead"); isCapturingAppAudio = false }
                }

                var micBytesRead = 0
                if (isCapturingMicAudio && micAudioRecord != null) {
                    micAudioByteBuffer.clear()
                    micBytesRead = micAudioRecord!!.read(micAudioByteBuffer, micAudioByteBuffer.capacity())
                    if (micBytesRead < 0) { Log.e(TAG, "Mic audio read error: $micBytesRead"); isCapturingMicAudio = false }
                }

                if (appBytesRead <= 0 && micBytesRead <= 0 && (isCapturingAppAudio || isCapturingMicAudio)) {
                    // No data from any active source, maybe wait a bit to avoid busy loop if sources end abruptly
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                    continue
                }
                
                mixedAudioByteBuffer.clear()
                appAudioByteBuffer?.flip() // Prepare for reading
                micAudioByteBuffer.flip()  // Prepare for reading

                // Mixing logic
                // Max samples to process is based on the output buffer's capacity in stereo frames
                val maxOutputFrames = mixedAudioByteBuffer.capacity() / PLAYBACK_BYTES_PER_FRAME

                for (i in 0 until maxOutputFrames) {
                    val appLeftShort: Short = if (isCapturingAppAudio && appAudioByteBuffer != null && appAudioByteBuffer.remaining() >= 2) appAudioByteBuffer.short else 0
                    val appRightShort: Short = if (isCapturingAppAudio && appAudioByteBuffer != null && appAudioByteBuffer.remaining() >= 2) appAudioByteBuffer.short else 0
                    val micShort: Short = if (isCapturingMicAudio && micAudioByteBuffer.remaining() >= 2) micAudioByteBuffer.short else 0

                    // Simple averaging mix.
                    // To prevent excessive volume reduction if one source is silent,
                    // a more adaptive mixing or gain control might be needed in a real app.
                    var mixedLeft = 0
                    var mixedRight = 0
                    var activeSources = 0
                    if (isCapturingAppAudio && appAudioRecord != null) {
                        mixedLeft += appLeftShort.toInt()
                        mixedRight += appRightShort.toInt()
                        activeSources++
                    }
                    if (isCapturingMicAudio && micAudioRecord != null) {
                        mixedLeft += micShort.toInt() // Add mono mic to left
                        mixedRight += micShort.toInt()// Add mono mic to right
                        activeSources++
                    }

                    if (activeSources > 0) {
                        mixedLeft /= activeSources
                        mixedRight /= activeSources
                    }
                    
                    // Clamp to Short range
                    mixedAudioByteBuffer.putShort(mixedLeft.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    mixedAudioByteBuffer.putShort(mixedRight.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
                
                mixedAudioByteBuffer.flip() // Prepare for writing to AudioTrack
                if (mixedAudioByteBuffer.remaining() > 0) {
                    audioTrack?.write(mixedAudioByteBuffer, mixedAudioByteBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                }
            }
            Log.d(TAG, "Audio processing thread finished.")
        }
        audioProcessingThread?.name = "AudioProcessingThread"
        audioProcessingThread?.start()
    }


    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping all audio processing operations...")
        val wasCapturing = isCapturingAppAudio || isCapturingMicAudio
        isCapturingAppAudio = false
        isCapturingMicAudio = false

        if (audioProcessingThread?.isAlive == true) {
            try { audioProcessingThread?.join(500) }
            catch (e: InterruptedException) { Log.w(TAG, "Interrupted joining audio processing thread", e); Thread.currentThread().interrupt() }
        }
        audioProcessingThread = null

        appAudioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) try { stop() } catch (e: IllegalStateException) { Log.e(TAG, "App AR stop ex", e) }
            release()
        }
        appAudioRecord = null
        micAudioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) try { stop() } catch (e: IllegalStateException) { Log.e(TAG, "Mic AR stop ex", e) }
            release()
        }
        micAudioRecord = null
        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING || playState == AudioTrack.PLAYSTATE_PAUSED) try { pause(); flush(); stop() } catch (e: IllegalStateException) { Log.e(TAG, "AT stop ex", e) }
            release()
        }
        audioTrack = null
        
        if (wasCapturing || hasAudioFocus) {
            abandonAudioFocus()
        }
        Log.d(TAG, "All audio resources released and focus abandoned (if held).")
    }

    private fun startForegroundNotification() {
        // ... (as before)
        val channelId = "AudioLoopServiceChannel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW); notificationManager = getSystemService(NotificationManager::class.java); notificationManager?.createNotificationChannel(channel) }; val notification: Notification = NotificationCompat.Builder(this, channelId).setContentTitle("AudioLoop Service Active").setContentText(if (isProjectionSetup()) "Capturing selected app" else "Ready to select app").setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build(); try { startForeground(SERVICE_NOTIFICATION_ID, notification); Log.d(TAG, "Service started in foreground.") } catch (e: Exception) { Log.e(TAG, "Error starting foreground service", e) }
    }

     private fun clearProjectionAndAudioInstance() {
        Log.d(TAG, "Clearing MediaProjection and stopping audio instance.")
        stopAudioProcessing() // This now stops all audio sources and playback
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop()
        currentMediaProjection = null
        _isRunning.postValue(isProjectionSetup()) // Update LiveData
        startForegroundNotification() // Update notification
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")
        when (action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "ACTION_START_SERVICE received. Cleaning up.")
                stopAudioProcessing() // Clear previous state
                if (currentMediaProjection != null) { // If projection exists from previous invalid state
                    Log.d(TAG, "Clearing existing MediaProjection from invalid state.")
                    currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                    currentMediaProjection?.stop(); currentMediaProjection = null
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
                    _isRunning.postValue(true) // Ensure service is marked as running
                }
                startForegroundNotification() // Update notification

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT) }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        // Stop any existing projection/audio before starting a new one
                        stopAudioProcessing() // Stop current audio
                        if (currentMediaProjection != null) { // Clear old projection
                            currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                            currentMediaProjection?.stop()
                            currentMediaProjection = null
                        }

                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection // Assign new projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.i(TAG, "MediaProjection obtained and stored.")
                            // Now start audio processing which includes app audio (if projection is Q+) and mic
                            startAudioProcessing()
                        } else {
                             Log.e(TAG, "getMediaProjection returned null despite RESULT_OK.")
                             clearProjectionAndAudioInstance() // Clean up if new projection is null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception obtaining/setting up MediaProjection", e)
                        clearProjectionAndAudioInstance()
                    }
                } else {
                    Log.w(TAG, "ACTION_SETUP_PROJECTION: Failed. ResultCode: $resultCode")
                    clearProjectionAndAudioInstance() // Clean up if projection setup fails
                }
                startForegroundNotification() // Update notification text
                _isRunning.postValue(isProjectionSetup()) // Reflect current projection state
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
        // ... (other companion object members as before)
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


