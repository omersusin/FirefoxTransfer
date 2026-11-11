package com.browsermover.app.core

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * Converts bookmarks and history between Chromium and Gecko on-device.
 *
 * SQLiteDatabase is used instead of the sqlite3 binary: most rooted phones do
 * not ship sqlite3, and making the whole migration depend on it is what made
 * the old Chromium transfer silently skip its database patching.
 */
internal object CrossEngineConverter {

    private const val CHROME_EPOCH_OFFSET = 11_644_473_600_000_000L

    // ------------------------------------------------------------------
    //  Reading
    // ------------------------------------------------------------------

    fun readChromium(historyPath: String?, bookmarksRaw: String?): ChromiumData {
        val places = historyPath?.let { readChromiumHistory(it) } ?: emptyList()
        val bookmarks = bookmarksRaw?.let { parseChromiumBookmarks(it) } ?: emptyList()
        return ChromiumData(places, bookmarks)
    }

    private fun readChromiumHistory(path: String): List<SourcePlace> {
        val db = SQLiteDatabase.openDatabase(
            path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        try {
            val byId = LinkedHashMap<Long, SourcePlace>()
            db.rawQuery(
                "SELECT id,url,title,visit_count,last_visit_time,typed_count FROM urls",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val url = c.getString(1) ?: continue
                    if (!isImportableUrl(url)) continue
                    val last = chromeToUnixMicros(c.getLong(4))
                    byId[c.getLong(0)] = SourcePlace(
                        url = url,
                        title = c.getString(2) ?: "",
                        visitCount = c.getInt(3),
                        lastVisitMicros = last,
                        typed = c.getInt(5) > 0
                    )
                }
            }

            try {
                db.rawQuery("SELECT url,visit_time FROM visits", null).use { c ->
                    while (c.moveToNext()) {
                        val place = byId[c.getLong(0)] ?: continue
                        val time = chromeToUnixMicros(c.getLong(1))
                        if (time > 0) place.visits.add(time)
                    }
                }
            } catch (_: Exception) {
                // Older/corrupt databases may not have visits; last_visit_time
                // still gives a usable history entry.
            }

            for (place in byId.values) {
                if (place.visits.isEmpty() && place.lastVisitMicros > 0) {
                    place.visits.add(place.lastVisitMicros)
                }
            }
            return byId.values.toList()
        } finally {
            db.close()
        }
    }

    private fun parseChromiumBookmarks(raw: String): List<SourceBookmark> {
        val result = mutableListOf<SourceBookmark>()
        val root = try { JSONObject(raw) } catch (_: Exception) { return result }
        val roots = root.optJSONObject("roots") ?: return result

        fun walk(node: JSONObject, path: List<String>) {
            val type = node.optString("type")
            if (type == "url") {
                val url = node.optString("url", "")
                if (isImportableUrl(url)) {
                    result.add(
                        SourceBookmark(
                            title = node.optString("name", ""),
                            url = url,
                            path = path,
                            dateAddedMillis = chromeStringToMillis(node.optString("date_added"))
                        )
                    )
                }
                return
            }

            val name = node.optString("name", "").trim()
            val next = if (name.isEmpty()) path else path + name
            val children = node.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.let { walk(it, next) }
            }
        }

