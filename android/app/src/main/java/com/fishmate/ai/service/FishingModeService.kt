package com.fishmate.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fishmate.ai.MainActivity
import com.fishmate.ai.R
import com.fishmate.ai.core.Log2

/**
 * Keeps Fishing Mode alive with the screen off.
 *
 * This is the honest answer to "the phone is in my pocket". Android will not
 * let an app record audio from the background indefinitely; microphone use
 * requires a foreground service with `foregroundServiceType="microphone"` and
 * a visible notification. So rather than pretending to listen always, FishMate
 * runs this service **only** while the angler has explicitly started Fishing
 * Mode, and the notification makes that unambiguous.
 *
 * The service does not own the Live session — the controller does, scoped to
 * the app. Its single job is to tell the platform that recording is
 * intentional and user-visible.
 */
class FishingModeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log2.i(TAG, "stop requested from notification")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log2.i(TAG, "fishing mode service started")
        // Not sticky: if the system kills us, silently resuming microphone
        // capture without the user asking would be the wrong behaviour.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log2.i(TAG, "fishing mode service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            // LOW: the notification must be visible, but it must not buzz the
            // angler's wrist every time the session reconnects.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FishingModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.service_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val TAG = "FishingService"
        private const val CHANNEL_ID = "fishing_mode"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.fishmate.ai.STOP_FISHING_MODE"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, FishingModeService::class.java))
            }.onFailure { Log2.w(TAG, "could not start foreground service", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FishingModeService::class.java))
            }
        }
    }
}
