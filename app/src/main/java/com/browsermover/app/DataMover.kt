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
                postProgress(listener, "Root method: ${RootHelper.getSuMethodName()}")

                val isFirefox = source.type == BrowserType.FIREFOX
                val script = if (isFirefox) {
                    buildFirefoxScript(srcPkg, dstPkg, backupFirst)
                } else {
                    buildChromiumScript(srcPkg, dstPkg, backupFirst)
                }

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

    private fun buildFirefoxScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            // 1. Setup and Discovery
            appendLine("SRC_PKG=\"$srcPkg\"")
            appendLine("TGT_PKG=\"$dstPkg\"")
            appendLine("SRC_DIR=\"/data/data/\$SRC_PKG\"")
            appendLine("TGT_DIR=\"/data/data/\$TGT_PKG\"")
            appendLine("")
            appendLine("echo STEP=Preparing_migration")
            
            // 2. Resolve UID/GID BEFORE wiping
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TGT_DIR\" 2>/dev/null || dumpsys package \"\$TGT_PKG\" | grep -m1 'userId=' | grep -oP 'userId=\\K[0-9]+')")
            appendLine("echo \"DEBUG_TGT_UID=\$TGT_UID\"")
            appendLine("")

            // 3. Force stop apps
            appendLine("echo STEP=Stopping_applications")
            appendLine("am force-stop \"\$SRC_PKG\" 2>/dev/null")
            appendLine("am force-stop \"\$TGT_PKG\" 2>/dev/null")
            appendLine("pkill -9 -f \"\$SRC_PKG\" 2>/dev/null")
            appendLine("pkill -9 -f \"\$TGT_PKG\" 2>/dev/null")
            appendLine("sleep 1")
            appendLine("")

            // 4. Backup if requested
            if (backup) {
                appendLine("echo STEP=Backup")
                appendLine("mkdir -p /sdcard/BrowserDataMover/backups")
                appendLine("tar -czf \"/sdcard/BrowserDataMover/backups/\${TGT_PKG}_\$(date +%s).tar.gz\" -C \"\$TGT_DIR\" . 2>/dev/null")
            }

            // 5. Clear Target (Keep lib symlink!)
            appendLine("echo STEP=Clearing_target")
            appendLine("find \"\$TGT_DIR\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")
            appendLine("")

            // 6. Copy Data (Selective to avoid architecture-specific crashes)
            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRC_DIR/files\" \"\$TGT_DIR/\"")
            appendLine("cp -a \"\$SRC_DIR/databases\" \"\$TGT_DIR/\"")
            appendLine("cp -a \"\$SRC_DIR/shared_prefs\" \"\$TGT_DIR/\"")
            appendLine("")

            // 7. Cleanup Volatile Caches
            appendLine("echo STEP=Cleaning_caches")
            appendLine("find \"\$TGT_DIR\" -type d \\( -name 'cache2' -o -name 'startupCache' -o -name 'shader-cache' -o -name 'app_tmpdir' \\) -exec rm -rf {} + 2>/dev/null")
            appendLine("find \"\$TGT_DIR\" -type f \\( -name 'addonStartup.json.lz4' -o -name '.parentlock' -o -name 'lock' -o -name '*-wal' -o -name '*-shm' \\) -delete 2>/dev/null")
            appendLine("")

            // 8. Patch Paths (Recursive text search and replace)
            appendLine("echo STEP=Patching_paths")
            appendLine("find \"\$TGT_DIR\" -type f \\( -name '*.ini' -o -name '*.js' -o -name '*.json' -o -name '*.xml' -o -name 'prefs' \\) | while read f; do")
            appendLine("  grep -ql \"\$SRC_PKG\" \"\$f\" && sed -i \"s|\$SRC_PKG|\$TGT_PKG|g\" \"\$f\"")
            appendLine("done")
            appendLine("")

            // 9. Patch Databases (Safe dump/restore)
            appendLine("echo STEP=Patching_databases")
            appendLine("find \"\$TGT_DIR/databases\" -type f -name '*.db' | while read db; do")
            appendLine("  echo \"    Checking: \$db\"")
            appendLine("  grep -ql \"\$SRC_PKG\" \"\$db\" && (")
            appendLine("    sqlite3 \"\$db\" .dump | sed \"s|\$SRC_PKG|\$TGT_PKG|g\" | sqlite3 \"\$db.tmp\" && mv \"\$db.tmp\" \"\$db\"")
            appendLine("  )")
            appendLine("done")
            appendLine("")

            // 10. Fix Ownership and SELinux (CRITICAL)
            appendLine("echo STEP=Restoring_security_context")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TGT_DIR\"")
            appendLine("restorecon -RF \"\$TGT_DIR\"")
            appendLine("")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun buildChromiumScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("SRC_PKG=\"$srcPkg\"")
            appendLine("TGT_PKG=\"$dstPkg\"")
            appendLine("SRC_DIR=\"/data/data/\$SRC_PKG\"")
            appendLine("TGT_DIR=\"/data/data/\$TGT_PKG\"")
            appendLine("")
            
            appendLine("echo STEP=Preparing_migration")
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TGT_DIR\" 2>/dev/null || dumpsys package \"\$TGT_PKG\" | grep -m1 'userId=' | grep -oP 'userId=\\K[0-9]+')")
            
            appendLine("am force-stop \"\$SRC_PKG\" 2>/dev/null")
            appendLine("am force-stop \"\$TGT_PKG\" 2>/dev/null")
            
            appendLine("echo STEP=Clearing_target")
            appendLine("find \"\$TGT_DIR\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")
            
            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRC_DIR/\"* \"\$TGT_DIR/\"")
            appendLine("rm -rf \"\$TGT_DIR/cache\" \"\$TGT_DIR/code_cache\"")
            
            appendLine("echo STEP=Patching_paths")
            appendLine("find \"\$TGT_DIR\" -type f \\( -name '*.xml' -o -name 'Preferences' -o -name 'Local State' \\) | while read f; do")
            appendLine("  sed -i \"s|\$SRC_PKG|\$TGT_PKG|g\" \"\$f\"")
            appendLine("done")
            
            appendLine("echo STEP=Restoring_security_context")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TGT_DIR\"")
            appendLine("restorecon -RF \"\$TGT_DIR\"")
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun parseOutput(output: String, error: String, source: BrowserInfo, target: BrowserInfo, listener: ProgressListener) {
        var hasError = false

        for (line in output.lines()) {
            val t = line.trim()
            if (t.isBlank()) continue

            when {
                t.startsWith("STEP=") -> postProgress(listener, t.removePrefix("STEP=").replace("_", " "))
                t.startsWith("ERROR=") -> { hasError = true; postError(listener, t.removePrefix("ERROR=")) }
                t == "TRANSFER_COMPLETE" -> {
                    if (!hasError) {
                        postSuccess(listener, "Transfer successful!\n\nFrom: ${source.name}\nTo: ${target.name}")
                    }
                    return
                }
            }
        }

        if (!hasError) {
            postError(listener, "Transfer may have failed.\n\n$error")
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
