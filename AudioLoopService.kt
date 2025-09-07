package com.audioloop.audioloop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AudioLoopService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "AudioLoopServiceChannel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        // TODO:
        // 1. Get MediaProjection token from the intent.
        // 2. Initialize AudioPlaybackCapture using the MediaProjection.
        // 3. Initialize AudioRecord to capture microphone input.
        // 4. Initialize AudioTrack to play back mixed audio.
        // 5. Create threads for capturing, mixing, and playback.
        // 6. Start the audio processing loop.

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // TODO:
        // 1. Stop all audio threads.
        // 2. Release AudioRecord, AudioTrack, and other resources.
        // 3. Stop foreground service.
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Audio Loop Service Channel", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AudioLoop Active").setContentText("Audio loopback and mixing is running.")
            // .setSmallIcon(R.drawable.ic_notification) // TODO: Add notification icon
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}