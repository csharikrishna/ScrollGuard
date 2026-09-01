package com.scrollguard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TimerState is the core state machine, so these tests run it end-to-end (real
 * SharedPreferences, real clocks via Robolectric) rather than mocking it apart. Phase
 * transitions gated on LOCKED require *both* the wall clock and SystemClock.elapsedRealtime()
 * to pass the deadline, so short real sleeps are used rather than assuming a specific
 * Robolectric shadow-clock implementation detail.
 */
@RunWith(RobolectricTestRunner::class)
class TimerStateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sg_state", Context.MODE_PRIVATE).edit().clear().commit()

        TimerState.phase = TimerState.Phase.IDLE
        TimerState.cycleCount = 0
        TimerState.monitoredApps.clear()
        TimerState.strictMode = false
        TimerState.strictness = TimerState.Strictness.NUCLEAR
        TimerState.totalSecondsSaved = 0L
        TimerState.scheduleEnabled = false
        TimerState.freeDuration = 3600L
        TimerState.lockDuration = 600L
        TimerState.allowDuration = 120L
        TimerState.accessibilityHealthy = true
        TimerState.clearAllGrace()
    }

    /**
     * Advances both clocks TimerState reads: a real sleep so System.currentTimeMillis()
     * (unshadowed by Robolectric) genuinely moves forward, plus an explicit shadow-clock
     * advance so SystemClock.elapsedRealtime() does too — Robolectric's elapsedRealtime does
     * NOT track real sleep time on its own. LOCKED-phase exits require *both* clocks to have
     * passed the deadline (see TimerState.tick's AND-gate for Phase.LOCKED), so relying on
     * sleep alone only works for the OR-gated non-LOCKED transitions.
     */
    private fun advanceClocks(seconds: Long) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))
        Thread.sleep(seconds * 1000 + 300)
    }

    // ---- Basic phase machine ----

    @Test
    fun start_entersFreePhase_withFullRemainingTime() {
        TimerState.freeDuration = 100L
        TimerState.start(context)

        assertEquals(TimerState.Phase.FREE, TimerState.phase)
        assertTrue(TimerState.isRunning())
        val remaining = TimerState.getRemainingSeconds()
        assertTrue("expected remaining close to 100, was $remaining", remaining in 95..100)
    }

    @Test
    fun tick_freeToLocked_afterDurationElapses() {
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 60L
        TimerState.start(context)
        assertEquals(TimerState.Phase.FREE, TimerState.phase)

        advanceClocks(1)
        TimerState.tick(context)

        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)
    }

    @Test
    fun fullCycle_incrementsCycleCountOnlyOnAllowedToLocked() {
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 1L
        TimerState.allowDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)

        // FREE -> LOCKED
        advanceClocks(1); TimerState.tick(context)
        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)
        assertEquals(0, TimerState.cycleCount)

        // LOCKED -> ALLOWED
        advanceClocks(1); TimerState.tick(context)
        assertEquals(TimerState.Phase.ALLOWED, TimerState.phase)
        assertEquals(0, TimerState.cycleCount)

        // ALLOWED -> LOCKED only once the monitored app has actually been used for the full
        // allowed duration (this is now usage-metered, not clock-based) -- this is the point
        // a cycle counts as completed.
        TimerState.currentForegroundPackage = "com.instagram.android"
        advanceClocks(1); TimerState.tick(context)
        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)
        assertEquals(1, TimerState.cycleCount)
        TimerState.currentForegroundPackage = null
    }

    @Test
    fun reset_returnsToIdle_andClearsCycleCount() {
        TimerState.freeDuration = 1L
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        TimerState.reset(context)

        assertEquals(TimerState.Phase.IDLE, TimerState.phase)
        assertFalse(TimerState.isRunning())
        assertEquals(0, TimerState.cycleCount)
        assertEquals(0L, TimerState.getRemainingSeconds())
    }

    @Test
    fun corruptedPhaseInPrefs_fallsBackToIdle_insteadOfCrashing() {
        context.getSharedPreferences("sg_state", Context.MODE_PRIVATE).edit()
            .putString("phase", "NOT_A_REAL_PHASE")
            .apply()

        TimerState.load(context)

        assertEquals(TimerState.Phase.IDLE, TimerState.phase)
    }

    // ---- Duration clamping ----

    @Test
    fun clampDuration_boundsToMinAndMax() {
        assertEquals(TimerState.MIN_DURATION_MIN, TimerState.clampDuration(0))
        assertEquals(TimerState.MIN_DURATION_MIN, TimerState.clampDuration(-500))
        assertEquals(TimerState.MAX_DURATION_MIN, TimerState.clampDuration(999_999))
        assertEquals(1L, TimerState.clampDuration(1))
        assertEquals(1440L, TimerState.clampDuration(1440))
    }

    // ---- GENTLE-mode grace window ----

    @Test
    fun isAppBlocked_falseWhenNotMonitored() {
        TimerState.freeDuration = 1L
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        assertFalse(TimerState.isAppBlocked("com.not.monitored"))
    }

    @Test
    fun dismiss_grantsGrace_soBlockedAppStaysAccessible() {
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        assertTrue(TimerState.isAppBlocked("com.instagram.android"))

        TimerState.grantGrace("com.instagram.android", durationMs = 2000L)

        assertFalse(
            "app should be unblocked during its grace window",
            TimerState.isAppBlocked("com.instagram.android")
        )
    }

    @Test
    fun grace_expiresOnItsOwn_andAppBecomesBlockedAgain() {
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        TimerState.grantGrace("com.instagram.android", durationMs = 300L)
        assertFalse(TimerState.isAppBlocked("com.instagram.android"))

        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        Thread.sleep(500)

        assertTrue(
            "grace should have expired and the app should be blocked again",
            TimerState.isAppBlocked("com.instagram.android")
        )
    }

    @Test
    fun grace_isPerPackage_notGlobal() {
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.monitoredApps.add("com.tiktok")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        TimerState.grantGrace("com.instagram.android")

        assertFalse(TimerState.isAppBlocked("com.instagram.android"))
        assertTrue("a different package must not inherit another package's grace", TimerState.isAppBlocked("com.tiktok"))
    }

    @Test
    fun newLockedPhase_clearsStaleGraceFromPreviousCycle() {
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 1L
        TimerState.allowDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)

        advanceClocks(1); TimerState.tick(context) // -> LOCKED
        TimerState.grantGrace("com.instagram.android", durationMs = 60_000L) // long grace
        assertFalse(TimerState.isAppBlocked("com.instagram.android"))

        advanceClocks(1); TimerState.tick(context) // -> ALLOWED
        // Simulate actually using the monitored app during ALLOWED so its usable budget
        // depletes and the window can end.
        TimerState.currentForegroundPackage = "com.instagram.android"
        advanceClocks(1); TimerState.tick(context) // -> LOCKED again (new lock cycle)
        TimerState.currentForegroundPackage = null

        assertTrue(
            "a dismiss from a previous lock cycle must not bypass a later one",
            TimerState.isAppBlocked("com.instagram.android")
        )
    }

    @Test
    fun reset_clearsGraceState() {
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED
        TimerState.grantGrace("com.instagram.android", durationMs = 60_000L)

        TimerState.reset(context)
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        assertTrue(
            "grace from a fully-ended session must not bleed into the next one",
            TimerState.isAppBlocked("com.instagram.android")
        )
    }

    @Test
    fun loadDuringActiveSession_doesNotWipeOutActiveGrace() {
        // Regression test: load() is not just a cold-start hook, it is called on every
        // single tick by both MainActivity's and BlockActivity's StateFlow collectors.
        // An earlier version cleared all grace inside load() as a defensive measure,
        // which wiped out a just-granted GENTLE-mode dismiss within ~1 second — caught
        // only by manual on-device testing, since it requires load() to be called by a
        // *different* component while grace is active, which a unit test calling
        // TimerState in isolation would never naturally exercise.
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED
        TimerState.grantGrace("com.instagram.android", durationMs = 60_000L)

        // Simulates another component (e.g. MainActivity's tick collector) reloading
        // state from disk while the dismissed app's grace window is still active.
        TimerState.load(context)

        assertFalse(
            "a routine load() during an active session must not clear live grace",
            TimerState.isAppBlocked("com.instagram.android")
        )
    }

    @Test
    fun freshProcessLoad_startsWithNoGrace() {
        // A genuinely new process starts with an empty in-memory grace map by
        // construction — no explicit clearing is needed (or, per the regression above,
        // wanted) for this case.
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        assertTrue(TimerState.isAppBlocked("com.instagram.android"))
    }

    // ---- Concurrency: monitoredApps must survive concurrent mutation/reads ----

    @Test
    fun monitoredApps_survivesConcurrentAddAndRead() {
        val threads = 8
        val perThread = 200
        val latch = CountDownLatch(threads)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(threads) { t ->
            Thread {
                try {
                    repeat(perThread) { i ->
                        val pkg = "com.test.app.$t.$i"
                        TimerState.monitoredApps.add(pkg)
                        TimerState.monitoredApps.contains(pkg)
                        if (i % 10 == 0) TimerState.monitoredApps.remove(pkg)
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals("concurrent mutation must not throw (e.g. ConcurrentModificationException)", 0, failures.get())
    }

    // ---- Process-death / multi-package grace interplay ----

    @Test
    fun multipleBlockedPackages_eachTracksItsOwnGraceIndependently() {
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.a")
        TimerState.monitoredApps.add("com.b")
        TimerState.monitoredApps.add("com.c")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        TimerState.grantGrace("com.a")
        TimerState.grantGrace("com.b")

        assertFalse(TimerState.isAppBlocked("com.a"))
        assertFalse(TimerState.isAppBlocked("com.b"))
        assertTrue(TimerState.isAppBlocked("com.c"))
    }

    // ---- Reboot recovery ----

    @Test
    fun healState_recoversFromReboot_evenWithShortLockDuration() {
        // Simulate: the device had been up ~90 minutes before the session started, the user
        // started a realistic 10-minute LOCKED phase, then the device rebooted with only two
        // minutes left on the lock. elapsedRealtime() resets to (near) zero on reboot, but
        // nowWall keeps advancing normally, so by real wall-clock time the lock is long over
        // by the time load() next runs (e.g. the very next tick after boot completes).
        val preBootUptimeMs = 90L * 60_000L // 90 minutes of uptime before the session
        val lockDurationMs = 600_000L // 10 minutes
        val wallNow = System.currentTimeMillis()

        val prefs = context.getSharedPreferences("sg_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("phase", "LOCKED")
            // Wall deadline passed a minute ago: a single LOCKED -> ALLOWED transition
            // resolves this, landing with ~60s of headroom left in ALLOWED (comfortably
            // clear of the few milliseconds of real execution time this test itself takes).
            .putLong("phaseEndTimeWall", wallNow - 60_000L)
            // But the persisted elapsed-clock deadline reflects the PRE-REBOOT epoch, where
            // uptime was already 90 minutes when the phase started.
            .putLong("phaseEndTimeElapsed", preBootUptimeMs + lockDurationMs)
            .putLong("currentPhaseStartElapsed", preBootUptimeMs)
            .putLong("accumulatedLockedMs", 0L)
            .putInt("cycleCount", 0)
            .putLong("freeDuration", 3600L)
            .putLong("lockDuration", 600L)
            .putLong("allowDuration", 120L)
            .apply()

        // Robolectric's elapsedRealtime() starts near zero for this test — standing in for
        // "the device just rebooted," since real elapsedRealtime resets to zero on reboot too.
        TimerState.load(context)

        assertEquals(
            "a phase whose wall-clock deadline has long passed must not stay LOCKED " +
                "just because a reboot reset the elapsed-time clock",
            TimerState.Phase.ALLOWED,
            TimerState.phase
        )
    }

    @Test
    fun healState_extremeOfflineGapAfterReboot_stillResetsSafelyToIdle_notStuckForever() {
        // An offline gap so large it would require more transitions than the heal loop's
        // safety cap allows to fully replay is intentionally reset to IDLE rather than
        // partially healed — this is the pre-existing "massive offline" safety valve, and
        // it must still terminate (not spin or leave the app stuck LOCKED) even when the
        // gap is caused by a reboot rather than the app simply being backgrounded.
        val preBootUptimeMs = 90L * 60_000L
        val wallNow = System.currentTimeMillis()

        val prefs = context.getSharedPreferences("sg_state", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("phase", "LOCKED")
            .putLong("phaseEndTimeWall", wallNow - TimeUnit.DAYS.toMillis(2))
            .putLong("phaseEndTimeElapsed", preBootUptimeMs + 600_000L)
            .putLong("currentPhaseStartElapsed", preBootUptimeMs)
            .putLong("accumulatedLockedMs", 0L)
            .putInt("cycleCount", 0)
            .putLong("freeDuration", 3600L)
            .putLong("lockDuration", 600L)
            .putLong("allowDuration", 120L)
            .apply()

        TimerState.load(context)

        assertEquals(
            "an extreme offline gap must terminate safely at IDLE, not spin or stay stuck LOCKED",
            TimerState.Phase.IDLE,
            TimerState.phase
        )
    }

    // ---- Self-healing catch-up: correctness must not depend on a live tick loop ----

    @Test
    fun tick_selfHealsMissedTransitions_butStopsAtAllowed_whenAppWasNeverUsed() {
        // Reproduces the real bug behind the user's report: BlockerAccessibilityService makes
        // its block/allow decision by calling tick() directly (not load()), and previously
        // tick() only ever advanced ONE phase per call, and ALLOWED's window expired purely on
        // the clock regardless of actual usage. TimerService's 1-second Handler loop is what's
        // *supposed* to keep calling tick() often enough that gaps never matter — but Android
        // does not guarantee that loop keeps firing (Doze mode, App Standby, an OEM
        // background-kill). This simulates that loop having been stalled across the FREE->LOCKED
        // and LOCKED->ALLOWED boundaries, with zero tick() calls and the monitored app never in
        // the foreground, then makes exactly ONE tick() call. The two purely clock-based
        // transitions must still resolve correctly, but the chain must NOT then fall through
        // ALLOWED->LOCKED, since nothing has actually used the monitored app to deplete that
        // window's budget — it must land in ALLOWED and stay there.
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 1L
        TimerState.allowDuration = 1L
        TimerState.start(context) // FREE, deadline at +1s

        advanceClocks(3) // 3s pass with NO tick() calls and no usage at all -- the stall

        TimerState.tick(context) // the single check post-stall

        assertEquals(
            "with no usage recorded, catch-up must resolve the clock-based FREE->LOCKED->ALLOWED " +
                "chain but stop there -- ALLOWED must not expire on the clock alone",
            TimerState.Phase.ALLOWED,
            TimerState.phase
        )
        assertEquals(0, TimerState.cycleCount)
    }

    @Test
    fun tick_selfHealsMissedTransitions_cascadesThroughAllowed_whenAppWasActuallyUsedThroughout() {
        // Same stalled-tick-loop shape as above, but this time the monitored app genuinely was
        // in the foreground for the whole gap (e.g. the user opened it right as the loop
        // stalled). The ALLOWED window's budget must be depleted by that real usage and the
        // chain must continue on into the next LOCKED, counting a completed cycle -- proving the
        // fix doesn't just freeze ALLOWED unconditionally, only when genuinely unused.
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 1L
        TimerState.allowDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context) // FREE, deadline at +1s

        TimerState.currentForegroundPackage = "com.instagram.android"
        advanceClocks(3) // 3s pass with NO tick() calls, app in foreground the whole time

        TimerState.tick(context) // the single check post-stall

        assertEquals(
            "with the monitored app genuinely in the foreground the whole time, the chain must " +
                "cascade all the way through ALLOWED into the next LOCKED",
            TimerState.Phase.LOCKED,
            TimerState.phase
        )
        assertEquals(1, TimerState.cycleCount)
        TimerState.currentForegroundPackage = null
    }

    @Test
    fun unusedAllowedWindow_doesNotExpire_evenWhenNextCheckedMuchLater() {
        // This is the core fix. Previously, ALLOWED's usable time was purely clock-based and
        // expired whether or not the user ever opened the monitored app during it -- a reported
        // "odd/even minutes" alternation with no relationship to actual usage. Now, ALLOWED only
        // depletes while a monitored app is actually in the foreground. Not opening it at all --
        // even across a long real-time gap, and even when the first check after that gap happens
        // very late -- must never cause the window to expire or the remaining time to shrink.
        TimerState.freeDuration = 5L
        TimerState.lockDuration = 5L
        TimerState.allowDuration = 5L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context) // FREE, 0-5s

        advanceClocks(5); TimerState.tick(context) // -> LOCKED, 5-10s
        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)

        advanceClocks(5); TimerState.tick(context) // -> ALLOWED at t=10s, budget = 5s
        assertEquals(TimerState.Phase.ALLOWED, TimerState.phase)

        // The user never opens the monitored app -- simulate a long real-time gap with no
        // usage and no tick() calls, then check very late.
        advanceClocks(30)
        TimerState.tick(context)

        assertEquals(
            "an ALLOWED window that was never actually used must not expire just because a " +
                "lot of clock time passed",
            TimerState.Phase.ALLOWED,
            TimerState.phase
        )
        assertEquals(
            "the unused window's remaining budget must still be fully intact",
            5L,
            TimerState.getRemainingSeconds()
        )
        assertEquals(0, TimerState.cycleCount)
    }

    @Test
    fun allowedWindow_depletesOnlyWhileMonitoredAppIsInForeground_andBanksWhenNot() {
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 1L
        TimerState.allowDuration = 4L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)

        advanceClocks(1); TimerState.tick(context) // -> LOCKED
        advanceClocks(1); TimerState.tick(context) // -> ALLOWED, budget = 4s
        assertEquals(TimerState.Phase.ALLOWED, TimerState.phase)

        // Use the monitored app for 2 of the 4 allowed seconds...
        TimerState.currentForegroundPackage = "com.instagram.android"
        advanceClocks(2); TimerState.tick(context)
        assertEquals(TimerState.Phase.ALLOWED, TimerState.phase)
        assertEquals(2L, TimerState.getRemainingSeconds())

        // ...then stop using it. The remaining 2 seconds must stay banked, not keep draining
        // just because more clock time passes.
        TimerState.currentForegroundPackage = null
        advanceClocks(10); TimerState.tick(context)
        assertEquals(
            "remaining budget must freeze once the monitored app leaves the foreground",
            TimerState.Phase.ALLOWED,
            TimerState.phase
        )
        assertEquals(2L, TimerState.getRemainingSeconds())

        // Resume using it -- the remaining 2 seconds should deplete and then lock.
        TimerState.currentForegroundPackage = "com.instagram.android"
        advanceClocks(2); TimerState.tick(context)
        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)
        assertEquals(1, TimerState.cycleCount)
        TimerState.currentForegroundPackage = null
    }

    @Test
    fun freePhase_remainsClockBased_unaffectedByForegroundApp() {
        // FREE is a one-time initial grace period before anything is blocked yet, so it
        // deliberately stays clock-based, unlike ALLOWED -- "usage of a monitored app" isn't a
        // meaningful concept to gate it on. No monitored app is ever put in the foreground here,
        // yet FREE must still expire exactly on schedule.
        TimerState.freeDuration = 2L
        TimerState.lockDuration = 60L
        TimerState.start(context)

        advanceClocks(2); TimerState.tick(context)

        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)
    }

    @Test
    fun usableRemainingMs_survivesProcessRestart_withoutDepletingFromElapsedTimeAlone() {
        TimerState.freeDuration = 1L
        TimerState.lockDuration = 1L
        TimerState.allowDuration = 10L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)

        advanceClocks(1); TimerState.tick(context) // -> LOCKED
        advanceClocks(1); TimerState.tick(context) // -> ALLOWED, budget = 10s
        assertEquals(TimerState.Phase.ALLOWED, TimerState.phase)

        // Simulate the process dying and a fresh load() picking persisted state back up, with
        // real time having passed but the monitored app never in the foreground meanwhile.
        TimerState.load(context)

        assertEquals(TimerState.Phase.ALLOWED, TimerState.phase)
        assertEquals(
            "reloading persisted state must not itself drain the usable budget",
            10L,
            TimerState.getRemainingSeconds()
        )
    }

    @Test
    fun rapidRepeatedChecks_aroundPhaseTransition_transitionExactlyOnce() {
        // Simulates the accessibility service firing many times in quick succession (normal
        // behavior around any window-state change) right around a phase boundary. Repeated
        // tick() calls before the deadline must be no-ops; the transition must happen exactly
        // once, not be skipped or double-applied.
        TimerState.freeDuration = 2L
        TimerState.lockDuration = 60L
        TimerState.start(context)

        repeat(20) { TimerState.tick(context) } // rapid re-checks, well before the deadline
        assertEquals(TimerState.Phase.FREE, TimerState.phase)

        advanceClocks(2)
        repeat(20) { TimerState.tick(context) } // rapid re-checks, at/after the deadline

        assertEquals(TimerState.Phase.LOCKED, TimerState.phase)
        assertEquals(0, TimerState.cycleCount)
    }

    @Test
    fun repeatedDismissAttempts_areIdempotentAndExtendGrace() {
        TimerState.freeDuration = 1L
        TimerState.monitoredApps.add("com.instagram.android")
        TimerState.start(context)
        advanceClocks(1); TimerState.tick(context) // -> LOCKED

        repeat(5) { TimerState.grantGrace("com.instagram.android", durationMs = 500L) }

        assertFalse(TimerState.isAppBlocked("com.instagram.android"))
    }
}
