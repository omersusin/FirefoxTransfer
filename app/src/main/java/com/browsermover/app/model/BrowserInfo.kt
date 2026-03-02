package com.browsermover.app.model

enum class BrowserType { GECKO, CHROMIUM, UNKNOWN }

data class BrowserInfo(
    val packageName: String,
    val browserType: BrowserType
)
