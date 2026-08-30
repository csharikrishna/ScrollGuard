package com.scrollguard.data.parental

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton Room entity storing the child device's pairing state and the
 * latest-known global parental-control configuration snapshot.
 *
 * Only one row ever exists (id = 1). This is the durable, offline-surviving
 * source of truth for whether the device is paired, who it's paired with,
 * and whether restrictions are globally enabled.
 */
@Entity(tableName = "parental_config")
data class ParentalConfig(
    @PrimaryKey val id: Int = 1,
    /** Whether this device has been successfully paired with a parent. */
    val isPaired: Boolean = false,
    /** The Firestore family document ID linking parent and child. */
    val familyId: String? = null,
    /** This child device's Firebase Auth UID. */
    val childUid: String? = null,
    /** The paired parent's Firebase Auth UID. */
    val parentUid: String? = null,
    /** The child device's display name (e.g. "Alex's Galaxy A54"). */
    val childDeviceName: String? = null,
    /** Global ON/OFF toggle set by the parent. OFF suspends enforcement
     *  but preserves app selection and allowances (spec Issue N). */
    val globalEnabled: Boolean = false,
    /** Monotonic version counter bumped on every parent config change.
     *  Used for sync conflict resolution — higher version wins. */
    val configVersion: Long = 0L,
    /** Wall-clock timestamp (ms) of the last successful config sync. */
    val lastSyncedAt: Long = 0L,
    /** The device role: "child", "parent", or null if not yet configured. */
    val role: String? = null
)
