package com.scrollguard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.data.parental.ParentalConfig
import com.scrollguard.data.parental.ParentalDao
import java.time.LocalDate
import kotlin.math.abs

/**
 * In-memory snapshot of the parental control configuration.
 *
 * The [BlockerAccessibilityService] reads this object in < 1 ms with
 * **zero** network or disk I/O in its event callback (Invariant #1).
 *
 * Data flows one way: Firestore → SyncEngine → Room → this cache.
 * The only mutations allowed are:
 *   - [hydrateFromRoom]: bulk-load at service start / reboot.
 *   - [refreshFromSync]: atomic swap after a successful cloud sync.
 *   - [incrementConsumed]: called by the 1-second tick loop.
 *   - [resetDayIfNeeded]: called before each tick to detect day rollover.
 */
object ParentalControlState {

    private const val TAG = "ParentalControlState"

    /** Grace period in seconds before blocking (spec Issue O). */
    private const val GRACE_SECONDS = 2L

    // ── Clock-tamper detection (spec Issue F) ───────────────────────────
    private const val CLOCK_ANCHOR_PREFS = "scrollguard_parental_clock_anchor"
    private const val KEY_BOOT_TIME_ESTIMATE = "boot_time_estimate_millis"
    private const val KEY_LAST_WALL_CLOCK = "last_wall_clock_millis"
    private const val CLOCK_ANCHOR_TOLERANCE_MS = 5_000L

    // ── Pairing / global state ──────────────────────────────────────────

    @Volatile
    var isPaired: Boolean = false
        private set

    @Volatile
    var globalEnabled: Boolean = false
        private set

    @Volatile
    var familyId: String? = null
        private set

    @Volatile
    var role: String? = null
        private set

    @Volatile
    var configVersion: Long = 0L
        private set

    // ── Per-app restrictions ────────────────────────────────────────────

    /**
     * Thread-safe snapshot of per-app restrictions.
     * Keyed by packageName. Reads are O(1) hash-map lookups.
     */
    @Volatile
    private var restrictions: Map<String, AppRestrictionSnapshot> = emptyMap()

    /** The epoch day (LocalDate.toEpochDay()) currently being tracked. */
    @Volatile
    private var currentEpochDay: Long = LocalDate.now().toEpochDay()

    // ── Snapshot data class ─────────────────────────────────────────────

    data class AppRestrictionSnapshot(
        val packageName: String,
        val appName: String,
        val enabled: Boolean,
        val allowanceSeconds: Int,
        @Volatile var consumedSeconds: Long,
        @Volatile var consumedEpochDay: Long
    ) {
        val remainingSeconds: Long
            get() = (allowanceSeconds - consumedSeconds).coerceAtLeast(0)
    }

    // ── Packages that must NEVER be blocked ─────────────────────────────

    private val EXCLUDED_PACKAGES = setOf(
        "com.scrollguard",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller"
    )

    /**
     * The device's current home/launcher package, resolved dynamically via PackageManager
     * (varies by device/OEM — e.g. Pixel Launcher, Samsung OneUI Home — so it can't be
     * hardcoded). Must never be blockable (spec Issue I): if it were, a restricted launcher
     * would make the home screen itself unreachable, with no way to get back to Settings or
     * ScrollGuard to undo it. Registered once by BlockerAccessibilityService at connect time.
     */
    @Volatile
    private var launcherPackage: String? = null

    fun registerLauncherPackage(packageName: String?) {
        launcherPackage = packageName
    }

    private fun isSelfOrSystemPackage(packageName: String): Boolean {
        return packageName in EXCLUDED_PACKAGES || packageName == launcherPackage
    }

    // ── Query methods (called from AccessibilityService, < 1ms) ────────

    /**
     * Returns true if the given package is parentally restricted, enabled,
     * and its daily quota is exhausted (with grace).
     */
    fun isAppQuotaExhausted(packageName: String): Boolean {
        if (!isPaired || !globalEnabled) return false
        if (isSelfOrSystemPackage(packageName)) return false
        val snap = restrictions[packageName] ?: return false
        if (!snap.enabled) return false
        return snap.consumedSeconds >= (snap.allowanceSeconds + GRACE_SECONDS)
    }

