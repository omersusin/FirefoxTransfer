package com.browsermover.app.core

import com.browsermover.app.root.RootHelper
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.impl.Iq80DBFactory
import org.iq80.leveldb.Options
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Firefox -> Chromium extension bridge.
 *
 * The app cannot run an extension inside another extension's storage context.
 * It can, however, do the two pieces Chromium-compatible browsers need:
 *
 *  1. find the same extension in the Chrome Web Store,
 *  2. convert Firefox storage.local values into Chromium's LevelDB format.
 *
 * Installation is opened in the target browser because Chrome Web Store
 * installs require a user gesture. If the target supports "Install CRX", the
 * downloaded package is also copied to Downloads for that flow.
 */
internal object ExtensionBridge {

    data class StoreMatch(
        val id: String,
        val name: String,
        val storeUrl: String
    )

    data class ImportResult(
        val targetId: String,
        val targetName: String,
        val valuesImported: Int,
        val source: String,
        val crxPath: String? = null,
        val warning: String? = null
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    /**
     * Find the Chrome Web Store counterpart by name. DuckDuckGo's HTML search
     * is used instead of the store's JS-only search endpoint.
     */
    suspend fun resolveStore(name: String): StoreMatch? {
        if (name.isBlank()) return null
        val query = "site:chromewebstore.google.com/detail \"$name\""
        val encoded = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query.replace(" ", "+")
        }
        val html = httpText("https://html.duckduckgo.com/html/?q=$encoded") ?: return null

        val ids = LinkedHashSet<String>()
        for (match in Regex("uddg=([^&\"']+)").findAll(html)) {
            val url = try {
                URLDecoder.decode(match.groupValues[1], "UTF-8")
            } catch (_: Exception) {
                continue
            }
            extensionIdFromUrl(url)?.let { ids.add(it) }
        }
        for (match in Regex("https?://chromewebstore\\.google\\.com/detail/[^\\s\"'<>]+")
            .findAll(html)) {
            extensionIdFromUrl(match.value)?.let { ids.add(it) }
        }

        for (id in ids.take(5)) {
            val storeUrl = "https://chromewebstore.google.com/detail/$id"
            val title = storeTitle(id) ?: continue
            if (namesMatch(name, title)) {
                return StoreMatch(id, title, storeUrl)
            }
        }
        return ids.firstOrNull()?.let { id ->
            StoreMatch(id, storeTitle(id) ?: name, "https://chromewebstore.google.com/detail/$id")
        }
    }

    private fun extensionIdFromUrl(url: String): String? {
        val match = Regex("/detail/(?:[^/]+/)?([a-p]{32})").find(url) ?: return null
        return match.groupValues[1]
    }

