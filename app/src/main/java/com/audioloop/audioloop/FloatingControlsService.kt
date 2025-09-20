package com.audioloop.audioloop

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.NotificationCompat

class FloatingControlsService : Service() {

    private var mWindowManager: WindowManager? = null
    private var mFloatingWidgetView: View? = null
    private var centerLogoWaveformContainer: ConstraintLayout? = null
    private var waveformDisplay: ImageView? = null
    private var masterVolumeSlider: SeekBar? = null
    private var floatingShareButton: ConstraintLayout? = null
    private var micToggleChannel: LinearLayout? = null
    private var micToggleIcon: ImageView? = null
    private var micGainSlider: SeekBar? = null
    private var micGainText: TextView? = null


    private var isServiceRunning = false
    private var isMicMuted = false
    private var overlayParams: WindowManager.LayoutParams? = null
    private var lastAction: Int = 0
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    companion object {
        const val ACTION_START_FOREGROUND_SERVICE = "ACTION_START_FOREGROUND_SERVICE"
        const val ACTION_STOP_FOREGROUND_SERVICE = "ACTION_STOP_FOREGROUND_SERVICE"
        const val ACTION_UPDATE_STATE = "ACTION_UPDATE_STATE"
        const val EXTRA_SERVICE_RUNNING = "EXTRA_SERVICE_RUNNING"
        const val ACTION_UPDATE_VOLUME = "ACTION_UPDATE_VOLUME"
        const val EXTRA_VOLUME_LEVEL = "EXTRA_VOLUME_LEVEL"
        const val ACTION_SHARE = "ACTION_SHARE"
        const val ACTION_TOGGLE_MIC_MUTE = "ACTION_TOGGLE_MIC_MUTE"
        const val EXTRA_IS_MIC_MUTED = "EXTRA_IS_MIC_MUTED"
        const val ACTION_UPDATE_MIC_GAIN = "ACTION_UPDATE_MIC_GAIN"
        const val EXTRA_MIC_GAIN_LEVEL = "EXTRA_MIC_GAIN_LEVEL"
        const val ACTION_UPDATE_APP_AUDIO_GAIN = "ACTION_UPDATE_APP_AUDIO_GAIN"
        const val EXTRA_APP_AUDIO_GAIN_LEVEL = "EXTRA_APP_AUDIO_GAIN_LEVEL"
    }

    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_STATE -> {
                    isServiceRunning = intent.getBooleanExtra(EXTRA_SERVICE_RUNNING, false)
                    updateUIForServiceState()
                }
                ACTION_TOGGLE_MIC_MUTE -> {
                    isMicMuted = intent.getBooleanExtra(EXTRA_IS_MIC_MUTED, false)
                    updateUIForServiceState()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        mFloatingWidgetView = inflater.inflate(R.layout.floating_gadget_luxury, null)

        val layoutFlag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        mWindowManager?.addView(mFloatingWidgetView, overlayParams)

        // Initialize UI elements
        centerLogoWaveformContainer = mFloatingWidgetView?.findViewById(R.id.center_logo_waveform)
        waveformDisplay = mFloatingWidgetView?.findViewById(R.id.waveform_display)
        masterVolumeSlider = mFloatingWidgetView?.findViewById(R.id.master_volume_slider)
        floatingShareButton = mFloatingWidgetView?.findViewById(R.id.floating_share_button)
        micToggleChannel = mFloatingWidgetView?.findViewById(R.id.mic_toggle_channel)
        micToggleIcon = mFloatingWidgetView?.findViewById(R.id.mic_toggle_icon)
        micGainSlider = mFloatingWidgetView?.findViewById(R.id.mic_gain_slider)
        micGainText = mFloatingWidgetView?.findViewById(R.id.mic_gain_text)


        // Set up listeners
        centerLogoWaveformContainer?.setOnClickListener {
            val intent = Intent(this, AudioLoopService::class.java).apply {
                action = if (isServiceRunning) ACTION_STOP_FOREGROUND_SERVICE else ACTION_START_FOREGROUND_SERVICE
            }
            startService(intent)
        }

        masterVolumeSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val volumeIntent = Intent(ACTION_UPDATE_VOLUME).apply {
                        putExtra(EXTRA_VOLUME_LEVEL, progress)
                    }
                    sendBroadcast(volumeIntent)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        floatingShareButton?.setOnClickListener {
            val shareIntent = Intent(ACTION_SHARE)
            sendBroadcast(shareIntent)
        }

        micToggleChannel?.setOnClickListener {
            val toggleMicIntent = Intent(ACTION_TOGGLE_MIC_MUTE).apply {
                putExtra(EXTRA_IS_MIC_MUTED, !isMicMuted) // Toggle the state
            }
            sendBroadcast(toggleMicIntent)
        }

        micGainSlider?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    micGainText?.text = "+${progress} dB"
                    val micGainIntent = Intent(ACTION_UPDATE_MIC_GAIN).apply {
                        putExtra(EXTRA_MIC_GAIN_LEVEL, progress)
                    }
                    sendBroadcast(micGainIntent)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Register receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_STATE)
            addAction(ACTION_TOGGLE_MIC_MUTE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(stateUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateUpdateReceiver, filter)
        }

        setupTouchListener()
        updateUIForServiceState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_FOREGROUND_SERVICE) {
            startForegroundServiceNotification()
        } else if (intent?.action == ACTION_STOP_FOREGROUND_SERVICE) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "AudioLoop_Floating_Controls_Channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "AudioLoop Floating Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for the AudioLoop floating widget"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AudioLoop Controls")
            .setContentText("Tap to open AudioLoop")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use an appropriate icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .build()

        startForeground(1, notification)
    }

    private fun updateUIForServiceState() {
        if (isServiceRunning) {
            centerLogoWaveformContainer?.background?.colorFilter =
                PorterDuffColorFilter(Color.parseColor("#80FFFFFF"), PorterDuff.Mode.SRC_ATOP)
            waveformDisplay?.visibility = View.VISIBLE
        } else {
            centerLogoWaveformContainer?.background?.clearColorFilter()
            waveformDisplay?.visibility = View.GONE
        }

        if (isMicMuted) {
            micToggleIcon?.alpha = 0.4f
        } else {
            micToggleIcon?.alpha = 1.0f
        }
        // Update mic gain text on UI update
        // We might want to set a default or retrieve the current state from AudioLoopService
        micGainText?.text = "+${micGainSlider?.progress ?: 0} dB"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        mFloatingWidgetView?.findViewById<View>(R.id.floating_gadget_container)?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = overlayParams?.x ?: 0
                        initialY = overlayParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastAction = MotionEvent.ACTION_DOWN
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (lastAction == MotionEvent.ACTION_DOWN) {
                            // This was a click, not a drag. Delegate to the click listener
                            v?.performClick()
                        }
                        lastAction = 0
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        overlayParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                        overlayParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                        mWindowManager?.updateViewLayout(mFloatingWidgetView, overlayParams)
                        lastAction = MotionEvent.ACTION_MOVE
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        mFloatingWidgetView?.let {
            mWindowManager?.removeView(it)
        }
        unregisterReceiver(stateUpdateReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Adjust widget position or layout if needed based on orientation changes
    }
}