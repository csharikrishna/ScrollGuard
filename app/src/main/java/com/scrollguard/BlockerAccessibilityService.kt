package com.scrollguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.scrollguard.data.ScrollGuardDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BlockerAccessibilityService"

        /** Block modes passed to BlockActivity via EXTRA_BLOCK_MODE. */
        const val BLOCK_MODE_FOCUS_TIMER = "FOCUS_TIMER"
        const val BLOCK_MODE_PARENTAL_LIMIT = "PARENTAL_LIMIT"

        /** How often to batch-persist parental consumed time to Room (ms). */
        private const val ROOM_PERSIST_INTERVAL_MS = 15_000L

        /** The 1-second tick interval for parental time accounting (ms). */
        private const val PARENTAL_TICK_INTERVAL_MS = 1_000L
    }

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

    // ── Parental time accounting ────────────────────────────────────────

    /** The package currently being counted for parental time consumption. */
    @Volatile
    private var activeParentalPackage: String? = null

    /** Handler for the 1-second parental tick loop. Runs on the main thread. */
    private val parentalTickHandler = Handler(Looper.getMainLooper())

    /** Accumulated deltas per-package since last Room persist. */
    private val pendingDeltas = mutableMapOf<String, Long>()

    /** Timestamp of the last batch persist to Room. */
    private var lastPersistTime = SystemClock.elapsedRealtime()

    private val parentalTickRunnable = object : Runnable {
        override fun run() {
            tickParentalTime()
            parentalTickHandler.postDelayed(this, PARENTAL_TICK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // The device's home/launcher package must never be blockable (spec Issue I) — if it
        // were, a restricted launcher would make the home screen itself unreachable, with no
        // way back to Settings/ScrollGuard to undo it. Resolved dynamically since it varies by
        // device/OEM (Pixel Launcher, OneUI Home, etc.) and can't be hardcoded.
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ParentalControlState.registerLauncherPackage(resolveInfo?.activityInfo?.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve launcher package", e)
        }

        // Hydrate parental control state from Room before enforcing.
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = ScrollGuardDatabase.getDatabase(applicationContext).parentalDao()
                ParentalControlState.hydrateFromRoom(applicationContext, dao)
                Log.i(TAG, "Parental state hydrated from Room on service connect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hydrate parental state from Room", e)
            }
        }

        // Existing focus timer tick collection.
        tickCollectJob = serviceScope.launch {
            TimerState.tickSignal.collect { checkAndBlockCurrentApp() }
        }

        // Start the parental 1-second tick loop.
        parentalTickHandler.postDelayed(parentalTickRunnable, PARENTAL_TICK_INTERVAL_MS)
    }

    override fun onDestroy() {
        parentalTickHandler.removeCallbacks(parentalTickRunnable)
        // Flush any pending parental time deltas to Room before dying.
        flushPendingDeltasToRoom()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // FIX #1: No TimerState.load() here — state stays fresh via TimerState's in-memory
        // singleton, kept current by TimerService's tick loop in the same process.
        checkAndBlockCurrentApp()
    }

    // ── Blocking logic (dual engine) ────────────────────────────────────

    private fun checkAndBlockCurrentApp() {
        val rootNode = rootInActiveWindow
        val activePkg = rootNode?.packageName?.toString()

        // Update which package is the active foreground for parental tracking.
        updateActiveParentalPackage(activePkg)

        // Engine 1: Focus Timer blocking
        if (TimerState.phase == TimerState.Phase.LOCKED) {
            if (activePkg != null && TimerState.isAppBlocked(activePkg)) {
                triggerBlock(activePkg, BLOCK_MODE_FOCUS_TIMER)
                return
            }
            // FIX #2: Always scan ALL windows (not just when activePkg==null).
            for (window in windows) {
                val windowPkg = window.root?.packageName?.toString()
                if (windowPkg != null && TimerState.isAppBlocked(windowPkg)) {
                    triggerBlock(windowPkg, BLOCK_MODE_FOCUS_TIMER)
                    return
                }
            }
        }

        // Engine 2: Parental quota blocking
        if (activePkg != null && ParentalControlState.isAppQuotaExhausted(activePkg)) {
            triggerBlock(activePkg, BLOCK_MODE_PARENTAL_LIMIT)
            return
        }
        // Also check split-screen windows for parental blocking.
        for (window in windows) {
            val windowPkg = window.root?.packageName?.toString()
            if (windowPkg != null && ParentalControlState.isAppQuotaExhausted(windowPkg)) {
                triggerBlock(windowPkg, BLOCK_MODE_PARENTAL_LIMIT)
                return
            }
        }
    }

    // ── Parental tick loop (1 second, monotonic) ────────────────────────

    /**
     * Updates which package is being tracked for parental time consumption.
     * Events only SELECT the active package; they never INCREMENT time.
     * Time incrementing is done solely by the 1-second tick loop.
     */
    private fun updateActiveParentalPackage(foregroundPkg: String?) {
        if (foregroundPkg == null || ParentalControlState.isExcludedFromBlocking(foregroundPkg)) {
            activeParentalPackage = null
            return
        }
        if (ParentalControlState.isParentallyRestricted(foregroundPkg)) {
            activeParentalPackage = foregroundPkg
        } else {
            activeParentalPackage = null
        }
    }

    /**
     * Called every 1 second by the tick handler. Increments consumed time
     * for the active restricted foreground package.
     */
    private fun tickParentalTime() {
        // Check for day boundary reset before counting.
        if (ParentalControlState.resetDayIfNeeded(applicationContext)) {
            // Day changed — reset pending deltas and persist the reset to Room.
            pendingDeltas.clear()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val dao = ScrollGuardDatabase.getDatabase(applicationContext).parentalDao()
                    val today = java.time.LocalDate.now().toEpochDay()
                    dao.resetConsumedForNewDay(today)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reset consumed for new day in Room", e)
                }
            }
        }

        val pkg = activeParentalPackage ?: return
        if (!ParentalControlState.isParentallyRestricted(pkg)) return

        // Increment in memory (< 1ms).
        ParentalControlState.incrementConsumed(pkg)

        // Accumulate delta for batch Room persist.
        pendingDeltas[pkg] = (pendingDeltas[pkg] ?: 0L) + 1L

        // Batch persist to Room every ~15 seconds.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPersistTime >= ROOM_PERSIST_INTERVAL_MS) {
            flushPendingDeltasToRoom()
            lastPersistTime = now
        }

        // Check if quota just got exhausted — trigger block immediately.
        if (ParentalControlState.isAppQuotaExhausted(pkg)) {
            triggerBlock(pkg, BLOCK_MODE_PARENTAL_LIMIT)
        }
    }

    /**
     * Flushes accumulated time deltas to Room. Called every ~15s and on service destroy.
     */
    private fun flushPendingDeltasToRoom() {
        val deltasToFlush = synchronized(pendingDeltas) {
            if (pendingDeltas.isEmpty()) return
            val copy = HashMap(pendingDeltas)
            pendingDeltas.clear()
            copy
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = ScrollGuardDatabase.getDatabase(applicationContext).parentalDao()
                for ((pkg, delta) in deltasToFlush) {
                    dao.incrementConsumed(pkg, delta)
                }
            } catch (e: Exception) {
                // On failure, re-add deltas so they aren't lost.
                synchronized(pendingDeltas) {
                    for ((pkg, delta) in deltasToFlush) {
                        pendingDeltas[pkg] = (pendingDeltas[pkg] ?: 0L) + delta
                    }
                }
                Log.e(TAG, "Failed to flush parental time deltas to Room", e)
            }
        }
    }

    // ── Block trigger ───────────────────────────────────────────────────

    private fun triggerBlock(packageName: String, blockMode: String) {
        val now = SystemClock.elapsedRealtime()
        // Debounce is a flood guard against rapid duplicate accessibility events for the
        // *same* package — it is not what prevents the GENTLE-mode dismiss/re-block loop
        // (that's handled by TimerState's grace window, checked inside isAppBlocked()).
        if (packageName == lastLaunchedPackage && now - lastLaunch < 500) return
        lastLaunch = now
        lastLaunchedPackage = packageName

        val intent = Intent(this, BlockActivity::class.java).apply {
            putExtra(BlockActivity.EXTRA_BLOCKED_PACKAGE, packageName)
            putExtra(BlockActivity.EXTRA_BLOCK_MODE, blockMode)
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

