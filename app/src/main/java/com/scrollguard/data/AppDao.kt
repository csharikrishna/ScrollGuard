package com.scrollguard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class AppBlockCount(
    val packageName: String,
    val appName: String,
    val count: Int
)

@Dao
interface AppDao {
    @Query("SELECT * FROM monitored_apps")
    fun getAllMonitoredApps(): Flow<List<AppEntry>>

    @Query("SELECT * FROM monitored_apps")
    suspend fun getMonitoredAppsList(): List<AppEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: AppEntry)

    // FIX L5: Use a @Query delete by primary key instead of @Delete which
    // requires exact entity field matching (fragile with addedTimestamp).
    @Query("DELETE FROM monitored_apps WHERE packageName = :packageName")
    suspend fun removeAppByPackage(packageName: String)

    @Query("SELECT * FROM usage_records ORDER BY date DESC LIMIT 30")
    fun getRecentUsage(): Flow<List<UsageRecord>>

    @Query("SELECT * FROM usage_records WHERE date = :date")
    suspend fun getUsageRecordForDate(date: Long): UsageRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageRecord(record: UsageRecord)

    // FIX H1 (and its own follow-up fix): accumulate seconds/cycles into today's row instead
    // of creating a duplicate one. The original implementation used raw
    // "INSERT ... ON CONFLICT(date) DO UPDATE SET" SQL — SQLite's upsert clause, added in
    // SQLite 3.24.0. Android's bundled SQLite is only 3.24+ starting with Android 11 (API 30);
    // Android 8/9/10 (API 26-29, all within this app's minSdk 26 range) ship older SQLite and
    // would throw a SQL syntax error the moment any session with recorded time ended, crashing
    // the app with no try/catch anywhere in the call chain to stop it. Read-then-write via two
    // ordinary Room operations (@Transaction keeps them atomic) needs nothing newer than SQLite
    // has supported since Android's very first release.
    @Transaction
    suspend fun upsertDailyUsage(date: Long, seconds: Long, cycles: Int) {
        val existing = getUsageRecordForDate(date)
        val updated = if (existing != null) {
            existing.copy(
                secondsSaved = existing.secondsSaved + seconds,
                cyclesCompleted = existing.cyclesCompleted + cycles
            )
        } else {
            UsageRecord(date = date, secondsSaved = seconds, cyclesCompleted = cycles)
        }
        insertUsageRecord(updated)
    }

    // ── App Groups ──────────────────────────────────────────────────────

    @Query("SELECT * FROM app_groups")
    fun getAllGroups(): Flow<List<AppGroup>>

    @Query("SELECT * FROM app_groups")
    suspend fun getAllGroupsList(): List<AppGroup>

    @Query("SELECT * FROM app_groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: String): AppGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: AppGroup)

    @Query("DELETE FROM app_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("UPDATE monitored_apps SET groupId = :groupId WHERE packageName = :packageName")
    suspend fun updateAppGroup(packageName: String, groupId: String?)

    // ── Block Events & Analytics ────────────────────────────────────────

    @Insert
    suspend fun insertBlockEvent(event: BlockEvent)

    @Query("""
        SELECT packageName, appName, COUNT(*) as count 
        FROM block_events 
        WHERE dateEpochDay >= :minEpochDay 
        GROUP BY packageName, appName 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    fun getTopBlockedApps(minEpochDay: Long, limit: Int = 5): Flow<List<AppBlockCount>>

    @Query("SELECT COUNT(*) FROM block_events WHERE dateEpochDay >= :minEpochDay")
    fun getTotalBlocksCount(minEpochDay: Long): Flow<Int>
}
