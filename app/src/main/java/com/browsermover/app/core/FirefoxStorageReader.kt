package com.browsermover.app.core

import com.browsermover.app.root.RootHelper
import org.iq80.snappy.Snappy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Reads Firefox's `storage.local` data without Firefox.
 *
 * Modern Firefox stores one record per extension key in IndexedDB
 * (`webExtensions-storage-local` / `storage-local-data`), whose SQLite rows
 * contain Gecko's structured-clone serialization. Older profiles still have
 * `browser-extension-data/<id>/storage.js`, which is a plain JSON object.
 *
 * Both formats are converted to the same key -> JSON value map. The map is
 * later written into Chromium's LevelDB extension storage by
 * [ExtensionBridge].
 */
private const val SC_HEADER = 0xFFF10000L
private const val SC_NULL = 0xFFFF0000L
private const val SC_UNDEFINED = 0xFFFF0001L
private const val SC_BOOLEAN = 0xFFFF0002L
private const val SC_INT32 = 0xFFFF0003L
private const val SC_STRING = 0xFFFF0004L
private const val SC_DATE_OBJECT = 0xFFFF0005L
private const val SC_ARRAY_OBJECT = 0xFFFF0007L
private const val SC_OBJECT_OBJECT = 0xFFFF0008L
private const val SC_BOOLEAN_OBJECT = 0xFFFF000AL
private const val SC_STRING_OBJECT = 0xFFFF000BL
private const val SC_NUMBER_OBJECT = 0xFFFF000CL
private const val SC_BACK_REFERENCE_OBJECT = 0xFFFF000DL
private const val SC_END_OF_KEYS = 0xFFFF0013L
private const val SC_FLOAT_MAX = 0xFFF00000L

private fun readU64(data: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
    }
    return value
}

internal object FirefoxStorageReader {

    data class Storage(
        val values: Map<String, Any?>,
        val source: String,
        val warning: String? = null
    )

    /**
     * Read storage for [extensionId]. [cacheDir] must be writable by this app;
     * root copies the IndexedDB files there first.
     */
    suspend fun read(
        profile: GeckoProfile,
        extensionId: String,
        cacheDir: File
    ): Storage? {
        val roots = listOf(profile.profileDir, profile.filesDir)
            .filterNotNull().distinct()

        // Prefer IndexedDB because it is the live backend. Keep storage.js as
        // a fallback for old profiles and extensions not yet migrated.
        for (root in roots) {
            val idbDir = findIdbDir(root, extensionId)
            if (idbDir != null) {
                val storage = readIdb(idbDir, cacheDir)
                if (storage != null && storage.values.isNotEmpty()) return storage
            }
        }

        for (root in roots) {
            val legacy = "$root/browser-extension-data/$extensionId/storage.js"
            if (BrowserPaths.exists(legacy)) {
                val json = readJsonFile(legacy)
                if (json != null) {
                    return Storage(
                        values = jsonObjectToMap(json),
                        source = legacy,
                        warning = "This Firefox build still uses storage.js; " +
                            "it may be an older snapshot than the IndexedDB data."
                    )
                }
            }
            val migrated = "$root/browser-extension-data/$extensionId/storage.js.migrated"
            if (BrowserPaths.exists(migrated)) {
                val json = readJsonFile(migrated)
                if (json != null) {
                    return Storage(
                        values = jsonObjectToMap(json),
                        source = migrated,
                        warning = "Only the pre-migration storage.js.migrated file exists; " +
                            "values are an older snapshot."
                    )
                }
            }
        }
        return null
    }

