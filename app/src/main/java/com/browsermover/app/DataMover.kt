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

                postProgress(listener, "Checking root...")
                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root access not found!")
                    return@Thread
                }

                val script = if (source.type == BrowserType.GECKO) {
                    buildGeckoScript(srcPkg, dstPkg, backupFirst)
                } else {
                    buildChromiumScript(srcPkg, dstPkg, backupFirst)
                }

                postProgress(listener, "Executing expert migration script...")

                val process = Runtime.getRuntime().exec(RootHelper.getSuMethod())
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
                process.waitFor(300, TimeUnit.SECONDS)

                parseOutput(output, error, source, target, listener)

            } catch (e: Exception) {
                postError(listener, "Error: ${e.message}")
            }
        }.start()
    }

    private fun buildGeckoScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("SRC_PKG=\"$srcPkg\"")
            appendLine("TGT_PKG=\"$dstPkg\"")
            appendLine("SRC_DIR=\"/data/data/\$SRC_PKG\"")
            appendLine("TGT_DIR=\"/data/data/\$TGT_PKG\"")
            
            appendLine("echo STEP=Resolving_UID")
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TGT_DIR\" 2>/dev/null || dumpsys package \"\$TGT_PKG\" | grep -m1 'userId=' | grep -oP 'userId=\\K[0-9]+')")
            
            appendLine("echo STEP=Stopping_apps")
            appendLine("am force-stop \"\$SRC_PKG\" 2>/dev/null")
            appendLine("am force-stop \"\$TGT_PKG\" 2>/dev/null")
            appendLine("pkill -9 -f \"\$SRC_PKG\" 2>/dev/null")
            appendLine("pkill -9 -f \"\$TGT_PKG\" 2>/dev/null")
            
            appendLine("echo STEP=WAL_Checkpoint")
            appendLine("find \"\$SRC_DIR\" -name '*.sqlite' -o -name '*.db' | while read db; do sqlite3 \"\$db\" 'PRAGMA wal_checkpoint(TRUNCATE);' 2>/dev/null; done")

            if (backup) {
                appendLine("echo STEP=Backup")
                appendLine("mkdir -p /sdcard/BrowserDataMover/backups")
                appendLine("tar -czf \"/sdcard/BrowserDataMover/backups/\${TGT_PKG}_\$(date +%s).tar.gz\" -C \"\$TGT_DIR\" . 2>/dev/null")
            }

            appendLine("echo STEP=Clearing_target")
            appendLine("find \"\$TGT_DIR\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")

            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRC_DIR/files\" \"\$TGT_DIR/\"")
            appendLine("cp -a \"\$SRC_DIR/databases\" \"\$TGT_DIR/\"")
            appendLine("cp -a \"\$SRC_DIR/shared_prefs\" \"\$TGT_DIR/\"")

            appendLine("echo STEP=Cleaning_crash_triggers")
            appendLine("find \"\$TGT_DIR\" -type d \\( -name 'cache2' -o -name 'startupCache' -o -name 'shader-cache' \\) -exec rm -rf {} + 2>/dev/null")
            appendLine("find \"\$TGT_DIR\" -type f \\( -name 'compatibility.ini' -o -name 'addonStartup.json.lz4' -o -name '.parentlock' -o -name 'lock' -o -name '*-wal' -o -name '*-shm' \\) -delete 2>/dev/null")

            appendLine("echo STEP=Patching_paths")
            appendLine("find \"\$TGT_DIR\" -type f \\( -name '*.ini' -o -name '*.js' -o -name '*.json' -o -name '*.xml' \\) | while read f; do sed -i \"s|\$SRC_PKG|\$TGT_PKG|g\" \"\$f\" 2>/dev/null; done")

            appendLine("echo STEP=Patching_databases")
            appendLine("find \"\$TGT_DIR/databases\" -type f -name '*.db' | while read db; do")
            appendLine("  sqlite3 \"\$db\" .dump | sed \"s|\$SRC_PKG|\$TGT_PKG|g\" | sqlite3 \"\$db.tmp\" && mv \"\$db.tmp\" \"\$db\"")
            appendLine("done")

            appendLine("echo STEP=Restoring_security")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TGT_DIR\"")
            appendLine("restorecon -RF \"\$TGT_DIR\"")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun buildChromiumScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("SRC_PKG=\"$srcPkg\"")
            appendLine("TGT_PKG=\"$dstPkg\"")
            appendLine("SRC_DIR=\"/data/data/\$SRC_PKG\"")
            appendLine("TGT_DIR=\"/data/data/\$TGT_PKG\"")
            
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TGT_DIR\" 2>/dev/null || dumpsys package \"\$TGT_PKG\" | grep -m1 'userId=' | grep -oP 'userId=\\K[0-9]+')")
            
            appendLine("am force-stop \"\$SRC_PKG\" 2>/dev/null")
            appendLine("am force-stop \"\$TGT_PKG\" 2>/dev/null")
            
            appendLine("echo STEP=Clearing_target")
            appendLine("find \"\$TGT_DIR\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")
            
            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRC_DIR/\"* \"\$TGT_DIR/\"")
            appendLine("rm -rf \"\$TGT_DIR/cache\" \"\$TGT_DIR/code_cache\"")
            
            appendLine("echo STEP=Patching_paths")
            appendLine("find \"\$TGT_DIR\" -type f \\( -name '*.xml' -o -name 'Preferences' -o -name 'Local State' \\) | while read f; do sed -i \"s|\$SRC_PKG|\$TGT_PKG|g\" \"\$f\" 2>/dev/null; done")
            
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TGT_DIR\"")
            appendLine("restorecon -RF \"\$TGT_DIR\"")
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun parseOutput(output: String, error: String, source: BrowserInfo, target: BrowserInfo, listener: ProgressListener) {
        var hasError = false
        for (line in output.lines()) {
            val t = line.trim()
            if (t.startsWith("STEP=")) postProgress(listener, t.removePrefix("STEP=").replace("_", " "))
            if (t.startsWith("ERROR=")) { hasError = true; postError(listener, t.removePrefix("ERROR=")) }
            if (t == "TRANSFER_COMPLETE") {
                if (!hasError) postSuccess(listener, "Clone successful!\nFrom: ${source.name}\nTo: ${target.name}")
                return
            }
        }
        if (!hasError) postError(listener, "Transfer failed or interrupted.\n$error")
    }

    private fun postProgress(listener: ProgressListener, msg: String) { mainHandler.post { listener.onProgress(msg) } }
    private fun postSuccess(listener: ProgressListener, msg: String) { mainHandler.post { listener.onSuccess(msg) } }
    private fun postError(listener: ProgressListener, msg: String) { mainHandler.post { listener.onError(msg) } }
}
