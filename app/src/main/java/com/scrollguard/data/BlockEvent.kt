package com.scrollguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Record of an app launch interception by ScrollGuard (Focus Timer or Parental Limit).
 * Used for deep analytics (Top Blocked Apps ranking, times blocked per week, etc.).
 */
@Entity(tableName = "block_events")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val packageName: String,
    val appName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val dateEpochDay: Long,
    val blockMode: String // "FOCUS_TIMER" or "PARENTAL_LIMIT"
)
