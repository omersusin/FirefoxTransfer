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

    fun moveData(source: BrowserInfo, target: BrowserInfo, backup: Boolean, listener: ProgressListener) {
        Thread {
            try {
                val src = source.packageName
                val dst = target.packageName
                
                if (src == dst) {
                    postError(listener, "Source and target cannot be the same!")
                    return@Thread
                }

                postProgress(listener, "Starting Safe-Clone: $src -> $dst")

                val script = buildSafeScript(src, dst)
                val process = Runtime.getRuntime().exec(RootHelper.getSuMethod())
                val os = DataOutputStream(process.outputStream)
                os.writeBytes(script)
                os.writeBytes("exit\n")
                os.flush()
                os.close()

                val error = process.errorStream.bufferedReader().readText()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor(120, TimeUnit.SECONDS)

                if (output.contains("TRANSFER_COMPLETE")) {
                    postSuccess(listener, "Migration successful!\n\nCore data (History, Bookmarks, Passwords, Cookies) moved.\n\nApp should start without crashing.")
                } else {
                    postError(listener, "Migration failed.\n$error")
                }
            } catch (e: Exception) {
                postError(listener, "Error: ${e.message}")
            }
        }.start()
    }

    private fun buildSafeScript(src: String, dst: String): String {
        return buildString {
            appendLine("SRC=\"$src\"; TGT=\"$dst\"")
            appendLine("SD=\"/data/data/\$SRC\"; TD=\"/data/data/\$TGT\"")
            
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TD\" 2>/dev/null || dumpsys package \"\$TGT\" | grep -m1 'userId=' | grep -oE '[0-9]+' | head -1)")
            
            appendLine("am force-stop \"\$SRC\"; am force-stop \"\$TGT\"")
            appendLine("pkill -9 -f \"\$SRC\"; pkill -9 -f \"\$TGT\"")
            appendLine("sleep 2")

            // 1. Find Profile Directories
            appendLine("SRC_PROF=\$(ls -d \"\$SD/files/mozilla/\"*.default* 2>/dev/null | head -1)")
            
            // 2. Create Target Profile Structure (Static Name to avoid confusion)
            appendLine("TGT_PROF_DIR=\"\$TD/files/mozilla/cloned.default-release\"")
            appendLine("rm -rf \"\$TD/files/mozilla\"")
            appendLine("mkdir -p \"\$TGT_PROF_DIR\"")
            
            // 3. Create fresh profiles.ini pointing to our cloned folder
            appendLine("echo -e \"[General]\\nStartWithLastProfile=1\\n\\n[Profile0]\\nName=default\\nIsRelative=1\\nPath=cloned.default-release\\nDefault=1\" > \"\$TD/files/mozilla/profiles.ini\"")

            // 4. Copy ONLY safe core data files
            val coreFiles = "places.sqlite favicons.sqlite cookies.sqlite logins.json key4.db cert9.db permissions.sqlite content-prefs.sqlite prefs.js user.js extensions.json"
            appendLine("for f in $coreFiles; do [ -f \"\$SRC_PROF/\$f\" ] && cp -a \"\$SRC_PROF/\$f\" \"\$TGT_PROF_DIR/\$f\"; done")
            
            // 5. Copy Extensions
            appendLine("[ -d \"\$SRC_PROF/extensions\" ] && cp -a \"\$SRC_PROF/extensions\" \"\$TGT_PROF_DIR/\"")
            appendLine("[ -d \"\$SRC_PROF/browser-extension-data\" ] && cp -a \"\$SRC_PROF/browser-extension-data\" \"\$TGT_PROF_DIR/\"")

            // 6. Patch Paths in core files
            appendLine("find \"\$TGT_PROF_DIR\" -type f -maxdepth 1 | while read f; do sed -i \"s|\$SRC|\$TGT|g\" \"\$f\" 2>/dev/null; done")

            // 7. Ownership and Security
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TD\"")
            appendLine("restorecon -RF \"\$TD\"")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun postProgress(l: ProgressListener, m: String) { mainHandler.post { l.onProgress(m) } }
    private fun postSuccess(l: ProgressListener, m: String) { mainHandler.post { l.onSuccess(m) } }
    private fun postError(l: ProgressListener, m: String) { mainHandler.post { l.onError(m) } }
}
