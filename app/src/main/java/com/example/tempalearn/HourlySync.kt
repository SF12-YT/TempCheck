package com.example.tempalearn

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.work.WorkManager
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors

/**
 * One-shot hourly scheduler aligned to the phone's local wall-clock hour.
 *
 * The alarm is re-created after every tick and after clock, timezone, boot, app-update,
 * or exact-alarm permission changes. It deliberately uses ZoneId.systemDefault(), not
 * the timezone of the selected weather location.
 */
object HourlySyncScheduler {
    const val ACTION_HOURLY_UPDATE = "com.example.tempalearn.action.HOURLY_UPDATE"
    private const val REQUEST_CODE = 7480
    private const val LEGACY_DAILY_WORK_NAME = "templearn_daily_temperature_sync_local_noon"
    private const val LEGACY_PERIODIC_WORK_NAME = "templearn_daily_temperature_sync"

    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        // Remove the old noon-based WorkManager chain from installations upgrading from 0.10.8.
        runCatching {
            WorkManager.getInstance(app).cancelUniqueWork(LEGACY_DAILY_WORK_NAME)
            WorkManager.getInstance(app).cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        }
        scheduleNext(app)
    }

    fun rescheduleForLocation(context: Context) {
        // Scheduling follows the phone's timezone, so changing weather locations does not
        // change the wall-clock schedule; it simply ensures one current alarm remains pending.
        scheduleNext(context.applicationContext)
    }

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            Intent(app, HourlySyncAlarmReceiver::class.java).setAction(ACTION_HOURLY_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val nextHour = now.withMinute(0).withSecond(0).withNano(0).plusHours(1)
        val triggerAtMillis = nextHour.toInstant().toEpochMilli()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // Fallback works without special access, but Android may defer it by up to an hour.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}

/** Fired at the top of each local hour. */
class HourlySyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HourlySyncScheduler.ACTION_HOURLY_UPDATE) return

        // Re-arm immediately so a long-running or failed refresh cannot lose the next hour.
        HourlySyncScheduler.scheduleNext(context)
        try {
            context.startForegroundService(
                Intent(context, HourlySyncService::class.java)
                    .setAction(HourlySyncScheduler.ACTION_HOURLY_UPDATE)
            )
        } catch (error: Exception) {
            NotificationHelper.showSyncFailure(
                context,
                error.message ?: "Android could not start the hourly refresh service.",
                source = "hourly"
            )
        }
    }
}

/** Restores the next local-hour alarm after reboot or a change to the device clock/timezone. */
class HourlySyncSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                HourlySyncScheduler.ensureScheduled(context)
            }
        }
    }
}

/** Performs the existing full sync path and posts a result notification when it finishes. */
class HourlySyncService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createStatusChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true

        val notification = Notification.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("TempLearn hourly refresh")
            .setContentText("Refreshing hourly weather and updating the local model…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                STATUS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(STATUS_NOTIFICATION_ID, notification)
        }

        executor.execute {
            try {
                val preferences = getSharedPreferences(
                    AppPreferences.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val location = preferences.getString(
                    AppPreferences.KEY_LOCATION,
                    AppPreferences.DEFAULT_LOCATION
                )?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("No weather location is saved.")

                val result = SyncRepository.sync(applicationContext, location)
                if (result.recentUpdateWarning.isNullOrBlank()) {
                    NotificationHelper.showSyncSuccess(
                        applicationContext,
                        result,
                        source = "hourly"
                    )
                } else {
                    NotificationHelper.showSyncFailure(
                        applicationContext,
                        result.recentUpdateWarning,
                        source = "hourly"
                    )
                }
            } catch (error: Exception) {
                NotificationHelper.showSyncFailure(
                    applicationContext,
                    error.message ?: "The hourly weather refresh could not be completed.",
                    source = "hourly"
                )
            } finally {
                mainHandler.post {
                    running = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            STATUS_CHANNEL_ID,
            "Hourly refresh status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Quiet ongoing status while TempLearn refreshes hourly weather in the background."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val STATUS_CHANNEL_ID = "templearn_hourly_refresh_status"
        private const val STATUS_NOTIFICATION_ID = 7481
    }
}
