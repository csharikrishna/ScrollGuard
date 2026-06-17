package com.scrollguard.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM monitored_apps")
    fun getAllMonitoredApps(): Flow<List<AppEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: AppEntry)

    // FIX L5: Use a @Query delete by primary key instead of @Delete which
    // requires exact entity field matching (fragile with addedTimestamp).
    @Query("DELETE FROM monitored_apps WHERE packageName = :packageName")
    suspend fun removeAppByPackage(packageName: String)

    @Query("SELECT * FROM usage_records ORDER BY date DESC LIMIT 30")
    fun getRecentUsage(): Flow<List<UsageRecord>>

    // FIX H1: Upsert daily usage — if a record for today already exists,
    // accumulate seconds and cycles instead of creating a duplicate row.
    @Query("""
        INSERT INTO usage_records (date, secondsSaved, cyclesCompleted)
        VALUES (:date, :seconds, :cycles)
        ON CONFLICT(date) DO UPDATE SET
            secondsSaved = secondsSaved + :seconds,
            cyclesCompleted = cyclesCompleted + :cycles
    """)
    suspend fun upsertDailyUsage(date: Long, seconds: Long, cycles: Int)
}
