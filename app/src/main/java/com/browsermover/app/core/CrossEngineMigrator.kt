package com.browsermover.app.core

import android.content.Context
import com.browsermover.app.model.BrowserType
import com.browsermover.app.model.MigrationResult
import com.browsermover.app.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orchestrates a Chromium <-> Gecko migration.
 *
 * Data is copied through the app's private cache by root, converted with
 * Android's SQLiteDatabase/JSON APIs, then written back by root. This works on
 * rooted phones that do not have a sqlite3 binary installed.
 */
class CrossEngineMigrator(private val context: Context) {

    companion object {
        private const val WORK_DIR = "/data/local/tmp/browser_migrator"
        private const val LOG_FILE = "$WORK_DIR/migration.log"
    }

    private val cacheRoot = File(context.cacheDir, "cross_engine")

    suspend fun migrate(
        sourcePkg: String,
        targetPkg: String,
        sourceEngine: BrowserType,
        targetEngine: BrowserType,
        onProgress: suspend (MigrationResult.Progress) -> Unit
    ): MigrationResult = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        var backupPath: String? = null
        try {
            emit(onProgress, 0, "Cross-engine", "Preparing ${sourceEngine.name} -> ${targetEngine.name} migration...", 4)

            if (!RootHelper.checkRoot()) {
                return@withContext MigrationResult.Failure("Root access is required for cross-engine migration.")
            }
            appendLog("=== Cross-engine migration: $sourcePkg ($sourceEngine) -> $targetPkg ($targetEngine) ===")

            val sourceChromium = if (sourceEngine == BrowserType.CHROMIUM) {
                BrowserPaths.chromiumProfile(sourcePkg)
            } else {
                null
            }
            val sourceGecko = if (sourceEngine == BrowserType.GECKO) {
                BrowserPaths.geckoProfile(sourcePkg)
            } else {
                null
            }

            if (sourceEngine == BrowserType.CHROMIUM && sourceChromium == null) {
                return@withContext MigrationResult.Failure(
                    "Chrome profile not found for $sourcePkg. Open the source browser once, then retry."
                )
            }
            if (sourceEngine == BrowserType.GECKO &&
                (sourceGecko == null || !BrowserPaths.exists(sourceGecko.placesDb))
            ) {
                return@withContext MigrationResult.Failure(
                    "Firefox places.sqlite not found for $sourcePkg. Open the source browser once, then retry."
                )
            }

            var targetChromium = if (targetEngine == BrowserType.CHROMIUM) {
                BrowserPaths.chromiumProfile(targetPkg)
            } else {
                null
            }
            var targetGecko = if (targetEngine == BrowserType.GECKO) {
                BrowserPaths.geckoProfile(targetPkg)
            } else {
                null
            }

            val targetNeedsInit =
                (targetEngine == BrowserType.CHROMIUM &&
                    (targetChromium == null || !BrowserPaths.exists(targetChromium.history))) ||
                    (targetEngine == BrowserType.GECKO &&
                        (targetGecko == null || !BrowserPaths.exists(targetGecko.placesDb)))

            if (targetNeedsInit) {
                emit(onProgress, 0, "Cross-engine", "Target profile missing. Opening $targetPkg once...", 8)
                if (targetEngine == BrowserType.CHROMIUM) {
                    BrowserPaths.ensureChromiumProfile(targetPkg)
                } else {
                    BrowserPaths.ensureGeckoProfile(targetPkg)
                }
                delay(1_500)
                if (targetEngine == BrowserType.CHROMIUM) {
                    targetChromium = BrowserPaths.chromiumProfile(targetPkg)
                } else {
                    targetGecko = BrowserPaths.geckoProfile(targetPkg)
                }
            }

            if (targetEngine == BrowserType.CHROMIUM &&
                (targetChromium == null || !BrowserPaths.exists(targetChromium.history))
            ) {
                return@withContext MigrationResult.Failure(
                    "Chromium target History not found for $targetPkg. Open the target browser once, then retry."
                )
            }
            if (targetEngine == BrowserType.GECKO &&
                (targetGecko == null || !BrowserPaths.exists(targetGecko.placesDb))
            ) {
                return@withContext MigrationResult.Failure(
                    "Firefox target places.sqlite not found for $targetPkg. Open the target browser once, then retry."
                )
            }

            emit(onProgress, 0, "Cross-engine", "Stopping browsers...", 10)
            RootHelper.exec(
                "am force-stop ${BrowserPaths.q(sourcePkg)}; " +
                    "am force-stop ${BrowserPaths.q(targetPkg)}"
            )

            emit(onProgress, 0, "Cross-engine", "Backing up target data...", 12)
            val backup = backupTarget(
                targetPkg, targetEngine, targetChromium, targetGecko
            )
            if (backup == null) {
                warnings.add("Could not create a target backup. Continuing without one.")
            } else {
                backupPath = backup
            }

            cacheRoot.mkdirs()
            warnings.add(
                "Passwords, cookies, and extension binaries/data are browser-specific " +
                    "and were not converted."
            )

            val result = when {
                sourceEngine == BrowserType.CHROMIUM && targetEngine == BrowserType.GECKO ->
                    chromiumToGecko(
                        targetPkg,
                        sourceChromium!!,
                        targetGecko!!,
                        onProgress,
                        warnings
                    )

                sourceEngine == BrowserType.GECKO && targetEngine == BrowserType.CHROMIUM ->
                    geckoToChromium(
                        targetPkg,
                        sourceGecko!!,
                        targetChromium!!,
                        onProgress,
                        warnings
                    )

                else -> ConversionResult(
                    false,
                    "Unsupported cross-engine pair: ${sourceEngine.name} -> ${targetEngine.name}"
                )
            }

            if (!result.success) {
                appendLog("FAILED: ${result.message}")
                val detail = if (!backupPath.isNullOrBlank()) {
                    "${result.message}\nTarget backup: $backupPath"
                } else {
                    result.message
                }
                return@withContext MigrationResult.Failure(detail, "", LOG_FILE)
            }

            val extensionSummary = migrateExtensionInventory(
                sourceEngine = sourceEngine,
                targetEngine = targetEngine,
                sourceChromium = sourceChromium,
                sourceGecko = sourceGecko,
                targetChromium = targetChromium,
                targetGecko = targetGecko,
                warnings = warnings
            )

            val tabSummary = restoreOpenTabs(
                sourceEngine = sourceEngine,
                sourceChromium = sourceChromium,
                sourceGecko = sourceGecko,
                targetPkg = targetPkg,
                onProgress = onProgress,
                warnings = warnings
            )

            val summary = buildString {
                append("Cross-engine migration completed. ")
                append(result.message)
                if (extensionSummary != null) {
                    append(' ')
                    append(extensionSummary)
                }
                if (tabSummary != null) {
                    append(' ')
                    append(tabSummary)
                }
                if (!backupPath.isNullOrBlank()) {
                    append(" Backup: ")
                    append(backupPath)
                }
            }
            appendLog(summary)
            for (warning in warnings) appendLog("WARN: $warning")
            MigrationResult.Success(
                summary,
                warnings.distinct(),
                LOG_FILE
            )
        } catch (e: Exception) {
            appendLog("ERROR: ${e.message}")
            MigrationResult.Failure(
                "Cross-engine error: ${e.message}",
                e.stackTraceToString().take(1500),
                LOG_FILE
            )
        } finally {
            cleanupCache()
        }
    }

    /**
     * Open tabs are read from the source engine's session files and launched
     * in the target browser as ordinary VIEW intents. This is intentionally
     * not a binary session-file transplant: SNSS and MozLz4 session formats
     * are private, engine-specific, and unsafe to write across browsers.
     */
    private suspend fun restoreOpenTabs(
        sourceEngine: BrowserType,
        sourceChromium: ChromiumProfile?,
        sourceGecko: GeckoProfile?,
        targetPkg: String,
        onProgress: suspend (MigrationResult.Progress) -> Unit,
        warnings: MutableList<String>
    ): String? {
        emit(onProgress, 4, "Open tabs", "Reading source browser tabs...", 92)
        val read = try {
            when (sourceEngine) {
                BrowserType.CHROMIUM -> {
                    val profile = sourceChromium
                    if (profile == null) TabMigrator.ReadResult(
                        emptyList(), "Source Chromium profile is unavailable."
                    ) else TabMigrator.collectChromium(profile) { source, name ->
                        exportToCache(source, name)
                    }
                }
                BrowserType.GECKO -> {
                    val profile = sourceGecko
                    if (profile == null) TabMigrator.ReadResult(
                        emptyList(), "Source Gecko profile is unavailable."
                    ) else TabMigrator.collectGecko(profile) { source, name ->
                        exportToCache(source, name)
                    }
                }
                else -> TabMigrator.ReadResult(
                    emptyList(), "Unsupported source engine for open tabs."
                )
            }
        } catch (e: Exception) {
            TabMigrator.ReadResult(emptyList(), "Could not read open tabs: ${e.message}")
        }

        if (read.tabs.isEmpty()) {
            read.warning?.let { warnings.add(it) }
            return null
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val listPath = writeWorkText(
            read.tabs.joinToString("\n") { "${it.title}\t${it.url}" },
            "$WORK_DIR/open_tabs_$stamp.txt"
        )
        if (listPath != null) {
            warnings.add("Open-tab URL list: $listPath")
        }

        emit(
            onProgress, 4, "Open tabs",
            "Opening ${read.tabs.size} source tab(s) in the target browser...",
            96
        )
        val opened = TabMigrator.openInTarget(targetPkg, read.tabs)
        if (opened.opened > 0) {
            warnings.add(
                "Open tabs were launched as new tabs in the target browser. " +
                    "Tab groups, pinned state, and per-tab back/forward history are not preserved."
            )
            if (opened.warning != null) warnings.add(opened.warning)
            return "Opened ${opened.opened} of ${read.tabs.size} source tab(s) in the target browser."
        }

        warnings.add(opened.warning ?: "The target browser did not accept any open-tab intents.")
        return null
    }

    private suspend fun migrateExtensionInventory(
        sourceEngine: BrowserType,
        targetEngine: BrowserType,
        sourceChromium: ChromiumProfile?,
        sourceGecko: GeckoProfile?,
        targetChromium: ChromiumProfile?,
        targetGecko: GeckoProfile?,
        warnings: MutableList<String>
    ): String? {
        return try {
            val source = when (sourceEngine) {
                BrowserType.CHROMIUM -> sourceChromium?.let {
                    ExtensionMigrator.collectChromium(it)
                }.orEmpty()
                BrowserType.GECKO -> sourceGecko?.let {
                    ExtensionMigrator.collectGecko(it)
                }.orEmpty()
                else -> emptyList()
            }
            val target = when (targetEngine) {
                BrowserType.CHROMIUM -> targetChromium?.let {
                    ExtensionMigrator.collectChromium(it)
                }.orEmpty()
                BrowserType.GECKO -> targetGecko?.let {
                    ExtensionMigrator.collectGecko(it)
                }.orEmpty()
                else -> emptyList()
            }

            val report = ExtensionMigrator.buildReport(
                sourceEngine, targetEngine, source, target
            )
            if (report.source.isEmpty()) {
                warnings.add("No source extensions were found to inventory.")
                return null
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val jsonPath = writeWorkText(
                report.json,
                "$WORK_DIR/extension_report_$stamp.json"
            )
            val htmlPath = writeWorkText(
                report.html,
                "$WORK_DIR/extension_report_$stamp.html"
            )
            warnings.addAll(report.warnings)
            val path = htmlPath ?: jsonPath
            if (path != null) {
                warnings.add("Extension report: $path")
                "Extension report: $path"
            } else {
                warnings.add("Could not write the extension report to $WORK_DIR.")
                null
            }
        } catch (e: Exception) {
            warnings.add("Extension inventory failed: ${e.message}")
            null
        }
    }

    private suspend fun writeWorkText(content: String, target: String): String? {
        cacheRoot.mkdirs()
        val temp = File(cacheRoot, "report_${System.currentTimeMillis()}.txt")
        return try {
            temp.writeText(content, Charsets.UTF_8)
            val result = RootHelper.execMultiple(listOf(
                "mkdir -p ${BrowserPaths.q(WORK_DIR)}",
                "cp -f ${BrowserPaths.q(temp.absolutePath)} ${BrowserPaths.q(target)}",
                "chmod 644 ${BrowserPaths.q(target)}",
                "command -v restorecon >/dev/null 2>&1 && " +
                    "restorecon -F ${BrowserPaths.q(target)} 2>/dev/null || true"
            ))
            if (result.success && BrowserPaths.exists(target)) target else null
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------------
    //  Direction handlers
    // ------------------------------------------------------------------

    private suspend fun chromiumToGecko(
        targetPkg: String,
        source: ChromiumProfile,
        target: GeckoProfile,
        onProgress: suspend (MigrationResult.Progress) -> Unit,
        warnings: MutableList<String>
    ): ConversionResult {
        emit(onProgress, 1, "Reading Chrome", "Copying Chrome History and Bookmarks...", 18)

        val history = exportToCache(source.history, "chrome_history.db")
        if (history == null) {
            warnings.add("Chrome History not found; only bookmarks will be imported.")
        }
        val bookmarksRaw = readRootText(source.bookmarks)
        if (bookmarksRaw == null) {
            warnings.add("Chrome Bookmarks file not found; only history will be imported.")
        }

        val targetPlaces = exportToCache(target.placesDb, "gecko_places.db")
            ?: return ConversionResult(false, "Could not copy the target places.sqlite")

        val data = try {
            CrossEngineConverter.readChromium(history, bookmarksRaw)
        } catch (e: Exception) {
            return ConversionResult(false, "Could not read Chrome data: ${e.message}")
        }

        if (data.places.isEmpty() && data.bookmarks.isEmpty()) {
            return ConversionResult(
                false,
                "Chrome has no bookmarks or history to import. " +
                    "Open Chrome, browse a page or add a bookmark, then retry."
            )
        }

        emit(onProgress, 2, "Importing into Firefox", "Writing bookmarks and history...", 55)
        val imported = CrossEngineConverter.importIntoGecko(targetPlaces, data)
        if (!imported.success) return imported

        emit(onProgress, 3, "Writing Firefox data", "Restoring places.sqlite...", 85)
        if (!writeToTarget(targetPlaces, target.placesDb, targetPkg)) {
            return ConversionResult(false, "Could not write the target places.sqlite")
        }

        return ConversionResult(true, imported.message, warnings)
    }

    private suspend fun geckoToChromium(
        targetPkg: String,
        source: GeckoProfile,
        target: ChromiumProfile,
        onProgress: suspend (MigrationResult.Progress) -> Unit,
        warnings: MutableList<String>
    ): ConversionResult {
        emit(onProgress, 1, "Reading Firefox", "Copying Firefox places.sqlite...", 18)

        val sourcePlaces = exportToCache(source.placesDb, "gecko_places.db")
            ?: return ConversionResult(false, "Could not copy the source places.sqlite")
        val targetHistory = exportToCache(target.history, "chromium_history.db")
            ?: return ConversionResult(
                false,
                "Chrome History not found. Open the target browser once, then retry."
            )
        val targetBookmarksRaw = readRootText(target.bookmarks)

        val data = try {
            CrossEngineConverter.readGecko(sourcePlaces)
        } catch (e: Exception) {
            return ConversionResult(false, "Could not read Firefox data: ${e.message}")
        }

        if (data.places.isEmpty() && data.bookmarks.isEmpty()) {
            return ConversionResult(
                false,
                "Firefox has no bookmarks or history to import. " +
                    "Open Firefox, browse a page or add a bookmark, then retry."
            )
        }

        emit(onProgress, 2, "Importing into Chrome", "Writing bookmarks and history...", 55)
        val imported = CrossEngineConverter.importIntoChromium(targetHistory, data)
        if (!imported.success) return imported

        emit(onProgress, 3, "Writing Chrome data", "Restoring History and Bookmarks...", 85)
        if (!writeToTarget(targetHistory, target.history, targetPkg)) {
            return ConversionResult(false, "Could not write the target History database")
        }

        val bookmarksJson = CrossEngineConverter.mergeChromiumBookmarks(
            targetBookmarksRaw, data.bookmarks
        )
        if (!writeTextToTarget(bookmarksJson, target.bookmarks, targetPkg)) {
            return ConversionResult(false, "Could not write the target Bookmarks file")
        }
        // Without checksum Chromium rebuilds it, but only if an old .bak does
        // not win the recovery path. The backup copy is in WORK_DIR already.
        RootHelper.exec(
            "rm -f ${BrowserPaths.q(target.bookmarks + ".bak")} " +
                "${BrowserPaths.q(target.bookmarks + ".tmp")}"
        )

        return ConversionResult(
            true,
            "${imported.message} ${data.bookmarks.size} bookmarks were merged into Chrome.",
            warnings
        )
    }

    // ------------------------------------------------------------------
    //  Root file plumbing
    // ------------------------------------------------------------------

    private suspend fun backupTarget(
        targetPkg: String,
        engine: BrowserType,
        chromium: ChromiumProfile?,
        gecko: GeckoProfile?
    ): String? {
        val source = if (engine == BrowserType.CHROMIUM) chromium?.profileDir else gecko?.filesDir
        if (source == null) return null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backup = "$WORK_DIR/backup_$stamp"
        val dest = if (engine == BrowserType.CHROMIUM) {
            "$backup/target_original_profile"
        } else {
            "$backup/target_files"
        }
        val profile = chromium?.profileDir ?: gecko?.profileDir.orEmpty()
        val dataDir = chromium?.dataDir ?: gecko?.dataDir.orEmpty()
        val manifest = buildString {
            appendLine("TARGET=$targetPkg")
            appendLine("TARGET_PACKAGE=$targetPkg")
            appendLine("ENGINE=${engine.name}")
            appendLine("PROFILE=$profile")
            appendLine("TARGET_PROFILE=$profile")
            appendLine("DATA_DIR=$dataDir")
            appendLine("TARGET_DATA_DIR=$dataDir")
            appendLine("BACKUP_KIND=${if (engine == BrowserType.CHROMIUM) "profile" else "files"}")
            appendLine("DATE=${Date()}")
        }

        RootHelper.execMultiple(listOf(
            "mkdir -p ${BrowserPaths.q(dest)}",
            "cp -a ${BrowserPaths.q(source)}/. ${BrowserPaths.q(dest)}/ 2>/dev/null || " +
                "cp -rf ${BrowserPaths.q(source)}/. ${BrowserPaths.q(dest)}/ 2>/dev/null",
            "cat > ${BrowserPaths.q("$backup/manifest.txt")} <<'EOF'\n$manifest\nEOF"
        ))

        return if (BrowserPaths.exists(dest)) backup else null
    }

    /**
     * Copy a root-only file into the app's cache. Root also changes ownership
     * so SQLiteDatabase can open and, where needed, write the copy.
     */
    private suspend fun exportToCache(source: String, name: String): String? {
        cacheRoot.mkdirs()
        val dest = File(cacheRoot, name)
        val path = dest.absolutePath
        val uid = android.os.Process.myUid()

        val commands = mutableListOf(
            "rm -f ${BrowserPaths.q(path)} ${BrowserPaths.q("$path-wal")} " +
                "${BrowserPaths.q("$path-shm")} ${BrowserPaths.q("$path-journal")}",
            "cp -f ${BrowserPaths.q(source)} ${BrowserPaths.q(path)} 2>/dev/null"
        )
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            commands.add(
                "[ -f ${BrowserPaths.q(source + suffix)} ] && " +
                    "cp -f ${BrowserPaths.q(source + suffix)} ${BrowserPaths.q(path + suffix)} " +
                    "2>/dev/null || true"
            )
        }
        commands.add("chown $uid:$uid ${BrowserPaths.q(path)} 2>/dev/null || true")
        commands.add("chmod 600 ${BrowserPaths.q(path)}")
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            commands.add(
                "[ -f ${BrowserPaths.q(path + suffix)} ] && " +
                    "chown $uid:$uid ${BrowserPaths.q(path + suffix)} 2>/dev/null || true"
            )
            commands.add(
                "[ -f ${BrowserPaths.q(path + suffix)} ] && " +
                    "chmod 600 ${BrowserPaths.q(path + suffix)} 2>/dev/null || true"
            )
        }

        RootHelper.execMultiple(commands)
        return if (dest.isFile && dest.length() > 0) path else null
    }

    private suspend fun writeToTarget(local: String, target: String, pkg: String): Boolean {
        val uid = BrowserPaths.uid(pkg)
        val commands = mutableListOf(
            "rm -f ${BrowserPaths.q("$target-wal")} ${BrowserPaths.q("$target-shm")} " +
                "${BrowserPaths.q("$target-journal")}",
            "cp -f ${BrowserPaths.q(local)} ${BrowserPaths.q(target)}",
            "chmod 600 ${BrowserPaths.q(target)}"
        )
        if (!uid.isNullOrBlank()) {
            commands.add("chown $uid:$uid ${BrowserPaths.q(target)} 2>/dev/null || true")
        }
        commands.add(
            "command -v restorecon >/dev/null 2>&1 && " +
                "restorecon -F ${BrowserPaths.q(target)} 2>/dev/null || true"
        )
        val result = RootHelper.execMultiple(commands)
        return result.success && BrowserPaths.exists(target)
    }

    private suspend fun writeTextToTarget(content: String, target: String, pkg: String): Boolean {
        cacheRoot.mkdirs()
        val tmp = File(cacheRoot, "write_${System.currentTimeMillis()}.json")
        return try {
            tmp.writeText(content, Charsets.UTF_8)
            writeToTarget(tmp.absolutePath, target, pkg)
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    private suspend fun readRootText(path: String): String? {
        val result = RootHelper.exec("cat ${BrowserPaths.q(path)} 2>/dev/null")
        return if (result.success && result.stdout.isNotBlank()) result.stdout else null
    }

    private suspend fun emit(
        callback: suspend (MigrationResult.Progress) -> Unit,
        phase: Int,
        name: String,
        detail: String,
        percent: Int
    ) = withContext(Dispatchers.Main) {
        callback(MigrationResult.Progress(phase, name, detail, percent))
    }

    private suspend fun appendLog(message: String) {
        val safe = message.replace("\r", "")
        RootHelper.exec(
            "mkdir -p ${BrowserPaths.q(WORK_DIR)}; " +
                "cat >> ${BrowserPaths.q(LOG_FILE)} <<'CE_LOG'\n$safe\nCE_LOG"
        )
    }

    private fun cleanupCache() {
        try {
            cacheRoot.listFiles()?.forEach { it.delete() }
            cacheRoot.delete()
        } catch (_: Exception) {
        }
    }
}
