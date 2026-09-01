package com.scrollguard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.scrollguard.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object TimerState {

    private const val TAG = "TimerState"

    /** Minimum configurable duration in minutes */
    const val MIN_DURATION_MIN = 1L
    /** Maximum configurable duration in minutes (24 hours) */
    const val MAX_DURATION_MIN = 1440L

    /** How long a GENTLE-mode dismiss keeps a specific package unblocked. */
    const val GENTLE_GRACE_DURATION_MS = 60_000L

    @Volatile var freeDuration = 3600L
    @Volatile var lockDuration = 600L
    @Volatile var allowDuration = 120L

    enum class Phase { IDLE, FREE, LOCKED, ALLOWED }
    enum class Strictness { GENTLE, NUCLEAR }

    private const val PREFS = "sg_state"
    private val scope = CoroutineScope(Dispatchers.IO)

    // @Volatile: these fields are read from the accessibility service, the main
    // thread (UI), TimerService's tick loop, and IO coroutines. Volatile ensures
    // writes on one thread are visible to reads on another.
    @Volatile var phase = Phase.IDLE
    @Volatile var cycleCount = 0

    // Thread-safe: mutated from UI click handlers (AppPickerActivity) while being
    // read concurrently from the accessibility service callback and IO coroutines.
    // A plain HashSet is not safe under concurrent add()/remove()/contains(); this
    // set is backed by ConcurrentHashMap for correct visibility and mutation.
    @Volatile var monitoredApps: MutableSet<String> = newConcurrentSet()

    /** The package BlockerAccessibilityService most recently observed in the foreground.
     *  Single in-process source of truth for "what's in front right now" — written on every
     *  accessibility event, read here to decide whether the ALLOWED window's usable time
     *  should deplete (see [isUsingMonitoredApp]). Null when nothing relevant is in front, or
     *  before the accessibility service has reported anything (e.g. right after process start). */
    @Volatile var currentForegroundPackage: String? = null

    @Volatile var strictMode = false
    @Volatile var strictness = Strictness.NUCLEAR

    // H5: currentStreak removed — it was never incremented (always 0).
    // If a streak feature is desired, design it with proper daily-completion logic.
    @Volatile var totalSecondsSaved = 0L

    @Volatile var scheduleEnabled = false
    @Volatile var startHour = 9
    @Volatile var endHour = 17

    /** True unless the accessibility service has been detected as unavailable while a
     *  session is running. Read by MainActivity to show a persistent warning even if the
     *  app wasn't foregrounded when the service died; written by TimerService's health check. */
    @Volatile var accessibilityHealthy: Boolean = true

    // Meaningful only while phase == LOCKED, which is a pure wall-clock wait regardless of
    // activity. FREE and ALLOWED no longer use these — see usableRemainingMs below.
    @Volatile private var phaseEndTimeWall = 0L
    @Volatile private var phaseEndTimeElapsed = 0L

    // Usage-metered budget for the ALLOWED phase (ms remaining). Unlike LOCKED, ALLOWED only
    // depletes while a monitored app is actually in the foreground — an unused window simply
    // doesn't advance (it stays exactly where it was, however long the device sits idle),
    // rather than expiring on a clock the user was never actually using up. FREE deliberately
    // stays clock-based (see transitionNext/catchUp): nothing is blocked yet during FREE, so
    // "usage of a monitored app" isn't a meaningful thing to gate it on.
    @Volatile private var usableRemainingMs = 0L
    // Anchor for delta-based usage accounting during ALLOWED — see catchUp(). Reset whenever
    // a new ALLOWED phase begins and whenever a reboot is detected (elapsedRealtime resets then).
    @Volatile private var lastUsageAccountedElapsed = 0L
    // Last elapsed-clock time usableRemainingMs was actually written to disk. save() only ran
    // on phase *transitions* until this was added — meaning a process/service kill mid-ALLOWED
    // (e.g. an OEM background-kill) would reload the value from the start of the window, silently
    // re-granting whatever had already been used. Batched like the parental engine's own
    // consumed-time persistence, not on every tick, to avoid a disk write every second for the
    // life of a long ALLOWED window.
    @Volatile private var lastUsablePersistElapsed = 0L
    private const val USABLE_PERSIST_INTERVAL_MS = 15_000L

    // Analytics accounting: only time actually spent in LOCKED counts as "saved."
    // currentPhaseStartElapsed marks (elapsed-clock) when the *current* phase began;
    // accumulatedLockedMs is the running total of completed LOCKED segments this session.
    @Volatile private var currentPhaseStartElapsed = 0L
    @Volatile private var accumulatedLockedMs = 0L

    // Per-package temporary bypass window (GENTLE-mode dismiss). ConcurrentHashMap-backed
    // so it's safe to read from the accessibility service thread while written from the UI.
    private val graceUntilByPackage = ConcurrentHashMap<String, Long>()

    // In-process replacement for the old "com.scrollguard.TICK" broadcast. Broadcasting a
    // system-wide Intent every second purely for in-process communication is unnecessary
    // overhead and is flagged by lint (UnsafeImplicitIntentLaunch) since it matches
    // non-exported receivers implicitly. A StateFlow is lifecycle-safe and process-local.
    private val _tickSignal = MutableStateFlow(0L)
    val tickSignal: StateFlow<Long> = _tickSignal.asStateFlow()

    private fun newConcurrentSet(): MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun publishTick() {
        _tickSignal.value = SystemClock.elapsedRealtime()
    }

    fun getRemainingSeconds(): Long {
        return when (phase) {
            Phase.IDLE -> 0L
            // FREE and LOCKED are both pure wall-clock waits with an absolute deadline.
            Phase.FREE, Phase.LOCKED -> {
                val nowElapsed = SystemClock.elapsedRealtime()
                ((phaseEndTimeElapsed - nowElapsed) / 1000).coerceAtLeast(0L)
            }
            // ALLOWED is usage-metered: remaining is whatever's left of the budget, not a
            // deadline.
            Phase.ALLOWED -> (usableRemainingMs / 1000).coerceAtLeast(0L)
        }
    }

    /** True while the current foreground app (per [currentForegroundPackage]) is one of
     *  [monitoredApps] — i.e. the ALLOWED phase's usable-time budget should be depleting. */
    private fun isUsingMonitoredApp(): Boolean {
        val pkg = currentForegroundPackage ?: return false
        return monitoredApps.contains(pkg)
    }

    /**
     * Grants [packageName] a temporary bypass so it stops being reported as blocked, even
     * while the phase is LOCKED. Used when the user dismisses a GENTLE-mode block screen —
     * without this, dismissing just reveals the blocked app, which the accessibility service
     * immediately re-detects and re-blocks in a tight loop.
     */
    fun grantGrace(packageName: String, durationMs: Long = GENTLE_GRACE_DURATION_MS) {
        graceUntilByPackage[packageName] = SystemClock.elapsedRealtime() + durationMs
    }

    private fun isInGrace(packageName: String): Boolean {
        val until = graceUntilByPackage[packageName] ?: return false
        if (SystemClock.elapsedRealtime() >= until) {
            graceUntilByPackage.remove(packageName)
            return false
        }
        return true
    }

    /**
     * Clears every outstanding grace window. Called whenever a new LOCKED phase begins and
     * on reset(), so a dismiss from a previous lock cycle (or previous session) can never
     * bleed into a later one and become an unintended indefinite bypass.
     */
    fun clearAllGrace() {
        graceUntilByPackage.clear()
    }

    fun isAppBlocked(packageName: String): Boolean {
        if (phase != Phase.LOCKED) return false
        if (!monitoredApps.contains(packageName)) return false
        if (scheduleEnabled) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour < startHour || hour >= endHour) return false
        }
        if (isInGrace(packageName)) return false
        return true
    }

    fun isRunning(): Boolean = phase != Phase.IDLE

    fun fmtTime(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0)
            "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else
            "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }

    /** Clamps a user-supplied duration (in minutes) to a safe range. */
    fun clampDuration(minutes: Long): Long =
        minutes.coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN)

    fun start(context: Context) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        phase = Phase.FREE
        val durationMs = freeDuration * 1000
        phaseEndTimeWall = nowWall + durationMs
        phaseEndTimeElapsed = nowElapsed + durationMs
        cycleCount = 0
        currentPhaseStartElapsed = nowElapsed
        accumulatedLockedMs = 0L
        usableRemainingMs = 0L
        lastUsageAccountedElapsed = nowElapsed
        lastUsablePersistElapsed = nowElapsed
        clearAllGrace()
        save(context)
    }

    /**
     * Advances the phase to reflect however much real time has actually elapsed, catching up
     * through *every* missed transition in one call (see [catchUp]) rather than just one step.
     * TimerService's 1-second loop calls this in the healthy case, but BlockerAccessibilityService
     * also calls it directly on every accessibility event — so a single call here is what makes
     * blocking decisions self-healing even if the background tick loop was paused (Doze mode,
     * App Standby, or an OEM background-kill) for longer than one phase. Without this, the
     * enforcement decision would trust a phase value that only the (unreliable) tick loop keeps
     * current, and could stay frozen — including frozen *unlocked* — indefinitely.
     */
    fun tick(context: Context) {
        catchUp(context, rebooted = false)
    }

    private fun transitionNext(context: Context, nowWall: Long, nowElapsed: Long) {
        val oldPhase = phase

        // Bank whatever time was actually spent in LOCKED before leaving it.
        if (oldPhase == Phase.LOCKED) {
            accumulatedLockedMs += (nowElapsed - currentPhaseStartElapsed).coerceAtLeast(0L)
        }

        when (phase) {
            Phase.FREE -> {
                phase = Phase.LOCKED
                val d = lockDuration * 1000
                phaseEndTimeWall = nowWall + d
                phaseEndTimeElapsed = nowElapsed + d
                clearAllGrace()
            }
            Phase.LOCKED -> {
                phase = Phase.ALLOWED
                // Usage-metered from here: a fresh budget that only depletes while a
                // monitored app is actually in the foreground (see catchUp()).
                usableRemainingMs = allowDuration * 1000
                lastUsageAccountedElapsed = nowElapsed
                lastUsablePersistElapsed = nowElapsed
            }
            Phase.ALLOWED -> {
                cycleCount++
                phase = Phase.LOCKED
                val d = lockDuration * 1000
                phaseEndTimeWall = nowWall + d
                phaseEndTimeElapsed = nowElapsed + d
                clearAllGrace()
            }
            Phase.IDLE -> {}
        }
        currentPhaseStartElapsed = nowElapsed
        if (oldPhase != phase) save(context)
    }

    fun reset(context: Context) {
        val nowElapsed = SystemClock.elapsedRealtime()
        var lockedMs = accumulatedLockedMs
        if (phase == Phase.LOCKED) {
            lockedMs += (nowElapsed - currentPhaseStartElapsed).coerceAtLeast(0L)
        }
        val secondsSaved = lockedMs / 1000
        if (secondsSaved > 60) {
            totalSecondsSaved += secondsSaved
            val cycles = cycleCount
            scope.launch { DataRepository.getInstance(context).logUsage(secondsSaved, cycles) }
        }
        phase = Phase.IDLE
        phaseEndTimeWall = 0L
        phaseEndTimeElapsed = 0L
        cycleCount = 0
        accumulatedLockedMs = 0L
        currentPhaseStartElapsed = 0L
        usableRemainingMs = 0L
        lastUsageAccountedElapsed = 0L
        lastUsablePersistElapsed = 0L
        clearAllGrace()
        save(context)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("phase", phase.name)
            putLong("phaseEndTimeWall", phaseEndTimeWall)
            putLong("phaseEndTimeElapsed", phaseEndTimeElapsed)
            putLong("currentPhaseStartElapsed", currentPhaseStartElapsed)
            putLong("accumulatedLockedMs", accumulatedLockedMs)
            putLong("usableRemainingMs", usableRemainingMs)
            putLong("lastUsageAccountedElapsed", lastUsageAccountedElapsed)
            putInt("cycleCount", cycleCount)
            // FIX M1: Always pass a NEW HashSet to putStringSet. The Android docs
            // warn that SharedPreferences may reuse the internal set reference,
            // causing writes to be silently dropped if the same object is passed.
            putStringSet("monitoredApps", HashSet(monitoredApps))
            putBoolean("strictMode", strictMode)
            putString("strictness", strictness.name)
            putLong("freeDuration", freeDuration)
            putLong("lockDuration", lockDuration)
            putLong("allowDuration", allowDuration)
            // H5: currentStreak removed (dead field)
            putLong("totalSecondsSaved", totalSecondsSaved)
            putBoolean("scheduleEnabled", scheduleEnabled)
            putInt("startHour", startHour)
            putInt("endHour", endHour)
            apply()
        }
    }

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // FIX C1: Safe enum parsing — corrupted prefs won't crash the app
        phase = try {
            Phase.valueOf(p.getString("phase", "IDLE") ?: "IDLE")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Corrupted phase value in prefs, resetting to IDLE", e)
            Phase.IDLE
        }

        phaseEndTimeWall = p.getLong("phaseEndTimeWall", 0L)
        phaseEndTimeElapsed = p.getLong("phaseEndTimeElapsed", 0L)
        currentPhaseStartElapsed = p.getLong("currentPhaseStartElapsed", 0L)
        if (currentPhaseStartElapsed == 0L && phase != Phase.IDLE) {
            // Migrating from a build that didn't persist this field, or first tick of a
            // freshly-started phase. Anchor to "now" rather than crediting a bogus huge
            // span back to epoch/boot.
            currentPhaseStartElapsed = SystemClock.elapsedRealtime()
        }
        accumulatedLockedMs = p.getLong("accumulatedLockedMs", 0L)
        cycleCount = p.getInt("cycleCount", 0)

        val loadedApps = p.getStringSet("monitoredApps", emptySet()) ?: emptySet()
        val concurrentApps = newConcurrentSet()
        concurrentApps.addAll(loadedApps)
        monitoredApps = concurrentApps

        strictMode = p.getBoolean("strictMode", false)

        // FIX C2: Safe enum parsing for Strictness
        strictness = try {
            Strictness.valueOf(p.getString("strictness", "NUCLEAR") ?: "NUCLEAR")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Corrupted strictness value in prefs, defaulting to NUCLEAR", e)
            Strictness.NUCLEAR
        }

        freeDuration = p.getLong("freeDuration", 3600L)
        lockDuration = p.getLong("lockDuration", 600L)
        allowDuration = p.getLong("allowDuration", 120L)

        // Migration safety for prefs written before usableRemainingMs existed: default to a
        // fresh budget if we're currently in ALLOWED, rather than 0 (which would incorrectly
        // look "already exhausted" and immediately lock someone mid-ALLOWED the moment they
        // update the app). Must run after allowDuration is loaded, just above.
        usableRemainingMs = if (p.contains("usableRemainingMs")) {
            p.getLong("usableRemainingMs", 0L)
        } else if (phase == Phase.ALLOWED) {
            allowDuration * 1000
        } else {
            0L
        }
        lastUsageAccountedElapsed = p.getLong("lastUsageAccountedElapsed", 0L)
        if (lastUsageAccountedElapsed == 0L && phase == Phase.ALLOWED) {
            lastUsageAccountedElapsed = SystemClock.elapsedRealtime()
        }
        // Not persisted — purely an in-memory throttle anchor for batch-persisting
        // usableRemainingMs (see its declaration). Reset fresh on every load so a just-reloaded
        // process doesn't immediately think a persist is overdue.
        lastUsablePersistElapsed = SystemClock.elapsedRealtime()
        // H5: currentStreak removed (dead field)
        totalSecondsSaved = p.getLong("totalSecondsSaved", 0L)
        scheduleEnabled = p.getBoolean("scheduleEnabled", false)
        startHour = p.getInt("startHour", 9)
        endHour = p.getInt("endHour", 17)

        // NOTE: grace is intentionally NOT cleared here. load() looked like a natural
        // place for a "fresh process never inherits stale grace" safety net, but load()
        // is not just a cold-start hook — it's called continuously during normal
        // operation (every tick, by both MainActivity and BlockActivity's StateFlow
        // collectors). Clearing grace here wiped it out within ~1 second of being
        // granted, making the whole GENTLE-mode dismiss mechanism non-functional
        // (caught only by manual on-device testing, not unit tests, since those call
        // TimerState in isolation without another component's load() interleaved).
        // graceUntilByPackage is a plain in-memory map, so a genuinely fresh process
        // already starts with it empty — no explicit clear is needed for that case.

        healState(context)
    }

    /**
     * Recovers state after process death or device reboot.
     * If the elapsed-time clock has drifted (e.g. reboot resets it), recalibrate
     * using wall-clock. Then advance through any expired phases — but cap at
     * MAX_HEAL_ITERATIONS to avoid CPU spin after very long off-periods.
     */
    private fun healState(context: Context) {
        if (phase == Phase.IDLE) return

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        // Recalibrate elapsed-time anchor if device rebooted. elapsedRealtime() is monotonic
        // and resets to (near) zero on every reboot, so if it's now *less* than when the
        // current phase started, a reboot must have happened in between — no arbitrary
        // magnitude threshold is needed (an earlier version required a >24h gap, which meant
        // a reboot during any realistic, non-maximal-length phase went undetected and left
        // the phase stuck LOCKED until post-reboot uptime happened to reach the stale
        // pre-reboot elapsed target, regardless of the real wall-clock deadline).
        val rebooted = nowElapsed < currentPhaseStartElapsed
        if (rebooted) {
            if (phase == Phase.LOCKED) {
                val remaining = (phaseEndTimeWall - nowWall)
                phaseEndTimeElapsed = nowElapsed + remaining
            }
            // The device was off for this whole gap — no LOCKED time was actually being
            // enforced during it, and no ALLOWED usage could have happened either, so don't
            // credit either accounting for it. Resume from now.
            currentPhaseStartElapsed = nowElapsed
            lastUsageAccountedElapsed = nowElapsed
        }

        // The "stuck forever" safety valve only applies to LOCKED, which is a pure wall-clock
        // wait. ALLOWED is usage-metered and is *supposed* to sit exactly where it was however
        // long the device was off — there is no analogous "too long" condition for it; that's
        // the whole point of this phase no longer being clock-driven.
        if (phase == Phase.LOCKED) {
            val cycleTimeMs = (freeDuration + lockDuration + allowDuration) * 1000L
            if (nowWall > phaseEndTimeWall + cycleTimeMs) {
                Log.w(TAG, "healState: Massive offline time detected, resetting to IDLE")
                reset(context)
                return
            }
        }

        val changed = catchUp(context, rebooted)
        if (changed || rebooted) save(context)
    }

    /**
     * Advances phase forward through as many transitions as [nowWall]/[nowElapsed] (captured
     * fresh, internally) actually justify, capped at [maxIterations] to avoid spinning after an
     * extreme gap. Shared by [tick] (the normal per-second/per-event path, always [rebooted] =
     * false) and [healState] (the cold-start/reboot-recovery path). Anchoring each transition to
     * the *theoretical* deadline of the phase that just ended — rather than to "now" — means a
     * phase's configured duration is exact even when the call that notices its end is itself
     * late: that lateness doesn't compound across a chain of catch-up transitions.
     *
     * Pure in-memory arithmetic except for [transitionNext]'s own save-on-change — safe to call
     * on every accessibility event, not just once a second.
     */
    private fun catchUp(context: Context, rebooted: Boolean): Boolean {
        if (phase == Phase.IDLE) return false
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        val maxIterations = 10
        var iterations = 0
        var changed = false

        // Virtual time trackers for LOCKED's chained catch-up (see below) — meaningless for
        // ALLOWED/FREE transitions triggered by budget exhaustion, which always happen "now".
        var virtualNowWall = phaseEndTimeWall
        var virtualNowElapsed = phaseEndTimeElapsed

        while (isRunning() && iterations < maxIterations) {
            var dueForTransition = false
            var transitionAtWall = nowWall
            var transitionAtElapsed = nowElapsed

            when (phase) {
                Phase.FREE -> {
                    // FREE remains a pure wall-clock grace period: nothing is blocked yet
                    // during FREE, so "usage of a monitored app" isn't meaningful to gate it
                    // on. Chains through virtual deadlines exactly as LOCKED does, below.
                    dueForTransition = if (!rebooted) {
                        nowWall >= virtualNowWall && nowElapsed >= virtualNowElapsed
                    } else {
                        nowWall >= virtualNowWall || nowElapsed >= virtualNowElapsed
                    }
                    transitionAtWall = virtualNowWall
                    transitionAtElapsed = virtualNowElapsed
                }
                Phase.ALLOWED -> {
                    // Usage accounting happens here, every time this phase is (re-)evaluated
                    // within a catch-up pass — not just once per tick() call — so a phase
                    // entered mid-catch-up (e.g. LOCKED -> ALLOWED partway through a long
                    // stall) still correctly accounts for real usage over the rest of that
                    // same stall in a single tick() call, rather than needing a second call
                    // to notice. Real elapsed time is only deducted from the budget if a
                    // monitored app is in front right now; otherwise the budget doesn't move.
                    val delta = (nowElapsed - lastUsageAccountedElapsed).coerceAtLeast(0L)
                    lastUsageAccountedElapsed = nowElapsed
                    if (isUsingMonitoredApp()) {
                        usableRemainingMs = (usableRemainingMs - delta).coerceAtLeast(0L)
                        // Batch-persist the in-progress budget so a process/service kill
                        // mid-window (OEM background-kill, crash) can't silently re-grant
                        // already-used time on reload — see USABLE_PERSIST_INTERVAL_MS's doc.
                        if (nowElapsed - lastUsablePersistElapsed >= USABLE_PERSIST_INTERVAL_MS) {
                            lastUsablePersistElapsed = nowElapsed
                            save(context)
                        }
                    }
                    dueForTransition = usableRemainingMs <= 0L
                }
                Phase.LOCKED -> {
                    // The AND-gate normally requires the elapsed clock to corroborate the
                    // wall clock, guarding against a user winding the wall clock forward to
                    // skip a lock early. That guard is meaningless once a reboot has been
                    // detected: the elapsed clock was reset by the reboot itself and has no
                    // data for phases that finished before it, so wall-clock alone is
                    // authoritative for this pass.
                    dueForTransition = if (!rebooted) {
                        nowWall >= virtualNowWall && nowElapsed >= virtualNowElapsed
                    } else {
                        nowWall >= virtualNowWall || nowElapsed >= virtualNowElapsed
                    }
                    transitionAtWall = virtualNowWall
                    transitionAtElapsed = virtualNowElapsed
                }
                Phase.IDLE -> {}
            }

            if (!dueForTransition) break
            changed = true
            transitionNext(context, transitionAtWall, transitionAtElapsed)
            virtualNowWall = phaseEndTimeWall
            virtualNowElapsed = phaseEndTimeElapsed
            iterations++
        }

        if (iterations >= maxIterations && isRunning()) {
            Log.w(TAG, "catchUp hit iteration cap ($maxIterations), forcing IDLE")
            reset(context)
            return true
        }
        return changed
    }
}
