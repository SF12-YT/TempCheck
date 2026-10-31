package com.example.tempalearn

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** A best-effort daily refresh. Android may defer it for battery, network or scheduler reasons. */
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
            Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
