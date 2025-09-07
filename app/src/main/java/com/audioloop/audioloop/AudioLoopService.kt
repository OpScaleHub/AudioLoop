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

// NO CLASS-LEVEL @RequiresPermission HERE
class AudioLoopService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var notificationManager: NotificationManager? = null

    private var audioRecord: AudioRecord? = null
    private var audioCaptureThread: Thread? = null
    @Volatile private var isCapturingAudio: Boolean = false // CORRECTED @Volatile

    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @RequiresPermission(Manifest.permission.RECORD_AUDIO) // THIS ONE STAYS on the method
    private fun startLoopbackAudio() {
        Log.d(TAG, "Attempting to start loopback audio capture...")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted to service. Cannot start audio capture.")
            return
        }

        if (currentMediaProjection == null) {
            Log.e(TAG, "MediaProjection is not available. Cannot start audio capture.")
            return
        }

        if (isCapturingAudio) {
            Log.w(TAG, "Audio capture is already in progress.")
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_FORMAT_ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG_IN)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT_ENCODING)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio parameters for getMinBufferSize.")
            return
        }
        val bufferSizeInBytes = minBufferSize * 2

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord: ${e.message}", e)
            return
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "UnsupportedOperationException creating AudioRecord: ${e.message}. Playback capture not supported?", e)
            return
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException creating AudioRecord: ${e.message}", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.")
            audioRecord?.release()
            audioRecord = null
            return
        }

        Log.d(TAG, "AudioRecord initialized. Min buffer size: $minBufferSize, Used buffer size: $bufferSizeInBytes")

        isCapturingAudio = true
        audioRecord?.startRecording()
        Log.d(TAG, "AudioRecord started recording.")

        audioCaptureThread = Thread {
            val audioBuffer = ByteArray(bufferSizeInBytes)
            while (isCapturingAudio && audioRecord != null && audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead != null && bytesRead > 0) {
                    // TODO: Process the audioBuffer
                } else if (bytesRead != null && bytesRead < 0) {
                    Log.e(TAG, "Error reading audio data: $bytesRead")
                }
            }
            Log.d(TAG, "Audio capture thread finished.")
        }
        audioCaptureThread?.name = "AudioCaptureThread"
        audioCaptureThread?.start()
        Log.d(TAG, "Audio capture thread started.")
    }

    private fun stopLoopbackAudio() {
        Log.d(TAG, "Stopping loopback audio capture...")
        if (!isCapturingAudio && audioRecord == null) {
            Log.d(TAG, "Audio capture not running or already stopped.")
            return
        }

        isCapturingAudio = false

        if (audioCaptureThread?.isAlive == true) {
            try {
                audioCaptureThread?.join(500)
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
        Log.d(TAG, "AudioRecord released.")
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
        stopLoopbackAudio()
        currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
        currentMediaProjection?.stop()
        currentMediaProjection = null
        _isRunning.postValue(isProjectionSetup()) // CRITICAL FIX HERE: Use a non-null value
        startForegroundNotification()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        _isRunning.postValue(false) // Initialize with a non-null value
        clearProjectionAndAudioInstance()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NO CLASS-LEVEL @RequiresPermission HERE
        class AudioLoopService : Service() {

            private lateinit var mediaProjectionManager: MediaProjectionManager
            private var notificationManager: NotificationManager? = null

            private var audioRecord: AudioRecord? = null
            private var audioCaptureThread: Thread? = null
            @Volatile
            private var isCapturingAudio: Boolean = false // CORRECTED @Volatile

            private val SAMPLE_RATE = 44100
            private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
            private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

            @RequiresPermission(Manifest.permission.RECORD_AUDIO) // THIS ONE STAYS on the method
            private fun startLoopbackAudio() {
                Log.d(TAG, "Attempting to start loopback audio capture...")

                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(
                        TAG,
                        "RECORD_AUDIO permission not granted to service. Cannot start audio capture."
                    )
                    return
                }

                if (currentMediaProjection == null) {
                    Log.e(TAG, "MediaProjection is not available. Cannot start audio capture.")
                    return
                }

                if (isCapturingAudio) {
                    Log.w(TAG, "Audio capture is already in progress.")
                    return
                }

                val config = AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT_ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_IN)
                    .build()

                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT_ENCODING
                )
                if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid audio parameters for getMinBufferSize.")
                    return
                }
                val bufferSizeInBytes = minBufferSize * 2

                try {
                    audioRecord = AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setAudioPlaybackCaptureConfig(config)
                        .setBufferSizeInBytes(bufferSizeInBytes)
                        .build()
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException creating AudioRecord: ${e.message}", e)
                    return
                } catch (e: UnsupportedOperationException) {
                    Log.e(
                        TAG,
                        "UnsupportedOperationException creating AudioRecord: ${e.message}. Playback capture not supported?",
                        e
                    )
                    return
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "IllegalArgumentException creating AudioRecord: ${e.message}", e)
                    return
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize.")
                    audioRecord?.release()
                    audioRecord = null
                    return
                }

                Log.d(
                    TAG,
                    "AudioRecord initialized. Min buffer size: $minBufferSize, Used buffer size: $bufferSizeInBytes"
                )

                isCapturingAudio = true
                audioRecord?.startRecording()
                Log.d(TAG, "AudioRecord started recording.")

                audioCaptureThread = Thread {
                    val audioBuffer = ByteArray(bufferSizeInBytes)
                    while (isCapturingAudio && audioRecord != null && audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val bytesRead = audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                        if (bytesRead != null && bytesRead > 0) {
                            // TODO: Process the audioBuffer
                        } else if (bytesRead != null && bytesRead < 0) {
                            Log.e(TAG, "Error reading audio data: $bytesRead")
                        }
                    }
                    Log.d(TAG, "Audio capture thread finished.")
                }
                audioCaptureThread?.name = "AudioCaptureThread"
                audioCaptureThread?.start()
                Log.d(TAG, "Audio capture thread started.")
            }

            private fun stopLoopbackAudio() {
                Log.d(TAG, "Stopping loopback audio capture...")
                if (!isCapturingAudio && audioRecord == null) {
                    Log.d(TAG, "Audio capture not running or already stopped.")
                    return
                }

                isCapturingAudio = false

                if (audioCaptureThread?.isAlive == true) {
                    try {
                        audioCaptureThread?.join(500)
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
                Log.d(TAG, "AudioRecord released.")
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
                stopLoopbackAudio()
                currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                currentMediaProjection?.stop()
                currentMediaProjection = null
                _isRunning.postValue(isProjectionSetup()) // CRITICAL FIX HERE: Use a non-null value
                startForegroundNotification()
            }

            override fun onCreate() {
                super.onCreate()
                Log.d(TAG, "Service onCreate")
                mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                notificationManager =
                    getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                _isRunning.postValue(false) // Initialize with a non-null value
                clearProjectionAndAudioInstance()
            }

            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                val action = intent?.action
                Log.d(TAG, "onStartCommand, action: $action")

                when (action) {
                    ACTION_START_SERVICE -> {
                        Log.d(
                            TAG,
                            "ACTION_START_SERVICE received. Cleaning up any existing session."
                        )
                        stopLoopbackAudio() // Stop any current audio capture
                        if (currentMediaProjection != null) {
                            Log.d(TAG, "Clearing existing MediaProjection.")
                            currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                            currentMediaProjection?.stop()
                            currentMediaProjection = null
                        }
                        _isRunning.postValue(true) // Explicitly set to true after cleanup
                        startForegroundNotification() // Update notification (will show "Ready to select app")
                    }

                    ACTION_STOP_SERVICE -> {
                        _isRunning.postValue(false)
                        clearProjectionAndAudioInstance()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    ACTION_SETUP_PROJECTION -> {
                        if (_isRunning.value != true) {
                            Log.w(
                                TAG,
                                "ACTION_SETUP_PROJECTION received but service is not marked as running. Starting service first."
                            )
                            _isRunning.postValue(true)
                        }
                        startForegroundNotification()

                        val resultCode =
                            intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                        val data: Intent? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(EXTRA_DATA_INTENT)
                            }

                        if (resultCode == Activity.RESULT_OK && data != null) {
                            try {
                                val projection =
                                    mediaProjectionManager.getMediaProjection(resultCode, data)
                                if (projection != null) {
                                    // It's good practice to clear any old projection just in case,
                                    // though ACTION_START_SERVICE should have handled most scenarios.
                                    // clearProjectionAndAudioInstance() // This might be too aggressive here if called right after start
                                    currentMediaProjection = projection // Assign new projection
                                    currentMediaProjection?.registerCallback(
                                        mediaProjectionCallback,
                                        null
                                    )
                                    Log.i(TAG, "MediaProjection obtained and stored.")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        startLoopbackAudio()
                                    } else {
                                        Log.w(
                                            TAG,
                                            "AudioPlaybackCaptureConfiguration requires Android Q (API 29)+"
                                        )
                                    }
                                } else {
                                    Log.e(
                                        TAG,
                                        "getMediaProjection returned null despite RESULT_OK."
                                    )
                                    clearProjectionAndAudioInstance() // Clear if projection is null
                                }
                            } catch (e: SecurityException) {
                                Log.e(
                                    TAG,
                                    "SecurityException getting MediaProjection. Is service in foreground?",
                                    e
                                )
                                clearProjectionAndAudioInstance()
                            } catch (e: Exception) {
                                Log.e(TAG, "Exception obtaining MediaProjection", e)
                                clearProjectionAndAudioInstance()
                            }
                        } else {
                            Log.w(
                                TAG,
                                "ACTION_SETUP_PROJECTION: Failed to get valid data. ResultCode: $resultCode"
                            )
                            clearProjectionAndAudioInstance()
                        }
                        startForegroundNotification() // Update notification text
                        // Post the current actual state after attempting projection setup
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
                const val ACTION_SETUP_PROJECTION =
                    "com.audioloop.audioloop.ACTION_SETUP_PROJECTION"

                const val EXTRA_RESULT_CODE = "com.audioloop.audioloop.EXTRA_RESULT_CODE"
                const val EXTRA_DATA_INTENT = "com.audioloop.audioloop.EXTRA_DATA_INTENT"

                var currentMediaProjection: MediaProjection? = null
                    private set

                private val _isRunning = MutableLiveData<Boolean>()
                val isRunning: LiveData<Boolean> = _isRunning

                fun isProjectionSetup(): Boolean {
                    return currentMediaProjection != null
                }

                fun clearStaticProjectionReference() {
                    Log.d(TAG, "Clearing static MediaProjection reference.")
                    currentMediaProjection?.stop() // Stop if not already
                    currentMediaProjection = null
                }
            }
        }
        val action = intent?.action
        Log.d(TAG, "onStartCommand, action: $action")

        when (action) {
            ACTION_START_SERVICE -> {
                _isRunning.postValue(true)
                clearProjectionAndAudioInstance()
                startForegroundNotification()
            }
            ACTION_STOP_SERVICE -> {
                _isRunning.postValue(false)
                clearProjectionAndAudioInstance()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SETUP_PROJECTION -> {
                if (_isRunning.value != true) {
                    // NO CLASS-LEVEL @RequiresPermission HERE
                    class AudioLoopService : Service() {

                        private lateinit var mediaProjectionManager: MediaProjectionManager
                        private var notificationManager: NotificationManager? = null

                        private var audioRecord: AudioRecord? = null
                        private var audioCaptureThread: Thread? = null
                        @Volatile
                        private var isCapturingAudio: Boolean = false // CORRECTED @Volatile

                        private val SAMPLE_RATE = 44100
                        private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
                        private val AUDIO_FORMAT_ENCODING = AudioFormat.ENCODING_PCM_16BIT

                        @RequiresPermission(Manifest.permission.RECORD_AUDIO) // THIS ONE STAYS on the method
                        private fun startLoopbackAudio() {
                            Log.d(TAG, "Attempting to start loopback audio capture...")

                            if (ActivityCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.RECORD_AUDIO
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                Log.e(
                                    TAG,
                                    "RECORD_AUDIO permission not granted to service. Cannot start audio capture."
                                )
                                return
                            }

                            if (currentMediaProjection == null) {
                                Log.e(
                                    TAG,
                                    "MediaProjection is not available. Cannot start audio capture."
                                )
                                return
                            }

                            if (isCapturingAudio) {
                                Log.w(TAG, "Audio capture is already in progress.")
                                return
                            }

                            val config =
                                AudioPlaybackCaptureConfiguration.Builder(currentMediaProjection!!)
                                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                    .build()

                            val audioFormat = AudioFormat.Builder()
                                .setEncoding(AUDIO_FORMAT_ENCODING)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(CHANNEL_CONFIG_IN)
                                .build()

                            val minBufferSize = AudioRecord.getMinBufferSize(
                                SAMPLE_RATE,
                                CHANNEL_CONFIG_IN,
                                AUDIO_FORMAT_ENCODING
                            )
                            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                                Log.e(TAG, "Invalid audio parameters for getMinBufferSize.")
                                return
                            }
                            val bufferSizeInBytes = minBufferSize * 2

                            try {
                                audioRecord = AudioRecord.Builder()
                                    .setAudioFormat(audioFormat)
                                    .setAudioPlaybackCaptureConfig(config)
                                    .setBufferSizeInBytes(bufferSizeInBytes)
                                    .build()
                            } catch (e: SecurityException) {
                                Log.e(
                                    TAG,
                                    "SecurityException creating AudioRecord: ${e.message}",
                                    e
                                )
                                return
                            } catch (e: UnsupportedOperationException) {
                                Log.e(
                                    TAG,
                                    "UnsupportedOperationException creating AudioRecord: ${e.message}. Playback capture not supported?",
                                    e
                                )
                                return
                            } catch (e: IllegalArgumentException) {
                                Log.e(
                                    TAG,
                                    "IllegalArgumentException creating AudioRecord: ${e.message}",
                                    e
                                )
                                return
                            }

                            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                                Log.e(TAG, "AudioRecord failed to initialize.")
                                audioRecord?.release()
                                audioRecord = null
                                return
                            }

                            Log.d(
                                TAG,
                                "AudioRecord initialized. Min buffer size: $minBufferSize, Used buffer size: $bufferSizeInBytes"
                            )

                            isCapturingAudio = true
                            audioRecord?.startRecording()
                            Log.d(TAG, "AudioRecord started recording.")

                            audioCaptureThread = Thread {
                                val audioBuffer = ByteArray(bufferSizeInBytes)
                                while (isCapturingAudio && audioRecord != null && audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                    val bytesRead =
                                        audioRecord?.read(audioBuffer, 0, audioBuffer.size)
                                    if (bytesRead != null && bytesRead > 0) {
                                        // TODO: Process the audioBuffer
                                    } else if (bytesRead != null && bytesRead < 0) {
                                        Log.e(TAG, "Error reading audio data: $bytesRead")
                                    }
                                }
                                Log.d(TAG, "Audio capture thread finished.")
                            }
                            audioCaptureThread?.name = "AudioCaptureThread"
                            audioCaptureThread?.start()
                            Log.d(TAG, "Audio capture thread started.")
                        }

                        private fun stopLoopbackAudio() {
                            Log.d(TAG, "Stopping loopback audio capture...")
                            if (!isCapturingAudio && audioRecord == null) {
                                Log.d(TAG, "Audio capture not running or already stopped.")
                                return
                            }

                            isCapturingAudio = false

                            if (audioCaptureThread?.isAlive == true) {
                                try {
                                    audioCaptureThread?.join(500)
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
                                    Log.e(
                                        TAG,
                                        "IllegalStateException stopping AudioRecord: ${e.message}",
                                        e
                                    )
                                }
                            }
                            audioRecord?.release()
                            audioRecord = null
                            Log.d(TAG, "AudioRecord released.")
                        }

                        private fun startForegroundNotification() {
                            val channelId = "AudioLoopServiceChannel"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val channel = NotificationChannel(
                                    channelId,
                                    "Audio Loop Service",
                                    NotificationManager.IMPORTANCE_LOW
                                )
                                notificationManager =
                                    getSystemService(NotificationManager::class.java)
                                notificationManager?.createNotificationChannel(channel)
                            }

                            val notification: Notification =
                                NotificationCompat.Builder(this, channelId)
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
                            stopLoopbackAudio()
                            currentMediaProjection?.unregisterCallback(mediaProjectionCallback)
                            currentMediaProjection?.stop()
                            currentMediaProjection = null
                            _isRunning.postValue(isProjectionSetup()) // CRITICAL FIX HERE: Use a non-null value
                            startForegroundNotification()
                        }

                        override fun onCreate() {
                            super.onCreate()
                            Log.d(TAG, "Service onCreate")
                            mediaProjectionManager =
                                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            notificationManager =
                                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            _isRunning.postValue(false) // Initialize with a non-null value
                            clearProjectionAndAudioInstance()
                        }

                        override fun onStartCommand(
                            intent: Intent?,
                            flags: Int,
                            startId: Int
                        ): Int {
                            val action = intent?.action
                            Log.d(TAG, "onStartCommand, action: $action")

                            when (action) {
                                ACTION_START_SERVICE -> {
                                    Log.d(
                                        TAG,
                                        "ACTION_START_SERVICE received. Cleaning up any existing session."
                                    )
                                    stopLoopbackAudio() // Stop any current audio capture
                                    if (currentMediaProjection != null) {
                                        Log.d(TAG, "Clearing existing MediaProjection.")
                                        currentMediaProjection?.unregisterCallback(
                                            mediaProjectionCallback
                                        )
                                        currentMediaProjection?.stop()
                                        currentMediaProjection = null
                                    }
                                    _isRunning.postValue(true) // Explicitly set to true after cleanup
                                    startForegroundNotification() // Update notification (will show "Ready to select app")
                                }

                                ACTION_STOP_SERVICE -> {
                                    _isRunning.postValue(false)
                                    clearProjectionAndAudioInstance()
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }

                                ACTION_SETUP_PROJECTION -> {
                                    if (_isRunning.value != true) {
                                        Log.w(
                                            TAG,
                                            "ACTION_SETUP_PROJECTION received but service is not marked as running. Starting service first."
                                        )
                                        _isRunning.postValue(true)
                                    }
                                    startForegroundNotification()

                                    val resultCode = intent.getIntExtra(
                                        EXTRA_RESULT_CODE,
                                        Activity.RESULT_CANCELED
                                    )
                                    val data: Intent? =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            intent.getParcelableExtra(
                                                EXTRA_DATA_INTENT,
                                                Intent::class.java
                                            )
                                        } else {
                                            @Suppress("DEPRECATION")
                                            intent.getParcelableExtra(EXTRA_DATA_INTENT)
                                        }

                                    if (resultCode == Activity.RESULT_OK && data != null) {
                                        try {
                                            val projection =
                                                mediaProjectionManager.getMediaProjection(
                                                    resultCode,
                                                    data
                                                )
                                            if (projection != null) {
                                                // It's good practice to clear any old projection just in case,
                                                // though ACTION_START_SERVICE should have handled most scenarios.
                                                // clearProjectionAndAudioInstance() // This might be too aggressive here if called right after start
                                                currentMediaProjection =
                                                    projection // Assign new projection
                                                currentMediaProjection?.registerCallback(
                                                    mediaProjectionCallback,
                                                    null
                                                )
                                                Log.i(TAG, "MediaProjection obtained and stored.")
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                    startLoopbackAudio()
                                                } else {
                                                    Log.w(
                                                        TAG,
                                                        "AudioPlaybackCaptureConfiguration requires Android Q (API 29)+"
                                                    )
                                                }
                                            } else {
                                                Log.e(
                                                    TAG,
                                                    "getMediaProjection returned null despite RESULT_OK."
                                                )
                                                clearProjectionAndAudioInstance() // Clear if projection is null
                                            }
                                        } catch (e: SecurityException) {
                                            Log.e(
                                                TAG,
                                                "SecurityException getting MediaProjection. Is service in foreground?",
                                                e
                                            )
                                            clearProjectionAndAudioInstance()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Exception obtaining MediaProjection", e)
                                            clearProjectionAndAudioInstance()
                                        }
                                    } else {
                                        Log.w(
                                            TAG,
                                            "ACTION_SETUP_PROJECTION: Failed to get valid data. ResultCode: $resultCode"
                                        )
                                        clearProjectionAndAudioInstance()
                                    }
                                    startForegroundNotification() // Update notification text
                                    // Post the current actual state after attempting projection setup
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

                            const val ACTION_START_SERVICE =
                                "com.audioloop.audioloop.ACTION_START_SERVICE"
                            const val ACTION_STOP_SERVICE =
                                "com.audioloop.audioloop.ACTION_STOP_SERVICE"
                            const val ACTION_SETUP_PROJECTION =
                                "com.audioloop.audioloop.ACTION_SETUP_PROJECTION"

                            const val EXTRA_RESULT_CODE =
                                "com.audioloop.audioloop.EXTRA_RESULT_CODE"
                            const val EXTRA_DATA_INTENT =
                                "com.audioloop.audioloop.EXTRA_DATA_INTENT"

                            var currentMediaProjection: MediaProjection? = null
                                private set

                            private val _isRunning = MutableLiveData<Boolean>()
                            val isRunning: LiveData<Boolean> = _isRunning

                            fun isProjectionSetup(): Boolean {
                                return currentMediaProjection != null
                            }

                            fun clearStaticProjectionReference() {
                                Log.d(TAG, "Clearing static MediaProjection reference.")
                                currentMediaProjection?.stop() // Stop if not already
                                currentMediaProjection = null
                            }
                        }
                    }
                     Log.w(TAG, "ACTION_SETUP_PROJECTION received but service is not marked as running. Starting service first.")
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
                            clearProjectionAndAudioInstance()
                            currentMediaProjection = projection
                            currentMediaProjection?.registerCallback(mediaProjectionCallback, null)
                            Log.i(TAG, "MediaProjection obtained and stored.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startLoopbackAudio()
                            } else {
                                Log.w(TAG, "AudioPlaybackCaptureConfiguration requires Android Q (API 29)+")
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
                startForegroundNotification() // Update notification text
                // Post the current actual state after attempting projection setup
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