    private fun storeTitle(id: String): String? {
        val html = httpText("https://chromewebstore.google.com/detail/$id?hl=en") ?: return null
        Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?.let { raw ->
                return unescape(raw)
                    .replace(Regex("\\s*-\\s*Chrome Web Store\\s*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
        return null
    }

    private fun namesMatch(source: String, target: String): Boolean {
        val a = normalizeName(source)
        val b = normalizeName(target)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        val at = a.split(' ').filter { it.length > 2 }.toSet()
        val bt = b.split(' ').filter { it.length > 2 }.toSet()
        if (at.isEmpty() || bt.isEmpty()) return false
        val overlap = at.intersect(bt).size
        return overlap >= maxOf(1, minOf(at.size, bt.size) / 2)
    }

    private fun normalizeName(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(
            Regex("\\b(chrome|chromium|extension|addon|add on|for|the|official)\\b"),
            " "
        )
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Download the signed CRX and copy it to Downloads. Bare installs signed
     * CRX files from its extensions page, and also offers a downloaded CRX.
     */
    suspend fun downloadCrx(
        id: String,
        name: String,
        cacheDir: File,
        publicDir: String
    ): String? {
        val url = "https://clients2.google.com/service/update2/crx" +
            "?response=redirect&prodversion=130.0&acceptformat=crx2,crx3" +
            "&x=id%3D$id%26uc"
        val conn = open(url) ?: return null
        val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_').ifBlank { id }.take(80)
        val temp = File(cacheDir, "$safe.crx")
        try {
            conn.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() < 4) return null
            val head = ByteArray(4)
            temp.inputStream().use { it.read(head) }
            if (String(head, StandardCharsets.US_ASCII) != "Cr24") {
                temp.delete()
                return null
            }
            RootHelper.execMultiple(listOf(
                "mkdir -p ${BrowserPaths.q(publicDir)} 2>/dev/null || true",
                "cp -f ${BrowserPaths.q(temp.absolutePath)} " +
                    "${BrowserPaths.q("$publicDir/$safe.crx")} 2>/dev/null || true",
                "chmod 644 ${BrowserPaths.q("$publicDir/$safe.crx")} 2>/dev/null || true"
            ))
            temp.delete()
            return if (BrowserPaths.exists("$publicDir/$safe.crx")) {
                "$publicDir/$safe.crx"
            } else null
        } catch (_: Exception) {
            temp.delete()
            return null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Open the exact store page. The final "Add to Chrome" tap is a user
     * gesture that Chrome/Chromium requires; the app cannot legally click it
     * through another app's UI without an accessibility service.
     */
    suspend fun openStore(pkg: String, url: String): Boolean {
        val result = RootHelper.exec(
            "am start -a android.intent.action.VIEW -d ${BrowserPaths.q(url)} " +
                "-p ${BrowserPaths.q(pkg)} >/dev/null 2>&1"
        )
        return result.success
    }

    /**
     * Try to hand the downloaded CRX to the target browser. Many Chromium
     * builds ignore this, in which case the file remains in Downloads and the
     * user can use Bare's "Install CRX" button.
     */
    suspend fun tryInstallCrx(pkg: String, path: String): Boolean {
        val result = RootHelper.exec(
            "am start -a android.intent.action.VIEW " +
                "-d ${BrowserPaths.q("file://$path")} " +
                "-t application/x-chrome-extension " +
                "-p ${BrowserPaths.q(pkg)} >/dev/null 2>&1"
        )
        return result.success
    }

    /**
     * Write [values] into `Local Extension Settings/<targetId>`.
     *
     * The target profile is first copied into the app cache, merged with a
     * LevelDB writer, then copied back as the target UID. The browser must be
     * stopped (the caller force-stops it).
     */
    suspend fun importData(
        targetPkg: String,
        targetProfileDir: String,
        targetId: String,
        targetName: String,
        values: Map<String, Any?>,
        source: String,
        cacheDir: File
    ): ImportResult? {
        if (values.isEmpty()) return null
        val targetDir = "$targetProfileDir/Local Extension Settings/$targetId"
        val localDir = File(cacheDir, "ext_${targetId}_${System.currentTimeMillis()}")
        try {
            localDir.deleteRecursively()
            localDir.mkdirs()

            val uid = android.os.Process.myUid()
            val hasExisting = BrowserPaths.exists(targetDir)
            if (hasExisting) {
                RootHelper.execMultiple(listOf(
                    "cp -a ${BrowserPaths.q(targetDir)}/. " +
                        "${BrowserPaths.q(localDir.absolutePath)}/ 2>/dev/null || " +
                        "cp -rf ${BrowserPaths.q(targetDir)}/. " +
                        "${BrowserPaths.q(localDir.absolutePath)}/ 2>/dev/null",
                    "chown -R $uid:$uid ${BrowserPaths.q(localDir.absolutePath)} " +
                        "2>/dev/null || true",
                    "chmod -R u+rwX ${BrowserPaths.q(localDir.absolutePath)} " +
                        "2>/dev/null || true"
                ))
            }

            val options = Options()
                .createIfMissing(true)
                .compressionType(CompressionType.SNAPPY)
                .verifyChecksums(false)

            var db = try {
                Iq80DBFactory.factory.open(localDir, options)
            } catch (_: Exception) {
                // A profile written by a different LevelDB build may not open.
                // Recreate a clean database rather than corrupting the target.
                localDir.deleteRecursively()
                localDir.mkdirs()
                Iq80DBFactory.factory.open(
                    localDir,
                    Options().createIfMissing(true).compressionType(CompressionType.SNAPPY)
                )
            }

            var imported = 0
            var warning: String? = null
            try {
                val batch = db.createWriteBatch()
                for ((key, value) in values) {
                    if (key.isBlank()) continue
                    batch.put(
                        key.toByteArray(StandardCharsets.UTF_8),
                        jsonString(value).toByteArray(StandardCharsets.UTF_8)
                    )
                    imported++
                }
                db.write(batch)
                batch.close()
            } catch (e: Exception) {
                warning = "Could not merge the existing extension database: ${e.message}"
            } finally {
                try { db.close() } catch (_: Exception) {}
            }

            if (imported == 0) {
                localDir.deleteRecursively()
                return null
            }

            val targetUid = BrowserPaths.uid(targetPkg)
            val commands = mutableListOf(
                "mkdir -p ${BrowserPaths.q("$targetProfileDir/Local Extension Settings")}",
                "rm -rf ${BrowserPaths.q(targetDir)}",
                "mkdir -p ${BrowserPaths.q(targetDir)}",
                "cp -a ${BrowserPaths.q(localDir.absolutePath)}/. " +
                    "${BrowserPaths.q(targetDir)}/ 2>/dev/null || " +
                    "cp -rf ${BrowserPaths.q(localDir.absolutePath)}/. " +
                    "${BrowserPaths.q(targetDir)}/ 2>/dev/null"
            )
            if (!targetUid.isNullOrBlank()) {
                commands.add(
                    "chown -R $targetUid:$targetUid ${BrowserPaths.q(targetDir)} " +
                        "2>/dev/null || true"
                )
            }
            commands.add("chmod -R u+rwX ${BrowserPaths.q(targetDir)} 2>/dev/null || true")
            commands.add(
                "command -v restorecon >/dev/null 2>&1 && " +
                    "restorecon -RF ${BrowserPaths.q(targetDir)} 2>/dev/null || true"
            )
            RootHelper.execMultiple(commands)

            if (!BrowserPaths.exists("$targetDir/CURRENT")) {
                return ImportResult(
                    targetId, targetName, imported, source,
                    warning = "Chromium did not create a readable LevelDB; values were not saved."
                )
            }
            return ImportResult(
                targetId = targetId,
                targetName = targetName,
                valuesImported = imported,
                source = source,
                warning = warning
            )
        } catch (e: Exception) {
            return ImportResult(
                targetId, targetName, 0, source,
                warning = "Extension data conversion failed: ${e.message}"
            )
        } finally {
            localDir.deleteRecursively()
        }
    }

    /**
     * Chromium's ValueStore writes each storage key as the LevelDB key and the
     * JSON serializer output as the value (see leveldb_value_store.cc).
     */
    private fun jsonString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        is JSONObject, is JSONArray -> value.toString()
        is Map<*, *> -> JSONObject(value as Map<*, *>).toString()
        is List<*> -> JSONArray(value as List<*>).toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun httpText(url: String): String? {
        val conn = open(url) ?: return null
        return try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun open(url: String): HttpURLConnection? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (conn.responseCode !in 200..399) {
                conn.disconnect()
                null
            } else conn
        } catch (_: Exception) {
            null
        }
    }

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
