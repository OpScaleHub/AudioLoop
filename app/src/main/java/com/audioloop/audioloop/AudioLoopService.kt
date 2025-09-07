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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestForListener = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequestForListener!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(onAudioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        Log.d(TAG, "Registered a 'passive' audio focus listener.")
    }

    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Ensuring passive audio focus listener is active. Not taking aggressive focus.")
        shouldBePlayingBasedOnFocus = true
        return true
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "Abandoning 'passive' audio focus listener registration if active.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestForListener?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        }
        Log.d(TAG, "Passive audio focus listener abandoned/reset.")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioProcessing(): Boolean {
        Log.d(TAG, "Attempting to start audio processing (mixing mode)...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.")
            return false
        }
        if (audioProcessingThread?.isAlive == true) {
            Log.w(TAG, "Audio processing thread already running.")
            return true
        }
        if (!requestAudioFocus()) {
            Log.e(TAG, "Focus request logic indicated an issue.")
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
            appRecordOk = true
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
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) audioTrackOk = true
                else Log.e(TAG, "AudioTrack failed to initialize state.")
            } catch (e: Exception) { Log.e(TAG, "Exception creating AudioTrack", e) }
        } else Log.e(TAG, "Invalid trackMinBufferSize: $trackBufferSizeInBytes")

        if (! (appRecordOk && micRecordOk && audioTrackOk) ) {
            Log.e(TAG, "Audio processing setup failed. Cleaning up. AppRec:$appRecordOk(needed:${currentMediaProjection!=null}), MicRec:$micRecordOk, Track:$audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null
            micAudioRecord?.release(); micAudioRecord = null
            audioTrack?.release(); audioTrack = null
            return false
        }

        isCapturingAppAudio = appAudioRecord != null
        isCapturingMicAudio = micAudioRecord != null

        appAudioRecord?.startRecording()
        micAudioRecord?.startRecording() // Mic still records, just not mixed for this test
        audioTrack?.play()
        Log.d(TAG, "Audio sources and track started for mixing test (app audio only to track).")

        audioProcessingThread = Thread {
            val appAudioByteBuffer = if (appAudioRecord != null && appRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val micAudioByteBuffer = if (micAudioRecord != null && micRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null // Mic data read but ignored in mix

            val outputBufferSize = if (appAudioRecord != null && appRecordBufferSizeInBytes > 0) appRecordBufferSizeInBytes
            else if (micAudioRecord != null && micRecordBufferSizeInBytes > 0) micRecordBufferSizeInBytes * (PLAYBACK_BYTES_PER_FRAME / MIC_BYTES_PER_FRAME)
            else if(trackBufferSizeInBytes > 0) trackBufferSizeInBytes
            else 4096
            val mixedAudioByteBuffer = if(outputBufferSize > 0) ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.LITTLE_ENDIAN) else null

            if (mixedAudioByteBuffer == null) {
                Log.e(TAG, "Failed to allocate mixedAudioByteBuffer. Aborting thread.")
                return@Thread
            }
            Log.d(TAG, "Audio processing thread started. Output buffer: ${mixedAudioByteBuffer.capacity()}")

            while ((isCapturingAppAudio || isCapturingMicAudio) && shouldBePlayingBasedOnFocus &&
                audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                var appBytesRead = 0
                if (isCapturingAppAudio && appAudioRecord != null && appAudioByteBuffer != null) {
                    appAudioByteBuffer.clear()
                    appBytesRead = appAudioRecord!!.read(appAudioByteBuffer, appAudioByteBuffer.capacity())
                    if (appBytesRead < 0) { Log.e(TAG, "App audio read error: $appBytesRead"); isCapturingAppAudio = false }
                }

                // Mic audio is read to keep the buffer flowing, but not used in the mix for this test
                var micBytesRead = 0
                if (isCapturingMicAudio && micAudioRecord != null && micAudioByteBuffer != null) {
                    micAudioByteBuffer.clear()
                    micBytesRead = micAudioRecord!!.read(micAudioByteBuffer, micAudioByteBuffer.capacity())
                    if (micBytesRead < 0) { Log.e(TAG, "Mic audio read error: $micBytesRead"); isCapturingMicAudio = false }
                }

                if (appBytesRead <= 0 && micBytesRead <= 0 && (appAudioRecord != null || micAudioRecord != null)) {
                    if (!isCapturingAppAudio && !isCapturingMicAudio) break
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                    continue
                }

                mixedAudioByteBuffer.clear()
                appAudioByteBuffer?.flip()
                // micAudioByteBuffer?.flip() // Not needed as mic data is ignored

                val maxOutputFrames = mixedAudioByteBuffer.capacity() / PLAYBACK_BYTES_PER_FRAME
                for (i in 0 until maxOutputFrames) {
                    // --- START OF TEMPORARY TEST MODIFICATION ---
                    var mixedLeft = 0
                    var mixedRight = 0

                    val appLeftShort: Short = if (isCapturingAppAudio && appAudioByteBuffer?.hasRemaining() == true && appAudioByteBuffer.remaining() >=2) appAudioByteBuffer.short else 0
                    val appRightShort: Short = if (isCapturingAppAudio && appAudioByteBuffer?.hasRemaining() == true && appAudioByteBuffer.remaining() >=2) appAudioByteBuffer.short else 0
                    // val micShort: Short = if (isCapturingMicAudio && micAudioByteBuffer?.hasRemaining() == true && micAudioByteBuffer.remaining() >=2) micAudioByteBuffer.short else 0 // Mic data ignored

                    // Test: Only process and play app audio
                    if (isCapturingAppAudio && appAudioRecord != null && appBytesRead > 0) {
                        mixedLeft = appLeftShort.toInt()
                        mixedRight = appRightShort.toInt()
                    }
                    // No "else" needed here, if no app audio, mixedLeft/Right remain 0 (silence)
                    // Mic input is effectively ignored for this test

                    // --- END OF TEMPORARY TEST MODIFICATION ---

                    mixedAudioByteBuffer.putShort(mixedLeft.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    mixedAudioByteBuffer.putShort(mixedRight.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }

                mixedAudioByteBuffer.flip()
                if (mixedAudioByteBuffer.remaining() > 0) {
                    audioTrack?.write(mixedAudioByteBuffer, mixedAudioByteBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
                }
            }
            Log.d(TAG, "Audio processing thread finished. Loop flags: appCap=$isCapturingAppAudio, micCap=$isCapturingMicAudio, focus=$shouldBePlayingBasedOnFocus, trackState=${audioTrack?.playState}")
        }
        audioProcessingThread?.name = "AudioProcessingMixThread"
        audioProcessingThread?.start()
        return true
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping all audio processing operations (mixing mode)...")
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
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")
        when (action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "ACTION_START_SERVICE received.")
                stopAudioProcessing()
                if (currentMediaProjection != null) {
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
                stopAudioProcessing()
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
                            if (startAudioProcessing()) {
                                projectionSuccess = true
                                Log.i(TAG, "Audio processing started successfully.")
                            } else {
                                Log.e(TAG, "Audio processing setup failed after getting projection.")
                                currentMediaProjection?.stop(); currentMediaProjection = null
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

                _isRunning.postValue(projectionSuccess)
                if (!projectionSuccess) {
                    clearProjectionAndAudioInstance()
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
