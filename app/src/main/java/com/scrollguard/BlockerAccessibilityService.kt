package com.scrollguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockerAccessibilityService : AccessibilityService() {

    private var lastLaunch = 0L
    private var lastLaunchedPackage: String? = null

    // Own lifecycle-scoped coroutine job. AccessibilityService isn't a LifecycleOwner, so this
    // is created in onServiceConnected and cancelled in onDestroy, mirroring what
    // repeatOnLifecycle does for Activities. Replaces the old BroadcastReceiver-based tick:
    // TimerState is maintained in memory by TimerService and this service in the same process,
    // so an in-process StateFlow is both simpler and avoids sending an implicit system-wide
    // broadcast every second (which lint flags as unsafe and which has needless overhead).
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)
    private var tickCollectJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        tickCollectJob = serviceScope.launch {
            TimerState.tickSignal.collect { checkAndBlockCurrentApp() }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // FIX #1: No TimerState.load() here — state stays fresh via TimerState's in-memory
        // singleton, kept current by TimerService's tick loop in the same process.
        checkAndBlockCurrentApp()
    }

    private fun checkAndBlockCurrentApp() {
        if (TimerState.phase != TimerState.Phase.LOCKED) return

        val rootNode = rootInActiveWindow
        val activePkg = rootNode?.packageName?.toString()

        if (activePkg != null && TimerState.isAppBlocked(activePkg)) {
            triggerBlock(activePkg)
            return
        }

        // FIX #2: Always scan ALL windows (not just when activePkg==null).
        // Previously, a blocked app open in split-screen was never caught when
        // the primary window was a non-blocked app (e.g., home screen).
        for (window in windows) {
            val windowPkg = window.root?.packageName?.toString()
            if (windowPkg != null && TimerState.isAppBlocked(windowPkg)) {
                triggerBlock(windowPkg)
                return
            }
        }
    }

    private fun triggerBlock(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        // Debounce is a flood guard against rapid duplicate accessibility events for the
        // *same* package — it is not what prevents the GENTLE-mode dismiss/re-block loop
        // (that's handled by TimerState's grace window, checked inside isAppBlocked()).
        if (packageName == lastLaunchedPackage && now - lastLaunch < 500) return
        lastLaunch = now
        lastLaunchedPackage = packageName

        val intent = Intent(this, BlockActivity::class.java).apply {
            putExtra(BlockActivity.EXTRA_BLOCKED_PACKAGE, packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}
}
