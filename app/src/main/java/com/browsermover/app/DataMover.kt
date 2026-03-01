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

                postProgress(listener, "Clone: ${source.name} -> ${target.name}")

                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root access required!")
                    return@Thread
                }

                val script = if (source.type == BrowserType.GECKO) {
                    buildGeckoScript(srcPkg, dstPkg, backupFirst)
                } else {
                    buildChromiumScript(srcPkg, dstPkg, backupFirst)
                }

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
            appendLine("SRC=\"$srcPkg\"; TGT=\"$dstPkg\"")
            appendLine("SD=\"/data/data/\$SRC\"; TD=\"/data/data/\$TGT\"")
            
            appendLine("echo STEP=UID_Resolution")
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TD\" 2>/dev/null || dumpsys package \"\$TGT\" | grep -m1 'userId=' | grep -oE '[0-9]+' | head -1)")
            
            appendLine("echo STEP=Stopping_apps")
            appendLine("am force-stop \"\$SRC\"; am force-stop \"\$TGT\"")
            appendLine("pkill -9 -f \"\$SRC\"; pkill -9 -f \"\$TGT\"")
            
            appendLine("echo STEP=WAL_Checkpoint")
            appendLine("find \"\$SD\" -type f \\( -name '*.sqlite' -o -name '*.db' \\) | while read db; do sqlite3 \"\$db\" \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>/dev/null; done")

            appendLine("echo STEP=Cleaning_Target")
            appendLine("find \"\$TD\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")

            appendLine("echo STEP=Seletive_Copy")
            appendLine("SRC_PROF=\$(ls -d \"\$SD/files/mozilla/\"*.default* 2>/dev/null | head -1)")
            appendLine("PROF_NAME=\$(basename \"\$SRC_PROF\")")
            appendLine("mkdir -p \"\$TD/files/mozilla/\$PROF_NAME\"")
            
            // Whitelist copy
            val whitelist = "cookies.sqlite logins.json key4.db cert9.db signedInUser.json places.sqlite favicons.sqlite formhistory.sqlite permissions.sqlite content-prefs.sqlite prefs.js user.js handlers.json extensions extensions.json storage"
            appendLine("for f in $whitelist; do [ -e \"\$SRC_PROF/\$f\" ] && cp -a \"\$SRC_PROF/\$f\" \"\$TD/files/mozilla/\$PROF_NAME/\$f\"; done")
            appendLine("cp -a \"\$SD/files/mozilla/profiles.ini\" \"\$TD/files/mozilla/\" 2>/dev/null")
            appendLine("cp -a \"\$SD/shared_prefs\" \"\$TD/\" 2>/dev/null")
            appendLine("cp -a \"\$SD/databases\" \"\$TD/\" 2>/dev/null")

            appendLine("echo STEP=Deep_Clean_Session_Crash_Triggers")
            // Remove AC session data that causes BEGIN_ARRAY crash
            appendLine("rm -rf \"\$TD/files/mozac.session.storage\" \"\$TD/files/session_store\" \"\$TD/files/.snapshots\" 2>/dev/null")
            appendLine("rm -f \"\$TD/files/session.json\" \"\$TD/files/session_backup.json\" 2>/dev/null")
            // Remove Gecko crash triggers
            appendLine("TP=\"\$TD/files/mozilla/\$PROF_NAME\"")
            appendLine("rm -f \"\$TP/compatibility.ini\" \"\$TP/.parentlock\" \"\$TP/lock\" \"\$TP/addonStartup.json.lz4\" 2>/dev/null")
            appendLine("rm -rf \"\$TP/startupCache\" \"\$TP/cache2\" 2>/dev/null")
            // Clean session-related prefs
            appendLine("find \"\$TD/shared_prefs\" -iname '*session*' -o -iname '*state*' -o -iname '*GeckoSession*' -delete 2>/dev/null")

            appendLine("echo STEP=Patching_Paths")
            appendLine("find \"\$TD\" -type f \\( -name '*.ini' -o -name '*.js' -o -name '*.json' -o -name '*.xml' \\) | while read f; do sed -i \"s|\$SRC|\$TGT|g\" \"\$f\" 2>/dev/null; done")
            
            appendLine("echo STEP=Patching_Databases")
            appendLine("find \"\$TD/databases\" -type f -name '*.db' | while read db; do sqlite3 \"\$db\" .dump | sed \"s|\$SRC|\$TGT|g\" | sqlite3 \"\$db.tmp\" && mv \"\$db.tmp\" \"\$db\"; done")

            appendLine("echo STEP=Restoring_Security")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TD\"")
            appendLine("restorecon -RF \"\$TD\"")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun buildChromiumScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("SRC=\"$srcPkg\"; TGT=\"$dstPkg\"")
            appendLine("TD=\"/data/data/\$TGT\"")
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TD\" 2>/dev/null || dumpsys package \"\$TGT\" | grep -m1 'userId=' | grep -oE '[0-9]+' | head -1)")
            appendLine("am force-stop \"\$SRC\"; am force-stop \"\$TGT\"")
            appendLine("find \"\$TD\" -mindepth 1 -maxdepth 1 ! -name 'lib' -exec rm -rf {} +")
            appendLine("cp -a \"/data/data/\$SRC/\"* \"\$TD/\"")
            appendLine("rm -rf \"\$TD/cache\" \"\$TD/code_cache\"")
            appendLine("find \"\$TD\" -type f | while read f; do sed -i \"s|\$SRC|\$TGT|g\" \"\$f\" 2>/dev/null; done")
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
                postSuccess(listener, "Clone complete!\n\nBookmarks, History and Passwords restored.\n\nTabs reset to prevent crashes.")
                return
            }
        }
        postError(listener, "Transfer failed or was partial.\n$error")
    }

    private fun postProgress(listener: ProgressListener, msg: String) { mainHandler.post { listener.onProgress(msg) } }
    private fun postSuccess(listener: ProgressListener, msg: String) { mainHandler.post { listener.onSuccess(msg) } }
    private fun postError(listener: ProgressListener, msg: String) { mainHandler.post { listener.onError(msg) } }
}