        val labels = listOf(
            "bookmark_bar" to "Bookmarks Bar",
            "other" to "Other Bookmarks",
            "synced" to "Mobile Bookmarks"
        )
        for ((key, label) in labels) {
            val node = roots.optJSONObject(key) ?: continue
            val children = node.optJSONArray("children") ?: continue
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.let { walk(it, listOf(label)) }
            }
        }
        return result
    }

    fun readGecko(placesPath: String): GeckoData {
        val db = SQLiteDatabase.openDatabase(placesPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val placesById = LinkedHashMap<Long, SourcePlace>()
            db.rawQuery(
                "SELECT id,url,title,visit_count,last_visit_date,typed FROM moz_places",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val url = c.getString(1) ?: continue
                    if (!isImportableUrl(url)) continue
                    placesById[c.getLong(0)] = SourcePlace(
                        url = url,
                        title = c.getString(2) ?: "",
                        visitCount = c.getInt(3),
                        lastVisitMicros = if (c.isNull(4)) 0L else c.getLong(4),
                        typed = c.getInt(5) > 0
                    )
                }
            }

            try {
                db.rawQuery(
                    "SELECT place_id,visit_date,visit_type FROM moz_historyvisits",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val place = placesById[c.getLong(0)] ?: continue
                        val time = c.getLong(1)
                        if (time > 0) place.visits.add(time)
                        if (c.getInt(2) == 2) place.typed = true
                    }
                }
            } catch (_: Exception) {
            }

            for (place in placesById.values) {
                if (place.visits.isEmpty() && place.lastVisitMicros > 0) {
                    place.visits.add(place.lastVisitMicros)
                }
            }

            return GeckoData(placesById.values.toList(), readGeckoBookmarks(db, placesById))
        } finally {
            db.close()
        }
    }

    private data class GeckoNode(
        val id: Long,
        val parent: Long,
        val type: Int,
        val title: String,
        val fk: Long?,
        val dateAdded: Long
    )

    private fun readGeckoBookmarks(
        db: SQLiteDatabase,
        placesById: Map<Long, SourcePlace>
    ): List<SourceBookmark> {
        val nodes = LinkedHashMap<Long, GeckoNode>()
        try {
            db.rawQuery(
                "SELECT id,parent,type,title,fk,dateAdded FROM moz_bookmarks WHERE type IN (1,2)",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    nodes[c.getLong(0)] = GeckoNode(
                        id = c.getLong(0),
                        parent = c.getLong(1),
                        type = c.getInt(2),
                        title = c.getString(3) ?: "",
                        fk = if (c.isNull(4)) null else c.getLong(4),
                        dateAdded = if (c.isNull(5)) 0L else c.getLong(5)
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        fun rootOf(id: Long): Long {
            var cur = id
            var guard = 0
            while (guard++ < 256) {
                if (cur in 2L..6L) return cur
                val node = nodes[cur] ?: return 1L
                if (node.parent == 0L || node.parent == 1L) return 1L
                cur = node.parent
            }
            return 1L
        }

        fun folderPath(id: Long): List<String> {
            val parts = mutableListOf<String>()
            var cur = nodes[id]?.parent ?: 1L
            var guard = 0
            while (guard++ < 256 && cur != 0L && cur !in 1L..6L) {
                val node = nodes[cur] ?: break
                if (node.type == 2) parts.add(node.title)
                cur = node.parent
            }
            return parts.asReversed().toList()
        }

        fun rootLabel(root: Long): String = when (root) {
            3L -> "Bookmarks Bar"
            4L -> "Other Bookmarks"
            5L -> "Mobile Bookmarks"
            2L -> "Bookmarks Menu"
            else -> "Other Bookmarks"
        }

        val result = mutableListOf<SourceBookmark>()
        for (node in nodes.values) {
            if (node.type != 1) continue
            val root = rootOf(node.id)
            if (root == 6L) continue // tags are not real bookmarks
            val place = node.fk?.let { placesById[it] } ?: continue
            result.add(
                SourceBookmark(
                    title = node.title.ifBlank { place.title },
                    url = place.url,
                    path = listOf(rootLabel(root)) + folderPath(node.id),
                    dateAddedMillis = if (node.dateAdded > 0) {
                        node.dateAdded / 1000
                    } else {
                        System.currentTimeMillis()
                    }
                )
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    //  Writing: Chromium -> Gecko
    // ------------------------------------------------------------------

    fun importIntoGecko(placesPath: String, data: ChromiumData): ConversionResult {
        val db = try {
            SQLiteDatabase.openDatabase(placesPath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            return ConversionResult(false, "Could not open target places.sqlite: ${e.message}")
        }

        try {
            val placeCols = columns(db, "moz_places")
            val bookmarkCols = columns(db, "moz_bookmarks")
            val visitCols = columns(db, "moz_historyvisits")
            if (placeCols.isEmpty() || bookmarkCols.isEmpty()) {
                return ConversionResult(
                    false,
                    "Target is not a Gecko places database. Open the target browser once, then retry."
                )
            }

            var importedPlaces = 0
            var importedVisits = 0
            var importedBookmarks = 0

            db.beginTransaction()
            try {
                val placeIds = HashMap<String, Long>()
                for (place in data.places) {
                    val id = upsertGeckoPlace(db, placeCols, place)
                    placeIds[place.url] = id
                    importedPlaces++
                    for (time in place.visits) {
                        if (insertGeckoVisit(db, visitCols, id, time)) importedVisits++
                    }
                }

                val rootId = geckoRootId(db) ?: return ConversionResult(
                    false,
                    "Target places.sqlite has no bookmark roots. Open the target browser once, then retry."
                )

                for (bookmark in data.bookmarks) {
                    val label = bookmark.path.firstOrNull().orEmpty()
                    var parent = geckoRootForLabel(db, label) ?: rootId
                    for (folder in bookmark.path.drop(1)) {
                        parent = ensureGeckoFolder(
                            db, bookmarkCols, parent, folder, bookmark.dateAddedMillis
                        )
                    }
                    val placeId = placeIds[bookmark.url]
                        ?: upsertGeckoPlace(
                            db, placeCols,
                            SourcePlace(
                                bookmark.url, bookmark.title, 1, 0, false
                            )
                        ).also { placeIds[bookmark.url] = it }

                    if (insertGeckoBookmark(
                            db, bookmarkCols, parent, placeId, bookmark
                        )
                    ) importedBookmarks++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            try {
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (_: Exception) {
            }

            return ConversionResult(
                true,
                "Imported $importedBookmarks bookmarks and $importedVisits history visits " +
                    "($importedPlaces URLs).",
                emptyList()
            )
        } catch (e: Exception) {
            return ConversionResult(false, "Gecko import failed: ${e.message}")
        } finally {
            db.close()
        }
    }

    private fun geckoRootId(db: SQLiteDatabase): Long? {
        for (guid in listOf("mobile______", "unfiled_____", "menu________")) {
            val id = queryLong(db, "SELECT id FROM moz_bookmarks WHERE guid=?", arrayOf(guid))
            if (id != null) return id
        }
        return queryLong(db, "SELECT id FROM moz_bookmarks WHERE parent=0 ORDER BY id LIMIT 1", null)
    }

    private fun geckoRootForLabel(db: SQLiteDatabase, label: String): Long? {
        val guid = when (label) {
            "Bookmarks Bar" -> "toolbar_____"
            "Other Bookmarks" -> "unfiled_____"
            "Mobile Bookmarks" -> "mobile______"
            "Bookmarks Menu" -> "menu________"
            else -> null
        } ?: return null
        return queryLong(db, "SELECT id FROM moz_bookmarks WHERE guid=?", arrayOf(guid))
    }

    private fun upsertGeckoPlace(
        db: SQLiteDatabase,
        cols: Set<String>,
        place: SourcePlace
    ): Long {
        val existing = queryLong(db, "SELECT id FROM moz_places WHERE url=?", arrayOf(place.url))
        val lastVisit = when {
            place.lastVisitMicros > 0 -> place.lastVisitMicros
            place.visits.isNotEmpty() -> place.visits.maxOrNull() ?: 0L
            else -> 0L
        }

        if (existing != null) {
            val update = ContentValues()
            if (place.title.isNotBlank() && "title" in cols) update.put("title", place.title)
            if (place.typed && "typed" in cols) update.put("typed", 1)
            if (update.size() > 0) {
                db.update("moz_places", update, "id=?", arrayOf(existing.toString()))
            }
            if ("visit_count" in cols) {
                db.execSQL(
                    "UPDATE moz_places SET visit_count=MAX(COALESCE(visit_count,0),?) WHERE id=?",
                    arrayOf<Any>(maxOf(place.visitCount, place.visits.size), existing)
                )
            }
            if ("last_visit_date" in cols && lastVisit > 0) {
                db.execSQL(
                    "UPDATE moz_places SET last_visit_date=MAX(COALESCE(last_visit_date,0),?) WHERE id=?",
                    arrayOf<Any>(lastVisit, existing)
                )
            }
            if ("url_hash" in cols) {
                db.execSQL(
                    "UPDATE moz_places SET url_hash=? WHERE id=?",
                    arrayOf<Any>(geckoUrlHash(place.url), existing)
                )
            }
            return existing
        }

        val values = ContentValues()
        values.put("url", place.url)
        if ("title" in cols) values.put("title", place.title)
        if ("rev_host" in cols) values.put("rev_host", revHost(place.url))
        if ("visit_count" in cols) values.put("visit_count", maxOf(place.visitCount, place.visits.size))
        if ("hidden" in cols) values.put("hidden", 0)
        if ("typed" in cols) values.put("typed", if (place.typed) 1 else 0)
        if ("frecency" in cols) values.put("frecency", -1)
        if ("last_visit_date" in cols && lastVisit > 0) values.put("last_visit_date", lastVisit)
        if ("guid" in cols) values.put("guid", geckoGuid())
        if ("url_hash" in cols) values.put("url_hash", geckoUrlHash(place.url))
        return db.insertOrThrow("moz_places", null, values)
    }

    private fun insertGeckoVisit(
        db: SQLiteDatabase,
        cols: Set<String>,
        placeId: Long,
        time: Long
    ): Boolean {
        if (cols.isEmpty()) return false
        val exists = queryLong(
            db,
            "SELECT 1 FROM moz_historyvisits WHERE place_id=? AND visit_date=? LIMIT 1",
            arrayOf(placeId.toString(), time.toString())
        )
        if (exists != null) return false

        val values = ContentValues()
        values.put("place_id", placeId)
        values.put("visit_date", time)
        if ("visit_type" in cols) values.put("visit_type", 1)
        if ("session" in cols) values.put("session", 0)
        if ("source" in cols) values.put("source", 0)
        fillRequired(db, "moz_historyvisits", values)
        return try {
            db.insertOrThrow("moz_historyvisits", null, values)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureGeckoFolder(
        db: SQLiteDatabase,
        cols: Set<String>,
        parent: Long,
        name: String,
        dateAddedMillis: Long
    ): Long {
        val existing = queryLong(
            db,
            "SELECT id FROM moz_bookmarks WHERE parent=? AND type=2 AND title=? LIMIT 1",
            arrayOf(parent.toString(), name)
        )
        if (existing != null) return existing

        val position = queryLong(
            db,
            "SELECT COALESCE(MAX(position), -1) + 1 FROM moz_bookmarks WHERE parent=?",
            arrayOf(parent.toString())
        ) ?: 0L
        val added = if (dateAddedMillis > 0) dateAddedMillis * 1000 else System.currentTimeMillis() * 1000

        val values = ContentValues()
        values.put("type", 2)
        values.put("parent", parent)
        values.put("position", position)
        values.put("title", name)
        if ("dateAdded" in cols) values.put("dateAdded", added)
        if ("lastModified" in cols) values.put("lastModified", System.currentTimeMillis() * 1000)
        if ("guid" in cols) values.put("guid", geckoGuid())
        fillRequired(db, "moz_bookmarks", values)
        return db.insertOrThrow("moz_bookmarks", null, values)
    }

    private fun insertGeckoBookmark(
        db: SQLiteDatabase,
        cols: Set<String>,
        parent: Long,
        placeId: Long,
        bookmark: SourceBookmark
    ): Boolean {
        val duplicate = queryLong(
            db,
            "SELECT b.id FROM moz_bookmarks b JOIN moz_places p ON b.fk=p.id " +
                "WHERE b.type=1 AND b.parent=? AND p.url=? LIMIT 1",
            arrayOf(parent.toString(), bookmark.url)
        )
        if (duplicate != null) return false

        val position = queryLong(
            db,
            "SELECT COALESCE(MAX(position), -1) + 1 FROM moz_bookmarks WHERE parent=?",
            arrayOf(parent.toString())
        ) ?: 0L
        val added = if (bookmark.dateAddedMillis > 0) {
            bookmark.dateAddedMillis * 1000
        } else {
            System.currentTimeMillis() * 1000
        }

        val values = ContentValues()
        values.put("type", 1)
        values.put("parent", parent)
        values.put("position", position)
        values.put("title", bookmark.title)
        values.put("fk", placeId)
        if ("dateAdded" in cols) values.put("dateAdded", added)
        if ("lastModified" in cols) values.put("lastModified", System.currentTimeMillis() * 1000)
        if ("guid" in cols) values.put("guid", geckoGuid())
        fillRequired(db, "moz_bookmarks", values)
        return try {
            db.insertOrThrow("moz_bookmarks", null, values)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    //  Writing: Gecko -> Chromium
    // ------------------------------------------------------------------

    fun importIntoChromium(historyPath: String, data: GeckoData): ConversionResult {
        val db = try {
            SQLiteDatabase.openDatabase(
                historyPath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (e: Exception) {
            return ConversionResult(false, "Could not open target History: ${e.message}")
        }

        try {
            if (columns(db, "urls").isEmpty()) {
                return ConversionResult(
                    false,
                    "Target is not a Chromium History database. Open the target browser once, then retry."
                )
            }

            var importedPlaces = 0
            var importedVisits = 0
            val urlCols = columns(db, "urls")
            val visitCols = columns(db, "visits")

            db.beginTransaction()
            try {
                for (place in data.places) {
                    val urlId = upsertChromiumUrl(db, urlCols, place)
                    importedPlaces++
                    for (time in place.visits) {
                        if (insertChromiumVisit(db, visitCols, urlId, time)) importedVisits++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            try {
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (_: Exception) {
            }

            return ConversionResult(
                true,
                "Imported ${data.bookmarks.size} bookmarks and $importedVisits history visits " +
                    "($importedPlaces URLs).",
                emptyList()
            )
        } catch (e: Exception) {
            return ConversionResult(false, "Chromium import failed: ${e.message}")
        } finally {
            db.close()
        }
    }

    private fun upsertChromiumUrl(
        db: SQLiteDatabase,
        cols: Set<String>,
        place: SourcePlace
    ): Long {
        val last = unixToChromeMicros(
            if (place.lastVisitMicros > 0) place.lastVisitMicros
            else place.visits.maxOrNull() ?: 0L
        )
        val existing = queryLong(db, "SELECT id FROM urls WHERE url=?", arrayOf(place.url))

        if (existing != null) {
            val update = ContentValues()
            if (place.title.isNotBlank() && "title" in cols) update.put("title", place.title)
            if (place.typed && "typed_count" in cols) update.put("typed_count", 1)
            if (update.size() > 0) {
                db.update("urls", update, "id=?", arrayOf(existing.toString()))
            }
            if ("visit_count" in cols) {
                db.execSQL(
                    "UPDATE urls SET visit_count=MAX(COALESCE(visit_count,0),?) WHERE id=?",
                    arrayOf<Any>(maxOf(place.visitCount, place.visits.size), existing)
                )
            }
            if ("last_visit_time" in cols && last > 0) {
                db.execSQL(
                    "UPDATE urls SET last_visit_time=MAX(COALESCE(last_visit_time,0),?) WHERE id=?",
                    arrayOf<Any>(last, existing)
                )
            }
            return existing
        }

        val values = ContentValues()
        if ("url" in cols) values.put("url", place.url)
        if ("title" in cols) values.put("title", place.title)
        if ("visit_count" in cols) values.put("visit_count", maxOf(place.visitCount, place.visits.size))
        if ("typed_count" in cols) values.put("typed_count", if (place.typed) 1 else 0)
        if ("last_visit_time" in cols) values.put("last_visit_time", last)
        if ("hidden" in cols) values.put("hidden", 0)
        fillRequired(db, "urls", values)
        return db.insertOrThrow("urls", null, values)
    }

    private fun insertChromiumVisit(
        db: SQLiteDatabase,
        cols: Set<String>,
        urlId: Long,
        timeMicros: Long
    ): Boolean {
        if (cols.isEmpty()) return false
        val chromeTime = unixToChromeMicros(timeMicros)
        if (chromeTime <= 0) return false

        val exists = queryLong(
            db,
            "SELECT 1 FROM visits WHERE url=? AND visit_time=? LIMIT 1",
            arrayOf(urlId.toString(), chromeTime.toString())
        )
        if (exists != null) return false

        val values = ContentValues()
        if ("url" in cols) values.put("url", urlId)
        if ("visit_time" in cols) values.put("visit_time", chromeTime)
        if ("from_visit" in cols) values.put("from_visit", 0)
        if ("transition" in cols) values.put("transition", 0)
        if ("segment_id" in cols) values.put("segment_id", 0)
        if ("visit_duration" in cols) values.put("visit_duration", 0)
        if ("visit_source" in cols) values.put("visit_source", 0)
        fillRequired(db, "visits", values)
        return try {
            db.insertOrThrow("visits", null, values)
            true
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    //  Chromium Bookmarks JSON
    // ------------------------------------------------------------------

    fun mergeChromiumBookmarks(
        targetRaw: String?,
        bookmarks: List<SourceBookmark>
    ): String {
        val root = try {
            if (targetRaw.isNullOrBlank()) JSONObject() else JSONObject(targetRaw)
        } catch (_: Exception) {
            JSONObject()
        }
        val roots = root.optJSONObject("roots") ?: JSONObject().also { root.put("roots", it) }

        val bar = ensureRoot(roots, "bookmark_bar", "Bookmarks bar", 1L)
        val other = ensureRoot(roots, "other", "Other bookmarks", 2L)
        val synced = ensureRoot(roots, "synced", "Mobile bookmarks", 3L)

        var nextId = maxOf(3L, maxChromiumId(root)) + 1
        val existingUrls = mutableSetOf<String>()
        collectChromiumUrls(root, existingUrls)

        fun add(bookmark: SourceBookmark, parent: JSONObject) {
            if (!existingUrls.add(bookmark.url)) return
            val children = parent.optJSONArray("children")
                ?: JSONArray().also { parent.put("children", it) }
            children.put(chromiumUrlJson(bookmark, nextId++))
        }

        for (bookmark in bookmarks) {
            val label = bookmark.path.firstOrNull()
            val folders = bookmark.path.drop(1)
            var parent = when (label) {
                "Bookmarks Bar" -> bar
                "Mobile Bookmarks" -> synced
                else -> other
            }
            for (folder in folders) {
                parent = ensureChromiumFolder(parent, folder, nextId++)
            }
            add(bookmark, parent)
        }

        root.put("version", 1)
        root.remove("checksum")
        root.remove("checksum_sha256")
        return root.toString(2)
    }

    fun emptyChromiumBookmarks(): String {
        val root = JSONObject()
        root.put("version", 1)
        val roots = JSONObject()
        roots.put("bookmark_bar", chromiumFolderJson("Bookmarks bar", 1L, 0L))
        roots.put("other", chromiumFolderJson("Other bookmarks", 2L, 0L))
        roots.put("synced", chromiumFolderJson("Mobile bookmarks", 3L, 0L))
        root.put("roots", roots)
        return root.toString(2)
    }

    private fun ensureRoot(
        roots: JSONObject,
        key: String,
        name: String,
        id: Long
    ): JSONObject {
        var root = roots.optJSONObject(key)
        if (root == null) {
            root = chromiumFolderJson(name, id, 0L)
            roots.put(key, root)
        }
        if (root.optJSONArray("children") == null) root.put("children", JSONArray())
        return root
    }

    private fun ensureChromiumFolder(
        parent: JSONObject,
        name: String,
        id: Long
    ): JSONObject {
        val children = parent.optJSONArray("children")
            ?: JSONArray().also { parent.put("children", it) }
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            if (child.optString("type") == "folder" && child.optString("name") == name) {
                return child
            }
        }
        val folder = chromiumFolderJson(name, id, System.currentTimeMillis())
        children.put(folder)
        return folder
    }

    private fun chromiumFolderJson(name: String, id: Long, dateAddedMillis: Long): JSONObject =
        JSONObject().apply {
            put("children", JSONArray())
            put("date_added", chromeTimeString(dateAddedMillis))
            put("date_last_used", "0")
            put("date_modified", chromeTimeString(System.currentTimeMillis()))
            put("guid", UUID.randomUUID().toString())
            put("id", id.toString())
            put("name", name)
            put("type", "folder")
        }

    private fun chromiumUrlJson(bookmark: SourceBookmark, id: Long): JSONObject =
        JSONObject().apply {
            put("date_added", chromeTimeString(bookmark.dateAddedMillis))
            put("date_last_used", "0")
            put("guid", UUID.randomUUID().toString())
            put("id", id.toString())
            put("name", bookmark.title.ifBlank { bookmark.url })
            put("type", "url")
            put("url", bookmark.url)
        }

    private fun maxChromiumId(node: JSONObject): Long {
        var max = 0L
        fun walk(obj: JSONObject) {
            obj.optString("id").toLongOrNull()?.let { if (it > max) max = it }
            val children = obj.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                children.optJSONObject(i)?.let { walk(it) }
            }
        }
        walk(node)
        return max
    }

    private fun collectChromiumUrls(node: JSONObject, out: MutableSet<String>) {
        if (node.optString("type") == "url") {
            node.optString("url").takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        val children = node.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            children.optJSONObject(i)?.let { collectChromiumUrls(it, out) }
        }
    }

    // ------------------------------------------------------------------
    //  Small helpers
    // ------------------------------------------------------------------

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val result = mutableSetOf<String>()
        try {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                val idx = c.getColumnIndex("name")
                if (idx < 0) return result
                while (c.moveToNext()) result.add(c.getString(idx))
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun queryLong(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>?
    ): Long? = try {
        db.rawQuery(sql, args).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun fillRequired(
        db: SQLiteDatabase,
        table: String,
        values: ContentValues
    ) {
        try {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                val typeIdx = c.getColumnIndex("type")
                val notNullIdx = c.getColumnIndex("notnull")
                val defaultIdx = c.getColumnIndex("dflt_value")
                val pkIdx = c.getColumnIndex("pk")
                if (nameIdx < 0) return
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    if (values.containsKey(name)) continue
                    val notNull = notNullIdx >= 0 && c.getInt(notNullIdx) == 1
                    val hasDefault = defaultIdx >= 0 && !c.isNull(defaultIdx)
                    val isPk = pkIdx >= 0 && c.getInt(pkIdx) != 0
                    if (!notNull || hasDefault || isPk) continue
                    val type = if (typeIdx >= 0) c.getString(typeIdx).uppercase() else ""
                    if (type.contains("INT") || type.contains("REAL") ||
                        type.contains("NUM") || type.contains("BOOL")
                    ) {
                        values.put(name, 0)
                    } else {
                        values.put(name, "")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isImportableUrl(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://", true) ||
            u.startsWith("https://", true) ||
            u.startsWith("ftp://", true)
    }

    private fun revHost(url: String): String = try {
        URI(url).host?.let { it.reversed() + "." } ?: "."
    } catch (_: Exception) {
        "."
    }

    private fun geckoGuid(): String =
        UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * Firefox's places URL hash (toolkit/components/places). It is not a
     * uniqueness constraint, but the address bar and duplicate checks look
     * rows up through url_hash; leaving it at zero makes imported places hard
     * to find.
     */
    private fun geckoUrlHash(url: String): Long {
        val colon = url.indexOf(':')
        return if (colon > 0) {
            val prefixHash = mozillaHashString(url.substring(0, colon)) and 0xFFFFL
            val urlHash = mozillaHashString(url) and 0xFFFFFFFFL
            (prefixHash shl 32) + urlHash
        } else {
            mozillaHashString(url) and 0xFFFFFFFFL
        }
    }

    private fun mozillaHashString(value: String): Long {
        var hash = 0L
        for (ch in value) {
            val rotated = ((hash shl 5) or (hash ushr 27)) and 0xFFFFFFFFL
            hash = (0x9E3779B9L * (rotated xor ch.code.toLong())) and 0xFFFFFFFFL
        }
        return hash
    }

    private fun chromeToUnixMicros(value: Long): Long =
        if (value > 0) value - CHROME_EPOCH_OFFSET else 0L

    private fun unixToChromeMicros(value: Long): Long =
        if (value > 0) value + CHROME_EPOCH_OFFSET else 0L

    private fun chromeTimeString(millis: Long): String {
        val ms = if (millis > 0) millis else System.currentTimeMillis()
        return unixToChromeMicros(ms * 1000).toString()
    }

    private fun chromeStringToMillis(value: String): Long {
        val chrome = value.toLongOrNull() ?: 0L
        val unix = chromeToUnixMicros(chrome)
        return if (unix > 0) unix / 1000 else System.currentTimeMillis()
    }
}
