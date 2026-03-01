package com.browsermover.app

data class BrowserInfo(
    val name: String,
    val packageName: String,
    val type: BrowserType,
    val isInstalled: Boolean = false
)

enum class BrowserType(val label: String) {
    GECKO("Gecko Family"),
    CHROMIUM("Chromium Family"),
    UNKNOWN("Unknown")
}
