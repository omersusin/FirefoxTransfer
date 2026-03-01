package com.browsermover.app

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class DataMover {

    interface ProgressListener {
        fun onProgress(message: String)
        fun onSuccess(message: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun moveData(
        source: BrowserInfo,
        target: BrowserInfo,
        backupFirst: Boolean,
        listener: ProgressListener
    ) {
        Thread {
            try {
                val srcPkg = source.packageName
                val dstPkg = target.packageName

                postProgress(listener, "Transfer: ${source.name} -> ${target.name}")

                postProgress(listener, "Detecting root method...")
                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root access not found!")
                    return@Thread
                }
                val suMethodName = RootHelper.getSuMethodName()
                postProgress(listener, "Root method: $suMethodName")

                val script = buildScript(srcPkg, dstPkg, backupFirst)

                postProgress(listener, "Executing transfer script...")

                val suMethod = RootHelper.getSuMethod()
                val process = Runtime.getRuntime().exec(suMethod)
                val os = DataOutputStream(process.outputStream)

                os.writeBytes(script)
                os.writeBytes("exit\n")
                os.flush()
                os.close()

                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                val stderr = BufferedReader(InputStreamReader(process.errorStream))

                val output = stdout.readText()
                val error = stderr.readText()

                stdout.close()
                stderr.close()
                process.waitFor(180, TimeUnit.SECONDS)

                parseOutput(output, error, source, target, listener)

            } catch (e: Exception) {
                postError(listener, "Error: ${e.message}")
            }
        }.start()
    }

    private fun buildScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("echo STEP=Debug")
            appendLine("echo \"ROOT_ID=\$(id)\"")
            appendLine("echo \"SELINUX=\$(getenforce 2>/dev/null || echo unknown)\"")
            appendLine("")

            // Find source
            appendLine("echo STEP=Finding_source")
            appendLine("SRCDIR=\"\"")
            appendLine("if [ -d \"/data/data/$srcPkg\" ]; then SRCDIR=\"/data/data/$srcPkg\"; elif [ -d \"/data/user/0/$srcPkg\" ]; then SRCDIR=\"/data/user/0/$srcPkg\"; fi")
            appendLine("echo \"SRCDIR=\$SRCDIR\"")
            appendLine("")

            // Validate source
            appendLine("if [ -z \"\$SRCDIR\" ]; then")
            appendLine("  echo \"ERROR=Source not found\"")
            appendLine("  echo \"DEBUG_DATA_DATA=\$(ls /data/data/ 2>&1 | grep -i moz | head -10)\"")
            appendLine("  echo \"DEBUG_USER_0=\$(ls /data/user/0/ 2>&1 | grep -i moz | head -10)\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("")

            // Source content
            appendLine("echo \"SRC_FILES=\$(ls \$SRCDIR/ 2>/dev/null | head -10)\"")
            appendLine("")

            // Find target
            appendLine("echo STEP=Finding_target")
            appendLine("DSTDIR=\"\"")
            appendLine("if [ -d \"/data/data/$dstPkg\" ]; then DSTDIR=\"/data/data/$dstPkg\"; elif [ -d \"/data/user/0/$dstPkg\" ]; then DSTDIR=\"/data/user/0/$dstPkg\"; fi")
            appendLine("echo \"DSTDIR=\$DSTDIR\"")
            appendLine("")

            // Validate target
            appendLine("if [ -z \"\$DSTDIR\" ]; then")
            appendLine("  echo \"ERROR=Target not found\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("")

            // Get owner BEFORE changes
            appendLine("echo STEP=Getting_owner")
            appendLine("UGROUP=\$(ls -ld \"\$DSTDIR\" | awk '{print \$3}')")
            appendLine("UGROUPG=\$(ls -ld \"\$DSTDIR\" | awk '{print \$4}')")
            appendLine("echo \"OWNER=\$UGROUP:\$UGROUPG\"")
            appendLine("")

            // Fallback owner via dumpsys
            appendLine("if [ -z \"\$UGROUP\" ] || [ \"\$UGROUP\" = \"root\" ]; then")
            appendLine("  DUMP_UID=\$(dumpsys package $dstPkg | grep 'userId=' | head -1 | sed 's/.*userId=//' | sed 's/[^0-9].*//')")
            appendLine("  if [ -n \"\$DUMP_UID\" ]; then")
            appendLine("    CALC=\$((\$DUMP_UID - 10000))")
            appendLine("    UGROUP=\"u0_a\$CALC\"")
            appendLine("    UGROUPG=\"u0_a\$CALC\"")
            appendLine("    echo \"OWNER_DUMP=\$UGROUP\"")
            appendLine("  fi")
            appendLine("fi")
            appendLine("")

            // Stop browsers
            appendLine("echo STEP=Stopping_browsers")
            appendLine("am force-stop $srcPkg 2>/dev/null")
            appendLine("am force-stop $dstPkg 2>/dev/null")
            appendLine("sleep 2")
            appendLine("")

            // Backup
            if (backup) {
                appendLine("echo STEP=Backup")
                appendLine("mkdir -p /sdcard/BrowserDataMover/backups")
                appendLine("TIMESTAMP=\$(date +%s)")
                appendLine("tar -czf \"/sdcard/BrowserDataMover/backups/${dstPkg}_\$TIMESTAMP.tar.gz\" -C \"\$DSTDIR\" . 2>/dev/null && echo BACKUP=OK || echo BACKUP=FAIL")
                appendLine("")
            }

            // Clear target
            appendLine("echo STEP=Clearing_target")
            appendLine("rm -rf \"\$DSTDIR\"/*")
            appendLine("")

            // Copy data
            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRCDIR\"/* \"\$DSTDIR\"/ 2>&1 | tail -3")
            appendLine("echo COPY=DONE")
            appendLine("")

            // Clean up incompatible session/state files
            appendLine("echo STEP=Cleaning_session_data")
            appendLine("")

            // Android Components session storage
            appendLine("rm -rf \"\$DSTDIR/files/.browser_state\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/session\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/session.json\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/session.json.bak\" 2>/dev/null")
            appendLine("")

            // GeckoView session files
            appendLine("rm -f \"\$DSTDIR/files/mozilla/*/sessionstore.jsonlz4\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/mozilla/*/sessionstore-backups\"/*.jsonlz4 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/mozilla/*/sessionstore-backups\" 2>/dev/null")
            appendLine("")

            // Snapshots and tab state
            appendLine("rm -rf \"\$DSTDIR/files/snapshots\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/tab_state\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/recently_closed_tabs\" 2>/dev/null")
            appendLine("")

            // Crash reports
            appendLine("rm -rf \"\$DSTDIR/files/mozilla/Crash Reports\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/cache\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/code_cache\" 2>/dev/null")
            appendLine("")

            // Shared prefs that may reference wrong package
            appendLine("rm -rf \"\$DSTDIR/shared_prefs\" 2>/dev/null")
            appendLine("")

            // App-specific databases that may be incompatible
            appendLine("rm -f \"\$DSTDIR/databases/fenix*\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/focus*\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/klar*\" 2>/dev/null")
            appendLine("")

            appendLine("echo SESSION_CLEAN=DONE")
            appendLine("")

            // Fix ownership
            appendLine("echo STEP=Fixing_ownership")
            appendLine("if [ -n \"\$UGROUP\" ] && [ \"\$UGROUP\" != \"root\" ]; then")
            appendLine("  chown -R \"\$UGROUP:\$UGROUPG\" \"\$DSTDIR\"")
            appendLine("  echo \"CHOWN=\$UGROUP\"")
            appendLine("else")
            appendLine("  echo CHOWN=SKIP")
            appendLine("fi")
            appendLine("")

            // Fix SELinux
            appendLine("echo STEP=SELinux")
            appendLine("restorecon -RF \"\$DSTDIR\" 2>/dev/null")
            appendLine("")

            // Verify
            appendLine("echo STEP=Verify")
            appendLine("echo \"DST_FILES=\$(ls \"\$DSTDIR\"/ 2>/dev/null | head -10)\"")
            appendLine("DST_COUNT=\$(ls \"\$DSTDIR\"/ 2>/dev/null | wc -l)")
            appendLine("echo \"DST_COUNT=\$DST_COUNT\"")
            appendLine("")

            // Show what was kept
            appendLine("echo STEP=Kept_data")
            appendLine("echo \"KEPT_MOZILLA=\$(ls \"\$DSTDIR/files/mozilla/\" 2>/dev/null | head -5)\"")
            appendLine("echo \"KEPT_DBS=\$(ls \"\$DSTDIR/databases/\" 2>/dev/null | head -10)\"")
            appendLine("")

            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun parseOutput(output: String, error: String, source: BrowserInfo, target: BrowserInfo, listener: ProgressListener) {
        var srcDir = ""
        var dstDir = ""
        var hasError = false

        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            when {
                trimmed.startsWith("STEP=") -> {
                    postProgress(listener, trimmed.removePrefix("STEP=").replace("_", " "))
                }
                trimmed.startsWith("ROOT_ID=") -> {
                    postProgress(listener, "Root: ${trimmed.removePrefix("ROOT_ID=").take(60)}")
                }
                trimmed.startsWith("SELINUX=") -> {
                    postProgress(listener, "SELinux: ${trimmed.removePrefix("SELINUX=")}")
                }
                trimmed.startsWith("SRCDIR=") -> {
                    srcDir = trimmed.removePrefix("SRCDIR=")
                    postProgress(listener, "Source dir: $srcDir")
                }
                trimmed.startsWith("DSTDIR=") -> {
                    dstDir = trimmed.removePrefix("DSTDIR=")
                    postProgress(listener, "Target dir: $dstDir")
                }
                trimmed.startsWith("SRC_FILES=") -> {
                    postProgress(listener, "Source files: ${trimmed.removePrefix("SRC_FILES=").take(100)}")
                }
                trimmed.startsWith("OWNER=") -> {
                    postProgress(listener, "Original owner: ${trimmed.removePrefix("OWNER=")}")
                }
                trimmed.startsWith("OWNER_DUMP=") -> {
                    postProgress(listener, "Owner (via UID): ${trimmed.removePrefix("OWNER_DUMP=")}")
                }
                trimmed.startsWith("BACKUP=") -> {
                    val s = trimmed.removePrefix("BACKUP=")
                    postProgress(listener, if (s == "OK") "✅ Backup saved" else "⚠️ Backup failed")
                }
                trimmed == "COPY=DONE" -> {
                    postProgress(listener, "✅ Copy complete")
                }
                trimmed == "SESSION_CLEAN=DONE" -> {
                    postProgress(listener, "✅ Session data cleaned (prevents crashes)")
                }
                trimmed.startsWith("CHOWN=") -> {
                    val v = trimmed.removePrefix("CHOWN=")
                    postProgress(listener, if (v == "SKIP") "⚠️ Ownership: skipped" else "✅ Owner set: $v")
                }
                trimmed.startsWith("DST_FILES=") -> {
                    postProgress(listener, "Target files: ${trimmed.removePrefix("DST_FILES=").take(100)}")
                }
                trimmed.startsWith("DST_COUNT=") -> {
                    postProgress(listener, "Target file count: ${trimmed.removePrefix("DST_COUNT=")}")
                }
                trimmed.startsWith("KEPT_MOZILLA=") -> {
                    postProgress(listener, "Kept (mozilla): ${trimmed.removePrefix("KEPT_MOZILLA=").take(100)}")
                }
                trimmed.startsWith("KEPT_DBS=") -> {
                    postProgress(listener, "Kept (databases): ${trimmed.removePrefix("KEPT_DBS=").take(100)}")
                }
                trimmed.startsWith("ERROR=") -> {
                    hasError = true
                    postError(listener, "${trimmed.removePrefix("ERROR=")}\n\nPackage: ${source.packageName}")
                }
                trimmed.startsWith("DEBUG_") -> {
                    postProgress(listener, "Debug: $trimmed")
                }
                trimmed == "TRANSFER_COMPLETE" -> {
                    if (!hasError) {
                        postSuccess(listener,
                            "Transfer successful!\n\n" +
                            "From: ${source.name}\n($srcDir)\n\n" +
                            "To: ${target.name}\n($dstDir)\n\n" +
                            "Transferred: Bookmarks, logins, cookies, history, extensions\n" +
                            "Cleaned: Session/tab data (prevents crashes)\n\n" +
                            "You can now open ${target.name}.")
                    }
                    return
                }
            }
        }

        if (!hasError) {
            postError(listener,
                "Transfer may have failed.\n\nOutput:\n${output.take(2000)}\n\nErrors:\n${error.take(500)}")
        }
    }

    private fun postProgress(listener: ProgressListener, msg: String) {
        mainHandler.post { listener.onProgress(msg) }
    }

    private fun postSuccess(listener: ProgressListener, msg: String) {
        mainHandler.post { listener.onSuccess(msg) }
    }

    private fun postError(listener: ProgressListener, msg: String) {
        mainHandler.post { listener.onError(msg) }
    }
}
