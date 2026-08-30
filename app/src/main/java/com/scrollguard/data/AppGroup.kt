package com.scrollguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a group of apps that share a customized focus cycle
 * or daily allowance (e.g. "Social Media", "Shorts & Video", "Gaming").
 */
@Entity(tableName = "app_groups")
data class AppGroup(
    @PrimaryKey val id: String,
    val name: String,
    /** Usage time in seconds before entering locked phase (default 30 min). */
    val freeDurationSec: Long = 1800L,
    /** Lock duration in seconds (default 10 min). */
    val lockDurationSec: Long = 600L,
    /** Break window duration in seconds (default 2 min). */
    val allowDurationSec: Long = 120L,
    /** Accent color hex code for UI badges. */
    val colorHex: String = "#1A73E8"
)
