package com.scrollguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Shared check for whether ScrollGuard's blocking service is enabled in system Accessibility
 * settings. Used both by MainActivity (to show/hide the permissions card) and by TimerService's
 * background health check (to detect the service being silently killed mid-session).
 */
object AccessibilityUtils {
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
}
