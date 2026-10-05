package com.jhftyyyty.githubuploader

import android.graphics.Color
import android.view.Window
import androidx.core.view.WindowCompat

internal fun updateSystemBars(window: Window, dark: Boolean) {
    val bg = if (dark) Color.BLACK else Color.WHITE
    window.statusBarColor = bg
    window.navigationBarColor = bg
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !dark
    controller.isAppearanceLightNavigationBars = !dark
}
