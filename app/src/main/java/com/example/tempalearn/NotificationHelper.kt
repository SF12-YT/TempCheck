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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Weather update results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Confirmation when TempLearn updates hourly BOM observations and previous-day daily weather context."
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
        val windowMessage = "BOM's latest approximately 72-hour observation window"
        val modelMessage = if (result.modelReady) {
            "Model updated · ${result.trainingExamples} local examples"
        } else {
            "Weather saved · more consecutive hours are needed for local learning"
        }
        val sources = buildList {
            add("BOM-only history: ${result.bomAppliedHours} new hourly observations")
            add(if (result.openMeteoAvailable) "Open-Meteo previous-day daily context: ${result.openMeteoDailyDays} completed daily record(s); no forecasts or temperature values imported" else "Open-Meteo previous-day daily context unavailable")
        }.joinToString(" · ")
        val body = "${result.resolvedAddress}: refreshed the $windowMessage (${result.fetchedHours} completed hourly records). $sources. $modelMessage."
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (source == "daily") "TempLearn daily update complete" else "TempLearn weather update complete")
            .setContentText(body.take(180))
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
