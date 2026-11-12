package com.browsermover.app.core

import android.database.sqlite.SQLiteDatabase
import com.browsermover.app.root.RootHelper
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Best-effort open-tab transfer.
 *
 * Chromium stores open tabs in its binary SNSS session files; Gecko stores
 * them in sessionstore.jsonlz4 (MozLz4) or tabs.sqlite. Binary session files
 * are deliberately read, never written: opening the extracted URLs in the
 * target browser is much safer than trying to make one engine read the other
 * engine's private session format.
 */
internal object TabMigrator {

    data class OpenTab(
        val url: String,
        val title: String,
        val windowIndex: Int,
        val tabIndex: Int
    )

    data class ReadResult(
        val tabs: List<OpenTab>,
        val warning: String? = null
    )

    data class OpenResult(
        val requested: Int,
        val opened: Int,
        val warning: String? = null
    )

    private const val MAX_TABS = 30

    // ------------------------------------------------------------------
    //  Discovery
    // ------------------------------------------------------------------

    suspend fun collectChromium(
        profile: ChromiumProfile,
        export: suspend (source: String, name: String) -> String?
    ): ReadResult {
        val candidates = mutableListOf<String>()
        val sessionsDir = "${profile.profileDir}/Sessions"
        if (BrowserPaths.exists(sessionsDir)) {
            val listed = RootHelper.exec(
                "ls -1t ${BrowserPaths.q(sessionsDir)} 2>/dev/null | head -5"
            ).outputLines
            for (name in listed) {
                if (name.isNotBlank()) candidates.add("$sessionsDir/$name")
            }
        }
        for (name in listOf(
            "Current Session", "Current Tabs", "Last Session", "Last Tabs"
        )) {
            candidates.add("${profile.profileDir}/$name")
        }

        var lastWarning: String? = null
        var index = 0
        for (path in candidates.distinct()) {
            if (!BrowserPaths.exists(path)) continue
            val local = export(path, "source_tabs_${index++}") ?: continue
            val result = try {
                parseSessionBytes(java.io.File(local).readBytes())
            } catch (e: Exception) {
                ReadResult(emptyList(), "Could not read Chromium session: ${e.message}")
            }
            if (result.tabs.isNotEmpty()) return result
            if (result.warning != null) lastWarning = result.warning
        }
        return ReadResult(emptyList(), lastWarning ?: "No open Chromium tabs found.")
    }

    suspend fun collectGecko(
        profile: GeckoProfile,
        export: suspend (source: String, name: String) -> String?
    ): ReadResult {
        val roots = listOf(profile.profileDir, profile.filesDir)
            .filterNotNull()
            .distinct()
        val candidates = mutableListOf<String>()
        for (root in roots) {
            for (name in listOf(
                "sessionstore.jsonlz4", "sessionstore.js",
                "sessionstore-backups/recovery.jsonlz4",
                "sessionstore-backups/recovery.js",
                "sessionstore-backups/previous.jsonlz4",
                "tabs.sqlite"
            )) {
                candidates.add("$root/$name")
            }
        }

        var lastWarning: String? = null
        var index = 0
        for (path in candidates.distinct()) {
            if (!BrowserPaths.exists(path)) continue
            val local = export(path, "source_tabs_${index++}") ?: continue
            val result = if (path.endsWith(".sqlite", true)) {
                parseGeckoTabsDatabase(local)
            } else {
                try {
                    parseSessionBytes(java.io.File(local).readBytes())
                } catch (e: Exception) {
                    ReadResult(emptyList(), "Could not read Gecko session: ${e.message}")
                }
            }
            if (result.tabs.isNotEmpty()) return result
            if (result.warning != null) lastWarning = result.warning
        }
        return ReadResult(emptyList(), lastWarning ?: "No open Gecko tabs found.")
    }

    // ------------------------------------------------------------------
    //  Opening in the target
    // ------------------------------------------------------------------

