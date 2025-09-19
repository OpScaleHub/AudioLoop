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
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class FloatingControlsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingGadget: ViewGroup? = null
    private var iconView: ImageView? = null
    private var closeButton: ImageButton? = null
    private var isExpanded: Boolean = false

    private lateinit var params: WindowManager.LayoutParams
    private var lastAction = 0
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var isServiceRunning = false

    companion object {
        private const val TAG = "AudioLoopApp"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "FloatingControlsChannel"
        const val ACTION_UPDATE_ICON = "com.audioloop.audioloop.UPDATE_ICON"
        const val EXTRA_IS_RUNNING = "IS_RUNNING"
    }

    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_ICON) {
                val previousState = isServiceRunning
                val newState = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)
                isServiceRunning = newState
                Log.d(TAG, "FloatingControlsService.stateUpdateReceiver: ACTION_UPDATE_ICON received. Prev isServiceRunning: $previousState, New isServiceRunning: $isServiceRunning")
                updateIcon()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "FloatingControlsService: onBind called")
        return null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingControlsService: onCreate called. Initial isServiceRunning: $isServiceRunning")

        val intentFilter = IntentFilter(ACTION_UPDATE_ICON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateUpdateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateUpdateReceiver, intentFilter)
        }
        Log.d(TAG, "FloatingControlsService: stateUpdateReceiver registered.")


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e(TAG, "FloatingControlsService: Overlay permission not granted. Stopping service.")
            Toast.makeText(this, "Overlay permission not granted.", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater

        try {
            val inflatedView = inflater.inflate(R.layout.floating_gadget, null)
            if (inflatedView == null) {
                Log.e(TAG, "FloatingControlsService: Critical - inflater.inflate returned null for R.layout.floating_gadget")
                Toast.makeText(this, "Error: Inflation returned null.", Toast.LENGTH_LONG).show()
                stopSelf(); return
            }

            iconView = inflatedView.findViewById<ImageView>(R.id.floating_widget_icon)
            closeButton = inflatedView.findViewById<ImageButton>(R.id.floating_widget_close_button)

            if (iconView == null) {
                Log.e(TAG, "FloatingControlsService: Critical - iconView not found.")
                Toast.makeText(this, "Error: iconView not found.", Toast.LENGTH_LONG).show()
                stopSelf(); return
            }
            if (closeButton == null) {
                Log.e(TAG, "FloatingControlsService: Critical - closeButton not found.")
                Toast.makeText(this, "Error: closeButton not found.", Toast.LENGTH_LONG).show()
                stopSelf(); return
            }

            if (inflatedView is ViewGroup) {
                floatingGadget = inflatedView
            } else {
                Log.e(TAG, "FloatingControlsService: Critical - Inflated view is not a ViewGroup.")
                Toast.makeText(this, "Error: Inflated layout is not ViewGroup.", Toast.LENGTH_LONG).show()
                stopSelf(); return
            }

            // Let the XML define the initial visual state
            // floatingGadget?.setBackgroundColor(Color.parseColor("#80FF0000")) // Removed debug background
            // iconView?.setImageResource(android.R.drawable.sym_def_app_icon) // Removed debug icon

            closeButton?.visibility = View.GONE

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 100
            }

            windowManager.addView(floatingGadget, params)
            Log.d(TAG, "FloatingControlsService: Floating gadget added to WindowManager.")

            setupTouchListener()
            setupIconPlayStopClickListener()
            setupIconLongClickListener()
            setupCloseButtonClickListener()

            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "FloatingControlsService: Service started in foreground.")

            Log.d(TAG, "FloatingControlsService: Sending ACTION_REQUEST_STATE to AudioLoopService.")
            sendBroadcast(Intent(AudioLoopService.ACTION_REQUEST_STATE))

        } catch (e: Exception) {
            Log.e(TAG, "FloatingControlsService: Error during floating widget setup: ${e.message}", e)
            Toast.makeText(this, "Setup Error: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "FloatingControlsService: onStartCommand received action: $action")
        return START_STICKY
    }

    private fun updateIcon() {
        Log.d(TAG, "FloatingControlsService: updateIcon() called. isServiceRunning: $isServiceRunning")
        if (isServiceRunning) {
            iconView?.setImageResource(R.drawable.ic_stop)
        } else {
            iconView?.setImageResource(R.drawable.ic_play)
        }
        iconView?.invalidate()
        floatingGadget?.invalidate()
        Log.d(TAG, "FloatingControlsService: iconView updated and invalidated.")
    }

    private fun setupIconPlayStopClickListener() {
        iconView?.setOnClickListener {
            Log.d(TAG, "FloatingControlsService: iconView clicked. Current isServiceRunning: $isServiceRunning, isExpanded: $isExpanded")
            if (!isExpanded) {
                val serviceIntent = Intent(this, AudioLoopService::class.java)
                if (isServiceRunning) {
                    serviceIntent.action = AudioLoopService.ACTION_PROCESS_AUDIO_STOP
                } else {
                    serviceIntent.action = AudioLoopService.ACTION_PROCESS_AUDIO_START
                }
                Log.d(TAG, "FloatingControlsService: Sending action to AudioLoopService: ${serviceIntent.action}")
                startService(serviceIntent)
            } else {
                Log.d(TAG, "FloatingControlsService: iconView clicked, but widget is expanded. No play/stop action.")
            }
        }
    }

    private fun toggleExpandState() {
        isExpanded = !isExpanded
        closeButton?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        Log.d(TAG, "FloatingControlsService: Toggled expand state. isExpanded: $isExpanded, closeButton visibility: ${closeButton?.visibility}")
    }

    private fun setupIconLongClickListener() {
        iconView?.setOnLongClickListener {
            Log.d(TAG, "FloatingControlsService: iconView long-clicked.")
            toggleExpandState()
            true
        }
    }

    private fun setupCloseButtonClickListener() {
        closeButton?.setOnClickListener {
            Log.d(TAG, "FloatingControlsService: closeButton clicked.")
            stopSelf()
        }
    }

    private fun setupTouchListener() {
        floatingGadget?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastAction = MotionEvent.ACTION_DOWN; initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lastAction == MotionEvent.ACTION_DOWN || lastAction == MotionEvent.ACTION_MOVE) {
                        if (!isExpanded) {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager.updateViewLayout(floatingGadget, params)
                            } catch (e: Exception) {
                                Log.e(TAG, "FloatingControlsService: Error updating layout", e)
                            }
                        }
                        lastAction = MotionEvent.ACTION_MOVE
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    lastAction = MotionEvent.ACTION_UP; event.action == MotionEvent.ACTION_MOVE
                }
                else -> false
            }
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Floating Controls Active").setContentText("Controls are visible.").setSmallIcon(R.mipmap.ic_launcher_round).setContentIntent(pendingIntent).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Floating Controls Channel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateUpdateReceiver)
        Log.d(TAG, "FloatingControlsService: stateUpdateReceiver unregistered.")
        Log.d(TAG, "FloatingControlsService: onDestroy called")
        if (floatingGadget?.windowToken != null) {
            try {
                windowManager.removeView(floatingGadget)
            } catch (e: Exception) {
                Log.e(TAG, "FloatingControlsService: Error removing view: ${e.message}", e)
            }
        }
        floatingGadget = null; iconView = null; closeButton = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "FloatingControlsService: onConfigurationChanged: ${newConfig.orientation}")
    }
}
