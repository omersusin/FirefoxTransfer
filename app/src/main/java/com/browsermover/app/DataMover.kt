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

                postProgress(listener, "Clone: $srcPkg -> $dstPkg")

                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root access required!")
                    return@Thread
                }

                val script = buildExpertScript(srcPkg, dstPkg, backupFirst)

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

    private fun buildExpertScript(src: String, tgt: String, backup: Boolean): String {
        return buildString {
            appendLine("SRC=\"$src\"; TGT=\"$tgt\"")
            appendLine("SD=\"/data/data/\$SRC\"; TD=\"/data/data/\$TGT\"")
            
            // 1. UID Resolution
            appendLine("echo STEP=UID_Resolution")
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TD\" 2>/dev/null || dumpsys package \"\$TGT\" | grep -m1 'userId=' | grep -oE '[0-9]+' | head -1)")
            appendLine("SRC_APK=\$(pm path \"\$SRC\" 2>/dev/null | head -1 | sed 's|^package:||;s|/base.apk$||')")
            appendLine("TGT_APK=\$(pm path \"\$TGT\" 2>/dev/null | head -1 | sed 's|^package:||;s|/base.apk$||')")

            // 2. Stop Apps
            appendLine("echo STEP=Stopping_applications")
            appendLine("am force-stop \"\$SRC\"; am force-stop \"\$TGT\"")
            appendLine("pkill -9 -f \"\$SRC\"; pkill -9 -f \"\$TGT\"")
            appendLine("sync")

            // 3. WAL Checkpoint
            appendLine("echo STEP=WAL_Checkpoint")
            appendLine("find \"\$SD\" -type f \\( -name '*.sqlite' -o -name '*.db' \\) | while read db; do sqlite3 \"\$db\" \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>/dev/null; done")

            // 4. Wipe Target
            appendLine("echo STEP=Clearing_target")
            appendLine("find \"\$TD\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")

            // 5. Selective Copy (Whitelist)
            appendLine("echo STEP=Copying_data")
            appendLine("SRC_PROF=\$(ls -d \"\$SD/files/mozilla/\"*.default* 2>/dev/null | head -1)")
            appendLine("PROF_NAME=\$(basename \"\$SRC_PROF\")")
            appendLine("TGT_PROF=\"\$TD/files/mozilla/\$PROF_NAME\"")
            appendLine("mkdir -p \"\$TGT_PROF\"")
            
            val profileWhitelist = "cookies.sqlite logins.json key4.db cert9.db signedInUser.json places.sqlite favicons.sqlite formhistory.sqlite permissions.sqlite content-prefs.sqlite prefs.js user.js handlers.json extensions extensions.json storage"
            appendLine("for f in $profileWhitelist; do [ -e \"\$SRC_PROF/\$f\" ] && cp -a \"\$SRC_PROF/\$f\" \"\$TGT_PROF/\$f\"; done")
            appendLine("cp -a \"\$SD/files/mozilla/profiles.ini\" \"\$TD/files/mozilla/\" 2>/dev/null")
            appendLine("cp -a \"\$SD/shared_prefs\" \"\$TD/\" 2>/dev/null")
            appendLine("cp -a \"\$SD/databases\" \"\$TD/\" 2>/dev/null")

            // 6. Aggressive Crash Fix (Delete Poisonous Files)
            appendLine("echo STEP=Aggressive_Cleanup")
            // AC Session
            appendLine("rm -rf \"\$TD/files/mozac.session.storage\" \"\$TD/files/session_store\" \"\$TD/files/.snapshots\" 2>/dev/null")
            appendLine("rm -f \"\$TD/files/session.json\" \"\$TD/files/session_backup.json\" 2>/dev/null")
            // Gecko Crash
            appendLine("rm -f \"\$TGT_PROF/compatibility.ini\" \"\$TGT_PROF/.parentlock\" \"\$TGT_PROF/lock\" \"\$TGT_PROF/addonStartup.json.lz4\" 2>/dev/null")
            appendLine("rm -rf \"\$TGT_PROF/startupCache\" \"\$TGT_PROF/cache2\" \"\$TGT_PROF/shader-cache\" 2>/dev/null")
            // Poisonous Prefs
            val spPatterns = "*session* *GeckoSession* *GeckoRuntime* *telemetry* *uuid* *installation* *device_id* *crash*"
            appendLine("for p in $spPatterns; do find \"\$TD/shared_prefs\" -iname \"\$p.xml\" -delete 2>/dev/null; done")
            // Session DBs
            appendLine("find \"\$TD/databases\" -iname '*session*' -o -iname '*recently_closed*' -delete 2>/dev/null")

            // 7. Patching
            appendLine("echo STEP=Patching_references")
            // Text files
            appendLine("find \"\$TD\" -type f \\( -name '*.ini' -o -name '*.js' -o -name '*.json' -o -name '*.xml' \\) | while read f; do")
            appendLine("  sed -i \"s|\$SRC|\$TGT|g\" \"\$f\" 2>/dev/null")
            appendLine("  [ -n \"\$SRC_APK\" ] && [ -n \"\$TGT_APK\" ] && sed -i \"s|\$SRC_APK|\$TGT_APK|g\" \"\$f\" 2>/dev/null")
            appendLine("done")
            // SQLite Databases
            appendLine("find \"\$TD\" -type f \\( -name '*.sqlite' -o -name '*.db' \\) | while read db; do")
            appendLine("  sqlite3 \"\$db\" \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>/dev/null")
            appendLine("  sqlite3 \"\$db\" .dump | sed \"s|\$SRC|\$TGT|g\" | sqlite3 \"\$db.tmp\" && mv \"\$db.tmp\" \"\$db\"")
            appendLine("done")

            // 8. Ownership and SELinux
            appendLine("echo STEP=Fixing_security")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TD\"")
            appendLine("restorecon -RF \"\$TD\"")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun parseOutput(output: String, error: String, source: BrowserInfo, target: BrowserInfo, listener: ProgressListener) {
        var hasError = false
        for (line in output.lines()) {
            val t = line.trim()
            if (t.startsWith("STEP=")) postProgress(listener, t.removePrefix("STEP=").replace("_", " "))
            if (t == "TRANSFER_COMPLETE") {
                postSuccess(listener, "Clone success!\n\nFirefox Account and Tabs reset to prevent crashes.\n\nBookmarks and Passwords migrated.")
                return
            }
        }
        postError(listener, "Migration failed or was partial.\n$error")
    }

    private fun postProgress(listener: ProgressListener, msg: String) { mainHandler.post { listener.onProgress(msg) } }
    private fun postSuccess(listener: ProgressListener, msg: String) { mainHandler.post { listener.onSuccess(msg) } }
    private fun postError(listener: ProgressListener, msg: String) { mainHandler.post { listener.onError(msg) } }
}
