package com.scrollguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager

/**
 * Single source of truth for whether ScrollGuard can actually enforce blocking right now.
 *
 * Android exposes two genuinely different signals here, and conflating them is what causes false
 * "protection active" claims:
 *  - CONFIG state (`isBlockerServiceEnabled`): what `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
 *    / [AccessibilityManager.getEnabledAccessibilityServiceList] say — RELIABLE for "does the system
 *    consider this service enabled," but this can be true while the service process is dead,
 *    crash-looping, or (briefly) not yet bound.
 *  - RUNTIME state ([BlockerAccessibilityService.isRuntimeConnected]): an in-memory flag the service
 *    itself sets in `onServiceConnected`/clears in `onUnbind`/`onDestroy`. RELIABLE within the current
 *    process's lifetime, and safe by construction across a process restart or OEM kill — a fresh
 *    process always starts with this false until the service instance genuinely reconnects, so it
 *    can never report a stale "true" the way a persisted flag could (see Part 12/13 of the
 *    investigation: runtime truth must beat persisted state).
 *
 * There is no public Android API that reports *why* a service isn't enabled — specifically, nothing
 * distinguishes "user hasn't turned it on" from "Android's Restricted Settings mechanism (API 33+)
 * is blocking it." [installedFromUnknownOrUntrustedSource] is the best available proxy: Restricted
 * Settings is applied based on install source, and `PackageManager.getInstallSourceInfo` (API 30+)
 * can tell us whether ScrollGuard was installed via Play Store vs. anything else (ADB, APK sideload,
 * a file manager, etc.). It is PARTIALLY RELIABLE — a real signal about install provenance, not a
 * direct read of the Restricted Settings flag itself, which Android does not expose.
 */
object AccessibilityUtils {

    /** Protection can only ever be reported ACTIVE in the last case. */
    enum class ProtectionState {
        /** Enabled per system Settings AND the service is currently connected — enforcement works. */
        ACTIVE,
        /** Not enabled per system Settings; no specific signal suggesting Restricted Settings. */
        DISABLED,
        /** Not enabled per system Settings, and ScrollGuard's install source suggests Android may
         *  be applying Restricted Settings (API 33+) — can't be confirmed via any public API. */
        DISABLED_MAY_BE_RESTRICTED,
        /** Enabled per system Settings, but the service is not currently connected — it was working
         *  and stopped (crash, OEM kill, disconnect) or is failing to start. */
        ENABLED_BUT_NOT_RUNNING,
    }

    /**
     * CONFIG state only — RELIABLE for "system Settings says this is enabled," NOT proof the
     * service is actually running. Kept as its own function because a few call sites (the Setup
     * Guide's "why" dialog picking its wording) care specifically about this signal.
     */
    fun isBlockerServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedName = BlockerAccessibilityService::class.java.name
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == expectedName
        }
    }

    /**
     * Best-effort, PARTIALLY RELIABLE signal for "was this installed somewhere Android's
     * Restricted Settings mechanism is likely to apply to." Not available before API 30; on older
     * APIs Restricted Settings doesn't exist anyway (it shipped in Android 13 / API 33), so this
     * returns false there rather than guessing.
     */
    fun installedFromUnknownOrUntrustedSource(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val installer = context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            installer != "com.android.vending"
        } catch (e: Exception) {
            // Unknown install source: can't rule out a sideload, so err toward the more
            // informative (not more alarming) message rather than silently assuming Play Store.
            true
        }
    }

    /**
     * The single authoritative "what's actually going on" state. Every UI surface that shows a
     * protection indicator must derive it from this, not from [isBlockerServiceEnabled] alone.
     */
    fun getProtectionState(context: Context): ProtectionState {
        val configEnabled = isBlockerServiceEnabled(context)
        val runtimeConnected = BlockerAccessibilityService.isRuntimeConnected
        return when {
            configEnabled && runtimeConnected -> ProtectionState.ACTIVE
            configEnabled && !runtimeConnected -> ProtectionState.ENABLED_BUT_NOT_RUNNING
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                installedFromUnknownOrUntrustedSource(context) -> ProtectionState.DISABLED_MAY_BE_RESTRICTED
            else -> ProtectionState.DISABLED
        }
    }

    /** Convenience for call sites that only need a yes/no — protection is active in exactly one
     *  of the four [ProtectionState] cases. */
    fun isProtectionActive(context: Context): Boolean = getProtectionState(context) == ProtectionState.ACTIVE
}
