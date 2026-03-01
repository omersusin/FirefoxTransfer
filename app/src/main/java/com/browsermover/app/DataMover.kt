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

                postProgress(listener, "Strategy: Clean Profile + Data Injection")
                postProgress(listener, "Clone: $src -> $dst")

                val script = buildExpertSafeScript(src, dst)
                val process = Runtime.getRuntime().exec(RootHelper.getSuMethod())
                val os = DataOutputStream(process.outputStream)
                os.writeBytes(script)
                os.writeBytes("exit\n")
                os.flush()
                os.close()

                // Read output in real-time to update progress
                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (stdout.readLine().also { line = it } != null) {
                    if (line!!.startsWith("STEP=")) {
                        postProgress(listener, line!!.removePrefix("STEP=").replace("_", " "))
                    }
                    if (line!!.startsWith("[INFO]")) postProgress(listener, line!!)
                    if (line!!.startsWith("[  OK ]")) postProgress(listener, "✅ " + line!!.substring(7))
                    if (line!!.startsWith("[WARN]")) postProgress(listener, "⚠️ " + line!!.substring(7))
                }

                val error = process.errorStream.bufferedReader().readText()
                process.waitFor(300, TimeUnit.SECONDS)

                if (process.exitValue() == 0) {
                    postSuccess(listener, "Migration successful!\n\nTarget app initialized its own engine.\nUser data (History, Bookmarks, Passwords, Extensions) injected safely.")
                } else {
                    postError(listener, "Migration failed.\n$error")
                }
            } catch (e: Exception) {
                postError(listener, "Error: ${e.message}")
            }
        }.start()
    }

    private fun buildExpertSafeScript(src: String, dst: String): String {
        return buildString {
            appendLine("SRC_PKG=\"$src\"; TGT_PKG=\"$dst\"")
            appendLine("SD=\"/data/data/\$SRC_PKG\"; TD=\"/data/data/\$TGT_PKG\"")
            
            // 1. Find Source Profile
            appendLine("echo STEP=Detecting_Source_Profile")
            appendLine("SRC_PROF=\"\"")
            appendLine("for d in \"\$SD/files/mozilla/\"*.default-release \"\$SD/files/mozilla/\"*.default; do [ -d \"\$d\" ] && { SRC_PROF=\"\$d\"; break; }; done")
            appendLine("[ -z \"\$SRC_PROF\" ] && { echo \"ERROR=Source profile not found\"; exit 1; }")
            appendLine("echo \"[INFO] Source profile: \$(basename \$SRC_PROF)\"")

            // 2. Stop Source & WAL Checkpoint
            appendLine("echo STEP=Preparing_Source")
            appendLine("am force-stop \"\$SRC_PKG\" 2>/dev/null; pkill -9 -f \"\$SRC_PKG\" 2>/dev/null; sleep 1")
            appendLine("find \"\$SRC_PROF\" -name '*.sqlite' -o -name '*.db' | while read db; do sqlite3 \"\$db\" \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>/dev/null; done")

            // 3. Clean and Start Target to create clean profile
            appendLine("echo STEP=Initializing_Target_Engine")
            appendLine("am force-stop \"\$TGT_PKG\" 2>/dev/null; pkill -9 -f \"\$TGT_PKG\" 2>/dev/null; sleep 1")
            appendLine("rm -rf \"\$TD/files/mozilla\" \"\$TD/files/mozac.session.storage\" \"\$TD/files/session_store\" \"\$TD/files/.snapshots\"")
            
            // Start app via monkey (generic way to launch)
            appendLine("monkey -p \"\$TGT_PKG\" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            appendLine("echo \"[INFO] Waiting for target app to create clean profile...\"")
            
            // Wait for profiles.ini
            appendLine("TGT_PROFILE=\"\"")
            appendLine("for i in \$(seq 1 30); do")
            appendLine("  if [ -f \"\$TD/files/mozilla/profiles.ini\" ]; then")
            appendLine("    for d in \"\$TD/files/mozilla/\"*.default-release \"\$TD/files/mozilla/\"*.default; do [ -d \"\$d\" ] && { TGT_PROFILE=\"\$d\"; break 2; }; done")
            appendLine("  fi")
            appendLine("  sleep 1")
            appendLine("done")
            appendLine("[ -z \"\$TGT_PROFILE\" ] && { echo \"ERROR=Target failed to init engine\"; exit 1; }")
            
            // Stop target after init
            appendLine("echo \"[  OK ] Target engine initialized: \$(basename \$TGT_PROFILE)\"")
            appendLine("sleep 2; am force-stop \"\$TGT_PKG\"; pkill -9 -f \"\$TGT_PKG\"; sleep 1; sync")

            // 4. Resolve IDs
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TD\" 2>/dev/null || dumpsys package \"\$TGT_PKG\" | grep -m1 'userId=' | grep -oE '[0-9]+' | head -1)")
            appendLine("SRC_PROF_NAME=\$(basename \"\$SRC_PROF\"); TGT_PROF_NAME=\$(basename \"\$TGT_PROFILE\")")

            // 5. Data Injection (ONLY user data, NO engine files)
            appendLine("echo STEP=Injecting_User_Data")
            val coreData = "places.sqlite favicons.sqlite cookies.sqlite logins.json key4.db cert9.db permissions.sqlite content-prefs.sqlite formhistory.sqlite signedInUser.json handlers.json"
            appendLine("for f in $coreData; do [ -f \"\$SRC_PROF/\$f\" ] && cp -f \"\$SRC_PROF/\$f\" \"\$TGT_PROFILE/\$f\"; done")
            
            // Extensions
            appendLine("echo \"[INFO] Moving Extensions...\"")
            appendLine("[ -d \"\$SRC_PROF/extensions\" ] && { rm -rf \"\$TGT_PROFILE/extensions\"; cp -a \"\$SRC_PROF/extensions\" \"\$TGT_PROFILE/\"; }")
            appendLine("[ -f \"\$SRC_PROF/extensions.json\" ] && cp -f \"\$SRC_PROF/extensions.json\" \"\$TGT_PROFILE/\"")
            appendLine("[ -d \"\$SRC_PROF/browser-extension-data\" ] && { rm -rf \"\$TGT_PROFILE/browser-extension-data\"; cp -a \"\$SRC_PROF/browser-extension-data\" \"\$TGT_PROFILE/\"; }")
            
            // 6. Path Patching
            appendLine("echo STEP=Patching_References")
            appendLine("find \"\$TGT_PROFILE\" -type f -maxdepth 1 \\( -name '*.json' -o -name '*.js' \\) | while read f; do")
            appendLine("  sed -i \"s|\$SRC_PKG|\$TGT_PKG|g\" \"\$f\" 2>/dev/null")
            appendLine("  sed -i \"s|\$SRC_PROF_NAME|\$TGT_PROF_NAME|g\" \"\$f\" 2>/dev/null")
            appendLine("done")

            // 7. Security Fix
            appendLine("echo STEP=Restoring_Security_Context")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TD/files/mozilla\"")
            appendLine("restorecon -RF \"\$TD/files/mozilla\"")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun postProgress(l: ProgressListener, m: String) { mainHandler.post { l.onProgress(m) } }
    private fun postSuccess(l: ProgressListener, m: String) { mainHandler.post { l.onSuccess(m) } }
    private fun postError(l: ProgressListener, m: String) { mainHandler.post { l.onError(m) } }
}