    /**
     * Returns true if the given package is in the parental restriction list
     * and currently enabled (regardless of remaining time).
     */
    fun isParentallyRestricted(packageName: String): Boolean {
        if (!isPaired || !globalEnabled) return false
        if (isSelfOrSystemPackage(packageName)) return false
        val snap = restrictions[packageName] ?: return false
        return snap.enabled
    }

    /**
     * Returns true if the given package should be excluded from parental
     * blocking (self, launcher, system UI, settings).
     */
    fun isExcludedFromBlocking(packageName: String): Boolean {
        return isSelfOrSystemPackage(packageName)
    }

    /**
     * Get the restriction snapshot for a specific package, or null if not restricted.
     */
    fun getRestriction(packageName: String): AppRestrictionSnapshot? {
        return restrictions[packageName]
    }

    /**
     * Get all current restriction snapshots.
     */
    fun getAllRestrictions(): Map<String, AppRestrictionSnapshot> = restrictions

    // ── Mutation methods ────────────────────────────────────────────────

    /**
     * Bulk-load from Room at service start / reboot / process death.
     * Must be called BEFORE the service begins enforcing.
     *
     * Resets any restriction whose stored consumedEpochDay doesn't match today — otherwise
     * currentEpochDay gets stamped to today unconditionally below while stale per-app
     * consumedSeconds from a previous day are loaded as-is, which permanently neuters
     * resetDayIfNeeded()'s day-mismatch check for the rest of that day (it compares against
     * currentEpochDay, which this method just set to today) and can falsely report the day's
     * quota as already exhausted immediately after a reboot/service restart. Guarded by the
     * same clock-tamper check resetDayIfNeeded() uses, so a device clock wound backward
     * without a reboot can't be laundered into a "day changed" reset via a service restart.
     */
    suspend fun hydrateFromRoom(context: Context, dao: ParentalDao) {
        val config = dao.getConfig()
        val appRestrictions = dao.getAllRestrictions()
        val today = LocalDate.now().toEpochDay()
        val tampered = isClockTamperedBackward(context)

        synchronized(this) {
            isPaired = config?.isPaired ?: false
            globalEnabled = config?.globalEnabled ?: false
            familyId = config?.familyId
            role = config?.role
            configVersion = config?.configVersion ?: 0L
            currentEpochDay = today

            restrictions = appRestrictions.associate { r ->
                val sameDay = tampered || r.consumedEpochDay == today
                r.packageName to AppRestrictionSnapshot(
                    packageName = r.packageName,
                    appName = r.appName,
                    enabled = r.enabled,
                    allowanceSeconds = r.allowanceSeconds,
                    consumedSeconds = if (sameDay) r.consumedSeconds else 0L,
                    consumedEpochDay = if (sameDay) r.consumedEpochDay else today
                )
            }
        }

        Log.i(TAG, "Hydrated from Room: paired=$isPaired, enabled=$globalEnabled, " +
                "apps=${restrictions.size}, epochDay=$currentEpochDay")
    }

    /**
     * Atomic refresh after a successful Firestore config sync.
     * Merges new config with locally-tracked consumedSeconds to avoid
     * losing in-flight consumption that hasn't been reported upstream yet.
     */
    fun refreshFromSync(
        config: ParentalConfig,
        appRestrictions: List<ParentalAppRestriction>
    ) {
        synchronized(this) {
            isPaired = config.isPaired
            globalEnabled = config.globalEnabled
            familyId = config.familyId
            role = config.role
            configVersion = config.configVersion

            val oldRestrictions = restrictions
            restrictions = appRestrictions.associate { r ->
                val oldSnap = oldRestrictions[r.packageName]
                r.packageName to AppRestrictionSnapshot(
                    packageName = r.packageName,
                    appName = r.appName,
                    enabled = r.enabled,
                    allowanceSeconds = r.allowanceSeconds,
                    // Preserve locally-tracked consumed only if it's from the SAME
                    // accounting day and higher than what came from the sync (local is
                    // fresher within a day). Across a day boundary, r's consumedSeconds is
                    // already the correctly-reset value (SyncEngine resets it when the
                    // Room-stored day differs from today) — blindly max-merging against a
                    // stale in-memory count from before resetDayIfNeeded() last ran would
                    // otherwise let yesterday's leftover consumption survive into today.
                    consumedSeconds = if (oldSnap != null && oldSnap.consumedEpochDay == r.consumedEpochDay) {
                        maxOf(r.consumedSeconds, oldSnap.consumedSeconds)
                    } else {
                        r.consumedSeconds
                    },
                    consumedEpochDay = r.consumedEpochDay
                )
            }
        }

        Log.i(TAG, "Refreshed from sync: paired=$isPaired, enabled=$globalEnabled, " +
                "apps=${restrictions.size}, configVersion=$configVersion")
    }

