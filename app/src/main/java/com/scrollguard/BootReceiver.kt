package com.scrollguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.scrollguard.data.ScrollGuardDatabase
import com.scrollguard.parental.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED fires after this app's own APK is updated/reinstalled — the OS
        // kills TimerService (a foreground service) as part of that, and unlike a real reboot,
        // nothing else was restarting it afterward. The recovery is identical to boot: reload
        // state and resume the service if a session was actually running.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // FIX #11: Single load here is sufficient. TimerService.onCreate()
            // also calls load() — both are idempotent and safe to run sequentially.
            TimerState.load(context)
            if (TimerState.isRunning()) {
                try {
                    // Match C5 fix: explicit "RESUME" action so TimerService.onStartCommand
                    // knows this is a reconnect, not a fresh start.
                    val serviceIntent = Intent(context, TimerService::class.java).apply {
                        action = "RESUME"
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart TimerService after boot/update", e)
                }
            }

            // Hydrate parental control state from Room and schedule sync.
            // This ensures parental restrictions survive reboot (spec Issue E).
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = ScrollGuardDatabase.getDatabase(context).parentalDao()
                    ParentalControlState.hydrateFromRoom(context, dao)
                    if (ParentalControlState.isPaired) {
                        SyncWorker.schedule(context)
                        Log.i(TAG, "Parental state hydrated and sync scheduled after boot")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to hydrate parental state after boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}