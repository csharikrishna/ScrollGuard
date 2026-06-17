package com.scrollguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, context.getString(R.string.toast_protection_enabled), Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, context.getString(R.string.toast_protection_disabled), Toast.LENGTH_SHORT).show()
        // FIX #13: Cleanly stop the session when admin is removed by the user,
        // so the app doesn't continue in a broken half-protected state.
        TimerState.load(context)
        if (TimerState.isRunning()) {
            TimerState.reset(context)
            try {
                val stopIntent = Intent(context, TimerService::class.java).apply { action = "RESET" }
                context.startService(stopIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop TimerService on admin disable", e)
            }
        }
    }
}