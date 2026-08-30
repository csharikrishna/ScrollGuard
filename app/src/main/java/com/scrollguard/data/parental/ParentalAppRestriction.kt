package com.scrollguard.data.parental

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-app restriction with daily quota tracking.
 *
 * `remaining` is never stored — it is derived as:
 *   remaining = max(0, allowanceSeconds − consumedSeconds)
 *
 * Changing the allowance never resets consumed (spec §12, Issue B).
 * If allowance drops below consumed, remaining clamps to 0.
 *
 * Time consumption is keyed on the device's local calendar day via
 * `consumedEpochDay` (LocalDate.toEpochDay()). On a new epoch-day,
 * consumedSeconds resets to 0 (spec Issue F).
 */
@Entity(tableName = "parental_app_restrictions")
data class ParentalAppRestriction(
    /** The Android package name (e.g. "com.instagram.android"). */
    @PrimaryKey val packageName: String,
    /** Human-readable app label from the child's installed apps catalog. */
    val appName: String,
    /** Whether this specific app's restriction is active. */
    val enabled: Boolean = true,
    /** Parent-set daily allowance in seconds (0 = fully blocked, max = 86400). */
    val allowanceSeconds: Int = 3600,
    /** Seconds consumed today on the child device. Resets on new consumedEpochDay. */
    val consumedSeconds: Long = 0L,
    /** The local calendar day (LocalDate.toEpochDay()) when consumedSeconds was last counted.
     *  When this differs from the current epoch day, consumedSeconds resets to 0. */
    val consumedEpochDay: Long = 0L
) {
    /** Derived remaining seconds — never stored in Firestore or Room as a separate field. */
    val remainingSeconds: Long
        get() = (allowanceSeconds - consumedSeconds).coerceAtLeast(0)
}
