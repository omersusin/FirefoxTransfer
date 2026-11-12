package com.browsermover.app.core

import com.browsermover.app.model.BrowserType
import com.browsermover.app.root.RootHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cross-engine extension handling.
 *
 * Chromium and Gecko extension packages (CRX/unpacked MV2/MV3 vs XPI) and their
 * private storage are not interchangeable. Silently copying a Chromium
 * extension into Firefox, or a Firefox XPI into Chrome, can corrupt the target
 * profile. This migrator therefore does the safe part automatically:
 *
 *  - inventory the extensions on both sides,
 *  - match extensions by name where possible,
 *  - create an HTML/JSON report with the correct store search link for every
 *    source extension,
 *  - expose the source data paths so the user can decide what to reinstall.
 *
 * The same-engine scripts still copy extension packages and data verbatim,
 * where the formats are compatible.
 */
internal object ExtensionMigrator {

    data class Extension(
        val id: String,
        val name: String,
        val version: String,
        val engine: BrowserType,
        val packagePath: String?,
        val dataPaths: List<String>,
        val enabled: Boolean = true
    ) {
        val normalizedName: String
            get() = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
    }

    data class Report(
        val source: List<Extension>,
        val target: List<Extension>,
        val matched: List<Pair<Extension, Extension>>,
        val json: String,
        val html: String,
        val warnings: List<String>
    )

    suspend fun collectChromium(profile: ChromiumProfile): List<Extension> {
        val root = "${profile.profileDir}/Extensions"
        if (!BrowserPaths.exists(root)) return emptyList()

        val result = mutableListOf<Extension>()
        val ids = RootHelper.exec(
            "find ${BrowserPaths.q(root)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null"
        ).outputLines.filter { it.startsWith("/") }

        for (idDir in ids) {
            val id = idDir.substringAfterLast('/')
            val manifestPath = RootHelper.exec(
                "find ${BrowserPaths.q(idDir)} -maxdepth 2 -name manifest.json " +
                    "2>/dev/null | head -1"
            ).stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") }
                ?: continue
            val manifest = try {
                JSONObject(readRootText(manifestPath) ?: continue)
            } catch (_: Exception) {
                continue
            }
            val rawName = manifest.optString("name", id)
            val name = resolveChromiumName(idDir, rawName)
            val version = manifest.optString("version", "")
            val dataPaths = chromiumDataPaths(profile.profileDir, id)
            result.add(
                Extension(
                    id = id,
                    name = name,
                    version = version,
                    engine = BrowserType.CHROMIUM,
                    packagePath = idDir,
                    dataPaths = dataPaths
                )
            )
        }
        return result.sortedBy { it.name.lowercase(Locale.US) }
    }