    suspend fun openInTarget(pkg: String, tabs: List<OpenTab>): OpenResult {
        if (tabs.isEmpty()) return OpenResult(0, 0, "No open tabs to restore.")
        val selected = tabs
            .filter { it.url.startsWith("http://", true) || it.url.startsWith("https://", true) }
            .distinctBy { it.url }
            .take(MAX_TABS)

        if (selected.isEmpty()) {
            return OpenResult(0, 0, "No HTTP(S) tabs were available to restore.")
        }

        val commands = mutableListOf(
            "am force-stop ${BrowserPaths.q(pkg)}",
            "sleep 1"
        )
        for (tab in selected) {
            commands.add(
                "am start -a android.intent.action.VIEW " +
                    "-d ${BrowserPaths.q(tab.url)} -p ${BrowserPaths.q(pkg)} " +
                    ">/dev/null 2>&1; sleep 0.35"
            )
        }
        val result = RootHelper.execMultiple(commands)
        val warning = when {
            !result.success -> "Could not open tabs in $pkg."
            tabs.size > selected.size ->
                "Opened the first ${selected.size} tabs only (limit $MAX_TABS)."
            else -> null
        }
        return OpenResult(selected.size, if (result.success) selected.size else 0, warning)
    }

    // ------------------------------------------------------------------
    //  Format dispatch
    // ------------------------------------------------------------------

    fun parseSessionBytes(bytes: ByteArray): ReadResult {
        if (bytes.size >= 4 &&
            bytes[0] == 'S'.code.toByte() &&
            bytes[1] == 'N'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()
        ) {
            return parseChromiumSession(bytes)
        }
        return parseGeckoSession(bytes)
    }

    // ------------------------------------------------------------------
    //  Chromium SNSS
    // ------------------------------------------------------------------

    private data class SnsTab(
        val id: Long,
        var windowId: Long = 0L,
        var position: Int = 0,
        var currentIndex: Int = -1,
        var deleted: Boolean = false
    ) {
        data class Entry(val index: Int, val url: String, val title: String)
        val entries = mutableListOf<Entry>()
    }

    private fun parseChromiumSession(bytes: ByteArray): ReadResult {
        return try {
            val r = ByteReader(bytes)
            val magic = r.bytes(4)
            if (magic == null || String(magic, StandardCharsets.US_ASCII) != "SNSS") {
                return ReadResult(emptyList(), "Not a Chromium SNSS session file.")
            }
            val version = r.u32() ?: return ReadResult(emptyList(), "Truncated SNSS header.")
            if (version != 1L && version != 3L) {
                return ReadResult(emptyList(), "Unsupported SNSS version $version.")
            }

            val tabs = LinkedHashMap<Long, SnsTab>()
            val closedWindows = mutableSetOf<Long>()
            var truncated = false

            while (r.remaining >= 3) {
                val size = r.u16() ?: break
                if (size == 0) break
                val type = r.u8() ?: break
                val payloadLength = size - 1
                val payload = r.bytes(payloadLength)
                if (payload == null) {
                    truncated = true
                    break
                }
                val p = ByteReader(payload)
                when (type) {
                    0 -> { // kCommandSetTabWindow
                        val windowId = p.u32() ?: continue
                        val tabId = p.u32() ?: continue
                        tab(tabs, tabId).windowId = windowId
                    }
                    2 -> { // kCommandSetTabIndexInWindow
                        val tabId = p.u32() ?: continue
                        val index = p.u32()?.toInt() ?: continue
                        tab(tabs, tabId).position = index
                    }
                    6 -> { // kCommandUpdateTabNavigation
                        p.u32() // pickled payload length
                        val tabId = p.u32() ?: continue
                        val historyIndex = p.u32()?.toInt() ?: continue
                        val url = p.string8() ?: continue
                        val title = p.string16() ?: ""
                        val tab = tab(tabs, tabId)
                        if (url.startsWith("http://", true) ||
                            url.startsWith("https://", true) ||
                            url.startsWith("ftp://", true)
                        ) {
                            val old = tab.entries.indexOfFirst { it.index == historyIndex }
                            if (old >= 0) {
                                tab.entries[old] = SnsTab.Entry(historyIndex, url, title)
                            } else {
                                tab.entries.add(SnsTab.Entry(historyIndex, url, title))
                            }
                        }
                    }
                    7 -> { // kCommandSetSelectedNavigationIndex
                        val tabId = p.u32() ?: continue
                        tab(tabs, tabId).currentIndex = p.u32()?.toInt() ?: -1
                    }
                    16 -> { // kCommandTabClosed
                        val tabId = p.u32() ?: continue
                        tab(tabs, tabId).deleted = true
                    }
                    17 -> { // kCommandWindowClosed
                        val windowId = p.u32() ?: continue
                        closedWindows.add(windowId)
                    }
                }
            }

            val result = mutableListOf<OpenTab>()
            for (tab in tabs.values) {
                if (tab.deleted || tab.windowId in closedWindows) continue
                val chosen = tab.entries
                    .sortedBy { it.index }
                    .let { entries ->
                        entries.firstOrNull { it.index == tab.currentIndex }
                            ?: entries.lastOrNull()
                    } ?: continue
                if (!isImportableUrl(chosen.url)) continue
                result.add(
                    OpenTab(
                        url = chosen.url,
                        title = chosen.title,
                        windowIndex = tab.windowId.toInt(),
                        tabIndex = tab.position
                    )
                )
            }

            val sorted = result
                .distinctBy { it.url }
                .sortedWith(compareBy({ it.windowIndex }, { it.tabIndex }))
            ReadResult(
                sorted,
                when {
                    sorted.isEmpty() -> "No restorable Chromium tabs found."
                    truncated -> "Chromium session was partially corrupted; recovered ${sorted.size} tabs."
                    else -> null
                }
            )
        } catch (e: Exception) {
            ReadResult(emptyList(), "Chromium session parse error: ${e.message}")
        }
    }

