package com.audioloop.audioloop

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
// import com.audioloop.audioloop.R // Already implicitly available by package

class FloatingControlsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingGadget: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var iconView: ImageView? = null

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.0f
    private var initialTouchY: Float = 0.0f

    // Observe AudioLoopService.isRunning to update UI
    private val audioLoopServiceRunningObserver = androidx.lifecycle.Observer<Boolean> {
        isServiceRunning -> updateIcon(isServiceRunning)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // This service should ideally not be started if permission is not granted.
            // MainActivity should handle this check before starting the service.
            Toast.makeText(this, "Overlay permission not granted", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingGadget = inflater.inflate(R.layout.floating_gadget, null)
        iconView = floatingGadget?.findViewById<ImageView>(R.id.floating_widget_icon)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100 // Initial position

        try {
            windowManager.addView(floatingGadget, params)
        } catch (e: Exception) {
            // Handle cases where window manager might fail (e.g. after screen rotation if not handled)
            stopSelf()
            return
        }

        setupTouchListener()
        setupClickListener()

        // Observe the LiveData from AudioLoopService
        // We need a way to get the main Looper for LiveData observation if this service is not on main thread
        // However, Service lifecycle methods are called on the main thread by default.
        AudioLoopService.isRunning.observeForever(audioLoopServiceRunningObserver)
        updateIcon(AudioLoopService.isRunning.value ?: false) // Initial icon state
    }

    private fun updateIcon(isRunning: Boolean) {
        if (isRunning) {
            iconView?.setImageResource(R.drawable.ic_stop) // Replace with your actual stop icon
            iconView?.alpha = 1.0f // Full opacity when running
        } else {
            iconView?.setImageResource(R.drawable.ic_play) // Replace with your actual play icon
            iconView?.alpha = 0.7f // Slightly dimmed when not running, to show it'''s active but pausable
        }
    }

    private fun setupClickListener() {
        iconView?.setOnClickListener {
            val currentServiceState = AudioLoopService.isRunning.value ?: false
            if (currentServiceState) {
                sendActionToAudioLoopService(AudioLoopService.ACTION_PROCESS_AUDIO_STOP)
            } else {
                if (AudioLoopService.isProjectionSetup()) {
                    sendActionToAudioLoopService(AudioLoopService.ACTION_PROCESS_AUDIO_START)
                } else {
                    Toast.makeText(this, "Screen capture not set up. Please set up from the main app.", Toast.LENGTH_LONG).show()
                    // Optionally, could try to bring MainActivity to front or send a signal
                }
            }
        }
    }

    private fun sendActionToAudioLoopService(action: String) {
        val serviceIntent = Intent(this, AudioLoopService::class.java)
        serviceIntent.action = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent) // Ensure AudioLoopService also calls startForeground for its notification
        } else {
            startService(serviceIntent)
        }
    }

    private fun setupTouchListener() {
        floatingGadget?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(floatingGadget, params)
                    } catch (e: Exception) {
                        // Could happen if service is stopping/view removed
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingGadget?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Handle if view already removed or window manager service not available
            }
        }
        AudioLoopService.isRunning.removeObserver(audioLoopServiceRunningObserver)
    }
}