    /**
     * Increment consumed time for a specific package by 1 second.
     * Called from the 1-second tick loop in the AccessibilityService.
     * Returns the new consumedSeconds value.
     */
    fun incrementConsumed(packageName: String): Long {
        // Look up AND increment inside the same lock a sync refresh uses to swap the
        // `restrictions` map — otherwise a lookup that races a concurrent
        // hydrateFromRoom/refreshFromSync could increment a just-replaced, orphaned
        // snapshot instance and silently lose that tick's second.
        synchronized(this) {
            val snap = restrictions[packageName] ?: return 0L
            snap.consumedSeconds++
            return snap.consumedSeconds
        }
    }

    /**
     * Checks if the current calendar day has changed and resets consumed
     * counters for all apps if it has. Returns true if a reset occurred.
     */
    fun resetDayIfNeeded(context: Context): Boolean {
        val todayEpochDay = LocalDate.now().toEpochDay()
        if (todayEpochDay == currentEpochDay) return false

        // The apparent day changed — before trusting it, rule out a backward clock change
        // made without a reboot (spec Issue F). A genuine midnight rollover and a genuine
        // reboot both pass this check untouched; only a manual clock-wind-back while the
        // process stays running is rejected, so it can't be used to grant a fresh day's
        // allowance early.
        if (isClockTamperedBackward(context)) {
            Log.w(TAG, "Ignoring apparent day change ($currentEpochDay -> $todayEpochDay) — " +
                    "backward clock change detected with no matching reboot")
            return false
        }

        synchronized(this) {
            currentEpochDay = todayEpochDay
            restrictions.values.forEach { snap ->
                snap.consumedSeconds = 0L
                snap.consumedEpochDay = todayEpochDay
            }
        }

        Log.i(TAG, "Day boundary crossed → reset all consumed to 0 (epochDay=$todayEpochDay)")
        return true
    }

    /**
     * Detects a backward wall-clock jump that happened WITHOUT a device reboot. A real reboot
     * legitimately resets SystemClock.elapsedRealtime() to 0 — which is why this compares a
     * *boot-time estimate* (wall clock minus elapsed realtime, stable across a single boot
     * session) rather than elapsedRealtime itself — so only a clock change with no matching
     * reboot is treated as tampering. Persisted in SharedPreferences rather than Room so it
     * survives process death without a schema migration; kept in its own prefs file, separate
     * from TimerState's, per invariant #5's state-isolation requirement.
     */
    private fun isClockTamperedBackward(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(CLOCK_ANCHOR_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val currentBootEstimate = now - SystemClock.elapsedRealtime()
        val lastBootEstimate = prefs.getLong(KEY_BOOT_TIME_ESTIMATE, currentBootEstimate)
        val lastWallClock = prefs.getLong(KEY_LAST_WALL_CLOCK, now)

        val rebooted = abs(currentBootEstimate - lastBootEstimate) > CLOCK_ANCHOR_TOLERANCE_MS
        val wallClockWentBackward = now < lastWallClock - CLOCK_ANCHOR_TOLERANCE_MS
        val tampered = !rebooted && wallClockWentBackward

        prefs.edit()
            .putLong(KEY_BOOT_TIME_ESTIMATE, currentBootEstimate)
            .putLong(KEY_LAST_WALL_CLOCK, maxOf(now, lastWallClock))
            .apply()

        return tampered
    }

    /**
     * Clears all parental control state (used on unpair).
     */
    fun clear() {
        synchronized(this) {
            isPaired = false
            globalEnabled = false
            familyId = null
            role = null
            configVersion = 0L
            restrictions = emptyMap()
        }
        Log.i(TAG, "State cleared")
    }
}
