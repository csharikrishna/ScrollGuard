package com.scrollguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// FIX H1: Use `date` (midnight epoch) as PK instead of autoGenerate.
// This means insertUsage with REPLACE will upsert by day, keeping
// analytics data cleanly aggregated per day instead of creating
// duplicate rows for multiple sessions on the same day.
@Entity(tableName = "usage_records")
data class UsageRecord(
    @PrimaryKey val date: Long, // midnight epoch — one row per day
    val secondsSaved: Long,
    val cyclesCompleted: Int
)
