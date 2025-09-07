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
import android.media.AudioFocusRequest // Keep for listener setup
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

    private var appAudioRecord: AudioRecord? = null
    @Volatile private var isCapturingAppAudio: Boolean = false
    private var micAudioRecord: AudioRecord? = null
    @Volatile private var isCapturingMicAudio: Boolean = false
    private var audioTrack: AudioTrack? = null
    private var audioProcessingThread: Thread? = null

    @Volatile private var shouldBePlayingBasedOnFocus: Boolean = true
    private var audioFocusRequestForListener: AudioFocusRequest? = null
    private lateinit var onAudioFocusChangeListener: AudioManager.OnAudioFocusChangeListener

    private val SAMPLE_RATE = 44100
    private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val APP_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    private val APP_BYTES_PER_FRAME = 4
    private val MIC_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val MIC_BYTES_PER_FRAME = 2
    private val PLAYBACK_CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val PLAYBACK_BYTES_PER_FRAME = 4

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
        _isRunning.postValue(false) // Initial state
        setupAudioFocusListener()
    }

    private fun setupAudioFocusListener() {
        onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Log.d(TAG, "AudioFocusListener: AUDIOFOCUS_GAIN.")
                    shouldBePlayingBasedOnFocus = true
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) {
                        audioTrack?.play()
                        Log.d(TAG, "AudioFocusListener: Resuming playback.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    Log.d(TAG, "AudioFocusListener: AUDIOFOCUS_LOSS.")
                    shouldBePlayingBasedOnFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
                    // Optionally, could call clearProjectionAndAudioInstance() if permanent loss should stop everything
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Log.d(TAG, "AudioFocusListener: AUDIOFOCUS_LOSS_TRANSIENT.")
                    shouldBePlayingBasedOnFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        Log.d(TAG, "AudioFocusListener: Paused playback.")
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "AudioFocusListener: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, pausing.")
                    shouldBePlayingBasedOnFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                        Log.d(TAG, "AudioFocusListener: Paused playback (duck).")
                    }
                }
                else -> Log.d(TAG, "Unknown audio focus change: $focusChange")
            }
        }
        // Register the passive listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestForListener = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true) // Allow delayed gain
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequestForListener!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(onAudioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        Log.d(TAG, "Registered a 'passive' audio focus listener.")
    }

    // This function is effectively a no-op for focus grabbing, relies on passive listener
    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Ensuring passive audio focus listener is active. Not taking aggressive focus.")
        shouldBePlayingBasedOnFocus = true
        return true
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "Abandoning 'passive' audio focus listener registration if active.")
        // Only abandon the listener request, not an aggressive focus we didn't take.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestForListener?.let { audioManager.abandonAudioFocusRequest(it) }
            // audioFocusRequestForListener = null // Keep it if we re-register in onCreate
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        }
        Log.d(TAG, "Passive audio focus listener abandoned/reset.")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioProcessing(): Boolean { // Returns true if setup is successful
        Log.d(TAG, "Attempting to start audio processing (mixing mode)...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.")
            return false
        }

        if (audioProcessingThread?.isAlive == true) {
            Log.w(TAG, "Audio processing thread already running.")
            return true // Or false, depending on desired behavior for re-entry
        }

        if (!requestAudioFocus()) { // This ensures our listener is respected
            Log.e(TAG, "Focus request logic indicated an issue (should not happen with new logic).")
            return false
        }

        var appRecordOk = false
        var micRecordOk = false
        var audioTrackOk = false
        var appRecordBufferSizeInBytes = 0

        if (currentMediaProjection != null) {
            val recordConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
            val recordAudioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(APP_CHANNEL_CONFIG_IN).build()
            appRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
            if (appRecordBufferSizeInBytes > 0) {
                try {
                    appAudioRecord = AudioRecord.Builder()
                        .setAudioFormat(recordAudioFormat).setAudioPlaybackCaptureConfig(recordConfig)
                        .setBufferSizeInBytes(appRecordBufferSizeInBytes).build()
                    if (appAudioRecord?.state == AudioRecord.STATE_INITIALIZED) appRecordOk = true
                    else Log.e(TAG, "App AudioRecord failed to initialize state.")
                } catch (e: Exception) { Log.e(TAG, "Exception creating App AudioRecord", e) }
            } else Log.e(TAG, "Invalid appRecordMinBufferSize: $appRecordBufferSizeInBytes")
        } else {
            Log.d(TAG, "No MediaProjection, App AudioRecord will not be used.")
            appRecordOk = true // No app audio to capture is considered "ok" for this flag
        }

        val micRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
        if (micRecordBufferSizeInBytes > 0) {
            try {
                micAudioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(MIC_CHANNEL_CONFIG_IN).build())
                    .setBufferSizeInBytes(micRecordBufferSizeInBytes).build()
                if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) micRecordOk = true
                else Log.e(TAG, "Mic AudioRecord failed to initialize state.")
            } catch (e: Exception) { Log.e(TAG, "Exception creating Mic AudioRecord", e) }
        } else Log.e(TAG, "Invalid micRecordMinBufferSize: $micRecordBufferSizeInBytes")

        val trackBufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING) * 2
        if (trackBufferSizeInBytes > 0) {
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(playbackAttributes)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT).build())
                    .setBufferSizeInBytes(trackBufferSizeInBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
                if (audioTrack?.state == AudioRecord.STATE_INITIALIZED) audioTrackOk = true
                else Log.e(TAG, "AudioTrack failed to initialize state.")
            } catch (e: Exception) { Log.e(TAG, "Exception creating AudioTrack", e) }
        } else Log.e(TAG, "Invalid trackMinBufferSize: $trackBufferSizeInBytes")

        if (! (appRecordOk && micRecordOk && audioTrackOk) ) {
            Log.e(TAG, "Audio processing setup failed. Cleaning up. AppRec:$appRecordOk(needed:${currentMediaProjection!=null}), MicRec:$micRecordOk, Track:$audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null
            micAudioRecord?.release(); micAudioRecord = null
            audioTrack?.release(); audioTrack = null
            // abandonAudioFocus() // Passive listener, less critical to abandon immediately on setup fail
            return false // Indicate setup failure
        }

        isCapturingAppAudio = appAudioRecord != null
        isCapturingMicAudio = micAudioRecord != null

        appAudioRecord?.startRecording()
        micAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "Audio sources and track started for mixing.")

        audioProcessingThread = Thread { /* ... (thread logic as before) ... */
            val appAudioByteBuffer = if (appAudioRecord != null && appRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val micAudioByteBuffer = if (micAudioRecord != null && micRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null

            val outputBufferSize = if (appAudioRecord != null && appRecordBufferSizeInBytes > 0) appRecordBufferSizeInBytes
            else if (micAudioRecord != null && micRecordBufferSizeInBytes > 0) micRecordBufferSizeInBytes * (PLAYBACK_BYTES_PER_FRAME / MIC_BYTES_PER_FRAME)
            else if(trackBufferSizeInBytes > 0) trackBufferSizeInBytes
            else 4096 // Absolute fallback buffer size
            val mixedAudioByteBuffer = if(outputBufferSize > 0) ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.LITTLE_ENDIAN) else null

            if (mixedAudioByteBuffer == null) {
                Log.e(TAG, "Failed to allocate mixedAudioByteBuffer. Aborting thread.")
                // Potentially set _isRunning to false here if this is critical
                return@Thread
            }
            Log.d(TAG, "Audio processing thread started. Output buffer size: ${mixedAudioByteBuffer.capacity()}")

            while ((isCapturingAppAudio || isCapturingMicAudio) && shouldBePlayingBasedOnFocus &&
                audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                var appBytesRead = 0
                if (isCapturingAppAudio && appAudioRecord != null && appAudioByteBuffer != null) {
                    appAudioByteBuffer.clear()
                    appBytesRead = appAudioRecord!!.read(appAudioByteBuffer, appAudioByteBuffer.capacity())
                    if (appBytesRead < 0) { Log.e(TAG, "App audio read error: $appBytesRead"); isCapturingAppAudio = false } // Stop trying if error
                }

                var micBytesRead = 0
                if (isCapturingMicAudio && micAudioRecord != null && micAudioByteBuffer != null) {
                    micAudioByteBuffer.clear()
                    micBytesRead = micAudioRecord!!.read(micAudioByteBuffer, micAudioByteBuffer.capacity())
                    if (micBytesRead < 0) { Log.e(TAG, "Mic audio read error: $micBytesRead"); isCapturingMicAudio = false } // Stop trying if error
                }

                if (appBytesRead <= 0 && micBytesRead <= 0 && (appAudioRecord != null || micAudioRecord != null)) {
                    if (!isCapturingAppAudio && !isCapturingMicAudio) break // Both sources errored or stopped
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                    continue
                }

                mixedAudioByteBuffer.clear()
                appAudioByteBuffer?.flip()
                micAudioByteBuffer?.flip()

                val maxOutputFrames = mixedAudioByteBuffer.capacity() / PLAYBACK_BYTES_PER_FRAME
                for (i in 0 until maxOutputFrames) {
                    val appLeftShort: Short = if (isCapturingAppAudio && appAudioByteBuffer?.hasRemaining() == true && appAudioByteBuffer.remaining() >=2) appAudioByteBuffer.short else 0
                    val appRightShort: Short = if (isCapturingAppAudio && appAudioByteBuffer?.hasRemaining() == true && appAudioByteBuffer.remaining() >=2) appAudioByteBuffer.short else 0
                    val micShort: Short = if (isCapturingMicAudio && micAudioByteBuffer?.hasRemaining() == true && micAudioByteBuffer.remaining() >=2) micAudioByteBuffer.short else 0

                    var mixedLeft = 0; var mixedRight = 0; var activeSources = 0
                    if (isCapturingAppAudio && appAudioRecord != null && appBytesRead > 0) { // Check appBytesRead also
                        mixedLeft += appLeftShort.toInt(); mixedRight += appRightShort.toInt(); activeSources++
                    }
                    if (isCapturingMicAudio && micAudioRecord != null && micBytesRead > 0) { // Check micBytesRead also
                        mixedLeft += micShort.toInt(); mixedRight += micShort.toInt(); activeSources++
                    }

                    if (activeSources > 0) { mixedLeft /= activeSources; mixedRight /= activeSources }
                    else { mixedLeft = 0; mixedRight = 0; } // Silence if no active input samples

                    mixedAudioByteBuffer.putShort(mixedLeft.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    mixedAudioByteBuffer.putShort(mixedRight.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }

                mixedAudioByteBuffer.flip()
                if (mixedAudioByteBuffer.remaining() > 0) {
                    audioTrack?.write(mixedAudioByteBuffer, mixedAudioByteBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                }
            }
            Log.d(TAG, "Audio processing thread finished. Loop condition flags: isAppCapturing=$isCapturingAppAudio, isMicCapturing=$isCapturingMicAudio, shouldPlayByFocus=$shouldBePlayingBasedOnFocus, trackPlayState=${audioTrack?.playState}")
        }
        audioProcessingThread?.name = "AudioProcessingMixThread"
        audioProcessingThread?.start()
        return true // Successfully started processing
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping all audio processing operations (mixing mode)...")
        // Signal the thread to stop BEFORE joining
        isCapturingAppAudio = false
        isCapturingMicAudio = false
        shouldBePlayingBasedOnFocus = false

        if (audioProcessingThread?.isAlive == true) {
            try { audioProcessingThread?.join(500) }
            catch (e: InterruptedException) { Log.w(TAG, "Interrupted joining audio processing thread", e); Thread.currentThread().interrupt() }
        }
        audioProcessingThread = null

        appAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) try { stop() } catch (e: Exception) {} ; release() }; appAudioRecord = null
        micAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) try { stop() } catch (e: Exception) {} ; release() }; micAudioRecord = null
        audioTrack?.apply { if (playState != AudioTrack.PLAYSTATE_STOPPED) try { pause(); flush(); stop() } catch (e: Exception) {} ; release() }; audioTrack = null

        // abandonAudioFocus() // Listener is typically kept for service lifetime or handled in onDestroy
        Log.d(TAG, "All audio resources released (mixing mode).")
    }

    private fun startForegroundNotification() {
        val channelId = "AudioLoopServiceChannel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW); notificationManager = getSystemService(NotificationManager::class.java); notificationManager?.createNotificationChannel(channel) }; val notification: Notification = NotificationCompat.Builder(this, channelId).setContentTitle("AudioLoop Service Active").setContentText(if (isProjectionSetup()) "Capturing: ${if(isCapturingAppAudio) "App" else ""}${if(isCapturingAppAudio && isCapturingMicAudio) " & " else ""}${if(isCapturingMicAudio) "Mic" else " (Mic Error?)"}" else "Ready to select app").setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build(); try { startForeground(SERVICE_NOTIFICATION_ID, notification); Log.d(TAG, "Service started in foreground.") } catch (e: Exception) { Log.e(TAG, "Error starting foreground service", e) }
    }

    private fun clearProjectionAndAudioInstance() {
        Log.d(TAG, "Clearing MediaProjection and stopping audio instance.")
        stopAudioProcessing()
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop()
        currentMediaProjection = null
        // _isRunning should be set by the caller or based on actual state after cleanup
        // For example, if called from ACTION_STOP_SERVICE, _isRunning will be false.
        // If called due to projection ending, _isRunning should also become false.
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")
        when (action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "ACTION_START_SERVICE received.")
                stopAudioProcessing() // Ensure clean state
                if (currentMediaProjection != null) { // Should not happen if stopAudioProcessing is effective
                    Log.w(TAG, "Clearing existing MediaProjection from unexpected state.")
                    currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                    currentMediaProjection?.stop(); currentMediaProjection = null
                }
                _isRunning.postValue(true) // Service is "active" and ready for projection
                startForegroundNotification()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "ACTION_STOP_SERVICE received.")
                _isRunning.postValue(false)
                clearProjectionAndAudioInstance()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(); Log.d(TAG,"Service stopped and self-terminated.")
            }
            ACTION_SETUP_PROJECTION -> {
                Log.d(TAG, "ACTION_SETUP_PROJECTION received.")
                stopAudioProcessing() // Stop any previous processing first
                if (currentMediaProjection != null) {
                    currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                    currentMediaProjection?.stop(); currentMediaProjection = null
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT) }

                var projectionSuccess = false
                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.i(TAG, "MediaProjection obtained. Attempting to start audio processing.")
                            if (startAudioProcessing()) { // Start processing and check success
                                projectionSuccess = true
                                Log.i(TAG, "Audio processing started successfully.")
                            } else {
                                Log.e(TAG, "Audio processing setup failed after getting projection.")
                                currentMediaProjection?.stop(); currentMediaProjection = null // Clean up failed projection
                            }
                        } else {
                            Log.e(TAG, "getMediaProjection returned null.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception in ACTION_SETUP_PROJECTION", e)
                    }
                } else {
                    Log.w(TAG, "ACTION_SETUP_PROJECTION: Failed to get valid data for projection. ResultCode: $resultCode")
                }

                _isRunning.postValue(projectionSuccess) // Update based on actual success
                if (!projectionSuccess) {
                    clearProjectionAndAudioInstance() // Ensure full cleanup if we failed
                }
                startForegroundNotification()
            }
        }
        return START_NOT_STICKY
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection session stopped (onStop callback).")
            _isRunning.postValue(false)
            clearProjectionAndAudioInstance()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        abandonAudioFocus() // Release the passive listener
        clearProjectionAndAudioInstance() // This will also call stopAudioProcessing
        _isRunning.postValue(false) // Final state
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

