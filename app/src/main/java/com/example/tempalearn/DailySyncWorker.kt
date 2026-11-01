package com.example.tempalearn

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Schedules the next refresh for 12:00 in the selected location's timezone. */
object DailySyncScheduler {
    const val WORK_NAME = "templearn_daily_temperature_sync_local_noon"
    private const val LEGACY_PERIODIC_WORK_NAME = "templearn_daily_temperature_sync"

    /** Called on app start; an already scheduled noon run is retained. */
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        enqueueNext(context, ExistingWorkPolicy.KEEP)
    }

    fun rescheduleForLocation(context: Context) {
        enqueueNext(context, ExistingWorkPolicy.REPLACE)
    }

    /** The worker appends tomorrow's noon run to its currently running unique-work chain. */
    fun enqueueNextAfterCurrent(context: Context) {
        enqueueNext(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueNext(context: Context, policy: ExistingWorkPolicy) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val location = prefs.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
            ?.takeIf { it.isNotBlank() } ?: AppPreferences.DEFAULT_LOCATION
        val locationKey = AppPreferences.activeLocationKey(prefs, location)
        val savedZone = prefs.getString(AppPreferences.timezoneKey(locationKey), null)
            ?: if (location.equals(AppPreferences.DEFAULT_LOCATION, ignoreCase = true)) {
                OpenMeteoClient.DEFAULT_TIMEZONE_ID
            } else null
        val zone = savedZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var nextNoon = now.toLocalDate().atTime(12, 0).atZone(zone)
        if (!nextNoon.isAfter(now)) nextNoon = nextNoon.plusDays(1)
        val delayMillis = Duration.between(now, nextNoon).toMillis().coerceAtLeast(0L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DailySyncWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

/** A best-effort daily refresh; Android may defer it for battery or network constraints. */
class DailySyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val preferences = applicationContext.getSharedPreferences(
            AppPreferences.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val location = preferences.getString(AppPreferences.KEY_LOCATION, AppPreferences.DEFAULT_LOCATION)
            ?.takeIf { it.isNotBlank() } ?: return Result.failure()

        return try {
            val result = SyncRepository.sync(applicationContext, location)
            NotificationHelper.showSyncSuccess(applicationContext, result, source = "daily")
            DailySyncScheduler.enqueueNextAfterCurrent(applicationContext)
            Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                DailySyncScheduler.enqueueNextAfterCurrent(applicationContext)
                Result.failure()
            }
        }
    }
}
