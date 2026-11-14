package com.browsermover.app.core

/** A bookmark read from either engine, with the folder path it lived in. */
internal data class SourceBookmark(
    val title: String,
    val url: String,
    val path: List<String>,
    val dateAddedMillis: Long
)

/** A URL plus all of its visit timestamps (microseconds since Unix epoch). */
internal data class SourcePlace(
    val url: String,
    val title: String,
    val visitCount: Int,
    val lastVisitMicros: Long,
    var typed: Boolean,
    val visits: MutableList<Long> = mutableListOf()
)

internal data class ConversionResult(
    val success: Boolean,
    val message: String,
    val warnings: List<String> = emptyList()
)

internal data class GeckoData(
    val places: List<SourcePlace>,
    val bookmarks: List<SourceBookmark>
)

internal data class ChromiumData(
    val places: List<SourcePlace>,
    val bookmarks: List<SourceBookmark>
)

/**
 * A cookie read from either engine. Expiry is Unix seconds (0 = session
 * cookie). SameSite uses the Gecko encoding internally: 0 = none/unset,
 * 1 = lax, 2 = strict.
 */
internal data class SourceCookie(
    val host: String,
    val name: String,
    val value: String,
    val path: String,
    val expiryEpochSeconds: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val sameSite: Int
)

internal data class CookieReadResult(
    val cookies: List<SourceCookie>,
    val encryptedSkipped: Int = 0
)

internal data class CookieImportResult(
    val imported: Int,
    val skipped: Int,
    val message: String
)
