package com.scrollguard

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class TimerService : Service() {

    companion object {
        private const val TAG = "TimerService"
        private const val CHANNEL = "scrollguard_channel"
        private const val ALERT_CHANNEL = "scrollguard_alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_NOTIF_ID = 2

        /** Accessibility health is checked every N ticks (not every second) — a
         *  system-settings lookup every second for the life of a session is wasteful. */
        private const val HEALTH_CHECK_INTERVAL_TICKS = 5
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tickCount = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            TimerState.tick(applicationContext)
            updateNotification()
            // Replaces the old "com.scrollguard.TICK" system broadcast with a direct,
            // in-process StateFlow update (see TimerState.tickSignal / publishTick).
            TimerState.publishTick()

            tickCount++
            if (tickCount % HEALTH_CHECK_INTERVAL_TICKS == 0) {
                checkAccessibilityHealth()
            }

            if (TimerState.isRunning()) {
                handler.postDelayed(this, 1000)
            } else {
                ServiceCompat.stopForeground(this@TimerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                cancelHealthAlert()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        TimerState.load(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                TimerState.start(applicationContext)
                tickCount = 0
            }
            "RESUME" -> { /* Reconnect only — state is already loaded in onCreate */ }
            "RESET" -> {
                TimerState.reset(applicationContext)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                cancelHealthAlert()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // FIX #8: Use FOREGROUND_SERVICE_TYPE_SPECIAL_USE on Android 14+ (API 34).
        // DATA_SYNC was semantically wrong and requires extra permissions on API 34+.
        // Also declared in AndroidManifest: android:foregroundServiceType="specialUse".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        // FIX #12: Wrapped in try/catch — Android 12+ restricts background
        // foreground-service starts. BootReceiver handles cold-start recovery.
        if (TimerState.isRunning()) {
            try {
                val restartIntent = Intent(this, TimerService::class.java).apply { action = "RESUME" }
                startForegroundService(restartIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service on destroy", e)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Best-effort health check for the enforcement mechanism. This can only detect that the
     *  accessibility service has been disabled (by the user or the OS) — it cannot prevent an
     *  OEM from killing it in the first place. See README's Reliability Notes. */
    private fun checkAccessibilityHealth() {
        val healthy = AccessibilityUtils.isBlockerServiceEnabled(applicationContext)
        val wasHealthy = TimerState.accessibilityHealthy
        TimerState.accessibilityHealthy = healthy
        if (!healthy && wasHealthy) {
            postHealthAlert()
        } else if (healthy && !wasHealthy) {
            cancelHealthAlert()
        }
        if (!healthy) updateNotification()
    }

    private fun postHealthAlert() {
        val manager = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setContentTitle(getString(R.string.notif_accessibility_lost_title))
            .setContentText(getString(R.string.notif_accessibility_lost_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager?.notify(ALERT_NOTIF_ID, notif)
    }

    private fun cancelHealthAlert() {
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIF_ID)
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val remaining = TimerState.getRemainingSeconds()
        val statusText = when (TimerState.phase) {
            TimerState.Phase.FREE    -> "🟣 Free time: ${TimerState.fmtTime(remaining)} left"
            TimerState.Phase.LOCKED  -> "🔴 LOCKED: ${TimerState.fmtTime(remaining)} remaining"
            TimerState.Phase.ALLOWED -> "🟢 OPEN: ${TimerState.fmtTime(remaining)} remaining"
            TimerState.Phase.IDLE    -> "⚪ Not started"
        }
        val prefixedText = if (!TimerState.accessibilityHealthy) {
            getString(R.string.notif_health_warning_prefix, statusText)
        } else {
            statusText
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ScrollGuard Active")
            .setContentText(prefixedText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "ScrollGuard Timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "App usage timer"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "ScrollGuard Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warns you if blocking stops working during a session"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
