package com.scrollguard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.scrollguard.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object TimerState {

    private const val TAG = "TimerState"

    /** Minimum configurable duration in minutes */
    const val MIN_DURATION_MIN = 1L
    /** Maximum configurable duration in minutes (24 hours) */
    const val MAX_DURATION_MIN = 1440L

    var freeDuration = 3600L
    var lockDuration = 600L
    var allowDuration = 120L

    enum class Phase { IDLE, FREE, LOCKED, ALLOWED }
    enum class Strictness { GENTLE, NUCLEAR }

    private const val PREFS = "sg_state"
    private val scope = CoroutineScope(Dispatchers.IO)

    // @Volatile: these fields are read from accessibility service thread,
    // main thread (UI), and IO coroutine scope. Volatile ensures visibility.
    @Volatile var phase = Phase.IDLE
    @Volatile var cycleCount = 0
    var monitoredApps = mutableSetOf<String>()
    var strictMode = false
    var strictness = Strictness.NUCLEAR

    // H5: currentStreak removed — it was never incremented (always 0).
    // If a streak feature is desired, design it with proper daily-completion logic.
    var totalSecondsSaved = 0L

    var scheduleEnabled = false
    var startHour = 9
    var endHour = 17

    @Volatile private var phaseEndTimeWall = 0L
    @Volatile private var phaseEndTimeElapsed = 0L
    private var sessionStartTimeReal = 0L

    fun getRemainingSeconds(): Long {
        if (phase == Phase.IDLE) return 0L
        val nowWall = System.currentTimeMillis()
        val rWall = (phaseEndTimeWall - nowWall) / 1000
        return if (rWall < 0) 0L else rWall
    }

    fun isAppBlocked(packageName: String): Boolean {
        if (phase != Phase.LOCKED) return false
        if (!monitoredApps.contains(packageName)) return false
        if (scheduleEnabled) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (hour < startHour || hour >= endHour) return false
        }
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
        sessionStartTimeReal = nowElapsed
        phase = Phase.FREE
        val durationMs = freeDuration * 1000
        phaseEndTimeWall = nowWall + durationMs
        phaseEndTimeElapsed = nowElapsed + durationMs
        cycleCount = 0
        save(context)
    }

    fun tick(context: Context) {
        if (phase == Phase.IDLE) return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val hasTimePassed = if (phase == Phase.LOCKED) {
            nowWall >= phaseEndTimeWall && nowElapsed >= phaseEndTimeElapsed
        } else {
            nowWall >= phaseEndTimeWall || nowElapsed >= phaseEndTimeElapsed
        }
        if (hasTimePassed) transitionNext(context, nowWall, nowElapsed)
    }

    private fun transitionNext(context: Context, nowWall: Long, nowElapsed: Long) {
        val oldPhase = phase
        when (phase) {
            Phase.FREE -> {
                phase = Phase.LOCKED
                val d = lockDuration * 1000
                phaseEndTimeWall = nowWall + d
                phaseEndTimeElapsed = nowElapsed + d
            }
            Phase.LOCKED -> {
                phase = Phase.ALLOWED
                val d = allowDuration * 1000
                phaseEndTimeWall = nowWall + d
                phaseEndTimeElapsed = nowElapsed + d
            }
            Phase.ALLOWED -> {
                cycleCount++
                phase = Phase.LOCKED
                val d = lockDuration * 1000
                phaseEndTimeWall = nowWall + d
                phaseEndTimeElapsed = nowElapsed + d
            }
            Phase.IDLE -> {}
        }
        if (oldPhase != phase) save(context)
    }

    fun reset(context: Context) {
        val secondsSaved = (SystemClock.elapsedRealtime() - sessionStartTimeReal) / 1000
        if (secondsSaved > 60) {
            totalSecondsSaved += secondsSaved
            scope.launch { DataRepository.getInstance(context).logUsage(secondsSaved, cycleCount) }
        }
        phase = Phase.IDLE
        phaseEndTimeWall = 0L
        phaseEndTimeElapsed = 0L
        cycleCount = 0
        save(context)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("phase", phase.name)
            putLong("phaseEndTimeWall", phaseEndTimeWall)
            putLong("phaseEndTimeElapsed", phaseEndTimeElapsed)
            putLong("sessionStartTime", sessionStartTimeReal)
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
        sessionStartTimeReal = p.getLong("sessionStartTime", 0L)
        cycleCount = p.getInt("cycleCount", 0)
        monitoredApps = p.getStringSet("monitoredApps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
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
        // H5: currentStreak removed (dead field)
        totalSecondsSaved = p.getLong("totalSecondsSaved", 0L)
        scheduleEnabled = p.getBoolean("scheduleEnabled", false)
        startHour = p.getInt("startHour", 9)
        endHour = p.getInt("endHour", 17)
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

        // Recalibrate elapsed-time anchor if device rebooted
        if (nowElapsed < phaseEndTimeElapsed - (3600L * 1000L * 24L)) {
            val remaining = (phaseEndTimeWall - nowWall)
            phaseEndTimeElapsed = nowElapsed + remaining
        }

        // Check if offline for a massive amount of time (e.g. > 1 full cycle of all phases)
        val cycleTimeMs = (freeDuration + lockDuration + allowDuration) * 1000L
        if (nowWall > phaseEndTimeWall + cycleTimeMs) {
            Log.w(TAG, "healState: Massive offline time detected, resetting to IDLE")
            reset(context)
            return
        }

        val maxIterations = 10
        var iterations = 0
        var changed = false

        // Use virtual time trackers to simulate advancing through missed phases
        var virtualNowWall = phaseEndTimeWall
        var virtualNowElapsed = phaseEndTimeElapsed

        while (isRunning() && iterations < maxIterations) {
            val hasTimePassed = if (phase == Phase.LOCKED) {
                nowWall >= virtualNowWall && nowElapsed >= virtualNowElapsed
            } else {
                nowWall >= virtualNowWall || nowElapsed >= virtualNowElapsed
            }
            if (!hasTimePassed) break
            changed = true
            transitionNext(context, virtualNowWall, virtualNowElapsed)
            virtualNowWall = phaseEndTimeWall
            virtualNowElapsed = phaseEndTimeElapsed
            iterations++
        }

        if (iterations >= maxIterations && isRunning()) {
            Log.w(TAG, "healState hit iteration cap ($maxIterations), forcing IDLE")
            reset(context)
            return
        }

        if (changed) save(context)
    }
}