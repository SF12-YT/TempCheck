package com.example.tempalearn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object NotificationHelper {
    private const val CHANNEL_ID = "templearn_weather_updates"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Weather update results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Confirmation when TempLearn imports Open-Meteo Best Match hourly history."
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun showSyncSuccess(context: Context, result: SyncResult, source: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val phase = if (result.initialImportCompletedNow) "Initial archive from 2000-01-01 completed"
            else "Latest local date refreshed"
        val model = if (result.modelReady) "Model updated · ${result.trainingExamples} local examples"
            else "Weather saved · more consecutive hours are needed for local learning"
        val body = "${result.resolvedAddress}: $phase · ${result.fetchedHours} fetched/updated hourly rows · ${result.savedReadings} stored rows. $model."
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(context, CHANNEL_ID)
            else { @Suppress("DEPRECATION") android.app.Notification.Builder(context) }
        val notification = builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (source == "daily") "TempLearn hourly update complete" else "TempLearn history update complete")
            .setContentText(body.take(180))
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
