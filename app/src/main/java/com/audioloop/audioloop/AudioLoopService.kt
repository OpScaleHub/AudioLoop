package com.audioloop.audioloop

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.math.min

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
        val updateIntent = Intent(FloatingControlsService.ACTION_UPDATE_STATE)
        updateIntent.putExtra(FloatingControlsService.EXTRA_SERVICE_RUNNING, running)
        sendBroadcast(updateIntent)
    }

    // Declare savedAudioMode here
    private var savedAudioMode: Int = 0

    // UI Control States
    @Volatile private var masterVolume: Float = 0.5f // Default to 50%
    @Volatile private var isMicMuted: Boolean = false
    @Volatile private var micGain: Int = 0 // Default to 0dB, assuming 0-100 range from UI
    @Volatile private var appAudioGain: Int = 0 // Default to 0dB, assuming 0-100 range from UI


    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            Log.d(TAG, "AudioLoopService: Received broadcast: ${intent.action}")
            when (intent.action) {
                FloatingControlsService.ACTION_UPDATE_VOLUME -> {
                    val volume = intent.getIntExtra(FloatingControlsService.EXTRA_VOLUME_LEVEL, 50)
                    masterVolume = volume / 100f // Convert 0-100 to 0.0-1.0
                    audioTrack?.setVolume(masterVolume) // Apply master volume to AudioTrack
                    Log.d(TAG, "AudioLoopService: Master volume updated to $masterVolume")
                }
                FloatingControlsService.ACTION_SHARE -> {
                    Log.d(TAG, "AudioLoopService: Share action received. Implementing placeholder.")
                    // TODO: Implement actual sharing logic here (e.g., share a recorded file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Check out AudioLoop!")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share via"))
                }
                FloatingControlsService.ACTION_TOGGLE_MIC_MUTE -> {
                    val isMuted = intent.getBooleanExtra(FloatingControlsService.EXTRA_IS_MIC_MUTED, false)
                    isMicMuted = isMuted
                    Log.d(TAG, "AudioLoopService: Mic mute toggled to $isMicMuted")
                    // The mute logic will be applied in the audio processing thread
                }
                FloatingControlsService.ACTION_UPDATE_MIC_GAIN -> {
                    val gain = intent.getIntExtra(FloatingControlsService.EXTRA_MIC_GAIN_LEVEL, 0)
                    micGain = gain
                    Log.d(TAG, "AudioLoopService: Mic gain updated to $micGain")
                }
                FloatingControlsService.ACTION_UPDATE_APP_AUDIO_GAIN -> {
                    val gain = intent.getIntExtra(FloatingControlsService.EXTRA_APP_AUDIO_GAIN_LEVEL, 0)
                    appAudioGain = gain
                    Log.d(TAG, "AudioLoopService: App audio gain updated to $appAudioGain")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AudioLoopService: onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _isRunning.postValue(false) // Initial state
        _isRunning.observeForever(runningStateObserver) // Start observing
        setupAudioFocusListener()

        // Register the control receiver
        val filter = IntentFilter().apply {
            addAction(FloatingControlsService.ACTION_UPDATE_VOLUME)
            addAction(FloatingControlsService.ACTION_SHARE)
            addAction(FloatingControlsService.ACTION_TOGGLE_MIC_MUTE)
            addAction(FloatingControlsService.ACTION_UPDATE_MIC_GAIN)
            addAction(FloatingControlsService.ACTION_UPDATE_APP_AUDIO_GAIN)
        }
        registerReceiver(controlReceiver, filter)
    }

    private fun setupAudioFocusListener() {
        onAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "AudioLoopService: AudioFocusChanged: $focusChange")
            shouldBePlayingBasedOnFocus = focusChange == AudioManager.AUDIOFOCUS_GAIN
            if (shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED) audioTrack?.play()
            else if (!shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack?.pause()
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
    }

    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "AudioLoopService: Requesting AudioFocus")
        shouldBePlayingBasedOnFocus = true
        return true
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "AudioLoopService: Abandoning AudioFocus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestForListener?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(onAudioFocusChangeListener)
        }
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioProcessing(): Boolean {
        Log.d(TAG, "AudioLoopService: Attempting startAudioProcessing...")
        this.savedAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "AudioLoopService: RECORD_AUDIO permission not granted.")
            _isRunning.postValue(false)
            return false
        }
        if (audioProcessingThread?.isAlive == true) {
            Log.w(TAG, "AudioLoopService: Thread already running.")
            return true
        }
        if (!requestAudioFocus()) {
            _isRunning.postValue(false)
            return false
        }

        var appRecordOk = true
        var micRecordOk = false
        var audioTrackOk = false
        var appRecordBufferSizeInBytes = 0
        var micRecordBufferSizeInBytes = 0
        var trackBufferSizeInBytes = 0

        if (currentMediaProjection != null) {
            val arFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(APP_CHANNEL_CONFIG_IN).build()
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Added USAGE_VOICE_COMMUNICATION
                .build()
            appRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, APP_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
            if (appRecordBufferSizeInBytes > 0) {
                try {
                    appAudioRecord = AudioRecord.Builder()
                        .setAudioFormat(arFormat)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setBufferSizeInBytes(appRecordBufferSizeInBytes)
                        .build()
                    if (appAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioLoopService: App AR init failed.")
                        appRecordOk = false
                    } else {
                        appRecordOk = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioLoopService: App AR creation ex", e)
                    appRecordOk = false
                }
            } else {
                Log.e(TAG, "AudioLoopService: App AR invalid buffer size")
                appRecordOk = false
            }
        } else {
            Log.w(TAG, "AudioLoopService: MediaProjection not available. App audio capture skipped.")
            appAudioRecord = null
            appRecordOk = true
        }

        val micFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(MIC_CHANNEL_CONFIG_IN).build()
        micRecordBufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, MIC_CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING) * 2
        if (micRecordBufferSizeInBytes > 0) {
            try {
                micAudioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(micFormat)
                    .setBufferSizeInBytes(micRecordBufferSizeInBytes)
                    .build()
                if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) micRecordOk = true else Log.e(TAG, "AudioLoopService: Mic AR init failed.")
            } catch (e: Exception) {
                Log.e(TAG, "AudioLoopService: Mic AR creation ex", e)
            }
        } else {
            Log.e(TAG, "AudioLoopService: Mic AR invalid buffer size")
        }

        val trackFormat = AudioFormat.Builder().setEncoding(AUDIO_FORMAT_ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(PLAYBACK_CHANNEL_CONFIG_OUT).build()
        trackBufferSizeInBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, PLAYBACK_CHANNEL_CONFIG_OUT, AUDIO_FORMAT_ENCODING) * 2
        if (trackBufferSizeInBytes > 0) {
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(playbackAttributes)
                    .setAudioFormat(trackFormat)
                    .setBufferSizeInBytes(trackBufferSizeInBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) audioTrackOk = true else Log.e(TAG, "AudioLoopService: AT init failed.")
            } catch (e: Exception) {
                Log.e(TAG, "AudioLoopService: AT creation ex", e)
            }
        } else {
            Log.e(TAG, "AudioLoopService: AT invalid buffer size")
        }

        if (!((appRecordOk && currentMediaProjection != null) || micRecordOk) || !audioTrackOk) {
            Log.e(TAG, "AudioLoopService: Audio setup failed. AppRec:$appRecordOk (Proj: ${currentMediaProjection!=null}), MicRec:$micRecordOk, Track:$audioTrackOk")
            appAudioRecord?.release(); appAudioRecord = null
            micAudioRecord?.release(); micAudioRecord = null
            audioTrack?.release(); audioTrack = null
            _isRunning.postValue(false)
            return false
        }

        isCapturingAppAudio = appAudioRecord != null && appRecordOk
        isCapturingMicAudio = micAudioRecord != null && micRecordOk

        if (!isCapturingAppAudio && !isCapturingMicAudio) {
            Log.e(TAG, "AudioLoopService: No audio source could be initialized.")
            _isRunning.postValue(false)
            return false
        }

        if (isCapturingAppAudio) appAudioRecord?.startRecording()
        if (isCapturingMicAudio) micAudioRecord?.startRecording()
        audioTrack?.play()
        Log.d(TAG, "AudioLoopService: Sources (App: $isCapturingAppAudio, Mic: $isCapturingMicAudio) and track started.")
        _isRunning.postValue(true) // This will trigger the observer to broadcast

        audioProcessingThread = Thread {
            Log.d(TAG, "AudioLoopService: Audio processing thread started.")
            val appBuf = if (isCapturingAppAudio && appAudioRecord != null && appRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(appRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val micBuf = if (isCapturingMicAudio && micAudioRecord != null && micRecordBufferSizeInBytes > 0) ByteBuffer.allocateDirect(micRecordBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null
            val playbackBuf = if(audioTrackOk && trackBufferSizeInBytes > 0) ByteBuffer.allocateDirect(trackBufferSizeInBytes).order(ByteOrder.LITTLE_ENDIAN) else null

            if (playbackBuf == null) {
                Log.e(TAG, "AudioLoopService: Failed to allocate playbackBuf.")
                _isRunning.postValue(false)
                return@Thread
            }

            val appIsStereo = APP_CHANNEL_CONFIG_IN == AudioFormat.CHANNEL_IN_STEREO

            while ((isCapturingAppAudio || isCapturingMicAudio) && shouldBePlayingBasedOnFocus && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING && _isRunning.value == true) {
                var appBytesRead = 0
                var micBytesRead = 0

                // Prepare buffers for reading
                appBuf?.clear()
                micBuf?.clear()

                if (isCapturingAppAudio && appAudioRecord != null) {
                    appBytesRead = appAudioRecord!!.read(appBuf!!, appBuf!!.capacity())
                    if (appBytesRead < 0) {
                        Log.e(TAG, "App audio read error: $appBytesRead")
                        isCapturingAppAudio = false
                    } else if (appBytesRead > 0) {
                        appBuf.limit(appBytesRead) // Set limit to the number of bytes read
                    }
                }

                if (isCapturingMicAudio && micAudioRecord != null && !isMicMuted) { // Apply mic mute here
                    micBytesRead = micAudioRecord!!.read(micBuf!!, micBuf!!.capacity())
                    if (micBytesRead < 0) {
                        Log.e(TAG, "Mic audio read error: $micBytesRead")
                        isCapturingMicAudio = false
                    } else if (micBytesRead > 0) {
                        micBuf.limit(micBytesRead) // Set limit to the number of bytes read
                    }
                }

                // If no data was read from either source, sleep briefly and continue
                if ((!isCapturingAppAudio || appBytesRead <= 0) && (!isCapturingMicAudio || micBytesRead <= 0)) {
                    if (!isCapturingAppAudio && !isCapturingMicAudio) break // Exit if both sources are gone
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                    continue
                }

                // Prepare playback buffer for writing mixed audio
                playbackBuf.clear()

                // Calculate how many frames can be processed based on the *minimum* data available from active sources
                val appFramesAvailable = if (isCapturingAppAudio && appBuf != null && appBytesRead > 0) appBytesRead / (if (appIsStereo) 4 else 2) else 0
                val micFramesAvailable = if (isCapturingMicAudio && micBuf != null && micBytesRead > 0) micBytesRead / 2 else 0 // Mic is mono, 2 bytes per frame

                val framesToProcess = when {
                    isCapturingAppAudio && isCapturingMicAudio -> min(appFramesAvailable, micFramesAvailable)
                    isCapturingAppAudio -> appFramesAvailable
                    isCapturingMicAudio -> micFramesAvailable
                    else -> 0
                }

                val bytesToWrite = framesToProcess * PLAYBACK_BYTES_PER_FRAME
                if (bytesToWrite > 0) {
                    playbackBuf.limit(bytesToWrite) // Ensure we don't write more than calculated

                    for (i in 0 until framesToProcess) {
                        var appLeftShort: Short = 0
                        var appRightShort: Short = 0
                        var micMonoShort: Short = 0

                        var appSampleAvailable = false
                        if (isCapturingAppAudio && appBuf != null && appBuf.hasRemaining()) {
                            appLeftShort = appBuf.short
                            appRightShort = if (appIsStereo) appBuf.short else appLeftShort
                            appSampleAvailable = true
                        }

                        var micSampleAvailable = false
                        if (isCapturingMicAudio && micBuf != null && micBuf.hasRemaining()) {
                            micMonoShort = micBuf.short
                            micSampleAvailable = true
                        }

                        // Apply individual channel gains and mix audio
                        // Convert UI gain (0-100) to a factor (e.g., 0.0 to 2.0)
                        val micGainFactor = 1.0f + (micGain / 100f) // Example: 0 (1.0x) to 100 (2.0x)
                        val appAudioGainFactor = 1.0f + (appAudioGain / 100f) // Example: 0 (1.0x) to 100 (2.0x)

                        val boostedMicInt = if (micSampleAvailable) (micMonoShort.toInt() * micGainFactor) else 0f
                        val boostedAppLeftInt = if (appSampleAvailable) (appLeftShort.toInt() * appAudioGainFactor) else 0f
                        val boostedAppRightInt = if (appSampleAvailable) (appRightShort.toInt() * appAudioGainFactor) else 0f


                        val finalLeft = (boostedAppLeftInt + boostedMicInt).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                        val finalRight = (boostedAppRightInt + boostedMicInt).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

                        playbackBuf.putShort(finalLeft)
                        playbackBuf.putShort(finalRight)
                    }

                    // Copy data from ByteBuffer to ByteArray for writing
                    val audioDataByteArray = ByteArray(playbackBuf.remaining())
                    playbackBuf.get(audioDataByteArray)

                    // Write the mixed audio to the AudioTrack using the ByteArray overload
                    val written = audioTrack?.write(audioDataByteArray, 0, audioDataByteArray.size)
                    if (written != null && written < 0) {
                        Log.e(TAG, "AudioTrack write error: $written")
                    }
                } else {
                    // If no frames could be processed, sleep briefly to avoid busy-waiting
                    try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
            Log.d(TAG, "AudioLoopService: Audio processing thread finished. _isRunning.value: ${_isRunning.value}")
            // If the service is still supposed to be running but no audio sources are available, stop it.
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
        isCapturingAppAudio = false
        isCapturingMicAudio = false

        audioProcessingThread?.interrupt()
        try {
            audioProcessingThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "AudioLoopService: Join interrupted", e)
            Thread.currentThread().interrupt()
        }
        audioProcessingThread = null

        appAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }
        appAudioRecord = null
        micAudioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }
        micAudioRecord = null
        audioTrack?.apply {
            if (playState != AudioTrack.PLAYSTATE_STOPPED) {
                try { pause() } catch (e: IllegalStateException) {}
                flush()
                stop()
            }
            release()
        }
        audioTrack = null
        Log.d(TAG, "AudioLoopService: All audio resources released.")
    }

    private fun startForegroundNotification() {
        val statusText = if (_isRunning.value == true) "Looping Audio" else "Ready"
        val channelId = "AudioLoopServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Audio Loop Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AudioLoop")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "AudioLoopService: Error starting fg", e)
        }
    }

    private fun clearProjectionAndAudioInstance() {
        Log.d(TAG, "AudioLoopService: Clearing MediaProjection and stopping audio.")
        stopAudioProcessing()
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop()
        currentMediaProjection = null
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentAction = intent?.action // Use a local val for the action
        Log.d(TAG, "AudioLoopService: onStartCommand, action: $currentAction, current _isRunning: ${_isRunning.value}")
        when (currentAction) {
            ACTION_START_SERVICE -> {
                startForegroundNotification()
            }
            ACTION_PROCESS_AUDIO_START -> {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startAudioProcessing()
                } else {
                    Log.e(TAG, "AudioLoopService: RECORD_AUDIO perm not granted for START.")
                    _isRunning.postValue(false)
                }
                startForegroundNotification()
            }
            ACTION_PROCESS_AUDIO_STOP -> {
                stopAudioProcessing()
                startForegroundNotification()
            }
            ACTION_STOP_SERVICE -> {
                clearProjectionAndAudioInstance()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SETUP_PROJECTION -> {
                stopAudioProcessing()
                if (currentMediaProjection != null) {
                    currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                    currentMediaProjection?.stop()
                    currentMediaProjection = null
                }

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
                val updateIntent = Intent(FloatingControlsService.ACTION_UPDATE_STATE)
                updateIntent.putExtra(FloatingControlsService.EXTRA_SERVICE_RUNNING, _isRunning.value ?: false)
                sendBroadcast(updateIntent)
            }
        }
        return START_NOT_STICKY
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "AudioLoopService: Projection stopped externally.")
            clearProjectionAndAudioInstance()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AudioLoopService: onDestroy. Removing LiveData observer.")
        _isRunning.removeObserver(runningStateObserver)
        abandonAudioFocus()
        clearProjectionAndAudioInstance()
        unregisterReceiver(controlReceiver) // Unregister the receiver
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AudioLoopApp" // Unified TAG
        private const val SERVICE_NOTIFICATION_ID = 12345
        // private const val MIC_GAIN_FACTOR = 8 // Removed, now dynamic
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