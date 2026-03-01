package com.browsermover.app

data class BrowserInfo(
    val name: String,
    val packageName: String,
    val type: BrowserType,
    val isInstalled: Boolean = false
)

enum class BrowserType(val label: String) {
    FIREFOX("Firefox Tabanlı"),
    CHROMIUM("Chromium Tabanlı"),
    UNKNOWN("Bilinmiyor")
}