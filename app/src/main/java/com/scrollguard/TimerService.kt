package com.scrollguard

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class TimerService : Service() {

    companion object {
        private const val TAG = "TimerService"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val CHANNEL = "scrollguard_channel"
    private val NOTIF_ID = 1

    private val tickRunnable = object : Runnable {
        override fun run() {
            TimerState.tick(applicationContext)
            updateNotification()
            sendBroadcast(Intent("com.scrollguard.TICK"))

            if (TimerState.isRunning()) {
                handler.postDelayed(this, 1000)
            } else {
                ServiceCompat.stopForeground(this@TimerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        TimerState.load(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> TimerState.start(applicationContext)
            "RESUME" -> { /* Reconnect only — state is already loaded in onCreate */ }
            "RESET" -> {
                TimerState.reset(applicationContext)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // FIX #8: Use FOREGROUND_SERVICE_TYPE_SPECIAL_USE on Android 14+ (API 34).
        // DATA_SYNC was semantically wrong and requires extra permissions on API 34+.
        // Also declare in AndroidManifest: android:foregroundServiceType="specialUse"
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
                val restartIntent = Intent(this, TimerService::class.java).apply { action = "START" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service on destroy", e)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("ScrollGuard Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "ScrollGuard Timer", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "App usage timer"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}