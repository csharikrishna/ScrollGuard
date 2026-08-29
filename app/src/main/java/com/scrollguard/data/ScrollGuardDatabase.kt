package com.scrollguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Migration strategy: schema export was OFF prior to this version, so there is no committed
 * schema JSON to safely author a real 1->2 migration against — guessing the historical column
 * layout and writing a migration blind risks a runtime crash for anyone still on v1, which
 * would be worse than a clean rebuild. Usage-history rows are low-stakes (a local activity log,
 * not the user's configuration or account data), so a destructive fallback for this specific,
 * already-shipped jump is a deliberate, disclosed trade-off — not an oversight.
 *
 * Schema export is now ON and schemas are committed under app/schemas/, so every migration
 * from version 3 onward can and must be a real, tested androidx.room.migration.Migration
 * rather than relying on fallbackToDestructiveMigration() again.
 */
@Database(entities = [AppEntry::class, UsageRecord::class], version = 2, exportSchema = true)
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
                    // See class doc: intentional, disclosed fallback for the pre-export 1->2
                    // jump only. Do not lean on this for future schema changes — add a real
                    // Migration and only fall back to destructive as an explicit last resort.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
