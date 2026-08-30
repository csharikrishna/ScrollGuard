package com.scrollguard

import com.scrollguard.data.parental.ParentalAppRestriction
import com.scrollguard.data.parental.ParentalConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ParentalControlStateTest {

    @Before
    fun setUp() {
        ParentalControlState.clear()
    }

    @Test
    fun `isAppQuotaExhausted returns false when not paired`() {
        assertFalse(ParentalControlState.isAppQuotaExhausted("com.instagram.android"))
    }

    @Test
    fun `isAppQuotaExhausted returns false when globally disabled`() {
        val config = ParentalConfig(
            isPaired = true,
            globalEnabled = false,
            familyId = "fam_123",
            role = "child"
        )
        val restrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.instagram.android",
                appName = "Instagram",
                enabled = true,
                allowanceSeconds = 10,
                consumedSeconds = 100,
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, restrictions)

        assertFalse(ParentalControlState.isAppQuotaExhausted("com.instagram.android"))
    }

    @Test
    fun `isAppQuotaExhausted returns false for excluded system packages`() {
        val config = ParentalConfig(
            isPaired = true,
            globalEnabled = true,
            familyId = "fam_123",
            role = "child"
        )
        val restrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.scrollguard",
                appName = "ScrollGuard",
                enabled = true,
                allowanceSeconds = 0,
                consumedSeconds = 100,
                consumedEpochDay = LocalDate.now().toEpochDay()
            ),
            ParentalAppRestriction(
                packageName = "com.android.settings",
                appName = "Settings",
                enabled = true,
                allowanceSeconds = 0,
                consumedSeconds = 100,
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, restrictions)

        assertFalse(ParentalControlState.isAppQuotaExhausted("com.scrollguard"))
        assertFalse(ParentalControlState.isAppQuotaExhausted("com.android.settings"))
    }

    @Test
    fun `isAppQuotaExhausted triggers with 2-second grace`() {
        val config = ParentalConfig(
            isPaired = true,
            globalEnabled = true,
            familyId = "fam_123",
            role = "child"
        )
        val restrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.instagram.android",
                appName = "Instagram",
                enabled = true,
                allowanceSeconds = 60,
                consumedSeconds = 59,
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, restrictions)

        // At 59s: not exhausted
        assertFalse(ParentalControlState.isAppQuotaExhausted("com.instagram.android"))

        // Increment to 60s (allowance): grace period (2s) means not exhausted until >= 62s
        ParentalControlState.incrementConsumed("com.instagram.android") // 60s
        assertFalse(ParentalControlState.isAppQuotaExhausted("com.instagram.android"))

        ParentalControlState.incrementConsumed("com.instagram.android") // 61s
        assertFalse(ParentalControlState.isAppQuotaExhausted("com.instagram.android"))

        ParentalControlState.incrementConsumed("com.instagram.android") // 62s (allowance + 2s grace)
        assertTrue(ParentalControlState.isAppQuotaExhausted("com.instagram.android"))
    }

    @Test
    fun `changing allowance does not reset consumed time`() {
        val config = ParentalConfig(
            isPaired = true,
            globalEnabled = true,
            familyId = "fam_123",
            role = "child"
        )
        val initialRestrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.youtube.android",
                appName = "YouTube",
                enabled = true,
                allowanceSeconds = 1800,
                consumedSeconds = 1200,
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, initialRestrictions)

        val snapBefore = ParentalControlState.getRestriction("com.youtube.android")
        assertNotNull(snapBefore)
        assertEquals(1200L, snapBefore!!.consumedSeconds)
        assertEquals(600L, snapBefore.remainingSeconds)

        // Parent increases allowance to 3600s
        val updatedRestrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.youtube.android",
                appName = "YouTube",
                enabled = true,
                allowanceSeconds = 3600,
                consumedSeconds = 0, // remote doc might not have latest consumed yet
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, updatedRestrictions)

        val snapAfter = ParentalControlState.getRestriction("com.youtube.android")
        assertNotNull(snapAfter)
        // Consumed time is preserved (1200s), new allowance applied (3600s)
        assertEquals(1200L, snapAfter!!.consumedSeconds)
        assertEquals(2400L, snapAfter.remainingSeconds)
    }

    @Test
    fun `remaining time is never negative`() {
        val config = ParentalConfig(
            isPaired = true,
            globalEnabled = true,
            familyId = "fam_123",
            role = "child"
        )
        val restrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.tiktok.android",
                appName = "TikTok",
                enabled = true,
                allowanceSeconds = 300,
                consumedSeconds = 500,
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, restrictions)

        val snap = ParentalControlState.getRestriction("com.tiktok.android")
        assertNotNull(snap)
        assertEquals(0L, snap!!.remainingSeconds)
    }

    @Test
    fun `clear resets state completely`() {
        val config = ParentalConfig(
            isPaired = true,
            globalEnabled = true,
            familyId = "fam_123",
            role = "child"
        )
        val restrictions = listOf(
            ParentalAppRestriction(
                packageName = "com.snapchat.android",
                appName = "Snapchat",
                enabled = true,
                allowanceSeconds = 600,
                consumedSeconds = 100,
                consumedEpochDay = LocalDate.now().toEpochDay()
            )
        )
        ParentalControlState.refreshFromSync(config, restrictions)

        assertTrue(ParentalControlState.isPaired)
        assertTrue(ParentalControlState.globalEnabled)

        ParentalControlState.clear()

        assertFalse(ParentalControlState.isPaired)
        assertFalse(ParentalControlState.globalEnabled)
        assertNull(ParentalControlState.familyId)
        assertNull(ParentalControlState.getRestriction("com.snapchat.android"))
    }
}
