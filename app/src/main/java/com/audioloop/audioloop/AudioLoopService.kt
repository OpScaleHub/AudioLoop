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
import android.media.*
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
import androidx.lifecycle.Observer
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
    private val MIC_CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val PLAYBACK_CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val PLAYBACK_BYTES_PER_FRAME = 4 // Stereo PCM16

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val runningStateObserver = Observer<Boolean> { running ->
        Log.d(TAG, "AudioLoopService: _isRunning LiveData changed to: $running. Broadcasting update to FloatingControlsService.")
        val updateIntent = Intent(FloatingControlsService.ACTION_UPDATE_ICON)
        updateIntent.putExtra(FloatingControlsService.EXTRA_IS_RUNNING, running)
        sendBroadcast(updateIntent)
    }

    // Declare savedAudioMode here
    private var savedAudioMode: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioLoopService: onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _isRunning.postValue(false) // Initial state
        _isRunning.observeForever(runningStateObserver) // Start observing
        setupAudioFocusListener()
    }

    private fun setupAudioFocusListener() { /* ... same as before ... */
        onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "AudioLoopService: AudioFocusChanged: $focusChange")
            shouldBePlayingBasedOnFocus = focusChange == AudioManager.AUDIOFOCUS_GAIN
            if (shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) audioTrack?.play()
            else if (!shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { audioFocusRequestForListener = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(playbackAttributes).setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(onAudioFocusChangeListener).build(); audioManager.requestAudioFocus(audioFocusRequestForListener!!)
        } else { @Suppress("DEPRECATION") audioManager.requestAudioFocus(onAudioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) }
    }
    private fun requestAudioFocus(): Boolean { /* ... same ... */ Log.d(TAG, "AudioLoopService: Requesting AudioFocus"); shouldBePlayingBasedOnFocus = true; return true }
    private fun abandonAudioFocus() { /* ... same ... */ Log.d(TAG, "AudioLoopService: Abandoning AudioFocus"); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { audioFocusRequestForListener?.let { audioManager.abandonAudioFocusRequest(it) } } else { @Suppress("DEPRECATION") audioManager.abandonAudioFocus(onAudioFocusChangeListener) } }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioProcessing(): Boolean {
        Log.d(TAG, "AudioLoopService: Attempting startAudioProcessing...")
        this.savedAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "AudioLoopService: RECORD_AUDIO permission not granted."); _isRunning.postValue(false); return false }
        if (audioProcessingThread?.isAlive == true) { Log.w(TAG, "AudioLoopService: Thread already running."); return true }
        if (!requestAudioFocus()) { _isRunning.postValue(false); return false }

        var appRecordOk = true; var micRecordOk = false; var audioTrackOk = false
        var appRecordBufferSizeInBytes = 0; var micRecordBufferSizeInBytes = 0; var trackBufferSizeInBytes = 0

        if (currentMediaProjection != null) {
            val arFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(APP_CHANNEL_CONFIG_IN).build()
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build() // Added USAGE_VOICE_COMMUNICATION
            appRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
            if (appRecordBufferSizeInBytes > 0) { try { appAudioRecord = AudioRecord.Builder().setAudioFormat(arFormat).setAudioPlaybackCaptureConfig(captureConfig).setBufferSizeInBytes(appRecordBufferSizeInBytes).build(); if (appAudioRecord?.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioLoopService: App AR init failed."); appRecordOk = false } else { appRecordOk = true; } } catch (e: Exception) { Log.e(TAG, "AudioLoopService: App AR creation ex", e); appRecordOk = false } } else { Log.e(TAG, "AudioLoopService: App AR invalid buffer size"); appRecordOk = false } }
        else { Log.w(TAG, "AudioLoopService: MediaProjection not available. App audio capture skipped."); appAudioRecord = null; appRecordOk = true; }

        val micFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(MIC_CHANNEL_CONFIG_IN).build()
        micRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
        if (micRecordBufferSizeInBytes > 0) { try { micAudioRecord = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(micFormat).setBufferSizeInBytes(micRecordBufferSizeInBytes).build(); if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) micRecordOk = true else Log.e(TAG, "AudioLoopService: Mic AR init failed.") } catch (e: Exception) { Log.e(TAG, "AudioLoopService: Mic AR creation ex", e) } } else Log.e(TAG, "AudioLoopService: Mic AR invalid buffer size")

        val trackFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT).build()
        trackBufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING) * 2
        if (trackBufferSizeInBytes > 0) { try { audioTrack = AudioTrack.Builder().setAudioAttributes(playbackAttributes).setAudioFormat(trackFormat).setBufferSizeInBytes(trackBufferSizeInBytes).setTransferMode(AudioTrack.MODE_STREAM).build(); if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) audioTrackOk = true else Log.e(TAG, "AudioLoopService: AT init failed.") } catch (e: Exception) { Log.e(TAG, "AudioLoopService: AT creation ex", e) } } else Log.e(TAG, "AudioLoopService: AT invalid buffer size")

        if (!((appRecordOk && currentMediaProjection != null) || micRecordOk) || !audioTrackOk) {
            Log.e(TAG, "AudioLoopService: Audio setup failed. AppRec:$appRecordOk (Proj: ${currentMediaProjection!=null}), MicRec:$micRecordOk, Track:$audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null; micAudioRecord?.release(); micAudioRecord = null; audioTrack?.release(); audioTrack = null
            _isRunning.postValue(false); return false
        }

        isCapturingAppAudio = appAudioRecord != null && appRecordOk
        isCapturingMicAudio = micAudioRecord != null && micRecordOk

        if (!isCapturingAppAudio && !isCapturingMicAudio) { Log.e(TAG, "AudioLoopService: No audio source could be initialized."); _isRunning.postValue(false); return false }

        if (isCapturingAppAudio) appAudioRecord?.startRecording()
        if (isCapturingMicAudio) micAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "AudioLoopService: Sources (App: $isCapturingAppAudio, Mic: $isCapturingMicAudio) and track started.")
        _isRunning.postValue(true) // This will trigger the observer to broadcast

        audioProcessingThread = Thread { /* ... audio processing loop ... */
            Log.d(TAG, "AudioLoopService: Audio processing thread started.")
            val appBuf = if (isCapturingAppAudio && appAudioRecord != null && appRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val micBuf = if (isCapturingMicAudio && micAudioRecord != null && micRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val playbackBuf = if(audioTrackOk && trackBufferSizeInBytes > 0) ByteBuffer.allocateDirect(trackBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null

            if (playbackBuf == null) { Log.e(TAG, "AudioLoopService: Failed to allocate playbackBuf."); _isRunning.postValue(false); return@Thread }

            val appIsStereo = APP_CHANNEL_CONFIG_IN == AudioFormat.CHANNEL_IN_STEREO

            while ((isCapturingAppAudio || isCapturingMicAudio) && shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING && _isRunning.value == true) {
                var appBytesRead = 0
                if (isCapturingAppAudio && appAudioRecord != null && appBuf != null) {
                    appBuf.clear(); appBytesRead = appAudioRecord!!.read(appBuf, appBuf.capacity())
                    if (appBytesRead < 0) { Log.e(TAG, "App audio read error: $appBytesRead"); isCapturingAppAudio = false; } else if (appBytesRead > 0) appBuf.flip()
                }
                var micBytesRead = 0
                if (isCapturingMicAudio && micAudioRecord != null && micBuf != null) {
                    micBuf.clear(); micBytesRead = micAudioRecord!!.read(micBuf, micBuf.capacity())
                    if (micBytesRead < 0) { Log.e(TAG, "Mic audio read error: $micBytesRead"); isCapturingMicAudio = false; } else if (micBytesRead > 0) micBuf.flip()
                }
                if ((!isCapturingAppAudio || appBytesRead <= 0) && (!isCapturingMicAudio || micBytesRead <= 0)) {
                    if (!isCapturingAppAudio && !isCapturingMicAudio) break
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }; continue
                }
                playbackBuf.clear()
                val framesInPlaybackBuf = playbackBuf.capacity() / PLAYBACK_BYTES_PER_FRAME
                for (i in 0 until framesInPlaybackBuf) {
                    var appLeftShort: Short = 0; var appRightShort: Short = 0; var micMonoShort: Short = 0
                    var appSampleAvailable = false
                    if (isCapturingAppAudio && appBuf != null && appBytesRead > 0 && appBuf.remaining() >= (if (appIsStereo) 4 else 2) ) { appLeftShort = appBuf.short; appRightShort = if (appIsStereo) appBuf.short else appLeftShort; appSampleAvailable = true; }
                    var micSampleAvailable = false
                    if (isCapturingMicAudio && micBuf != null && micBytesRead > 0 && micBuf.remaining() >= 2 ) { micMonoShort = micBuf.short; micSampleAvailable = true; }
                    if (!appSampleAvailable && !micSampleAvailable) { playbackBuf.limit(playbackBuf.position()); break; }
                    val boostedMicInt = if (micSampleAvailable) (micMonoShort.toInt() * MIC_GAIN_FACTOR).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) else 0
                    val finalLeft = if (appSampleAvailable) (appLeftShort.toInt() + boostedMicInt).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() else boostedMicInt.toShort()
                    val finalRight = if (appSampleAvailable) (appRightShort.toInt() + boostedMicInt).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() else boostedMicInt.toShort()
                    playbackBuf.putShort(finalLeft); playbackBuf.putShort(finalRight)
                }
                playbackBuf.flip()
                if (playbackBuf.remaining() > 0) { val written = audioTrack?.write(playbackBuf, playbackBuf.remaining(), AudioTrack.WRITE_BLOCKING); if (written != null && written < 0) Log.e(TAG, "AudioTrack write error: $written") }
            }
            Log.d(TAG, "AudioLoopService: Audio processing thread finished. _isRunning.value: ${_isRunning.value}")
            if (_isRunning.value == true && !(isCapturingAppAudio || isCapturingMicAudio)) {
                _isRunning.postValue(false)
            }
        }.apply { name = "AudioLoopMixThread"; start() }
        return true
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "AudioLoopService: Stopping audio processing...")
        if (_isRunning.value == true) {
            _isRunning.postValue(false)
        }
        isCapturingAppAudio = false; isCapturingMicAudio = false;

        audioProcessingThread?.interrupt()
        try { audioProcessingThread?.join(500) }
        catch (e: InterruptedException) { Log.w(TAG, "AudioLoopService: Join interrupted", e); Thread.currentThread().interrupt() }
        audioProcessingThread = null

        appAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }; appAudioRecord = null
        micAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }; micAudioRecord = null
        audioTrack?.apply { if (playState != AudioTrack.PLAYSTATE_STOPPED) { try { pause() } catch (e: IllegalStateException) {} ; flush(); stop() }; release() }; audioTrack = null
        Log.d(TAG, "AudioLoopService: All audio resources released.")
    }

    private fun startForegroundNotification() {
        val statusText = if (_isRunning.value == true) "Looping Audio" else "Ready"
        val channelId = "AudioLoopServiceChannel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val channel = NotificationChannel(channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW); notificationManager = getSystemService(NotificationManager::class.java); notificationManager?.createNotificationChannel(channel) }; val notification: Notification = NotificationCompat.Builder(this, channelId).setContentTitle("AudioLoop").setContentText(statusText).setSmallIcon(R.mipmap.ic_launcher).setOngoing(true).build(); try { startForeground(SERVICE_NOTIFICATION_ID, notification) } catch (e: Exception) { Log.e(TAG, "AudioLoopService: Error starting fg", e) }
    }

    private fun clearProjectionAndAudioInstance() { Log.d(TAG, "AudioLoopService: Clearing MediaProjection and stopping audio."); stopAudioProcessing(); currentMediaProjection?.unregisterCallback(mediaProjectionCallback); currentMediaProjection?.stop(); currentMediaProjection = null; startForegroundNotification() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentAction = intent?.action // Use a local val for the action
        Log.d(TAG, "AudioLoopService: onStartCommand, action: $currentAction, current _isRunning: ${_isRunning.value}")
        when (currentAction) {
            ACTION_START_SERVICE -> { startForegroundNotification() }
            ACTION_PROCESS_AUDIO_START -> {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startAudioProcessing()
                } else { Log.e(TAG, "AudioLoopService: RECORD_AUDIO perm not granted for START."); _isRunning.postValue(false) }
                startForegroundNotification()
            }
            ACTION_PROCESS_AUDIO_STOP -> { stopAudioProcessing(); startForegroundNotification() }
            ACTION_STOP_SERVICE -> { clearProjectionAndAudioInstance(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_SETUP_PROJECTION -> {
                stopAudioProcessing()
                if (currentMediaProjection != null) { currentMediaProjection?.unregisterCallback(mediaProjectionCallback); currentMediaProjection?.stop(); currentMediaProjection = null }

                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_DATA_INTENT)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    try {
                        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                        if (projection != null) {
                            currentMediaProjection = projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.d(TAG, "AudioLoopService: MediaProjection obtained.")
                            _isRunning.postValue(false) // Update state as projection is set up but not yet looping
                        } else {
                            Log.e(TAG, "AudioLoopService: getMediaProjection returned null.")
                            _isRunning.postValue(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioLoopService: Ex in ACTION_SETUP_PROJECTION", e)
                        _isRunning.postValue(false)
                    }
                } else {
                    Log.w(TAG, "AudioLoopService: ACTION_SETUP_PROJECTION failed. ResultCode: $resultCode, Data is null: ${data==null}")
                    _isRunning.postValue(false)
                }
                startForegroundNotification()
            }
            ACTION_REQUEST_STATE -> {
                Log.d(TAG, "AudioLoopService: ACTION_REQUEST_STATE received. Current _isRunning: ${_isRunning.value}. Broadcasting to FloatingControlsService.")
                val updateIntent = Intent(FloatingControlsService.ACTION_UPDATE_ICON)
                updateIntent.putExtra(FloatingControlsService.EXTRA_IS_RUNNING, _isRunning.value ?: false)
                sendBroadcast(updateIntent)
            }
        }
        return START_NOT_STICKY
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() { override fun onStop() { super.onStop(); Log.w(TAG, "AudioLoopService: Projection stopped externally."); clearProjectionAndAudioInstance() } }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AudioLoopService: onDestroy. Removing LiveData observer.")
        _isRunning.removeObserver(runningStateObserver)
        abandonAudioFocus()
        clearProjectionAndAudioInstance()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AudioLoopApp" // Unified TAG
        private const val SERVICE_NOTIFICATION_ID = 12345
        private const val MIC_GAIN_FACTOR = 8
        const val ACTION_START_SERVICE = "com.audioloop.audioloop.ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.audioloop.audioloop.ACTION_STOP_SERVICE"
        const val ACTION_SETUP_PROJECTION = "com.audioloop.audioloop.ACTION_SETUP_PROJECTION"
        const val ACTION_PROCESS_AUDIO_START = "com.audioloop.audioloop.ACTION_PROCESS_AUDIO_START"
        const val ACTION_PROCESS_AUDIO_STOP = "com.audioloop.audioloop.ACTION_PROCESS_AUDIO_STOP"
        const val ACTION_REQUEST_STATE = "com.audioloop.audioloop.ACTION_REQUEST_STATE"
        const val EXTRA_RESULT_CODE = "com.audioloop.audioloop.EXTRA_RESULT_CODE"
        const val EXTRA_DATA_INTENT = "com.audioloop.audioloop.EXTRA_DATA_INTENT"
        var currentMediaProjection: MediaProjection? = null; private set
        private val _isRunning = MutableLiveData<Boolean>()
        val isRunning: LiveData<Boolean> = _isRunning
        fun isProjectionSetup(): Boolean = currentMediaProjection != null
    }
}
