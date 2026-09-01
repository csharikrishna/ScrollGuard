package com.scrollguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.scrollguard.data.ScrollGuardDatabase
import com.scrollguard.parental.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // Tamper signal: if this is a paired child device, a parent-authorized protection layer
        // being removed is exactly the kind of event the parent should learn about immediately,
        // not up to ~10-15 minutes later via generic staleness (see SyncEngine.pushTamperAlert's
        // doc). goAsync() extends this receiver's lifetime long enough for the async Firestore
        // write to actually complete before the system is free to kill the process — a plain
        // fire-and-forget launch from a BroadcastReceiver callback has no such guarantee.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val dao = ScrollGuardDatabase.getDatabase(appContext).parentalDao()
                ParentalControlState.hydrateFromRoom(appContext, dao)
                if (ParentalControlState.isPaired && ParentalControlState.role == "child") {
                    SyncEngine(appContext).pushTamperAlert("device_admin_disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push device-admin-disabled tamper alert", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}