package com.scrollguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.google.firebase.firestore.ListenerRegistration
import com.scrollguard.data.ScrollGuardDatabase
import com.scrollguard.parental.SyncEngine
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

        /**
         * True only between a real `onServiceConnected()` and the matching `onUnbind()`/
         * `onDestroy()` in *this* process. This is the runtime half of [AccessibilityUtils]'s
         * protection-state check (see its doc for why config state alone isn't enough).
         *
         * Deliberately in-memory only, never persisted: a fresh process (after an OEM kill, a
         * crash, or a reboot) always starts with this false until the service instance genuinely
         * reconnects, so it can never report a stale "true" for a service that is no longer
         * actually running — which a SharedPreferences-backed flag could.
         */
        @Volatile
        var isRuntimeConnected: Boolean = false
            private set
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

    /** Live Firestore listeners for parental config changes (see [SyncEngine.attachLiveConfigListener]) — attached only once this device is confirmed to be a paired child, and removed in [onDestroy]. */
    private var liveConfigListeners: List<ListenerRegistration> = emptyList()

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
        isRuntimeConnected = true
        // Immediate, event-driven truth rather than waiting for TimerService's next polled
        // health check (up to 5s away) — see AccessibilityUtils' doc on why runtime state must
        // win over a merely-polled config check.
        TimerState.accessibilityHealthy = true

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

        // Hydrate parental control state from Room before enforcing, then attach a live config
        // listener if this device is a paired child — closes the up-to-15-minute SyncWorker gap
        // for as long as this service (effectively always) stays alive. See
        // SyncEngine.attachLiveConfigListener for why this isn't FCM.
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dao = ScrollGuardDatabase.getDatabase(applicationContext).parentalDao()
                ParentalControlState.hydrateFromRoom(applicationContext, dao)
                Log.i(TAG, "Parental state hydrated from Room on service connect")

                if (ParentalControlState.isPaired && ParentalControlState.role == "child") {
                    val fid = ParentalControlState.familyId
                    if (fid != null) {
                        liveConfigListeners = SyncEngine(applicationContext)
                            .attachLiveConfigListener(fid, serviceScope)
                    }
                }
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

    /**
     * Called by the system as soon as it decides to unbind this service — reliably fires the
     * moment the user turns the Accessibility toggle off, ahead of (and more promptly than)
     * [onDestroy]. This is the immediate, event-driven "protection just stopped" signal Part 12
     * of the investigation calls for, rather than waiting on the next polled health check.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        isRuntimeConnected = false
        TimerState.accessibilityHealthy = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isRuntimeConnected = false
        TimerState.accessibilityHealthy = false
        parentalTickHandler.removeCallbacks(parentalTickRunnable)
        // Flush any pending parental time deltas to Room before dying.
        flushPendingDeltasToRoom()
        hidePipBlockOverlay()
        liveConfigListeners.forEach { it.remove() }
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

        // Update the shared foreground-package signal before ticking the focus-timer engine:
        // TimerState's ALLOWED phase only depletes its usable-time budget while a monitored
        // app is actually in front (see TimerState.tick/catchUp), so it needs this pass's
        // foreground package before, not after, ticking.
        TimerState.currentForegroundPackage = activePkg

        // Self-heal the phase from elapsed real time on every check, rather than trusting
        // whatever TimerState.phase currently holds. TimerService's 1-second Handler loop is
        // the normal way phase advances, but Android does not guarantee that loop keeps firing
        // (Doze mode, App Standby, OEM background-kill) — this AccessibilityService itself is
        // far more likely to survive such stalls, so it must be able to notice a phase is stale
        // and catch up before making a block/allow decision, instead of acting on a frozen value.
        TimerState.tick(applicationContext)

        // Update which package is the active foreground for parental tracking.
        updateActiveParentalPackage(activePkg)

        // Whether a blocked package was found ONLY via the all-windows fallback scan this pass
        // (PiP, or the non-active pane of split-screen) rather than as the true active window.
        // Verified on-device: BlockActivity's launch DOES gain focus in this case, but Android's
        // platform PiP windowing keeps the offending app's PiP surface rendered on top of the
        // newly-launched BlockActivity regardless — the block screen exists underneath but the
        // restricted content stays visible and (per PiP's own design) at least partially
        // interactive. requiresOverlayBackstop tracks whether that gap applies this pass.
        var requiresOverlayBackstop = false

        // The active window's own screen bounds, used by isGenuineMultiWindowMatch below to
        // tell a real concurrently-visible split-screen pane apart from a background app's
        // window that the accessibility API still happens to enumerate. See that function's
        // doc for why this distinction is load-bearing (root-caused from a real device report:
        // the black, input-swallowing PiP backstop overlay was firing on ordinary single-window
        // blocks, because the just-blocked app's now-backgrounded window was still showing up
        // in getWindows() — on some OEM window managers a stopped activity's window is kept
        // enumerable noticeably longer than on stock AOSP, long enough for the very next
        // accessibility tick to see it and wrongly treat it as a second, simultaneously-visible
        // pane).
        val activeWindowBounds = rootInActiveWindow?.let { root ->
            Rect().also { root.getBoundsInScreen(it) }
        }

        // Engine 1: Parental quota blocking — checked FIRST. These two engines used to be fully
        // independent checks, each free to call triggerBlock() for the same active package.
        // triggerBlock()'s own duplicate-launch debounce (same package within 500ms) then
        // silently swallowed whichever engine's call happened to run second — meaning the Focus
        // Timer engine (checked first in the old code order) always won that race and its
        // BlockActivity screen (with its Emergency Pass button) displayed instead of the
        // parental one, regardless of which restriction should actually take precedence. A
        // parent-set limit must never be maskable by a child's own Focus Timer mechanisms, so
        // parental blocking now runs first, and parentalHandledActivePkg tells Engine 2 below to
        // skip the active package if this engine already claimed it this pass.
        var parentalHandledActivePkg = false
        if (activePkg != null && ParentalControlState.isAppQuotaExhausted(activePkg)) {
            triggerBlock(activePkg, BLOCK_MODE_PARENTAL_LIMIT)
            parentalHandledActivePkg = true
        } else {
            // Also check split-screen/PiP windows for parental blocking.
            for (window in windows) {
                val windowPkg = window.root?.packageName?.toString()
                if (windowPkg != null && ParentalControlState.isAppQuotaExhausted(windowPkg) &&
                    isGenuineMultiWindowMatch(window, activeWindowBounds)
                ) {
                    triggerBlock(windowPkg, BLOCK_MODE_PARENTAL_LIMIT)
                    requiresOverlayBackstop = true
                    break
                }
            }
        }

        // Engine 2: Focus Timer blocking. Skips the active package if Engine 1 already claimed
        // it above — see the comment on parentalHandledActivePkg.
        if (TimerState.phase == TimerState.Phase.LOCKED) {
            if (activePkg != null && !parentalHandledActivePkg && TimerState.isAppBlocked(activePkg)) {
                triggerBlock(activePkg, BLOCK_MODE_FOCUS_TIMER)
            } else if (!parentalHandledActivePkg) {
                for (window in windows) {
                    val windowPkg = window.root?.packageName?.toString()
                    if (windowPkg != null && TimerState.isAppBlocked(windowPkg) &&
                        isGenuineMultiWindowMatch(window, activeWindowBounds)
                    ) {
                        triggerBlock(windowPkg, BLOCK_MODE_FOCUS_TIMER)
                        requiresOverlayBackstop = true
                        break
                    }
                }
            }
        }

        if (requiresOverlayBackstop) showPipBlockOverlay() else hidePipBlockOverlay()
    }

    /**
     * True only if [window] is a genuinely, concurrently-visible second window — real PiP, or
     * the non-focused pane of a real split-screen layout — as opposed to a background app's
     * window that getWindows() still happens to report even though nothing of it is actually
     * on screen. Two ways a window can prove that:
     *   1. It reports itself as a real PiP window ([AccessibilityWindowInfo.isInPictureInPictureMode]).
     *   2. Its on-screen bounds don't overlap the active window's bounds at all — which is what
     *      distinguishes a real side-by-side/stacked split-screen pane (disjoint regions, by
     *      definition) from a fully-eclipsed background window sitting exactly behind the
     *      current full-screen foreground window (identical/overlapping bounds).
     * A background window whose bounds happen to still equal the active window's full-screen
     * bounds fails both checks and is correctly ignored, rather than triggering a block (and the
     * opaque, touch-swallowing overlay) for an app that isn't actually visible to the user.
     */
    private fun isGenuineMultiWindowMatch(window: AccessibilityWindowInfo, activeBounds: Rect?): Boolean {
        if (window.isInPictureInPictureMode) return true
        if (activeBounds == null) return true // no active-window bounds to compare against
        val windowBounds = Rect()
        window.getBoundsInScreen(windowBounds)
        // A degenerate (zero-area) rect isn't actually visible anywhere on screen, so it can't
        // be a genuine split-screen pane either — without this check it would trivially pass
        // the "doesn't overlap" test below and reintroduce the false-positive this function
        // exists to prevent.
        if (windowBounds.isEmpty) return false
        return !Rect.intersects(windowBounds, activeBounds)
    }

    // ── PiP/split-screen overlay backstop ───────────────────────────────

    /** The overlay view currently shown, or null if none is up. */
    private var pipOverlayView: View? = null

    /**
     * Shows a full-screen, opaque TYPE_ACCESSIBILITY_OVERLAY window — a window type reserved for
     * bound AccessibilityServices specifically so they can draw over other apps' content,
     * including PiP windows, without needing the SYSTEM_ALERT_WINDOW permission at all. This is
     * the backstop for the PiP/multi-window bypass: BlockActivity's own launch does not reliably
     * gain top z-order over a PiP window (that window is designed by the platform to float above
     * regular app windows), so this overlay provides guaranteed visual AND touch coverage
     * (it is focusable/touchable, not click-through) for as long as the bypass condition holds.
     */
    private fun showPipBlockOverlay() {
        if (pipOverlayView != null) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val overlay = View(this).apply { setBackgroundColor(Color.BLACK) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                0, // focusable and touchable — must consume input so PiP's own controls can't be reached
                PixelFormat.OPAQUE
            )
            wm.addView(overlay, params)
            pipOverlayView = overlay
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show PiP/multi-window block overlay", e)
        }
    }

    private fun hidePipBlockOverlay() {
        val view = pipOverlayView ?: return
        pipOverlayView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove PiP/multi-window block overlay", e)
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
            synchronized(pendingDeltas) { pendingDeltas.clear() }
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

        // Accumulate delta for batch Room persist. Synchronized because flushPendingDeltasToRoom's
        // failure-retry path (below) mutates this same map from a background IO coroutine —
        // without this, that was the only side taking the lock, which protects nothing: a plain
        // HashMap mutated concurrently from two threads with only one side synchronized is just
        // as unsafe as neither side being synchronized, and could throw or corrupt the map.
        synchronized(pendingDeltas) {
            pendingDeltas[pkg] = (pendingDeltas[pkg] ?: 0L) + 1L
        }

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

