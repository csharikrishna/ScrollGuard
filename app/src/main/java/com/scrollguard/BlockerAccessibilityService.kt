package com.scrollguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class BlockerAccessibilityService : AccessibilityService() {

    private var lastLaunch = 0L

    // TimerState is maintained in memory by TimerService and MainActivity in the same process.
    // Reading from memory is instantaneous and avoids SharedPreferences I/O race conditions.
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkAndBlockCurrentApp()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter("com.scrollguard.TICK")
        ContextCompat.registerReceiver(
            this, tickReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // FIX #1: No TimerState.load() here — state stays fresh via tickReceiver
        checkAndBlockCurrentApp()
    }

    private fun checkAndBlockCurrentApp() {
        if (TimerState.phase != TimerState.Phase.LOCKED) return

        val rootNode = rootInActiveWindow
        val activePkg = rootNode?.packageName?.toString()

        if (activePkg != null && TimerState.isAppBlocked(activePkg)) {
            triggerBlock()
            return
        }

        // FIX #2: Always scan ALL windows (not just when activePkg==null).
        // Previously, a blocked app open in split-screen was never caught when
        // the primary window was a non-blocked app (e.g., home screen).
        for (window in windows) {
            val windowPkg = window.root?.packageName?.toString()
            if (windowPkg != null && TimerState.isAppBlocked(windowPkg)) {
                triggerBlock()
                return
            }
        }
    }

    private fun triggerBlock() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 500) return
        lastLaunch = now

        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS  // FIX #9
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
}