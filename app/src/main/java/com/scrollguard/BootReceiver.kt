package com.scrollguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
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
                    Log.e(TAG, "Failed to restart TimerService after boot", e)
                }
            }
        }
    }
}