    private fun tab(tabs: MutableMap<Long, SnsTab>, id: Long): SnsTab =
        tabs.getOrPut(id) { SnsTab(id) }

    // ------------------------------------------------------------------
    //  Gecko session JSON / MozLz4
    // ------------------------------------------------------------------

    private fun parseGeckoSession(bytes: ByteArray): ReadResult {
        return try {
            val jsonBytes = decodeMozLz4(bytes) ?: bytes
            val text = String(jsonBytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
            val root = JSONObject(text)
            val windows = root.optJSONArray("windows") ?: return ReadResult(
                emptyList(), "Gecko session has no windows array."
            )
            val result = mutableListOf<OpenTab>()
            for (wi in 0 until windows.length()) {
                val window = windows.optJSONObject(wi) ?: continue
                val tabs = window.optJSONArray("tabs") ?: continue
                for (ti in 0 until tabs.length()) {
                    val tab = tabs.optJSONObject(ti) ?: continue
                    val entries = tab.optJSONArray("entries") ?: continue
                    if (entries.length() == 0) continue
                    val requested = tab.optInt("index", entries.length() - 1)
                    val index = requested.coerceIn(0, entries.length() - 1)
                    val entry = entries.optJSONObject(index) ?: continue
                    val url = entry.optString("url", "")
                    if (!isImportableUrl(url)) continue
                    result.add(
                        OpenTab(
                            url = url,
                            title = entry.optString("title", ""),
                            windowIndex = wi,
                            tabIndex = ti
                        )
                    )
                }
            }
            ReadResult(
                result.distinctBy { it.url },
                if (result.isEmpty()) "No restorable Gecko tabs found." else null
            )
        } catch (e: Exception) {
            ReadResult(emptyList(), "Gecko session parse error: ${e.message}")
        }
    }

    fun decodeMozLz4(data: ByteArray): ByteArray? {
        if (data.size < 12) return null
        val magic = "mozLz40\u0000".toByteArray(StandardCharsets.US_ASCII)
        for (i in magic.indices) {
            if (data[i] != magic[i]) return null
        }
        val expected = (data[8].toLong() and 0xFF) or
            ((data[9].toLong() and 0xFF) shl 8) or
            ((data[10].toLong() and 0xFF) shl 16) or
            ((data[11].toLong() and 0xFF) shl 24)
        if (expected <= 0 || expected > 64L * 1024 * 1024) return null

        val out = ByteArray(expected.toInt())
        var inPos = 12
        var outPos = 0
        try {
            while (inPos < data.size && outPos < out.size) {
                val token = data[inPos++].toInt() and 0xFF
                var literalLength = token ushr 4
                if (literalLength == 15) {
                    while (true) {
                        val b = data[inPos++].toInt() and 0xFF
                        literalLength += b
                        if (b != 255) break
                    }
                }
                if (inPos + literalLength > data.size ||
                    outPos + literalLength > out.size
                ) return null
                System.arraycopy(data, inPos, out, outPos, literalLength)
                inPos += literalLength
                outPos += literalLength
                if (inPos >= data.size || outPos >= out.size) break

                if (inPos + 2 > data.size) return null
                val offset = (data[inPos].toInt() and 0xFF) or
                    ((data[inPos + 1].toInt() and 0xFF) shl 8)
                inPos += 2
                if (offset <= 0 || offset > outPos) return null
                var matchLength = token and 0x0F
                if (matchLength == 15) {
                    while (true) {
                        val b = data[inPos++].toInt() and 0xFF
                        matchLength += b
                        if (b != 255) break
                    }
                }
                matchLength += 4
                if (outPos + matchLength > out.size) return null
                for (i in 0 until matchLength) {
                    out[outPos + i] = out[outPos - offset + i]
                }
                outPos += matchLength
            }
        } catch (_: Exception) {
            return null
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }

    // ------------------------------------------------------------------
    //  Gecko tabs.sqlite fallback
    // ------------------------------------------------------------------

    private fun parseGeckoTabsDatabase(path: String): ReadResult {
        val db = try {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            return ReadResult(emptyList(), "Could not open tabs.sqlite: ${e.message}")
        }
        try {
            val cols = columns(db, "tabs")
            if (cols.isEmpty()) return ReadResult(emptyList(), "tabs.sqlite has no tabs table.")
            if ("url" in cols) {
                val titleExpr = if ("title" in cols) "title" else "''"
                val result = mutableListOf<OpenTab>()
                db.rawQuery("SELECT url,$titleExpr FROM tabs", null).use { c ->
                    var i = 0
                    while (c.moveToNext()) {
                        val url = c.getString(0) ?: continue
                        if (!isImportableUrl(url)) continue
                        result.add(OpenTab(url, c.getString(1) ?: "", 0, i++))
                    }
                }
                return ReadResult(
                    result.distinctBy { it.url },
                    if (result.isEmpty()) "No restorable rows in tabs.sqlite." else null
                )
            }
            return ReadResult(emptyList(), "tabs.sqlite has no url column.")
        } catch (e: Exception) {
            return ReadResult(emptyList(), "tabs.sqlite read error: ${e.message}")
        } finally {
            db.close()
        }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val out = mutableSetOf<String>()
        try {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                val idx = c.getColumnIndex("name")
                if (idx < 0) return out
                while (c.moveToNext()) out.add(c.getString(idx))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun isImportableUrl(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://", true) ||
            u.startsWith("https://", true) ||
            u.startsWith("ftp://", true)
    }

    // ------------------------------------------------------------------
    //  Small binary reader
    // ------------------------------------------------------------------

    private class ByteReader(private val data: ByteArray) {
        var position = 0
        val remaining: Int get() = data.size - position

        fun u8(): Int? {
            if (position >= data.size) return null
            return data[position++].toInt() and 0xFF
        }

        fun u16(): Int? {
            val a = u8() ?: return null
            val b = u8() ?: return null
            return a or (b shl 8)
        }

        fun u32(): Long? {
            var value = 0L
            for (i in 0 until 4) {
                val b = u8() ?: return null
                value = value or (b.toLong() shl (8 * i))
            }
            return value
        }

        fun bytes(count: Int): ByteArray? {
            if (count < 0 || position + count > data.size) return null
            val out = data.copyOfRange(position, position + count)
            position += count
            return out
        }

        fun string8(): String? {
            val length = u32()?.toInt() ?: return null
            if (length < 0 || length > remaining) return null
            val bytes = bytes(length) ?: return null
            val padded = (length + 3) and 3.inv()
            if (padded > length) {
                if (padded - length > remaining) return null
                position += padded - length
            }
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun string16(): String? {
            val units = u32()?.toInt() ?: return null
            if (units < 0) return null
            val byteCount = units * 2
            if (byteCount > remaining) return null
            val bytes = bytes(byteCount) ?: return null
            val padded = (byteCount + 3) and 3.inv()
            if (padded > byteCount) {
                if (padded - byteCount > remaining) return null
                position += padded - byteCount
            }
            return String(bytes, StandardCharsets.UTF_16LE)
        }
    }
}
