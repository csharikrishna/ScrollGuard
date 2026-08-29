package com.scrollguard.data.parental

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for parental control persistence. Fully isolated from the existing
 * AppDao / monitored_apps / usage_records tables.
 */
@Dao
interface ParentalDao {

    // ── ParentalConfig (singleton) ──────────────────────────────────────

    @Query("SELECT * FROM parental_config WHERE id = 1")
    suspend fun getConfig(): ParentalConfig?

    @Query("SELECT * FROM parental_config WHERE id = 1")
    fun observeConfig(): Flow<ParentalConfig?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: ParentalConfig)

    @Query("DELETE FROM parental_config")
    suspend fun clearConfig()

    // ── ParentalAppRestriction ──────────────────────────────────────────

    @Query("SELECT * FROM parental_app_restrictions")
    suspend fun getAllRestrictions(): List<ParentalAppRestriction>

    @Query("SELECT * FROM parental_app_restrictions")
    fun observeRestrictions(): Flow<List<ParentalAppRestriction>>

    @Query("SELECT * FROM parental_app_restrictions WHERE packageName = :packageName")
    suspend fun getRestriction(packageName: String): ParentalAppRestriction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRestriction(restriction: ParentalAppRestriction)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRestrictions(restrictions: List<ParentalAppRestriction>)

    @Query("DELETE FROM parental_app_restrictions WHERE packageName = :packageName")
    suspend fun deleteRestriction(packageName: String)

    @Query("DELETE FROM parental_app_restrictions")
    suspend fun clearAllRestrictions()

    /**
     * Increments consumedSeconds for a specific package. Called from the
     * tick loop's batch-persist (every ~15s and on lifecycle stop).
     * Does NOT touch consumedEpochDay — the caller must check/reset the
     * day boundary separately via [resetConsumedForNewDay].
     */
    @Query("""
        UPDATE parental_app_restrictions 
        SET consumedSeconds = consumedSeconds + :deltaSeconds 
        WHERE packageName = :packageName
    """)
    suspend fun incrementConsumed(packageName: String, deltaSeconds: Long)

    /**
     * Resets consumedSeconds to 0 and updates consumedEpochDay for ALL
     * restricted apps. Called when a day boundary is crossed.
     */
    @Query("""
        UPDATE parental_app_restrictions 
        SET consumedSeconds = 0, consumedEpochDay = :newEpochDay
    """)
    suspend fun resetConsumedForNewDay(newEpochDay: Long)

    /**
     * Updates only the parent-owned columns for an existing restriction row, leaving
     * consumedSeconds/consumedEpochDay untouched. Used by [applyPulledConfig] so a synced
     * config change can never clobber consumption the tick loop already persisted.
     */
    @Query("""
        UPDATE parental_app_restrictions
        SET appName = :appName, enabled = :enabled, allowanceSeconds = :allowanceSeconds
        WHERE packageName = :packageName
    """)
    suspend fun updateAppMeta(packageName: String, appName: String, enabled: Boolean, allowanceSeconds: Int)

    /**
     * Applies a freshly-pulled parent config to Room as a single atomic operation.
     *
     * This exists instead of a blind delete-all/insert-all because that approach raced the
     * tick loop's own batch-persist: SyncEngine used to read Room, compute a merge in Kotlin
     * from that snapshot, then write the merge result back — and an incrementConsumed() flush
     * landing in that window would be durably applied and then immediately clobbered by the
     * stale merge, regressing consumedSeconds (a real, confirmed bug found during the parental
     * control audit). Doing the merge decision AND the write inside one `@Transaction` means
     * Room's single-writer transaction serialization is what prevents the race, not
     * best-effort timing.
     *
     * For each incoming app: a row already on the SAME accounting day only has its
     * parent-owned columns updated (enabled/allowanceSeconds/appName) — consumedSeconds is
     * left exactly as it stands in Room at transaction time. A day rollover, or a brand-new
     * package, resets consumedSeconds to 0 under the same lock. Packages no longer present in
     * the incoming set are removed (the parent unrestricted them).
     */
    @Transaction
    suspend fun applyPulledConfig(incoming: List<ParentalAppRestriction>, todayEpochDay: Long) {
        val existingByPackage = getAllRestrictions().associateBy { it.packageName }
        val incomingPackages = incoming.map { it.packageName }.toSet()

        existingByPackage.keys.filter { it !in incomingPackages }.forEach { deleteRestriction(it) }

        incoming.forEach { new ->
            val existing = existingByPackage[new.packageName]
            if (existing != null && existing.consumedEpochDay == todayEpochDay) {
                updateAppMeta(new.packageName, new.appName, new.enabled, new.allowanceSeconds)
            } else {
                upsertRestriction(new.copy(consumedSeconds = 0L, consumedEpochDay = todayEpochDay))
            }
        }
    }
}
