package com.browsermover.app.core

import android.content.Context
import com.browsermover.app.model.BrowserInfo
import com.browsermover.app.model.BrowserType
import com.browsermover.app.model.MigrationResult
import com.browsermover.app.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    }

    private val jsonPatcher = JsonPatcher(context)

    suspend fun migrate(
        source: BrowserInfo,
        target: BrowserInfo,
        onProgress: suspend (MigrationResult.Progress) -> Unit
    ): MigrationResult = withContext(Dispatchers.IO) {

        try {
            PackageValidator.validateOrThrow(source.packageName, "Kaynak")
            PackageValidator.validateOrThrow(target.packageName, "Hedef")
            
            if (source.packageName == target.packageName) {
                return@withContext MigrationResult.Failure("Kaynak ve hedef ayni paket olamaz!")
            }

            emitProgress(onProgress, 0, "Hazirlik", "Scriptler cikariliyor...", 3)
            if (!extractScripts()) {
                return@withContext MigrationResult.Failure("Script cikarma basarisiz!")
            }

            extractSqlite3Binary()

            val engine = if (source.browserType != BrowserType.UNKNOWN) source.browserType else target.browserType
            if (engine == BrowserType.UNKNOWN) {
                return@withContext MigrationResult.Failure("Motor belirlenemedi!")
            }

            val scriptName = when (engine) {
                BrowserType.GECKO -> "gecko_migrate.sh"
                BrowserType.CHROMIUM -> "chromium_migrate.sh"
                else -> return@withContext MigrationResult.Failure("Motor secilemedi")
            }

            emitProgress(onProgress, 0, "Baslatiliyor", "Motor: ${engine.name}", 8)

            val logLines = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val errors = mutableListOf<String>()
            var phase = 0
            var phaseName = ""

            val exitCode = RootHelper.execStreaming(
                scriptPath = "$SCRIPTS_DIR/$scriptName",
                args = listOf(source.packageName, target.packageName)
            ) { line ->
                logLines.add(line)
                parsePhase(line)?.let { (n, nm) -> phase = n; phaseName = nm }
                emitProgress(onProgress, phase, phaseName, line, progressPct(phase, engine))
                if ("[WARN]" in line) warnings.add(line.substringAfter("[WARN]").trim())
                if ("[ERR]" in line) errors.add(line.substringAfter("[ERR]").trim())
            }

            if (engine == BrowserType.CHROMIUM) {
                kotlinPatchChromium(source, target).forEach { if (!it.success) warnings.add(it.message) }
            } else if (engine == BrowserType.GECKO) {
                kotlinPatchGecko(source, target).forEach { if (!it.success) warnings.add(it.message) }
            }

            buildResult(exitCode, errors, warnings, logLines)

        } catch (e: Exception) {
            MigrationResult.Failure("Hata: ${e.message}", e.stackTraceToString().take(1000), LOG_FILE)
        }
    }

    suspend fun listBackups(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val res = RootHelper.exec("ls -d ${WORK_DIR}/backup_* 2>/dev/null | sort -r")
            res.outputLines.map { path ->
                val raw = path.substringAfterLast("backup_")
                val date = raw.substring(0, 8).let { "${it.substring(0,4)}/${it.substring(4,6)}/${it.substring(6,8)}" }
                val time = raw.substring(9).let { "${it.substring(0,2)}:${it.substring(2,4)}:${it.substring(4,6)}" }
                path to "$date $time"
            }
        } catch (_: Exception) { emptyList() }
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
                val content = context.assets.open(path).bufferedReader().readText()
                val tmp = File(context.cacheDir, "s_$name").apply { writeText(content) }
                RootHelper.execMultiple(listOf("cp -f '${tmp.absolutePath}' '$target'", "chmod 755 '$target'", "sed -i 's/\\r$//' '$target'"))
                tmp.delete()
            }
            true
        } catch (_: Exception) { false }
    }

    private suspend fun extractSqlite3Binary() = withContext(Dispatchers.IO) {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val input = context.assets.open("bin/$abi/sqlite3")
            val tmp = File(context.cacheDir, "sqlite3")
            tmp.outputStream().use { input.copyTo(it) }
            RootHelper.execMultiple(listOf("cp -f '${tmp.absolutePath}' '$WORK_DIR/sqlite3'", "chmod 755 '$WORK_DIR/sqlite3'"))
            tmp.delete()
        } catch (_: Exception) { }
    }

    private suspend fun kotlinPatchChromium(src: BrowserInfo, dst: BrowserInfo): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        val dstBase = RootHelper.exec("for d in app_chrome app_chromium app_brave app_vivaldi; do [ -d \"/data/data/${dst.packageName}/\$d\" ] && echo \"\$d\" && break; done").stdout.trim()
        if (dstBase.isNotEmpty()) {
            val path = "/data/data/${dst.packageName}/$dstBase/Default"
            results.add(jsonPatcher.neutralizeSecurePreferences("$path/Secure Preferences", src.packageName, dst.packageName))
            results.add(jsonPatcher.patchPreferences("$path/Preferences", src.packageName, dst.packageName))
        }
        return results
    }

    private suspend fun kotlinPatchGecko(src: BrowserInfo, dst: BrowserInfo): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        val dstProf = RootHelper.exec("p=\$(find /data/data/${dst.packageName}/files/mozilla -maxdepth 1 -type d -name \"*.default*\" 2>/dev/null | head -1); echo \"\$p\"").stdout.trim()
        val srcProf = RootHelper.exec("p=\$(find /data/data/${src.packageName}/files/mozilla -maxdepth 1 -type d -name \"*.default*\" 2>/dev/null | head -1); echo \"\$p\"").stdout.trim()
        if (dstProf.isNotEmpty() && srcProf.isNotEmpty()) {
            results.add(jsonPatcher.patchGeckoExtensionsJson("$dstProf/extensions.json", src.packageName, dst.packageName, srcProf.substringAfterLast("/"), dstProf.substringAfterLast("/")))
            results.add(jsonPatcher.syncGeckoUuids("$srcProf/prefs.js", "$dstProf/prefs.js"))
        }
        return results
    }

    private fun parsePhase(line: String) = Regex("""FAZA\s+(\d+):\s+(.+)""").find(line)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() }
    private fun progressPct(p: Int, e: BrowserType) = if (e == BrowserType.GECKO) intArrayOf(12,22,45,70,85,92).getOrElse(p){92} else intArrayOf(8,22,38,52,62,75,85,92).getOrElse(p){95}
    private suspend fun emitProgress(cb: suspend (MigrationResult.Progress) -> Unit, ph: Int, nm: String, d: String, pct: Int) = withContext(Dispatchers.Main) { cb(MigrationResult.Progress(ph, nm, d, pct)) }
    private fun buildResult(exit: Int, errs: List<String>, warns: List<String>, lines: List<String>) = if (exit == 0) MigrationResult.Success("Goc basarili!", warns, LOG_FILE) else MigrationResult.Failure("Basarisiz (exit=$exit)", lines.takeLast(10).joinToString("\n"), LOG_FILE)
}
