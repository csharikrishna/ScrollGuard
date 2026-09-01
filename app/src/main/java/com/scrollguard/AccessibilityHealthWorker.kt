package com.scrollguard

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scrollguard.data.ScrollGuardDatabase
import java.util.concurrent.TimeUnit

/**
 * Periodic (WorkManager-scheduled, ~15 min — its shortest allowed interval) watchdog for the
 * Accessibility service, independent of [TimerService] and the accessibility service's own
 * process.
 *
 * Why this exists: [TimerService] already runs a health check (see its checkAccessibilityHealth),
 * but that check, the accessibility service it's checking, and the notification it would post
 * are ALL in the same app process. If an OEM's background-kill takes that whole process out
 * (observed on Xiaomi/HyperOS — see docs on battery/background management), the one thing that
 * would have told the user is dead along with the problem it exists to catch, and nothing
 * in-app ever surfaces it again until the user happens to reopen ScrollGuard. A WorkManager job
 * is scheduled through the system's own JobScheduler, which — unlike an ordinary foreground
 * service process — the OS will restart the app's process to run when due, giving this a real
 * chance to notice and warn even after a full process kill. It is a detector and best-effort
 * recovery attempt, not a guarantee: some OEMs restrict JobScheduler too, and nothing running in
 * app code can force an OS-killed AccessibilityService to rebind (only the user, or the OS
 * itself, can do that) — see [AccessibilityHealthAlert] and README's Reliability Notes.
 */
class AccessibilityHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AccessibilityHealthWorker"
        private const val WORK_NAME = "accessibility_health_check"

        /** Safe to call from any entry point (app open, boot, session start, pairing) —
         *  KEEP means an already-scheduled job is left alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AccessibilityHealthWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        // Cold-process safe: both reload their state from disk rather than trusting in-memory
        // defaults, since WorkManager may be starting this in a freshly (re)launched process.
        TimerState.load(applicationContext)
        val dao = ScrollGuardDatabase.getDatabase(applicationContext).parentalDao()
        ParentalControlState.hydrateFromRoom(applicationContext, dao)

        val personalSessionActive = TimerState.isRunning()
        val parentalProtectionActive = ParentalControlState.isPaired && ParentalControlState.globalEnabled
        if (!personalSessionActive && !parentalProtectionActive) {
            // Nothing is supposed to be enforcing right now — an unhealthy service isn't
            // actionable (there's nothing for it to be blocking), so don't alarm the user.
            return Result.success()
        }

        val healthy = AccessibilityUtils.isProtectionActive(applicationContext)
        TimerState.accessibilityHealthy = healthy
        if (!healthy) {
            Log.w(TAG, "Accessibility service unhealthy while protection should be active — alerting")
            AccessibilityHealthAlert.post(applicationContext)
            // Best-effort recovery: if a personal session should be running but this process
            // was killed and restarted cold by WorkManager, TimerService itself may not be
            // alive either — nudge it back the same way BootReceiver does after a reboot.
            // This cannot restart the accessibility service itself; only the OS or the user can.
            if (personalSessionActive) {
                try {
                    val restartIntent = Intent(applicationContext, TimerService::class.java).apply {
                        action = "RESUME"
                    }
                    ContextCompat.startForegroundService(applicationContext, restartIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to nudge TimerService back alive", e)
                }
            }
        } else {
            AccessibilityHealthAlert.cancel(applicationContext)
        }
        return Result.success()
    }
}
