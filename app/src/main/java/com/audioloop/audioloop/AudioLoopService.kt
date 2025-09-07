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
import android.media.MediaRecorder
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
    private val APP_BYTES_PER_FRAME = 4 // Stereo PCM16: 2 bytes/sample * 2 channels
    private val MIC_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val MIC_BYTES_PER_FRAME = 2 // Mono PCM16: 2 bytes/sample * 1 channel
    private val PLAYBACK_CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val PLAYBACK_BYTES_PER_FRAME = 4 // Stereo PCM16

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
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Log.d(TAG, "AudioFocusListener: AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, pausing.")
                    shouldBePlayingBasedOnFocus = false
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
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
        shouldBePlayingBasedOnFocus = true
        return true
    }

    private fun abandonAudioFocus() {
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
        Log.d(TAG, "Attempting to start audio processing (full mix)...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted.")
            return false
        }
        if (audioProcessingThread?.isAlive == true) {
            Log.w(TAG, "Audio processing thread already running.")
            return true
        }
        if (!requestAudioFocus()) {
            return false
        }

        var appRecordOk = false
        var micRecordOk = false
        var audioTrackOk = false
        var appRecordBufferSizeInBytes = 0
        var micRecordBufferSizeInBytes = 0
        var trackBufferSizeInBytes = 0

        if (currentMediaProjection != null) {
            val arFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(APP_CHANNEL_CONFIG_IN).build()
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
            appRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
            if (appRecordBufferSizeInBytes > 0) {
                try {
                    appAudioRecord = AudioRecord.Builder().setAudioFormat(arFormat).setAudioPlaybackCaptureConfig(captureConfig).setBufferSizeInBytes(appRecordBufferSizeInBytes).build()
                    if (appAudioRecord?.state == AudioRecord.STATE_INITIALIZED) appRecordOk = true
                    else Log.e(TAG, "App AudioRecord init failed.")
                } catch (e: Exception) { Log.e(TAG, "App AudioRecord creation ex", e) }
            } else Log.e(TAG, "App AudioRecord invalid buffer size")
        } else {
            Log.d(TAG, "No MediaProjection, App AudioRecord not used.")
            appRecordOk = true // OK to proceed without app audio if no projection
        }

        val micFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(MIC_CHANNEL_CONFIG_IN).build()
        micRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
        if (micRecordBufferSizeInBytes > 0) {
            try {
                micAudioRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(micFormat).setBufferSizeInBytes(micRecordBufferSizeInBytes).build()
                if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) micRecordOk = true
                else Log.e(TAG, "Mic AudioRecord init failed.")
            } catch (e: Exception) { Log.e(TAG, "Mic AudioRecord creation ex", e) }
        } else Log.e(TAG, "Mic AudioRecord invalid buffer size")

        val trackFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT).build()
        trackBufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING) * 2
        if (trackBufferSizeInBytes > 0) {
            try {
                audioTrack = AudioTrack.Builder().setAudioAttributes(playbackAttributes).setAudioFormat(trackFormat).setBufferSizeInBytes(trackBufferSizeInBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) audioTrackOk = true
                else Log.e(TAG, "AudioTrack init failed.")
            } catch (e: Exception) { Log.e(TAG, "AudioTrack creation ex", e) }
        } else Log.e(TAG, "AudioTrack invalid buffer size")

        if (! (appRecordOk && micRecordOk && audioTrackOk) ) {
            Log.e(TAG, "Audio setup failed. AppRec:$appRecordOk(needed:${currentMediaProjection!=null}), MicRec:$micRecordOk, Track:$audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null
            micAudioRecord?.release(); micAudioRecord = null
            audioTrack?.release(); audioTrack = null
            return false
        }

        isCapturingAppAudio = appAudioRecord != null
        isCapturingMicAudio = micAudioRecord != null

        appAudioRecord?.startRecording()
        micAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "Audio sources and track started for full mix.")

        audioProcessingThread = Thread {
            val appBuf = if (appAudioRecord != null && appRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val micBuf = if (micAudioRecord != null && micRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val outBufSize = trackBufferSizeInBytes // Output buffer based on AudioTrack's buffer
            val mixedBuf = if(outBufSize > 0) ByteBuffer.allocateDirect(outBufSize).order(ByteOrder.LITTLE_ENDIAN) else null

            if (mixedBuf == null) {
                Log.e(TAG, "Failed to allocate mixedAudioByteBuffer. Aborting thread."); return@Thread
            }
            Log.d(TAG, "Audio processing thread started. AppBuf:${appBuf?.capacity()}, MicBuf:${micBuf?.capacity()}, MixedBuf:${mixedBuf.capacity()}")

            while ((isCapturingAppAudio || isCapturingMicAudio) && shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                var appBytes = 0
                if (isCapturingAppAudio && appAudioRecord != null && appBuf != null) {
                    appBuf.clear()
                    appBytes = appAudioRecord!!.read(appBuf, appBuf.capacity())
                    if (appBytes < 0) { Log.e(TAG, "App audio read error: $appBytes"); isCapturingAppAudio = false }
                    else if (appBytes > 0) appBuf.flip()
                }

                var micBytes = 0
                if (isCapturingMicAudio && micAudioRecord != null && micBuf != null) {
                    micBuf.clear()
                    micBytes = micAudioRecord!!.read(micBuf, micBuf.capacity())
                    if (micBytes < 0) { Log.e(TAG, "Mic audio read error: $micBytes"); isCapturingMicAudio = false }
                    else if (micBytes > 0) micBuf.flip()
                }

                if (appBytes <= 0 && micBytes <= 0) {
                    if ((appAudioRecord != null && !isCapturingAppAudio) || (micAudioRecord != null && !isCapturingMicAudio)) break // Stop if sources have errored
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                    continue
                }

                mixedBuf.clear()
                val framesToProcess = mixedBuf.capacity() / PLAYBACK_BYTES_PER_FRAME

                for (i in 0 until framesToProcess) {
                    var appL: Short = 0; var appR: Short = 0; var micM: Short = 0
                    var appHasSample = false; var micHasSample = false

                    if (isCapturingAppAudio && appBytes > 0 && appBuf != null && appBuf.remaining() >= APP_BYTES_PER_FRAME) {
                        appL = appBuf.short; appR = appBuf.short
                        appHasSample = true
                    }
                    if (isCapturingMicAudio && micBytes > 0 && micBuf != null && micBuf.remaining() >= MIC_BYTES_PER_FRAME) {
                        micM = micBuf.short
                        micHasSample = true
                    }

                    if (!appHasSample && !micHasSample && i > 0) { // If no more data from any source but we've processed some frames
                        mixedBuf.limit(mixedBuf.position()) // Set limit to current position
                        break // Stop filling this output buffer
                    }

                    var mixedL = 0; var mixedR = 0; var activeSources = 0
                    if (appHasSample) { mixedL += appL; mixedR += appR; activeSources++ }
                    if (micHasSample) { mixedL += micM; mixedR += micM; activeSources++ }
                    if (activeSources > 0) { mixedL /= activeSources; mixedR /= activeSources }

                    mixedBuf.putShort(mixedL.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    mixedBuf.putShort(mixedR.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }

                mixedBuf.flip()
                if (mixedBuf.remaining() > 0) {
                    audioTrack?.write(mixedBuf, mixedBuf.remaining(), AudioTrack.WRITE_BLOCKING)
                }
            }
            Log.d(TAG, "Audio processing thread finished.")
        }
        audioProcessingThread?.name = "AudioProcessingFullMixThread"
        audioProcessingThread?.start()
        return true
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping all audio processing...")
        isCapturingAppAudio = false; isCapturingMicAudio = false; shouldBePlayingBasedOnFocus = false
        if (audioProcessingThread?.isAlive == true) {
            try { audioProcessingThread?.join(500) }
            catch (e: InterruptedException) { Log.w(TAG, "Interrupted joining audio thread", e); Thread.currentThread().interrupt() }
        }
        audioProcessingThread = null
        appAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }; appAudioRecord = null
        micAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }; micAudioRecord = null
        audioTrack?.apply { if (playState != AudioTrack.PLAYSTATE_STOPPED) { pause(); flush(); stop(); }; release() }; audioTrack = null
        Log.d(TAG, "All audio resources released.")
    }

    private fun startForegroundNotification() {
        val appText = if(isCapturingAppAudio && appAudioRecord != null) "App" else ""
        val micText = if(isCapturingMicAudio && micAudioRecord != null) "Mic" else ""
        val connector = if (appText.isNotEmpty() && micText.isNotEmpty()) " & " else ""
        val statusText = if (isProjectionSetup() || micAudioRecord != null) "Capturing: $appText$connector$micText" else "Ready"
        if (appText.isEmpty() && micText.isEmpty() && (isProjectionSetup() || micAudioRecord != null)) {
            // This case means setup might have been attempted but sources aren't active
            //statusText = "Capturing: (Error?)" // Or more specific
        }


        val channelId = "AudioLoopServiceChannel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW); notificationManager = getSystemService(NotificationManager::class.java); notificationManager?.createNotificationChannel(channel) }; val notification: Notification = NotificationCompat.Builder(this, channelId).setContentTitle("AudioLoop Service Active").setContentText(statusText).setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build(); try { startForeground(SERVICE_NOTIFICATION_ID, notification) } catch (e: Exception) { Log.e(TAG, "Error starting foreground", e) }
    }

    private fun clearProjectionAndAudioInstance() {
        Log.d(TAG, "Clearing MediaProjection and stopping audio instance.")
        stopAudioProcessing()
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop(); currentMediaProjection = null
        startForegroundNotification() // Update notification to "Ready"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")
        when (action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "ACTION_START_SERVICE received.")
                stopAudioProcessing()
                if (currentMediaProjection != null) { currentMediaProjection?.stop(); currentMediaProjection = null }
                _isRunning.postValue(true)
                startForegroundNotification()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "ACTION_STOP_SERVICE received.")
                _isRunning.postValue(false)
                clearProjectionAndAudioInstance()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            ACTION_SETUP_PROJECTION -> {
                Log.d(TAG, "ACTION_SETUP_PROJECTION received.")
                stopAudioProcessing()
                if (currentMediaProjection != null) { currentMediaProjection?.stop(); currentMediaProjection = null }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT) }

                var processingStarted = false
                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            if (startAudioProcessing()) processingStarted = true
                            else { currentMediaProjection?.stop(); currentMediaProjection = null }
                        } else Log.e(TAG, "getMediaProjection returned null.")
                    } catch (e: Exception) { Log.e(TAG, "Exception in ACTION_SETUP_PROJECTION", e) }
                } else Log.w(TAG, "ACTION_SETUP_PROJECTION: Failed. ResultCode: $resultCode")

                _isRunning.postValue(processingStarted)
                if (!processingStarted) clearProjectionAndAudioInstance() // Full cleanup if failed
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
        abandonAudioFocus()
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
