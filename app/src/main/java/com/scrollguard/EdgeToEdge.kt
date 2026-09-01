package com.scrollguard

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Shared edge-to-edge setup for every Activity in the app.
 *
 * Android makes edge-to-edge rendering mandatory starting with API 35 (this app targets 36) —
 * regardless of whether an Activity opts in, content draws behind the status/navigation bars.
 * Every screen therefore needs to apply its own top/bottom padding from the real system-bar
 * insets, or its content visually overlaps the status bar. This was previously done ad hoc in
 * MainActivity and BlockActivity only; every other Activity had no inset handling at all and
 * genuinely overlapped the status bar on any API 35+ device.
 *
 * [rootView] should be the outermost view passed to `setContentView` (typically `binding.root`).
 * Bottom padding unions `systemGestures()` with `systemBars()`: on gesture-navigation OEM skins
 * (confirmed on OxygenOS 16) `systemBars()` alone under-reports the bottom inset — it only covers
 * the visual nav pill, not the wider swipe-up-home gesture strip beneath it, which still swallows
 * touches regardless of what the app draws there. Left/right intentionally stay systemBars()-only:
 * unioning gesture insets there was observed to add unwanted side padding that narrowed cards and
 * changed text wrapping on some screens.
 */
fun Activity.applyEdgeToEdge(rootView: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    val initialPadding = Rect4(rootView.paddingLeft, rootView.paddingTop, rootView.paddingRight, rootView.paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val gestureBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        view.setPadding(
            initialPadding.left + bars.left,
            initialPadding.top + bars.top,
            initialPadding.right + bars.right,
            initialPadding.bottom + maxOf(bars.bottom, gestureBottom)
        )
        insets
    }
}

private data class Rect4(val left: Int, val top: Int, val right: Int, val bottom: Int)
