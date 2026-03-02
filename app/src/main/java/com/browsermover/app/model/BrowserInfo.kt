package com.browsermover.app.model

import android.graphics.drawable.Drawable

enum class BrowserType {
    GECKO,
    CHROMIUM,
    UNKNOWN
}

data class BrowserInfo(
    val packageName: String,
    val appName: String,
    val browserType: BrowserType,
    val icon: Drawable? = null,
    val versionName: String = "",
    val isInstalled: Boolean = true
) {
    val displayLabel: String
        get() = "$appName ($packageName)"

    val engineLabel: String
        get() = when (browserType) {
            BrowserType.GECKO    -> "\uD83E\uDD8E Gecko"
            BrowserType.CHROMIUM -> "⚡ Chromium"
            BrowserType.UNKNOWN  -> "❓ Unknown"
        }
}
