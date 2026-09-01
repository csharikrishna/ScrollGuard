package com.scrollguard.parental

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.scrollguard.ParentalControlState
import com.scrollguard.TimerState
import com.scrollguard.data.ScrollGuardDatabase
import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.data.parental.ParentalConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * Thin sync orchestrator: Firestore → Room → in-memory cache.
 *
 * Leverages Firestore's built-in offline persistence and write queue
 * (spec Issue D) — does NOT hand-roll an outbox/retry system.
 *
 * Config flows DOWN (parent-owned). Status flows UP (child-owned).
 * Enforcement reads only local state (Invariant #1).
 */
class SyncEngine(private val context: Context) {

    companion object {
        private const val TAG = "SyncEngine"

        /** How long attachLiveConfigListener waits for a second listener to fire before actually
         *  pulling — long enough to coalesce the config-doc + apps-subcollection pair a single
         *  parent write typically triggers, short enough to stay well under the sync-delay fix's
         *  whole point. */
        private const val LIVE_LISTENER_DEBOUNCE_MS = 400L
    }

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val db by lazy { ScrollGuardDatabase.getDatabase(context) }
    private val parentalDao by lazy { db.parentalDao() }

    // ── Config: pull from Firestore (parent → child) ────────────────────

    /**
     * Reads config/current + apps subcollection from Firestore, writes to
     * Room, then refreshes the in-memory enforcement cache.
     *
     * Called by: SyncWorker (periodic), FCM receiver (push), app open.
     */
    suspend fun pullConfig(): Result<Unit> {
        val config = parentalDao.getConfig() ?: return Result.failure(
            Exception("Not paired — no config to pull")
        )
        val familyId = config.familyId ?: return Result.failure(
            Exception("No familyId in local config")
        )

        return try {
            val familyRef = firestore.collection("families").document(familyId)

            // Read family doc for pairing status.
            val familyDoc = familyRef.get().await()
            val parentUid = familyDoc.getString("parentUid")

            // Read config.
            val configDoc = familyRef.collection("config").document("current").get().await()
            val globalEnabled = configDoc.getBoolean("enabled") ?: false
            val configVersion = configDoc.getLong("configVersion") ?: 0L

            // Read per-app restrictions.
            val appsSnapshot = familyRef.collection("config").document("current")
                .collection("apps").get().await()

            val today = LocalDate.now().toEpochDay()

            // Parent-owned fields only — consumedSeconds/consumedEpochDay below are just
            // placeholders. parentalDao.applyPulledConfig() decides the real values
            // atomically against whatever Room holds at write time, so a concurrent
            // tick-loop batch-persist (BlockerAccessibilityService's incrementConsumed)
            // can never be clobbered by a stale pre-fetched merge.
            val pulledRestrictions = appsSnapshot.documents.mapNotNull { doc ->
                val packageName = doc.id
                val enabled = doc.getBoolean("enabled") ?: true
                val allowanceSeconds = doc.getLong("allowanceSeconds")?.toInt() ?: 3600
                val label = doc.getString("label") ?: packageName

                ParentalAppRestriction(
                    packageName = packageName,
                    appName = label,
                    enabled = enabled,
                    allowanceSeconds = allowanceSeconds,
                    consumedSeconds = 0L,
                    consumedEpochDay = today
                )
            }

            // Update Room.
            val updatedConfig = config.copy(
                isPaired = parentUid != null,
                parentUid = parentUid,
                globalEnabled = globalEnabled,
                configVersion = configVersion,
                lastSyncedAt = System.currentTimeMillis()
            )
            parentalDao.upsertConfig(updatedConfig)
            parentalDao.applyPulledConfig(pulledRestrictions, today)

            // Refresh in-memory cache from the now-authoritative Room state (the real
            // consumedSeconds after the atomic merge above, not the placeholders).
            val persistedRestrictions = parentalDao.getAllRestrictions()
            ParentalControlState.refreshFromSync(updatedConfig, persistedRestrictions)

            Log.i(TAG, "Config pulled: enabled=$globalEnabled, " +
                    "apps=${persistedRestrictions.size}, version=$configVersion")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull config", e)
            Result.failure(e)
        }
    }

    // ── Live config listener (parent → child, while process is alive) ───

    /**
     * Attaches live Firestore listeners for config/current and its apps subcollection, so a
     * child device picks up a parent's restriction change immediately instead of waiting for
     * the 15-min SyncWorker floor (Issue: parental sync delay).
     *
     * Deliberately not FCM. FCM's actual advantage over this is waking up a *killed* process —
     * but this device already runs a persistent AccessibilityService (and a foreground
     * TimerService whenever a session is active) for the entire time enforcement matters, so
     * there's normally no killed process to wake. A listener attached from that already-running
     * process closes the delay for the common case with zero new server infrastructure, reusing
     * the exact same [pullConfig] merge/transaction logic SyncWorker already relies on.
     * SyncWorker remains the correct fallback for the genuine edge case a listener can't cover —
     * the process actually being dead (OEM background kill, long offline stretch).
     *
     * Callers are responsible for calling [ListenerRegistration.remove] on every returned
     * registration when the owning component is destroyed.
     */
    /** Coalesces bursts from the two listeners below into a single [pullConfig] call — a parent
     *  changing one app's allowance writes to both the apps subcollection AND bumps configVersion
     *  on the config doc as two separate writes, so both listeners fired for what is really one
     *  logical change, each independently launching a full pullConfig() (itself several
     *  sequential Firestore reads) concurrently. Debouncing means a burst within [DEBOUNCE_MS]
     *  collapses into one pull instead of two-plus racing ones. */
    private var pendingConfigPull: Job? = null

    fun attachLiveConfigListener(familyId: String, scope: CoroutineScope): List<ListenerRegistration> {
        val configRef = firestore.collection("families").document(familyId)
            .collection("config").document("current")

        fun scheduleDebouncedPull() {
            pendingConfigPull?.cancel()
            pendingConfigPull = scope.launch {
                delay(LIVE_LISTENER_DEBOUNCE_MS)
                pullConfig()
            }
        }

        val configListener = configRef.addSnapshotListener { _, error ->
            if (error != null) Log.w(TAG, "Live config listener error", error) else scheduleDebouncedPull()
        }
        val appsListener = configRef.collection("apps").addSnapshotListener { _, error ->
            if (error != null) Log.w(TAG, "Live apps listener error", error) else scheduleDebouncedPull()
        }
        return listOf(configListener, appsListener)
    }

    // ── Status: push to Firestore (child → parent, throttled) ───────────

    /**
     * Reports current consumption and health status to Firestore.
     * Writes only child-owned fields (spec Part D field ownership).
     *
     * Called: on app switch, on delta ≥ 60s, on background, periodic cap.
     */
    suspend fun pushStatus(): Result<Unit> {
        val config = parentalDao.getConfig() ?: return Result.failure(
            Exception("Not paired — no status to push")
        )
        val familyId = config.familyId ?: return Result.failure(
            Exception("No familyId in local config")
        )

        return try {
            val restrictions = parentalDao.getAllRestrictions()
            val statusRef = firestore.collection("families").document(familyId)
                .collection("status").document("current")

            // Build consumed-by-package map.
            val consumedMap = restrictions.associate { r ->
                r.packageName to mapOf(
                    "consumedSeconds" to r.consumedSeconds
                )
            }

            val today = LocalDate.now().toEpochDay()
            statusRef.set(mapOf(
                "consumedByPackage" to consumedMap,
                "consumedEpochDay" to today,
                "lastSeen" to FieldValue.serverTimestamp(),
                // Previously hardcoded to true regardless of reality — TimerState.accessibilityHealthy
                // is the same flag TimerService's own health check already maintains, so the parent
                // can now actually be told if the child device's enforcement mechanism (the
                // AccessibilityService) has been disabled, instead of always reading "healthy".
                "accessibilityHealthy" to TimerState.accessibilityHealthy
            )).await()

            Log.i(TAG, "Status pushed: ${restrictions.size} apps, epochDay=$today")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push status", e)
            Result.failure(e)
        }
    }

    /**
     * Reports an immediate, specific tamper signal (e.g. "the child removed Device Admin") to
     * the parent, distinct from the generic staleness-based "child offline" detection in
     * [ParentalControlActivity]. Staleness alone tells the parent *something* stopped reporting
     * within ~10-15 minutes, with no reason; this gives an immediate, actionable reason the
     * moment it happens, for the specific tamper events this app can actually observe on its own
     * process before it's potentially killed. A targeted field `update()`, not the full `set()`
     * [pushStatus] uses, so this doesn't race with or clobber a concurrent regular status push.
     */
    suspend fun pushTamperAlert(reason: String): Result<Unit> {
        val config = parentalDao.getConfig() ?: return Result.failure(
            Exception("Not paired — no tamper alert to push")
        )
        val familyId = config.familyId ?: return Result.failure(
            Exception("No familyId in local config")
        )
        return try {
            val statusRef = firestore.collection("families").document(familyId)
                .collection("status").document("current")
            statusRef.update(
                mapOf(
                    "lastTamperEvent" to reason,
                    "lastTamperEventAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Log.w(TAG, "Tamper alert pushed: $reason")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push tamper alert", e)
            Result.failure(e)
        }
    }

    // ── Catalog: push child's app list to Firestore ─────────────────────

    /**
     * Uploads the child's launchable app catalog to Firestore so the
     * parent can see which apps are available to restrict.
     */
    suspend fun pushCatalog(apps: List<Map<String, String>>): Result<Unit> {
        val config = parentalDao.getConfig() ?: return Result.failure(
            Exception("Not paired — no catalog to push")
        )
        val familyId = config.familyId ?: return Result.failure(
            Exception("No familyId in local config")
        )

        return try {
            val catalogRef = firestore.collection("families").document(familyId)
                .collection("catalog").document("current")

            catalogRef.set(mapOf(
                "apps" to apps,
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()

            Log.i(TAG, "Catalog pushed: ${apps.size} apps")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push catalog", e)
            Result.failure(e)
        }
    }

    // ── Parent-side: read status from Firestore ─────────────────────────

    /**
     * Parent reads the child's current status (consumption, health).
     */
    suspend fun readChildStatus(familyId: String): Result<Map<String, Any?>> {
        return try {
            val statusDoc = firestore.collection("families").document(familyId)
                .collection("status").document("current").get().await()

            if (!statusDoc.exists()) {
                Result.success(emptyMap())
            } else {
                Result.success(statusDoc.data ?: emptyMap())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read child status", e)
            Result.failure(e)
        }
    }

    /**
     * Parent reads the child's app catalog.
     */
    suspend fun readChildCatalog(familyId: String): Result<List<Map<String, Any>>> {
        return try {
            val catalogDoc = firestore.collection("families").document(familyId)
                .collection("catalog").document("current").get().await()

            if (!catalogDoc.exists()) {
                Result.success(emptyList())
            } else {
                @Suppress("UNCHECKED_CAST")
                val apps = catalogDoc.get("apps") as? List<Map<String, Any>> ?: emptyList()
                Result.success(apps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read child catalog", e)
            Result.failure(e)
        }
    }

    // ── Parent-side: write config to Firestore ──────────────────────────

    /**
     * Parent writes the global enabled state.
     */
    suspend fun writeGlobalEnabled(familyId: String, enabled: Boolean): Result<Unit> {
        return try {
            val configRef = firestore.collection("families").document(familyId)
                .collection("config").document("current")

            configRef.update(mapOf(
                "enabled" to enabled,
                "configVersion" to FieldValue.increment(1),
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()

            Log.i(TAG, "Global enabled set to $enabled")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write global enabled", e)
            Result.failure(e)
        }
    }

    /**
     * Parent writes/updates a per-app restriction.
     */
    suspend fun writeAppRestriction(
        familyId: String,
        packageName: String,
        label: String,
        enabled: Boolean,
        allowanceSeconds: Int
    ): Result<Unit> {
        return try {
            val appRef = firestore.collection("families").document(familyId)
                .collection("config").document("current")
                .collection("apps").document(packageName)

            appRef.set(mapOf(
                "enabled" to enabled,
                "label" to label,
                "allowanceSeconds" to allowanceSeconds
            )).await()

            // Bump config version.
            val configRef = firestore.collection("families").document(familyId)
                .collection("config").document("current")
            configRef.update(mapOf(
                "configVersion" to FieldValue.increment(1),
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()

            Log.i(TAG, "App restriction written: $packageName, enabled=$enabled, " +
                    "allowance=$allowanceSeconds")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write app restriction", e)
            Result.failure(e)
        }
    }

    /**
     * Parent removes a per-app restriction.
     */
    suspend fun removeAppRestriction(familyId: String, packageName: String): Result<Unit> {
        return try {
            firestore.collection("families").document(familyId)
                .collection("config").document("current")
                .collection("apps").document(packageName)
                .delete().await()

            // Bump config version.
            val configRef = firestore.collection("families").document(familyId)
                .collection("config").document("current")
            configRef.update(mapOf(
                "configVersion" to FieldValue.increment(1),
                "updatedAt" to FieldValue.serverTimestamp()
            )).await()

            Log.i(TAG, "App restriction removed: $packageName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove app restriction", e)
            Result.failure(e)
        }
    }
}
