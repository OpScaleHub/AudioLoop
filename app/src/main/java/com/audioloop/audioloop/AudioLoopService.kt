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

    private var appAudioRecord: AudioRecord? = null // Will be read but ignored for playback
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
    private val MIC_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val MIC_BYTES_PER_FRAME = 2 // Mono PCM16
    private val PLAYBACK_CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val PLAYBACK_BYTES_PER_FRAME = 4 // Stereo PCM16

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate (Boosted Mic Test)")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _isRunning.postValue(false)
        setupAudioFocusListener()
    }

    private fun setupAudioFocusListener() {
        onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            val action = when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
                AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
                else -> "Unknown focus change: $focusChange"
            }
            Log.d(TAG, "BoostedMicTest: $action")
            shouldBePlayingBasedOnFocus = focusChange == AudioManager.AUDIOFOCUS_GAIN
            if (shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) audioTrack?.play()
            else if (!shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestForListener = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes).setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(onAudioFocusChangeListener).build()
            audioManager.requestAudioFocus(audioFocusRequestForListener!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(onAudioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        Log.d(TAG, "BoostedMicTest: Registered 'passive' audio focus listener.")
    }

    private fun requestAudioFocus(): Boolean { shouldBePlayingBasedOnFocus = true; return true }
    private fun abandonAudioFocus() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { audioFocusRequestForListener?.let { audioManager.abandonAudioFocusRequest(it) } } else { @Suppress("DEPRECATION") audioManager.abandonAudioFocus(onAudioFocusChangeListener) }; Log.d(TAG, "BoostedMicTest: Passive listener abandoned.") }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioProcessing(): Boolean {
        Log.d(TAG, "BoostedMicTest: Attempting audio processing (Boosted Mic to Track)...")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "BoostedMicTest: RECORD_AUDIO permission not granted."); return false }
        if (audioProcessingThread?.isAlive == true) { Log.w(TAG, "BoostedMicTest: Thread already running."); return true }
        if (!requestAudioFocus()) { return false }

        var appRecordOk = true
        var micRecordOk = false
        var audioTrackOk = false
        var appRecordBufferSizeInBytes = 0
        var micRecordBufferSizeInBytes = 0
        var trackBufferSizeInBytes = 0

        if (currentMediaProjection != null) {
            val arFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(APP_CHANNEL_CONFIG_IN).build()
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
            appRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
            if (appRecordBufferSizeInBytes > 0) {
                try {
                    appAudioRecord = AudioRecord.Builder().setAudioFormat(arFormat).setAudioPlaybackCaptureConfig(captureConfig).setBufferSizeInBytes(appRecordBufferSizeInBytes).build()
                    if (appAudioRecord?.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "BoostedMicTest: App AR init failed."); appRecordOk = false }
                } catch (e: Exception) { Log.e(TAG, "BoostedMicTest: App AR creation ex", e); appRecordOk = false }
            } else { Log.e(TAG, "BoostedMicTest: App AR invalid buffer size"); appRecordOk = false }
        } else { Log.d(TAG, "BoostedMicTest: No MediaProjection, App AR not used for playback.") }

        val micFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(MIC_CHANNEL_CONFIG_IN).build()
        micRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
        if (micRecordBufferSizeInBytes > 0) {
            try {
                micAudioRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(micFormat).setBufferSizeInBytes(micRecordBufferSizeInBytes).build()
                if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) micRecordOk = true
                else Log.e(TAG, "BoostedMicTest: Mic AR init failed.")
            } catch (e: Exception) { Log.e(TAG, "BoostedMicTest: Mic AR creation ex", e) }
        } else Log.e(TAG, "BoostedMicTest: Mic AR invalid buffer size")

        val trackFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT).build()
        trackBufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING) * 2
        if (trackBufferSizeInBytes > 0) {
            try {
                audioTrack = AudioTrack.Builder().setAudioAttributes(playbackAttributes).setAudioFormat(trackFormat).setBufferSizeInBytes(trackBufferSizeInBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) audioTrackOk = true
                else Log.e(TAG, "BoostedMicTest: AT init failed.")
            } catch (e: Exception) { Log.e(TAG, "BoostedMicTest: AT creation ex", e) }
        } else Log.e(TAG, "BoostedMicTest: AT invalid buffer size")

        if (! (appRecordOk && micRecordOk && audioTrackOk) ) {
            Log.e(TAG, "BoostedMicTest: Audio setup failed. AppRec:$appRecordOk, MicRec:$micRecordOk, Track:$audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null; micAudioRecord?.release(); micAudioRecord = null; audioTrack?.release(); audioTrack = null
            return false
        }

        isCapturingAppAudio = appAudioRecord != null
        isCapturingMicAudio = micAudioRecord != null

        appAudioRecord?.startRecording() // Data will be drained
        micAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "BoostedMicTest: Sources and track started (Boosted Mic to Track Test).")

        audioProcessingThread = Thread {
            val appBuf = if (appAudioRecord != null && appRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val micBuf = if (micAudioRecord != null && micRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val playbackBuf = if(trackBufferSizeInBytes > 0) ByteBuffer.allocateDirect(trackBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null

            if (playbackBuf == null) { Log.e(TAG, "BoostedMicTest: Failed to allocate playbackBuf."); return@Thread }
            var logCounter = 0

            while (isCapturingMicAudio && shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                if (isCapturingAppAudio && appAudioRecord != null && appBuf != null) {
                    appBuf.clear(); appAudioRecord!!.read(appBuf, appBuf.capacity()) // Drain app audio
                }

                var micBytes = 0
                if (isCapturingMicAudio && micAudioRecord != null && micBuf != null) {
                    micBuf.clear()
                    micBytes = micAudioRecord!!.read(micBuf, micBuf.capacity())
                    if (micBytes < 0) { Log.e(TAG, "BoostedMicTest: Mic read error: $micBytes"); isCapturingMicAudio = false }
                    else if (micBytes > 0) micBuf.flip()
                }

                if (micBytes <= 0) {
                    if (!isCapturingMicAudio) break
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                    continue
                }

                playbackBuf.clear()
                val framesToProcess = playbackBuf.capacity() / PLAYBACK_BYTES_PER_FRAME

                for (i in 0 until framesToProcess) {
                    var micM: Short = 0
                    var originalMicM : Short = 0
                    if (micBuf != null && micBuf.remaining() >= MIC_BYTES_PER_FRAME) {
                        originalMicM = micBuf.short
                        micM = originalMicM
                    } else {
                        playbackBuf.limit(playbackBuf.position()); break
                    }

                    val boostedMicSample = (micM.toInt() * 8).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                    if (logCounter % 200 == 0 && originalMicM != 0.toShort()) { // Log non-zero original samples occasionally
                        Log.d(TAG, "BoostedMicTest: MicSample Original: $originalMicM, Boosted: $boostedMicSample")
                    }

                    playbackBuf.putShort(boostedMicSample)
                    playbackBuf.putShort(boostedMicSample)
                }
                logCounter++

                playbackBuf.flip()
                if (playbackBuf.remaining() > 0) {
                    audioTrack?.write(playbackBuf, playbackBuf.remaining(), AudioTrack.WRITE_BLOCKING)
                }
            }
            Log.d(TAG, "BoostedMicTest: Audio processing thread finished.")
        }
        audioProcessingThread?.name = "BoostedMicTestThread"
        audioProcessingThread?.start()
        return true
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "BoostedMicTest: Stopping audio processing...")
        isCapturingAppAudio = false; isCapturingMicAudio = false; shouldBePlayingBasedOnFocus = false
        if (audioProcessingThread?.isAlive == true) { try { audioProcessingThread?.join(500) } catch (e: InterruptedException) { Log.w(TAG, "BoostedMicTest: Join interrupted", e); Thread.currentThread().interrupt() } }
        audioProcessingThread = null
        appAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }; appAudioRecord = null
        micAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }; micAudioRecord = null
        audioTrack?.apply { if (playState != AudioTrack.PLAYSTATE_STOPPED) { pause(); flush(); stop(); }; release() }; audioTrack = null
        Log.d(TAG, "BoostedMicTest: All audio resources released.")
    }

    private fun startForegroundNotification() {
        val statusText = if (isCapturingMicAudio && micAudioRecord != null) "Testing Boosted Mic to Headphones" else "Ready (Boosted Mic Test)"
        val channelId = "AudioLoopServiceChannel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW); notificationManager = getSystemService(NotificationManager::class.java); notificationManager?.createNotificationChannel(channel) }; val notification: Notification = NotificationCompat.Builder(this, channelId).setContentTitle("AudioLoop (Boosted Mic Test)").setContentText(statusText).setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build(); try { startForeground(SERVICE_NOTIFICATION_ID, notification) } catch (e: Exception) { Log.e(TAG, "BoostedMicTest: Error starting fg", e) }
    }

    private fun clearProjectionAndAudioInstance() { Log.d(TAG, "BoostedMicTest: Clearing MediaProjection and stopping audio."); stopAudioProcessing(); currentMediaProjection?.unregisterCallback(mediaProjectionCallback); currentMediaProjection?.stop(); currentMediaProjection = null; startForegroundNotification() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action; Log.d(TAG, "BoostedMicTest: onStartCommand, action: $action")
        when (action) {
            ACTION_START_SERVICE -> { Log.d(TAG, "BoostedMicTest: ACTION_START_SERVICE"); stopAudioProcessing(); if (currentMediaProjection != null) { currentMediaProjection?.stop(); currentMediaProjection = null }; _isRunning.postValue(true); startForegroundNotification() }
            ACTION_STOP_SERVICE -> { Log.d(TAG, "BoostedMicTest: ACTION_STOP_SERVICE"); _isRunning.postValue(false); clearProjectionAndAudioInstance(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_SETUP_PROJECTION -> {
                Log.d(TAG, "BoostedMicTest: ACTION_SETUP_PROJECTION")
                stopAudioProcessing(); if (currentMediaProjection != null) { currentMediaProjection?.stop(); currentMediaProjection = null }
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA_INTENT)
                var processingStarted = false
                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection; currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            if (startAudioProcessing()) processingStarted = true
                            else { currentMediaProjection?.stop(); currentMediaProjection = null }
                        } else Log.e(TAG, "BoostedMicTest: getMediaProjection null.")
                    } catch (e: Exception) { Log.e(TAG, "BoostedMicTest: Ex in SETUP_PROJECTION", e) }
                } else Log.w(TAG, "BoostedMicTest: SETUP_PROJECTION Failed. Code: $resultCode")
                _isRunning.postValue(processingStarted)
                if (!processingStarted) clearProjectionAndAudioInstance()
                startForegroundNotification()
            }
        }
        return START_NOT_STICKY
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() { override fun onStop() { super.onStop(); Log.w(TAG, "BoostedMicTest: Projection stopped."); _isRunning.postValue(false); clearProjectionAndAudioInstance() } }
    override fun onDestroy() { super.onDestroy(); Log.d(TAG, "BoostedMicTest: Service onDestroy"); abandonAudioFocus(); clearProjectionAndAudioInstance(); _isRunning.postValue(false) }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AudioLoopService"
        // Other companion object members as before...
        private const val SERVICE_NOTIFICATION_ID = 12345
        const val ACTION_START_SERVICE = "com.audioloop.audioloop.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.audioloop.audioloop.ACTION_STOP_SERVICE"
        const val ACTION_SETUP_PROJECTION = "com.audioloop.audioloop.ACTION_SETUP_PROJECTION"
        const val EXTRA_RESULT_CODE = "com.audioloop.audioloop.EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "com.audioloop.audioloop.EXTRA_DATA_INTENT"
        var currentMediaProjection: MediaProjection? = null; private set
        private val _isRunning = MutableLiveData<Boolean>(); val isRunning: LiveData<Boolean> = _isRunning
        fun isProjectionSetup(): Boolean = currentMediaProjection != null
    }
}
