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
            "scripts/chromium_migrate.sh"
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
            "com.duckduckgo.mobile.android"
        )
    }

    private val jsonPatcher = JsonPatcher(context)

    private suspend fun detectEngine(pkg: String): BrowserType {
        if (pkg in KNOWN_GECKO) return BrowserType.GECKO
        if (pkg in KNOWN_CHROMIUM) return BrowserType.CHROMIUM

        try {
            val gecko = RootHelper.exec("[ -d '/data/data/$pkg/files/mozilla' ] && echo GECKO")
            if ("GECKO" in gecko.stdout) return BrowserType.GECKO

            val chromium = RootHelper.exec(
                """for d in app_chrome app_chromium app_brave app_vivaldi; do
                    [ -d "/data/data/$pkg/${'$'}d" ] && echo CHROMIUM && break
                done"""
            )
            if ("CHROMIUM" in chromium.stdout) return BrowserType.CHROMIUM
        } catch (_: Exception) { }

        return BrowserType.UNKNOWN
    }

    suspend fun migrate(
        sourcePkg: String,
        targetPkg: String,
        onProgress: suspend (MigrationResult.Progress) -> Unit
    ): MigrationResult = withContext(Dispatchers.IO) {

        try {
            PackageValidator.validateOrThrow(sourcePkg, "Kaynak")
            PackageValidator.validateOrThrow(targetPkg, "Hedef")
            if (sourcePkg == targetPkg) {
                return @withContext MigrationResult.Failure("Kaynak ve hedef ayni olamaz!")
            }

            emit(onProgress, 0, "Hazirlik", "Scriptler cikariliyor...", 3)
            if (!extractScripts()) {
                return @withContext MigrationResult.Failure(
                    error = "Script dosyalari cikarilamadi!",
                    technicalDetail = "assets/scripts/ kontrol edin"
                )
            }

            emit(onProgress, 0, "Motor", "Motor tespit ediliyor...", 5)
            val srcEngine = detectEngine(sourcePkg)
            val dstEngine = detectEngine(targetPkg)

            val engine = when {
                srcEngine != BrowserType.UNKNOWN -> srcEngine
                dstEngine != BrowserType.UNKNOWN -> dstEngine
                else -> {
                    return @withContext MigrationResult.Failure(
                        error = "Motor tipi belirlenemedi!",
                        technicalDetail = "Kaynak: $srcEngine, Hedef: $dstEngine\n" +
                            "Paketlerin /data/data/ altindaki dizin yapilarini kontrol edin."
                    )
                }
            }

            if (srcEngine != BrowserType.UNKNOWN && dstEngine != BrowserType.UNKNOWN && srcEngine != dstEngine) {
                emit(onProgress, 0, "Uyari", "[WARN] Capraz motor! Eklentiler tasinamaz.", 6)
            }

            val scriptName = when (engine) {
                BrowserType.GECKO -> "gecko_migrate.sh"
                BrowserType.CHROMIUM -> "chromium_migrate.sh"
                else -> return @withContext MigrationResult.Failure("Motor secilemedi")
            }

            emit(onProgress, 0, "Baslatiliyor", "Motor: ${engine.name}", 8)

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
                val phaseMatch = Regex("""FAZA\s+(\d+):\s*(.*)""").find(line)
                if (phaseMatch != null) {
                    phase = phaseMatch.groupValues[1].toIntOrNull() ?: phase
                    phaseName = phaseMatch.groupValues[2].trim()
                }
                emit(onProgress, phase, phaseName, line, progressPct(phase, engine))
                if ("[WARN]" in line) warnings.add(line.substringAfter("[WARN]").trim())
                if ("[ERR]" in line) errors.add(line.substringAfter("[ERR]").trim())
            }

            if (engine == BrowserType.CHROMIUM) {
                emit(onProgress, 7, "Kotlin JSON", "Chromium JSON yamalaniyor...", 90)
                kotlinPatchChromium(sourcePkg, targetPkg).forEach { r ->
                    emit(onProgress, 7, "JSON", "${if (r.success) "[OK]" else "[WARN]"} ${r.message}", 92)
                    if (!r.success) warnings.add(r.message)
                }
            } else if (engine == BrowserType.GECKO) {
                emit(onProgress, 5, "Kotlin Gecko", "UUID dogrulaniyor...", 90)
                kotlinPatchGecko(sourcePkg, targetPkg).forEach { r ->
                    emit(onProgress, 5, "Gecko", "${if (r.success) "[OK]" else "[WARN]"} ${r.message}", 92)
                    if (!r.success) warnings.add(r.message)
                }
            }

            buildResult(exitCode, errors, warnings, logLines)

        } catch (e: IllegalArgumentException) {
            MigrationResult.Failure(error = e.message ?: "Dogrulama hatasi")
        } catch (e: Exception) {
            MigrationResult.Failure(error = "Beklenmeyen hata: ${e.message}", technicalDetail = e.stackTraceToString().take(2000))
        }
    }

    private suspend fun extractScripts(): Boolean = withContext(Dispatchers.IO) {
        try {
            RootHelper.exec("mkdir -p '$SCRIPTS_DIR'")
            for (assetPath in SCRIPT_FILES) {
                val fileName = File(assetPath).name
                val targetPath = "$SCRIPTS_DIR/$fileName"
                val content = context.assets.open(assetPath).bufferedReader().readText()
                val tempFile = File(context.cacheDir, "s_$fileName").apply { writeText(content) }
                val result = RootHelper.execMultiple(listOf("cp -f '${tempFile.absolutePath}' '$targetPath'", "chmod 755 '$targetPath'", "sed -i 's/\\r$//' '$targetPath'"))
                tempFile.delete()
                if (!result.success) return @withContext false
            }
            true
        } catch (_: Exception) { false }
    }

    private suspend fun kotlinPatchChromium(srcPkg: String, dstPkg: String): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        try {
            val dstBase = findChromiumBase(dstPkg)
            val srcBase = findChromiumBase(srcPkg)
            if (dstBase.isBlank()) { results.add(JsonPatcher.PatchResult(false, "Hedef base bulunamadi")); return results }
            val path = "/data/data/$dstPkg/$dstBase"
            results.add(jsonPatcher.neutralizeSecurePreferences("$path/Default/Secure Preferences", srcPkg, dstPkg))
            results.add(jsonPatcher.patchPreferences("$path/Default/Preferences", srcPkg, dstPkg, srcBase, dstBase))
            results.add(jsonPatcher.patchLocalState("$path/Local State", srcPkg, dstPkg))
        } catch (e: Exception) { results.add(JsonPatcher.PatchResult(false, "Exception: ${e.message}")) }
        return results
    }

    private suspend fun kotlinPatchGecko(srcPkg: String, dstPkg: String): List<JsonPatcher.PatchResult> {
        val results = mutableListOf<JsonPatcher.PatchResult>()
        try {
            val srcProf = findGeckoProfile(srcPkg)
            val dstProf = findGeckoProfile(dstPkg)
            if (srcProf.isBlank() || dstProf.isBlank()) { results.add(JsonPatcher.PatchResult(false, "Profil bulunamadi")); return results }
            results.add(jsonPatcher.patchGeckoExtensionsJson("$dstProf/extensions.json", srcPkg, dstPkg, srcProf.substringAfterLast("/"), dstProf.substringAfterLast("/")))
            results.add(jsonPatcher.syncGeckoUuids("$srcProf/prefs.js", "$dstProf/prefs.js"))
        } catch (e: Exception) { results.add(JsonPatcher.PatchResult(false, "Exception: ${e.message}")) }
        return results
    }

    private suspend fun findChromiumBase(pkg: String): String = RootHelper.exec("for d in app_chrome app_chromium app_brave app_vivaldi; do [ -d \"/data/data/$pkg/\$d\" ] && echo \"\$d\" && break; done").stdout.trim()
    private suspend fun findGeckoProfile(pkg: String): String = RootHelper.exec("d=\"/data/data/$pkg/files/mozilla\"; [ -d \"\$d\" ] || exit 0; for p in \"\$d\"/*; do [ -d \"\$p\" ] && echo \"\$p\" && exit 0; done").stdout.trim()
    private fun progressPct(phase: Int, engine: BrowserType): Int = if (engine == BrowserType.GECKO) intArrayOf(12, 22, 45, 70, 85, 92).getOrElse(phase) { 92 } else intArrayOf(8, 22, 38, 52, 62, 75, 85, 92).getOrElse(phase) { 95 }
    private suspend fun emit(cb: suspend (MigrationResult.Progress) -> Unit, phase: Int, name: String, detail: String, pct: Int) = withContext(Dispatchers.Main) { cb(MigrationResult.Progress(phase, name, detail, pct)) }
    private fun buildResult(exit: Int, errors: List<String>, warnings: List<String>, lines: List<String>): MigrationResult = if (exit == 0 && errors.isEmpty() && warnings.isEmpty()) MigrationResult.Success("Goc basariyla tamamlandi!", logPath = LOG_FILE) else if (exit == 0 && errors.isEmpty()) MigrationResult.Partial("Tamamlandi, uyarilar var.", listOf("Cekirdek veriler tasindi"), warnings, LOG_FILE) else if (exit == 0) MigrationResult.Partial("Kismen basarili.", listOf("Script tamamlandi"), errors + warnings, LOG_FILE) else MigrationResult.Failure("Basarisiz (exit=$exit)", lines.takeLast(20).joinToString("\n"), LOG_FILE)
}