    suspend fun collectGecko(profile: GeckoProfile): List<Extension> {
        val roots = listOf(profile.profileDir, profile.filesDir)
            .filterNotNull().distinct()
        val extensionsJson = roots.asSequence()
            .map { "$it/extensions.json" }
            .firstOrNull { BrowserPaths.exists(it) }

        val result = mutableListOf<Extension>()
        val seen = mutableSetOf<String>()

        if (extensionsJson != null) {
            val raw = readRootText(extensionsJson)
            if (raw != null) {
                try {
                    val addons = JSONObject(raw).optJSONArray("addons") ?: JSONArray()
                    for (i in 0 until addons.length()) {
                        val addon = addons.optJSONObject(i) ?: continue
                        if (addon.optString("type") != "extension") continue
                        val id = addon.optString("id")
                        if (id.isBlank() || !seen.add(id)) continue
                        val locale = addon.optJSONObject("defaultLocale")
                        val name = locale?.optString("name")
                            ?.takeIf { it.isNotBlank() }
                            ?: addon.optString("name").takeIf { it.isNotBlank() }
                            ?: id
                        val path = addon.optString("path").takeIf { it.isNotBlank() }
                        val active = addon.optBoolean("active", true)
                        result.add(
                            Extension(
                                id = id,
                                name = name,
                                version = addon.optString("version", ""),
                                engine = BrowserType.GECKO,
                                packagePath = path,
                                dataPaths = geckoDataPaths(roots, id),
                                enabled = active
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Fall through to the loose extensions directory scan.
                }
            }
        }

        // Some builds have XPI files but no readable extensions.json.
        if (result.isEmpty()) {
            for (root in roots) {
                val extDir = "$root/extensions"
                if (!BrowserPaths.exists(extDir)) continue
                val files = RootHelper.exec(
                    "find ${BrowserPaths.q(extDir)} -maxdepth 1 -type f " +
                        "\\( -name '*.xpi' -o -name '*.json' \\) 2>/dev/null"
                ).outputLines.filter { it.startsWith("/") }
                for (path in files) {
                    val file = path.substringAfterLast('/')
                    val id = file.substringBefore(".").ifBlank { file }
                    if (!seen.add(id)) continue
                    result.add(
                        Extension(
                            id = id,
                            name = id,
                            version = "",
                            engine = BrowserType.GECKO,
                            packagePath = path,
                            dataPaths = geckoDataPaths(roots, id)
                        )
                    )
                }
            }
        }
        return result.sortedBy { it.name.lowercase(Locale.US) }
    }

    fun buildReport(
        sourceEngine: BrowserType,
        targetEngine: BrowserType,
        source: List<Extension>,
        target: List<Extension>
    ): Report {
        val targetByName = target
            .filter { it.normalizedName.isNotBlank() }
            .groupBy { it.normalizedName }
        val matched = source.mapNotNull { src ->
            targetByName[src.normalizedName]?.firstOrNull()?.let { src to it }
        }
        val warnings = mutableListOf<String>()

        val reportObject = JSONObject().apply {
            put("generated", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("sourceEngine", sourceEngine.name)
            put("targetEngine", targetEngine.name)
            put("source", JSONArray().apply { source.forEach { put(it.toJson(targetEngine)) } })
            put("target", JSONArray().apply { target.forEach { put(it.toJson(null)) } })
            put("matchedByName", JSONArray().apply {
                matched.forEach { (s, t) ->
                    put(JSONObject().apply {
                        put("sourceId", s.id)
                        put("sourceName", s.name)
                        put("targetId", t.id)
                        put("targetName", t.name)
                    })
                }
            })
            put(
                "note",
                "For Firefox -> Chromium, matching store extensions are resolved and storage.local " +
                    "is converted to Chromium LevelDB. A store install still needs one Add to Chrome tap. " +
                    "Other directions list store links and source data paths."
            )
        }

        if (source.isEmpty()) {
            warnings.add("No source extensions were found.")
        } else {
            warnings.add(
                "${source.size} source extension(s) were inventoried. Store links and source data " +
                    "paths are in the extension report; Firefox -> Chromium also runs the storage bridge."
            )
        }
        if (matched.isNotEmpty()) {
            warnings.add(
                "${matched.size} extension name(s) also exist in the target. Cross-engine settings " +
                    "are only transplanted for Firefox -> Chromium when a store match is found."
            )
        }
        if (targetEngine == BrowserType.CHROMIUM) {
            warnings.add(
                "Normal Chrome builds may block extension installs. Bare Browser supports Chrome " +
                    "Web Store extensions, CRX files, and unpacked extensions."
            )
        }

        return Report(
            source = source,
            target = target,
            matched = matched,
            json = reportObject.toString(2),
            html = buildHtml(sourceEngine, targetEngine, source, target, matched),
            warnings = warnings
        )
    }

    private fun Extension.toJson(targetEngine: BrowserType?): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("engine", engine.name)
        put("enabled", enabled)
        put("packagePath", packagePath ?: "")
        put("dataPaths", JSONArray().apply { dataPaths.forEach { put(it) } })
        put("storeSearchUrl", storeSearchUrl(name, targetEngine ?: engine))
    }

    private fun buildHtml(
        sourceEngine: BrowserType,
        targetEngine: BrowserType,
        source: List<Extension>,
        target: List<Extension>,
        matched: List<Pair<Extension, Extension>>
    ): String {
        val rows = source.joinToString("\n") { ext ->
            val match = matched.firstOrNull { it.first.id == ext.id }?.second
            """
            <tr>
              <td>${escape(ext.name)}</td>
              <td>${escape(ext.id)}</td>
              <td>${escape(ext.version)}</td>
              <td>${if (match != null) "name match: ${escape(match.name)}" else "—"}</td>
              <td><a href="${escape(storeSearchUrl(ext.name, targetEngine))}">search target store</a></td>
              <td>${ext.dataPaths.joinToString("<br>") { escape(it) }}</td>
            </tr>
            """.trimIndent()
        }
        return """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Extension migration report</title>
<style>
body{font:14px system-ui,sans-serif;background:#121212;color:#eee;padding:16px}
h1{font-size:20px} a{color:#64b5f6} table{border-collapse:collapse;width:100%}
th,td{border:1px solid #444;padding:8px;vertical-align:top;text-align:left}
th{background:#222} code{color:#ffcc80}.warn{color:#ffb74d}
</style></head><body>
<h1>Extension migration report</h1>
<p>${escape(sourceEngine.name)} → ${escape(targetEngine.name)}</p>
<p class="warn">Firefox → Chromium: matching Chrome Web Store extensions are resolved, downloaded
as CRX, and their storage.local values are converted to Chromium's LevelDB. The final
<b>Add to Chrome</b> tap is required by the store. Other directions only list store links and data paths.</p>
<p>Source: ${source.size} extension(s), target: ${target.size} extension(s), name matches: ${matched.size}.</p>
<table><thead><tr><th>Name</th><th>ID</th><th>Version</th><th>Target match</th><th>Store</th><th>Source data paths</th></tr></thead>
<tbody>$rows</tbody></table>
<p>Generated ${escape(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))}</p>
</body></html>"""
    }

    private fun storeSearchUrl(name: String, targetEngine: BrowserType): String {
        val encoded = try {
            URLEncoder.encode(name, "UTF-8")
        } catch (_: Exception) {
            name.replace(" ", "%20")
        }
        return when (targetEngine) {
            BrowserType.GECKO -> "https://addons.mozilla.org/android/search/?q=$encoded"
            BrowserType.CHROMIUM -> "https://chromewebstore.google.com/search/$encoded"
            else -> "https://addons.mozilla.org/android/search/?q=$encoded"
        }
    }

    private suspend fun resolveChromiumName(idDir: String, raw: String): String {
        if (!raw.startsWith("__MSG_") || !raw.endsWith("__")) return raw
        val key = raw.removePrefix("__MSG_").removeSuffix("__")
        val messages = RootHelper.exec(
            "find ${BrowserPaths.q(idDir)} -path '*/_locales/*/messages.json' " +
                "2>/dev/null | head -1"
        ).stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") }
            ?: return raw
        return try {
            JSONObject(readRootText(messages) ?: return raw)
                .optJSONObject(key)?.optString("message")?.takeIf { it.isNotBlank() } ?: raw
        } catch (_: Exception) {
            raw
        }
    }

    private fun chromiumDataPaths(profileDir: String, id: String): List<String> {
        return listOf(
            "$profileDir/Local Extension Settings/$id",
            "$profileDir/Sync Extension Settings/$id",
            "$profileDir/Extension State/$id",
            "$profileDir/Extension Rules/$id"
        )
    }

    private fun geckoDataPaths(roots: List<String>, id: String): List<String> {
        return roots.flatMap {
            listOf(
                "$it/browser-extension-data/$id",
                "$it/storage/default/moz-extension+++*^addonId=$id"
            )
        }
    }

    private suspend fun readRootText(path: String): String? {
        val r = RootHelper.exec("cat ${BrowserPaths.q(path)} 2>/dev/null")
        return if (r.success && r.stdout.isNotBlank()) r.stdout else null
    }

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
