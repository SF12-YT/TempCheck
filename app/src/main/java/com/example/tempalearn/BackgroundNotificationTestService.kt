package com.example.tempalearn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * User-started diagnostic service.
 *
 * The foreground-service status notification is persistent and quiet. A separate,
 * high-importance notification is posted every five seconds so the device visibly
 * demonstrates that the service is still running after the activity is dismissed.
 */
class BackgroundNotificationTestService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var tickCount = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount += 1
            postTestAlertNotification()
            updateForegroundNotification()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTest()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            tickCount = 0L
            updateForegroundNotification()
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_INTERVAL_MS)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Dismissing the activity from Recents does not deliberately stop this service.
        // Android can still terminate it after an explicit force-stop or system kill.
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
        clearRecentTestAlerts()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateForegroundNotification() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
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
            "First test alert due in five seconds"
        } else {
            "Sent $tickCount test alerts · $time"
        }

        return Notification.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("TempLearn background test running")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(
                "$status\nA separate alert is posted every five seconds. Tap Stop test to end the service."
            ))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop test", stopPendingIntent)
            .build()
    }

    private fun postTestAlertNotification() {
        val time = LocalTime.now().format(TIME_FORMAT)
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("TempLearn test notification #$tickCount")
            .setContentText("Five-second background heartbeat · $time")
            .setStyle(Notification.BigTextStyle().bigText(
                "Notification #$tickCount was posted at $time. The service is still running."
            ))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(ALERT_TIMEOUT_MS)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        val notificationId = ALERT_NOTIFICATION_ID_BASE +
            ((tickCount - 1L) % ALERT_NOTIFICATION_WINDOW).toInt()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun clearRecentTestAlerts() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        for (offset in 0 until ALERT_NOTIFICATION_WINDOW) {
            manager.cancel(ALERT_NOTIFICATION_ID_BASE + offset)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val foregroundChannel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "Background service status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Quiet ongoing status for the five-second background test."
            setShowBadge(false)
        }
        manager.createNotificationChannel(foregroundChannel)

        // New channel ID: the old low-importance test channel cannot suppress these alerts.
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Five-second background test alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Visible alert posted every five seconds while the diagnostic runs."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 180)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setShowBadge(false)
        }
        manager.createNotificationChannel(alertChannel)
    }

    companion object {
        const val ACTION_START = "com.example.tempalearn.action.START_BACKGROUND_TEST"
        const val ACTION_STOP = "com.example.tempalearn.action.STOP_BACKGROUND_TEST"

        private const val FOREGROUND_CHANNEL_ID = "templearn_background_test"
        private const val ALERT_CHANNEL_ID = "templearn_background_test_alerts_v2"
        private const val FOREGROUND_NOTIFICATION_ID = 7405
        private const val ALERT_NOTIFICATION_ID_BASE = 7500
        private const val ALERT_NOTIFICATION_WINDOW = 6
        private const val ALERT_TIMEOUT_MS = 25_000L
        private const val STOP_PENDING_INTENT_REQUEST = 7406
        private const val OPEN_PENDING_INTENT_REQUEST = 7407
        private const val TICK_INTERVAL_MS = 5_000L
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
    }
}
