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
