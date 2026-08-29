package com.scrollguard.parental

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scrollguard.ParentalControlState
import com.scrollguard.data.ScrollGuardDatabase
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker (~15 min, network-constrained) as a safety-net
 * fallback for syncing parental control config and status.
 *
 * This is the "always-running" background sync that ensures the child eventually
 * picks up config changes even if FCM push is delayed or missed.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "parental_sync"

        /**
         * Schedules the periodic sync worker. Safe to call multiple times —
         * KEEP policy means it won't replace an existing schedule.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic sync scheduled (15 min, network-constrained)")
        }

        /**
         * Cancels the periodic sync worker (used on unpair).
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Periodic sync cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Sync worker running")

        // WorkManager can start this in a cold process (e.g. after the app process was
        // killed and the accessibility service hasn't reconnected yet). ParentalControlState
        // is an in-memory singleton that defaults to isPaired=false, so without this the
        // safety-net sync would silently no-op precisely in the process-death scenario it
        // exists to recover from. Re-hydrating from Room is idempotent and cheap.
        val dao = ScrollGuardDatabase.getDatabase(applicationContext).parentalDao()
        ParentalControlState.hydrateFromRoom(dao)

        // Only sync if paired.
        if (!ParentalControlState.isPaired) {
            Log.i(TAG, "Not paired — skipping sync")
            return Result.success()
        }

        val role = ParentalControlState.role

        return try {
            val syncEngine = SyncEngine(applicationContext)

            if (role == "child") {
                // Child: pull config (down) + push status (up).
                syncEngine.pullConfig()
                syncEngine.pushStatus()

                // Also push the app catalog periodically.
                val catalogApps = buildAppCatalog()
                if (catalogApps.isNotEmpty()) {
                    syncEngine.pushCatalog(catalogApps)
                }
            }
            // Parent role doesn't need periodic sync — it reads on-demand.

            Log.i(TAG, "Sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.retry()
        }
    }

    /**
     * Builds the launchable app catalog from the child device.
     */
    private fun buildAppCatalog(): List<Map<String, String>> {
        val pm = applicationContext.packageManager
        val launcherIntent = android.content.Intent(
            android.content.Intent.ACTION_MAIN
        ).addCategory(android.content.Intent.CATEGORY_LAUNCHER)

        val seenPackages = HashSet<String>()
        return pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter {
                it.packageName != applicationContext.packageName &&
                        seenPackages.add(it.packageName)
            }
            .map { appInfo ->
                mapOf(
                    "packageName" to appInfo.packageName,
                    "label" to pm.getApplicationLabel(appInfo).toString()
                )
            }
    }
}
