package com.scrollguard.data

import android.graphics.drawable.Drawable

data class AppPickerItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var isMonitored: Boolean
)
