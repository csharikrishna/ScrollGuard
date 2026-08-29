package com.scrollguard

import android.app.Activity
import android.content.Intent
import android.os.Build

/**
 * Activity#overridePendingTransition is deprecated as of API 34 in favor of
 * Activity#overrideActivityTransition, which must be set up before the transition occurs
 * rather than immediately after. minSdk is 26, so both paths are needed.
 */
object TransitionUtil {

    private val ENTER_ANIM = android.R.anim.fade_in
    private val EXIT_ANIM = android.R.anim.fade_out

    fun startWithFade(activity: Activity, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, ENTER_ANIM, EXIT_ANIM)
            activity.startActivity(intent)
        } else {
            activity.startActivity(intent)
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(ENTER_ANIM, EXIT_ANIM)
        }
    }

    fun finishWithFade(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, ENTER_ANIM, EXIT_ANIM)
            activity.finish()
        } else {
            activity.finish()
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(ENTER_ANIM, EXIT_ANIM)
        }
    }
}
