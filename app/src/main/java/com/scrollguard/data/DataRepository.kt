package com.scrollguard.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Single point of access for all database operations.
 * Uses the Room singleton under the hood.
 */
class DataRepository private constructor(context: Context) {
    private val db = ScrollGuardDatabase.getDatabase(context)
    private val appDao = db.appDao()

    val recentUsage: Flow<List<UsageRecord>> = appDao.getRecentUsage()

    suspend fun addApp(app: AppEntry) = appDao.insertApp(app)

    suspend fun removeApp(packageName: String) = appDao.removeAppByPackage(packageName)

    /**
     * Logs or accumulates usage for today.
     * Uses an UPSERT so multiple sessions in one day are aggregated (FIX H1).
     */
    suspend fun logUsage(seconds: Long, cycles: Int) {
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000) * (24 * 60 * 60 * 1000)
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
