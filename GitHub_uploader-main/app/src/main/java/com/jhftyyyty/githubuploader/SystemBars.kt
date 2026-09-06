package com.jhftyyyty.githubuploader

import android.view.View
import android.view.Window

internal fun updateSystemBars(window: Window, dark: Boolean) {
    val bg = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    window.statusBarColor = bg
    window.navigationBarColor = bg
    val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    window.decorView.systemUiVisibility =
        if (dark) window.decorView.systemUiVisibility and light.inv()
        else window.decorView.systemUiVisibility or light
}
