package com.scrollguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitored_apps")
data class AppEntry(
    @PrimaryKey val packageName: String,
    val appName: String,
    val addedTimestamp: Long = System.currentTimeMillis()
)
