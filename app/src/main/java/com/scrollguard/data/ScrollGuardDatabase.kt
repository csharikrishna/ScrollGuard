package com.scrollguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Version 2: Changed UsageRecord PK from autoGenerate to date-based (FIX H1).
@Database(entities = [AppEntry::class, UsageRecord::class], version = 2, exportSchema = false)
abstract class ScrollGuardDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: ScrollGuardDatabase? = null

        fun getDatabase(context: Context): ScrollGuardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScrollGuardDatabase::class.java,
                    "scrollguard_db"
                )
                    // FIX L6: If schema changes without a Migration, wipe and rebuild.
                    // This is acceptable for this app since usage data is not critical
                    // and prevents crashes on version upgrades.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
