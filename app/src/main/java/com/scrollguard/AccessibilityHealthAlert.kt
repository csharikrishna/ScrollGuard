package com.scrollguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The "ScrollGuard can't block apps right now" alert — extracted out of [TimerService] so
 * [AccessibilityHealthWorker] can post the exact same notification from a periodic WorkManager
 * job, independent of whether TimerService (and the process it runs in) is currently alive.
 * That independence is the point: TimerService's own health check can only warn about a dead
 * Accessibility service while TimerService's own process is itself still alive — if an OEM's
 * background-kill takes out the whole process (Accessibility service and TimerService share one),
 * the check that would have warned the user dies together with the problem it exists to catch.
 */
object AccessibilityHealthAlert {
    private const val ALERT_CHANNEL = "scrollguard_alerts"
    private const val ALERT_NOTIF_ID = 2

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "ScrollGuard Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns you if blocking stops working during a session"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun post(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setContentTitle(context.getString(R.string.notif_accessibility_lost_title))
            .setContentText(context.getString(R.string.notif_accessibility_lost_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager?.notify(ALERT_NOTIF_ID, notif)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIF_ID)
    }
}
