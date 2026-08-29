package com.scrollguard.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.data.parental.ParentalConfig
import com.scrollguard.data.parental.ParentalDao

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
@Database(
    entities = [
        AppEntry::class,
        UsageRecord::class,
        ParentalConfig::class,
        ParentalAppRestriction::class,
        AppGroup::class,
        BlockEvent::class
    ],
    version = 4,
    exportSchema = true
)
abstract class ScrollGuardDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao
    abstract fun parentalDao(): ParentalDao

    companion object {
        @Volatile
        private var INSTANCE: ScrollGuardDatabase? = null

        /**
         * v2 → v3: Creates the two new parental-control tables.
         * This is a real, additive migration — no data loss.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `parental_config` (
                        `id` INTEGER NOT NULL PRIMARY KEY,
                        `isPaired` INTEGER NOT NULL DEFAULT 0,
                        `familyId` TEXT,
                        `childUid` TEXT,
                        `parentUid` TEXT,
                        `childDeviceName` TEXT,
                        `globalEnabled` INTEGER NOT NULL DEFAULT 0,
                        `configVersion` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncedAt` INTEGER NOT NULL DEFAULT 0,
                        `role` TEXT
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `parental_app_restrictions` (
                        `packageName` TEXT NOT NULL PRIMARY KEY,
                        `appName` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `allowanceSeconds` INTEGER NOT NULL DEFAULT 3600,
                        `consumedSeconds` INTEGER NOT NULL DEFAULT 0,
                        `consumedEpochDay` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /**
         * v3 → v4: Adds app groups, custom cycle columns to monitored_apps, and block_events table.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `monitored_apps` ADD COLUMN `groupId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `monitored_apps` ADD COLUMN `customFreeSec` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `monitored_apps` ADD COLUMN `customLockSec` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `monitored_apps` ADD COLUMN `customAllowSec` INTEGER DEFAULT NULL")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_groups` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `freeDurationSec` INTEGER NOT NULL DEFAULT 1800,
                        `lockDurationSec` INTEGER NOT NULL DEFAULT 600,
                        `allowDurationSec` INTEGER NOT NULL DEFAULT 120,
                        `colorHex` TEXT NOT NULL DEFAULT '#1A73E8'
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `block_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `appName` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `dateEpochDay` INTEGER NOT NULL,
                        `blockMode` TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): ScrollGuardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScrollGuardDatabase::class.java,
                    "scrollguard_db"
                )
                    // Scoped to the specific, disclosed v1 jump (see the class doc comment
                    // above) rather than the blanket, version-agnostic
                    // fallbackToDestructiveMigration() — that form would silently wipe
                    // parental_config/parental_app_restrictions too if a future migration is
                    // ever forgotten, instead of crashing loudly and surfacing the bug.
                    .fallbackToDestructiveMigrationFrom(1)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
