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
        private const val NOTIF_ID = 1

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
                // Independent of this service's own in-process health check below — see
                // AccessibilityHealthWorker's doc for why a session needs a watchdog that can
                // survive the same OEM process-kill that would take the in-process check out too.
                AccessibilityHealthWorker.schedule(applicationContext)
                // Check immediately rather than waiting up to HEALTH_CHECK_INTERVAL_TICKS
                // seconds — the very first notification a session posts must already reflect
                // real protection state, not the optimistic default.
                checkAccessibilityHealth()
            }
            "RESUME" -> {
                // Reconnect only — state is already loaded in onCreate. Still re-check
                // immediately: this process may have just been restarted (reboot, OEM kill),
                // and the notification must not show stale health from before that happened.
                checkAccessibilityHealth()
            }
            "RESET" -> {
                TimerState.reset(applicationContext)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                cancelHealthAlert()
                stopSelf()
                return START_NOT_STICKY
            }
            "DISMISS_NOTIF" -> {
                // If the user dismissed the notification manually, we stop the service to avoid zombie states.
                // WorkManager will eventually wake us up if we are supposed to be running.
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Best-effort health check for the enforcement mechanism. This can only detect that the
     *  accessibility service has been disabled (by the user or the OS) — it cannot prevent an
     *  OEM from killing it in the first place. See README's Reliability Notes. */
    private fun checkAccessibilityHealth() {
        val healthy = AccessibilityUtils.isProtectionActive(applicationContext)
        val wasHealthy = TimerState.accessibilityHealthy
        TimerState.accessibilityHealthy = healthy
        if (!healthy && wasHealthy) {
            postHealthAlert()
        } else if (healthy && !wasHealthy) {
            cancelHealthAlert()
        }
        if (!healthy) updateNotification()
    }

    private fun postHealthAlert() = AccessibilityHealthAlert.post(applicationContext)

    private fun cancelHealthAlert() = AccessibilityHealthAlert.cancel(applicationContext)

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
            TimerState.Phase.FREE    -> getString(R.string.notif_phase_free, TimerState.fmtTime(remaining))
            TimerState.Phase.LOCKED  -> getString(R.string.notif_phase_locked, TimerState.fmtTime(remaining))
            TimerState.Phase.ALLOWED -> getString(R.string.notif_phase_open, TimerState.fmtTime(remaining))
            TimerState.Phase.IDLE    -> getString(R.string.notif_phase_idle)
        }
        val healthy = TimerState.accessibilityHealthy
        val title = if (healthy) getString(R.string.notif_title_active) else getString(R.string.notif_title_action_needed)
        val text = if (healthy) statusText else getString(R.string.notif_health_warning_prefix, statusText)
        // A single, unambiguous small icon communicates the health state instead of stacking
        // an emoji on top of the phase text — the icon changes, the text stays plain.
        val icon = if (healthy) android.R.drawable.ic_lock_idle_alarm else android.R.drawable.ic_dialog_alert
        // Intent for when the user swipes away the notification (Android 14+)
        val deleteIntent = PendingIntent.getService(
            this, 1, Intent(this, TimerService::class.java).apply { action = "DISMISS_NOTIF" }, flags
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(open)
            .setDeleteIntent(deleteIntent)
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
        AccessibilityHealthAlert.ensureChannel(applicationContext)
    }
}
