package com.scrollguard.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single point of access for all database operations.
 * Uses the Room singleton under the hood.
 */
class DataRepository private constructor(context: Context) {
    private val db = ScrollGuardDatabase.getDatabase(context)
    private val appDao = db.appDao()

    val recentUsage: Flow<List<UsageRecord>> = appDao.getRecentUsage()
    val allGroups: Flow<List<AppGroup>> = appDao.getAllGroups()
    val allMonitoredApps: Flow<List<AppEntry>> = appDao.getAllMonitoredApps()

    suspend fun addApp(app: AppEntry) = appDao.insertApp(app)

    suspend fun removeApp(packageName: String) = appDao.removeAppByPackage(packageName)

    suspend fun addGroup(group: AppGroup) = appDao.insertGroup(group)

    suspend fun deleteGroup(groupId: String) = appDao.deleteGroup(groupId)

    suspend fun setAppGroup(packageName: String, groupId: String?) =
        appDao.updateAppGroup(packageName, groupId)

    suspend fun logBlockEvent(packageName: String, appName: String, blockMode: String) {
        val today = LocalDate.now().toEpochDay()
        appDao.insertBlockEvent(
            BlockEvent(
                packageName = packageName,
                appName = appName,
                dateEpochDay = today,
                blockMode = blockMode
            )
        )
    }

    fun getTopBlockedApps(daysAgo: Long = 7, limit: Int = 5) =
        appDao.getTopBlockedApps(LocalDate.now().toEpochDay() - daysAgo, limit)

    fun getTotalBlocksCount(daysAgo: Long = 7) =
        appDao.getTotalBlocksCount(LocalDate.now().toEpochDay() - daysAgo)

    /**
     * Logs or accumulates usage for today.
     * Uses an UPSERT so multiple sessions in one day are aggregated (FIX H1).
     *
     * "Today" is the user's local calendar day, not a UTC day boundary — flooring
     * System.currentTimeMillis() to a 24h multiple buckets by UTC midnight, which silently
     * misattributes a session to the wrong day for anyone not in UTC (e.g. a 9pm session in
     * UTC-8 rolls into "tomorrow" hours before the user's own midnight). java.time is available
     * natively on minSdk 26+, no desugaring needed.
     */
    suspend fun logUsage(seconds: Long, cycles: Int) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        appDao.upsertDailyUsage(today, seconds, cycles)
    }

    companion object {
        @Volatile
        private var INSTANCE: DataRepository? = null

        // FIX C4: Singleton pattern avoids creating new instances on every call.
        fun getInstance(context: Context): DataRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
