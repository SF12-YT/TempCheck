package com.example.tempalearn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Temporary diagnostic service: refreshes one persistent notification every five seconds.
 *
 * It is intentionally user-started and foreground-backed. Dismissing the activity from
 * Recents does not stop this started service; an explicit Stop action does.
 */
class BackgroundNotificationTestService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tickCount = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount += 1
            updateForegroundNotification()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTest()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            tickCount = 0L
            // Promote immediately, before doing any work, to meet the FGS start deadline.
            updateForegroundNotification()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_INTERVAL_MS)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately leave the foreground service running when the user dismisses
        // TempLearn's task from Recents.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopTest() {
        running = false
        handler.removeCallbacks(tick)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, BackgroundNotificationTestService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT_REQUEST,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_PENDING_INTENT_REQUEST,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val time = LocalTime.now().format(TIME_FORMAT)
        val status = if (tickCount == 0L) {
            "Waiting for the first five-second update"
        } else {
            "Heartbeat #$tickCount · $time"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("TempLearn background test running")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(
                "$status\nOne notification is refreshed every five seconds. Tap Stop test to end the service."
            ))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop test", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background service test",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing five-second TempLearn background-service diagnostic."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.tempalearn.action.START_BACKGROUND_TEST"
        const val ACTION_STOP = "com.example.tempalearn.action.STOP_BACKGROUND_TEST"

        private const val CHANNEL_ID = "templearn_background_test"
        private const val NOTIFICATION_ID = 7405
        private const val STOP_PENDING_INTENT_REQUEST = 7406
        private const val OPEN_PENDING_INTENT_REQUEST = 7407
        private const val TICK_INTERVAL_MS = 5_000L
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
    }
}
