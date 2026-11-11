package com.browsermover.app.core

import android.content.Context
import com.browsermover.app.model.BrowserInfo
import com.browsermover.app.model.BrowserType
import com.browsermover.app.model.MigrationResult
import com.browsermover.app.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DataMover(private val context: Context) {

    companion object {
        private const val WORK_DIR = "/data/local/tmp/browser_migrator"
        private const val SCRIPTS_DIR = "$WORK_DIR/scripts"
        private const val LOG_FILE = "$WORK_DIR/migration.log"

        private val SCRIPT_FILES = listOf(
            "scripts/common.sh",
            "scripts/gecko_migrate.sh",
            "scripts/chromium_migrate.sh",
            "scripts/rollback.sh"
        )

        private val KNOWN_GECKO = setOf(
            "org.mozilla.firefox", "org.mozilla.firefox_beta",
            "org.mozilla.fenix", "org.mozilla.fenix.nightly",
            "org.mozilla.focus", "org.mozilla.klar",
            "org.mozilla.fennec_fdroid",
            "org.torproject.torbrowser", "org.torproject.torbrowser_alpha",
            "us.spotco.fennec_dos",
            "io.github.nicothin.nicofox",
            "io.github.nicothin.nicofox.debug",
            "io.github.forkmaintainers.iceraven",
            "org.gnu.icecat"
        )

        private val KNOWN_CHROMIUM = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
            "com.brave.browser", "com.brave.browser_beta",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.sec.android.app.sbrowser",
            "com.duckduckgo.mobile.android",
            "org.barebrowser"
        )
    }

    private val jsonPatcher = JsonPatcher(context)
    private val crossEngineMigrator = CrossEngineMigrator(context)

    private suspend fun detectEngine(pkg: String): BrowserType {
        if (pkg in KNOWN_GECKO) return BrowserType.GECKO
        if (pkg in KNOWN_CHROMIUM) return BrowserType.CHROMIUM

        try {
            val res = RootHelper.exec(
                """dd=""
                for p in "/data/data/$pkg" "/data/user/0/$pkg"; do
                    [ -d "${'$'}p" ] && dd="${'$'}p" && break
                done
                [ -z "${'$'}dd" ] && exit 1
                if [ -d "${'$'}dd/files/mozilla" ]; then echo GECKO; exit 0; fi
                for d in app_chrome app_chromium app_brave app_vivaldi app_edge app_kiwi app_quark; do
                    [ -d "${'$'}dd/${'$'}d" ] && echo CHROMIUM && exit 0
                done"""
            )
            if ("GECKO" in res.stdout) return BrowserType.GECKO
            if ("CHROMIUM" in res.stdout) return BrowserType.CHROMIUM
        } catch (_: Exception) { }

        return BrowserType.UNKNOWN
    }

    suspend fun migrate(
        sourcePkg: String,
        targetPkg: String,
        onProgress: suspend (MigrationResult.Progress) -> Unit
    ): MigrationResult = withContext(Dispatchers.IO) {

        try {
            PackageValidator.validateOrThrow(sourcePkg, "Source")
            PackageValidator.validateOrThrow(targetPkg, "Target")

            if (sourcePkg == targetPkg) {
                return@withContext MigrationResult.Failure("Source and target cannot be the same!")
            }

            if (!RootHelper.checkRoot()) {
                return@withContext MigrationResult.Failure(
                    "Root access is required. Grant root permission to Browser Data Migrator and retry."
                )
            }

            emitProgress(onProgress, 0, "Detection", "Detecting browser engines...", 3)
            val srcEngine = detectEngine(sourcePkg)
            val dstEngine = detectEngine(targetPkg)

            if (srcEngine != BrowserType.UNKNOWN &&
                dstEngine != BrowserType.UNKNOWN &&
                srcEngine != dstEngine
            ) {
                emitProgress(
                    onProgress, 0, "Cross-engine",
                    "Cross-engine transfer: ${srcEngine.name} -> ${dstEngine.name}", 6
                )
                return@withContext crossEngineMigrator.migrate(
                    sourcePkg, targetPkg, srcEngine, dstEngine, onProgress
                )
            }

            val engine = when {
                srcEngine != BrowserType.UNKNOWN -> srcEngine
                dstEngine != BrowserType.UNKNOWN -> dstEngine
                else -> return@withContext MigrationResult.Failure(
                    "Could not determine the browser engine for $sourcePkg or $targetPkg. " +
                        "Open both browsers once and retry."
                )
            }

            emitProgress(onProgress, 0, "Preparation", "Extracting scripts...", 5)
            if (!extractScripts()) {
                return@withContext MigrationResult.Failure("Script extraction failed!")
            }

            extractSqlite3Binary()

            val scriptName = when (engine) {
                BrowserType.GECKO -> "gecko_migrate.sh"
                BrowserType.CHROMIUM -> "chromium_migrate.sh"
                else -> return@withContext MigrationResult.Failure("Engine selection failed")
            }

            emitProgress(onProgress, 0, "Initializing", "Engine: ${engine.name}", 8)
            val logLines = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var phase = 0
            var phaseName = ""

            val exitCode = RootHelper.execStreaming(
                scriptPath = "$SCRIPTS_DIR/$scriptName",
                args = listOf(sourcePkg, targetPkg)
            ) { line ->
                logLines.add(line)
                val phaseMatch = Regex("""PHASE\s+(\d+):\s*(.*)""").find(line)
                if (phaseMatch != null) {
                    phase = phaseMatch.groupValues[1].toIntOrNull() ?: phase
                    phaseName = phaseMatch.groupValues[2].trim()
                }
                emitProgress(onProgress, phase, phaseName, line, progressPct(phase, engine))
                if ("[WARN]" in line) warnings.add(line.substringAfter("[WARN]").trim())
                if ("[ERR]" in line) errors.add(line.substringAfter("[ERR]").trim())
            }

            if (engine == BrowserType.CHROMIUM) {
                kotlinPatchChromium(sourcePkg, targetPkg).forEach { if (!it.success) warnings.add(it.message) }
            } else if (engine == BrowserType.GECKO) {
                kotlinPatchGecko(sourcePkg, targetPkg).forEach { if (!it.success) warnings.add(it.message) }
            }

            buildResult(exitCode, errors, warnings, logLines)

        } catch (e: Exception) {
            MigrationResult.Failure("Error: ${e.message}", e.stackTraceToString().take(1000), LOG_FILE)
        }
    }

    suspend fun listBackups(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val res = RootHelper.exec("ls -d ${WORK_DIR}/backup_* 2>/dev/null | sort -r")
            val pattern = Regex("""backup_(\d{8})_(\d{6})""")
            res.outputLines.mapNotNull { path ->
                val match = pattern.find(path) ?: return@mapNotNull null
                val (datePart, timePart) = match.destructured
                val date = "${datePart.substring(0, 4)}/${datePart.substring(4, 6)}/${datePart.substring(6, 8)}"
                val time = "${timePart.substring(0, 2)}:${timePart.substring(2, 4)}:${timePart.substring(4, 6)}"
                path to "$date $time"
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun clearBackups(): Int = withContext(Dispatchers.IO) {
        try {
            val count = RootHelper.exec("ls -d ${WORK_DIR}/backup_* 2>/dev/null | wc -l").stdout.trim().toIntOrNull() ?: 0
            if (count > 0) {
                RootHelper.exec("rm -rf ${WORK_DIR}/backup_*")
            }
            count
        } catch (_: Exception) { 0 }
    }

    suspend fun rollback(backupPath: String, onLine: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        extractScripts()
        val code = RootHelper.execStreaming("$SCRIPTS_DIR/rollback.sh", listOf(backupPath)) { line ->
            withContext(Dispatchers.Main) { onLine(line) }
        }
        code == 0
    }

    private suspend fun extractScripts(): Boolean = withContext(Dispatchers.IO) {
        try {
            RootHelper.exec("mkdir -p '$SCRIPTS_DIR'")
            for (path in SCRIPT_FILES) {
                val name = File(path).name
                val target = "$SCRIPTS_DIR/$name"
                val content = try { context.assets.open(path).bufferedReader().readText() } catch (e: Exception) { return@withContext false }
                val tmp = File(context.cacheDir, "s_$name").apply { writeText(content) }
                val result = RootHelper.execMultiple(listOf("cp -f '${tmp.absolutePath}' '$target'", "chmod 755 '$target'", "sed -i 's/\\r$//' '$target'"))
                tmp.delete()
                if (!result.success) return@withContext false
            }
            true
        } catch (_: Exception) { false }
    }

    private suspend fun extractSqlite3Binary() = withContext(Dispatchers.IO) {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val input = try { context.assets.open("bin/$abi/sqlite3") } catch (e: Exception) { return@withContext }
            val tmp = File(context.cacheDir, "sqlite3")
            tmp.outputStream().use { input.copyTo(it) }
            RootHelper.execMultiple(listOf("cp -f '${tmp.absolutePath}' '$WORK_DIR/sqlite3'", "chmod 755 '$WORK_DIR/sqlite3'"))
            tmp.delete()
        } catch (_: Exception) { }
    }

    private suspend fun kotlinPatchChromium(srcPkg: String, dstPkg: String): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        val dst = BrowserPaths.chromiumProfile(dstPkg)
        if (dst != null) {
            val dstBaseName = dst.baseDir.substringAfterLast('/')
            results.add(
                jsonPatcher.neutralizeSecurePreferences(
                    dst.securePreferences, srcPkg, dstPkg
                )
            )
            results.add(
                jsonPatcher.patchPreferences(
                    dst.preferences, srcPkg, dstPkg, dstBaseName, dstBaseName
                )
            )
        } else {
            results.add(JsonPatcher.PatchResult(false, "Could not locate Chromium profile for $dstPkg"))
        }
        return results
    }

    private suspend fun kotlinPatchGecko(srcPkg: String, dstPkg: String): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        val dst = BrowserPaths.geckoProfile(dstPkg)
        val src = BrowserPaths.geckoProfile(srcPkg)
        val dstProfile = dst?.profileDir
        val srcProfile = src?.profileDir
        if (dstProfile != null && srcProfile != null) {
            results.add(
                jsonPatcher.patchGeckoExtensionsJson(
                    "$dstProfile/extensions.json",
                    srcPkg,
                    dstPkg,
                    srcProfile.substringAfterLast('/'),
                    dstProfile.substringAfterLast('/')
                )
            )
            results.add(
                jsonPatcher.syncGeckoUuids("$srcProfile/prefs.js", "$dstProfile/prefs.js")
            )
        } else {
            results.add(JsonPatcher.PatchResult(false, "Could not locate Gecko profile for $srcPkg/$dstPkg"))
        }
        return results
    }

    private suspend fun findChromiumBase(pkg: String): String {
        val r = RootHelper.exec(
            """dd=""
            for p in "/data/data/$pkg" "/data/user/0/$pkg"; do
                [ -d "${'$'}p" ] && dd="${'$'}p" && break
            done
            [ -z "${'$'}dd" ] && exit 1
            for d in app_chrome app_chromium app_brave app_vivaldi; do
                [ -d "${'$'}dd/${'$'}d" ] && echo "${'$'}d" && exit 0
            done"""
        )
        return r.stdout.trim()
    }

    private suspend fun findGeckoProfile(pkg: String): String {
        val r = RootHelper.exec(
            """dd=""
            for p in "/data/data/$pkg" "/data/user/0/$pkg"; do
                [ -d "${'$'}p" ] && dd="${'$'}p" && break
            done
            [ -z "${'$'}dd" ] && exit 1
            md="${'$'}dd/files/mozilla"
            if [ -d "${'$'}md" ]; then
                for d in "${'$'}md"/*/; do
                    [ -d "${'$'}d" ] && echo "${'$'}{d%/}" && exit 0
                done
            fi
            f=${'$'}(find "${'$'}dd" -name "places.sqlite" -type f 2>/dev/null | head -1)
            if [ -n "${'$'}f" ]; then
                dirname "${'$'}f"
                exit 0
            fi
            f=${'$'}(find "${'$'}dd" -name "prefs.js" -type f 2>/dev/null | head -1)
            if [ -n "${'$'}f" ]; then
                dirname "${'$'}f"
                exit 0
            fi"""
        )
        return r.stdout.trim()
    }

    private fun progressPct(p: Int, e: BrowserType) = if (e == BrowserType.GECKO) intArrayOf(12,22,45,70,85,92).getOrElse(p){92} else intArrayOf(8,22,38,52,62,75,85,92).getOrElse(p){95}
    private suspend fun emitProgress(cb: suspend (MigrationResult.Progress) -> Unit, ph: Int, nm: String, d: String, pct: Int) = withContext(Dispatchers.Main) { cb(MigrationResult.Progress(ph, nm, d, pct)) }
    private fun buildResult(exit: Int, errs: List<String>, warns: List<String>, lines: List<String>) =
        if (exit == 0 && errs.isEmpty()) {
            MigrationResult.Success("Migration successful!", warns, LOG_FILE)
        } else {
            val detail = (errs.take(6) + lines.takeLast(10)).joinToString("\n")
            MigrationResult.Failure("Failed (exit=$exit)", detail, LOG_FILE)
        }
}
