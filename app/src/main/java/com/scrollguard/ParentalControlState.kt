package com.scrollguard

import android.util.Log
import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.data.parental.ParentalConfig
import com.scrollguard.data.parental.ParentalDao
import java.time.LocalDate

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

    // ── Query methods (called from AccessibilityService, < 1ms) ────────

    /**
     * Returns true if the given package is parentally restricted, enabled,
     * and its daily quota is exhausted (with grace).
     */
    fun isAppQuotaExhausted(packageName: String): Boolean {
        if (!isPaired || !globalEnabled) return false
        if (packageName in EXCLUDED_PACKAGES) return false
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
        if (packageName in EXCLUDED_PACKAGES) return false
        val snap = restrictions[packageName] ?: return false
        return snap.enabled
    }

    /**
     * Returns true if the given package should be excluded from parental
     * blocking (self, launcher, system UI, settings).
     */
    fun isExcludedFromBlocking(packageName: String): Boolean {
        return packageName in EXCLUDED_PACKAGES
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
     */
    suspend fun hydrateFromRoom(dao: ParentalDao) {
        val config = dao.getConfig()
        val appRestrictions = dao.getAllRestrictions()

        synchronized(this) {
            isPaired = config?.isPaired ?: false
            globalEnabled = config?.globalEnabled ?: false
            familyId = config?.familyId
            role = config?.role
            configVersion = config?.configVersion ?: 0L
            currentEpochDay = LocalDate.now().toEpochDay()

            restrictions = appRestrictions.associate { r ->
                r.packageName to AppRestrictionSnapshot(
                    packageName = r.packageName,
                    appName = r.appName,
                    enabled = r.enabled,
                    allowanceSeconds = r.allowanceSeconds,
                    consumedSeconds = r.consumedSeconds,
                    consumedEpochDay = r.consumedEpochDay
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
    fun resetDayIfNeeded(): Boolean {
        val todayEpochDay = LocalDate.now().toEpochDay()
        if (todayEpochDay == currentEpochDay) return false

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
