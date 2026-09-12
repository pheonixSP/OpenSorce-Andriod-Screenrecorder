package com.cleanrecorder.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Builds the persistent "recording in progress" notification.
 *
 * IMPORTANT NAMING NOTE: there is no public Android or One UI API called
 * "Dynamic Island", "Live Activity", or "Ongoing Activity" that a third-party app can hook
 * into on today's platform. What actually produces a live, ticking status-bar element on
 * both stock Android and One UI is a foreground-service notification with
 * setOngoing(true) + setUsesChronometer(true): the system renders the small icon in the
 * status bar and keeps a live MM:SS (or H:MM:SS) counter in the shade without the app
 * touching a timer thread for the displayed text. We layer a manual 1Hz icon swap on top
 * purely for the pulsing-dot small icon, since the chronometer text itself is fully
 * system-driven and immune to process throttling.
 */
object NotificationHelper {
    private const val CHANNEL_ID = "recording_status"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_recording_desc)
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildLiveNotification(context: Context, elapsedSeconds: Int, pulseOn: Boolean, configSummary: String? = null): Notification {
        val stopIntent = Intent(context, RecordService::class.java).apply {
            action = RecordService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Tapping the notification body (the "pill") also stops & saves instantly —
        // no need to hit the explicit action button.
        val contentTapPendingIntent = stopPendingIntent

        val timeText = formatElapsed(elapsedSeconds)
        val smallIcon = if (pulseOn) R.drawable.ic_record_dot else R.drawable.ic_record_dot_dim

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle("Recording • $timeText")
            .setContentText(configSummary?.let { "$it • Tap to stop" } ?: "Tap to stop and save")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsedSeconds * 1000L)
            .setShowWhen(true)
            .setContentIntent(contentTapPendingIntent)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stop),
                    "Stop & Save",
                    stopPendingIntent
                ).build()
            )

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    fun updateLiveNotification(context: Context, elapsedSeconds: Int, pulseOn: Boolean, configSummary: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(RecordService.NOTIF_ID, buildLiveNotification(context, elapsedSeconds, pulseOn, configSummary))
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(RecordService.NOTIF_ID)
    }

    private fun formatElapsed(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%02d:%02d".format(m, s)
    }
}