    private suspend fun findIdbDir(root: String, extensionId: String): String? {
        val dirs = RootHelper.exec(
            "for base in ${BrowserPaths.q("$root/storage/default")} " +
                "${BrowserPaths.q("$root/storage/permanent")}; do " +
                "find \"\$base\" -mindepth 1 -maxdepth 1 -type d " +
                "-name 'moz-extension+++*' 2>/dev/null; done"
        ).outputLines.filter { it.startsWith("/") }

        for (dir in dirs) {
            val encoded = dir.substringAfter("^addonId=", "")
            if (encoded.isEmpty()) continue
            val decoded = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                encoded
            }
            if (decoded == extensionId) return "$dir/idb"
        }
        return null
    }

    private suspend fun readIdb(idbDir: String, cacheDir: File): Storage? {
        val local = File(cacheDir, "firefox_idb_${System.currentTimeMillis()}")
        try {
            local.deleteRecursively()
            local.mkdirs()
            val uid = android.os.Process.myUid()
            val copied = RootHelper.execMultiple(listOf(
                "rm -rf ${BrowserPaths.q(local.absolutePath)}",
                "mkdir -p ${BrowserPaths.q(local.absolutePath)}",
                "cp -a ${BrowserPaths.q(idbDir)}/. ${BrowserPaths.q(local.absolutePath)}/ " +
                    "2>/dev/null || cp -rf ${BrowserPaths.q(idbDir)}/. " +
                    "${BrowserPaths.q(local.absolutePath)}/ 2>/dev/null",
                "chown -R $uid:$uid ${BrowserPaths.q(local.absolutePath)} 2>/dev/null || true",
                "chmod -R u+rwX ${BrowserPaths.q(local.absolutePath)} 2>/dev/null || true"
            ))
            if (!copied.success) return null

            val dbFile = local.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".sqlite", true) }
                ?.maxByOrNull { it.length() }
                ?: return null

            val values = readSqlite(dbFile) ?: return null
            return Storage(
                values = values,
                source = "$idbDir/${dbFile.name} (Firefox IndexedDB)"
            )
        } catch (_: Exception) {
            return null
        } finally {
            local.deleteRecursively()
        }
    }

    private fun readSqlite(file: File): Map<String, Any?>? {
        val db = try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
        } catch (_: Exception) {
            return null
        }
        try {
            val store = db.rawQuery(
                "SELECT id FROM object_store WHERE name = ? LIMIT 1",
                arrayOf("storage-local-data")
            ).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            } ?: return null

            val out = LinkedHashMap<String, Any?>()
            db.rawQuery(
                "SELECT key, data FROM object_data WHERE object_store_id = ?",
                arrayOf(store.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val rawKey = c.getBlob(0) ?: continue
                    val rawValue = c.getBlob(1) ?: continue
                    val key = decodeKey(rawKey) ?: continue
                    val value = try {
                        StructuredClone.decode(rawValue)
                    } catch (_: Exception) {
                        null
                    }
                    out[key] = value ?: continue
                }
            }
            return out
        } catch (_: Exception) {
            return null
        } finally {
            db.close()
        }
    }

    private suspend fun readJsonFile(path: String): JSONObject? {
        val result = RootHelper.exec("cat ${BrowserPaths.q(path)} 2>/dev/null")
        if (!result.success || result.stdout.isBlank()) return null
        return try {
            JSONObject(result.stdout)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in json.keys()) {
            out[key] = jsonValue(json.get(key))
        }
        return out
    }

    private fun jsonValue(value: Any?): Any? {
        if (value == null || value == JSONObject.SC_NULL) return null
        return when (value) {
            is JSONObject, is JSONArray, is String, is Number, is Boolean -> value
            else -> value.toString()
        }
    }

    /**
     * Firefox IndexedDB keys are "encoded JS values": a type byte, then a
     * string whose bytes are shifted by one and can use Gecko's two-byte
     * compaction. We only need string keys, which is what storage.local uses.
     */
    private fun decodeKey(raw: ByteArray): String? {
        if (raw.isEmpty()) return null
        var pos = 0
        val keyType = raw[pos++].toInt() and 0xFF
        if (keyType % 0x50 != 0x30) return null

        val bytes = ArrayList<Byte>()
        while (pos < raw.size) {
            val b = raw[pos++].toInt() and 0xFF
            if (b == 0) break
            bytes.add(b.toByte())
        }
        if (bytes.isEmpty()) return ""

        val adjusted = ByteArray(bytes.size)
        var i = 0
        while (i < adjusted.size) {
            val b = bytes[i].toInt() and 0xFF
            if ((b and 0x80) == 0) {
                adjusted[i] = (b - 1).toByte()
                i++
            } else if ((b and 0x40) == 0 && i + 1 < adjusted.size) {
                // Gecko's compact two-byte form: big-endian uint16 with
                // 0x8000 added and each byte shifted by 0x7f.
                val c = (b shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val d = c - 0x8000 + 0x7F
                adjusted[i] = ((d ushr 8) and 0xFF).toByte()
                adjusted[i + 1] = (d and 0xFF).toByte()
                i += 2
            } else {
                // High bit set with 0x40 set is not produced for storage keys.
                adjusted[i] = (b - 1).toByte()
                i++
            }
        }
        return try {
            String(adjusted, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    //  Gecko structured clone decoder
    // ------------------------------------------------------------------

    private object StructuredClone {

        fun decode(raw: ByteArray): Any? {
            val data = decompressIfNeeded(raw)
            return Parser(data).decodeValue()
        }

        private fun decompressIfNeeded(raw: ByteArray): ByteArray {
            if (raw.size >= 8) {
                val frame = byteArrayOf(
                    0xFF.toByte(), 0x06, 0x00, 0x00, 's'.code.toByte(),
                    'N'.code.toByte(), 'a'.code.toByte(), 'P'.code.toByte(),
                    'p'.code.toByte(), 'Y'.code.toByte()
                )
                var isFrame = raw.size >= frame.size
                if (isFrame) {
                    for (i in frame.indices) {
                        if (raw[i] != frame[i]) { isFrame = false; break }
                    }
                }
                if (isFrame) return decompressFrame(raw)
            }
            // A raw structured-clone value begins with the SC_HEADER tag.
            if (raw.size >= 8 && readU64(raw, 0).ushr(32) == SC_HEADER) return raw
            return try {
                Snappy.uncompress(raw, 0, raw.size)
            } catch (_: Exception) {
                raw
            }
        }

        private fun decompressFrame(raw: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var pos = 10
            while (pos + 4 <= raw.size) {
                val uncompressed = raw[pos].toInt() and 0xFF
                val blockSize = (raw[pos + 1].toInt() and 0xFF) or
                    ((raw[pos + 2].toInt() and 0xFF) shl 8) or
                    ((raw[pos + 3].toInt() and 0xFF) shl 16)
                if (blockSize < 4 || pos + 4 + blockSize > raw.size) break
                val payload = raw.copyOfRange(pos + 8, pos + 4 + blockSize)
                if (uncompressed != 0) {
                    out.write(payload)
                } else {
                    out.write(Snappy.uncompress(payload, 0, payload.size))
                }
                pos += blockSize + 4
            }
            return out.toByteArray()
        }

        private class Parser(private val data: ByteArray) {
            private var pos = 0
            private val allObjects = ArrayList<Any?>()
            private val stack = ArrayList<Any>()

            fun decodeValue(): Any? {
                val (tag, _) = readTagAndData()
                if (tag != SC_HEADER) throw IllegalArgumentException("not a structured clone")
                val value = startRead()
                while (stack.isNotEmpty()) {
                    align()
                    val (nextTag, _) = peekTagAndData()
                    val obj = stack.last()
                    if (nextTag == SC_END_OF_KEYS) {
                        stack.removeAt(stack.lastIndex)
                        readTagAndData()
                        continue
                    }
                    val key = startRead()
                    if (key == null && obj is MutableMap<*, *>) {
                        stack.removeAt(stack.lastIndex)
                        continue
                    }
                    when (obj) {
                        is MutableList<*> -> {
                            val index = (key as? Number)?.toInt() ?: -1
                            val field = startRead()
                            if (index in 0 until obj.size) {
                                @Suppress("UNCHECKED_CAST")
                                (obj as MutableList<Any?>)[index] = field
                            }
                        }
                        is MutableMap<*, *> -> {
                            val field = startRead()
                            @Suppress("UNCHECKED_CAST")
                            (obj as MutableMap<String, Any?>)[key?.toString() ?: "null"] = field
                        }
                    }
                }
                return value
            }

            private fun startRead(): Any? {
                val (tag, dataInt) = readTagAndData()
                val data = dataInt.toLong() and 0xFFFFFFFFL
                val value: Any? = when (tag) {
                    SC_NULL -> null
                    SC_UNDEFINED -> null
                    SC_BOOLEAN -> dataInt != 0
                    SC_INT32 -> dataInt
                    SC_STRING -> readString(data)
                    SC_BOOLEAN_OBJECT -> dataInt != 0
                    SC_STRING_OBJECT -> readString(data)
                    SC_NUMBER_OBJECT -> readDouble()
                    SC_DATE_OBJECT -> readDouble()
                    SC_ARRAY_OBJECT -> {
                        val list = MutableList<Any?>(dataInt.coerceAtLeast(0)) { null }
                        stack.add(list)
                        allObjects.add(list)
                        list
                    }
                    SC_OBJECT_OBJECT -> {
                        val map = LinkedHashMap<String, Any?>()
                        stack.add(map)
                        allObjects.add(map)
                        map
                    }
                    SC_BACK_REFERENCE_OBJECT -> allObjects.getOrNull(dataInt)
                    else -> {
                        if (tag <= SC_FLOAT_MAX) {
                            Double.fromBits((tag shl 32) or data)
                        } else {
                            null
                        }
                    }
                }
                align()
                return value
            }

            private fun readString(meta: Long): String {
                val length = (meta and 0x7FFFFFFFL).toInt()
                val latin1 = (meta and 0x80000000L) != 0L
                if (length < 0 || length > data.size) throw IllegalArgumentException("bad string")
                return if (latin1) {
                    if (pos + length > data.size) throw IllegalArgumentException("bad string")
                    String(data, pos, length, Charsets.ISO_8859_1).also { pos += length }
                } else {
                    val bytes = length * 2
                    if (pos + bytes > data.size) throw IllegalArgumentException("bad string")
                    String(data, pos, bytes, Charsets.UTF_16LE).also { pos += bytes }
                }
            }

            private fun readDouble(): Double {
                if (pos + 8 > data.size) throw IllegalArgumentException("bad double")
                val bits = readU64(data, pos)
                pos += 8
                return Double.fromBits(bits)
            }

            private fun align() {
                val rem = pos % 8
                if (rem != 0) pos += 8 - rem
            }

            private fun peekTagAndData(): Pair<Long, Int> {
                if (pos + 8 > data.size) return SC_END_OF_KEYS to 0
                val pair = readU64(data, pos)
                return pair.ushr(32) to (pair and 0xFFFFFFFFL).toInt()
            }

            private fun readTagAndData(): Pair<Long, Int> {
                if (pos + 8 > data.size) throw IllegalArgumentException("truncated clone")
                val pair = readU64(data, pos)
                pos += 8
                return pair.ushr(32) to (pair and 0xFFFFFFFFL).toInt()
            }
        }
    }
}
