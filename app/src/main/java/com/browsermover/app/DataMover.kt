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

                postProgress(listener, "Strategy: Full Extension Migration (UUID Sync)")
                postProgress(listener, "Clone: $src -> $dst")

                val script = buildCompleteExtensionScript(src, dst)
                val process = Runtime.getRuntime().exec(RootHelper.getSuMethod())
                val os = DataOutputStream(process.outputStream)
                os.writeBytes(script)
                os.writeBytes("exit\n")
                os.flush()
                os.close()

                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (stdout.readLine().also { line = it } != null) {
                    if (line!!.startsWith("STEP=")) postProgress(listener, line!!.removePrefix("STEP=").replace("_", " "))
                    if (line!!.startsWith("[INFO]")) postProgress(listener, line!!)
                    if (line!!.startsWith("[  OK ]")) postProgress(listener, "✅ " + line!!.substring(7))
                    if (line!!.startsWith("[WARN]")) postProgress(listener, "⚠️ " + line!!.substring(7))
                }

                val error = process.errorStream.bufferedReader().readText()
                process.waitFor(300, TimeUnit.SECONDS)

                if (process.exitValue() == 0) {
                    postSuccess(listener, "Migration successful!\n\nExtensions should appear after 15s.\nHistory and Account preserved.")
                } else {
                    postError(listener, "Migration failed.\n$error")
                }
            } catch (e: Exception) {
                postError(listener, "Error: ${e.message}")
            }
        }.start()
    }

    private fun buildCompleteExtensionScript(src: String, dst: String): String {
        return buildString {
            appendLine("SRC_PKG=\"$src\"; TGT_PKG=\"$dst\"")
            appendLine("SD=\"/data/data/\$SRC_PKG\"; TD=\"/data/data/\$TGT_PKG\"")
            
            // 1. Detect Source Profile
            appendLine("echo STEP=Detecting_Source")
            appendLine("SRC_PROF=\"\"")
            appendLine("for d in \"\$SD/files/mozilla/\"*.default-release \"\$SD/files/mozilla/\"*.default; do [ -d \"\$d\" ] && { SRC_PROF=\"\$d\"; break; }; done")
            appendLine("[ -z \"\$SRC_PROF\" ] && { echo \"ERROR=Source profile not found\"; exit 1; }")

            // 2. Prepare Source
            appendLine("am force-stop \"\$SRC_PKG\" 2>/dev/null; pkill -9 -f \"\$SRC_PKG\" 2>/dev/null")
            appendLine("find \"\$SRC_PROF\" -name '*.sqlite' -o -name '*.db' | while read db; do sqlite3 \"\$db\" \"PRAGMA wal_checkpoint(TRUNCATE);\" 2>/dev/null; done")

            // 3. Init Target (Clean Init Strategy)
            appendLine("echo STEP=Initializing_Target")
            appendLine("am force-stop \"\$TGT_PKG\" 2>/dev/null; pkill -9 -f \"\$TGT_PKG\" 2>/dev/null")
            appendLine("rm -rf \"\$TD/files/mozilla\" \"\$TD/files/mozac.session.storage\"")
            
            // Launch to create profile
            appendLine("monkey -p \"\$TGT_PKG\" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
            
            appendLine("TGT_PROFILE=\"\"")
            appendLine("for i in \$(seq 1 30); do")
            appendLine("  if [ -f \"\$TD/files/mozilla/profiles.ini\" ]; then")
            appendLine("    for d in \"\$TD/files/mozilla/\"*.default-release \"\$TD/files/mozilla/\"*.default; do [ -d \"\$d\" ] && { TGT_PROFILE=\"\$d\"; break 2; }; done")
            appendLine("  fi")
            appendLine("  sleep 1")
            appendLine("done")
            appendLine("[ -z \"\$TGT_PROFILE\" ] && { echo \"ERROR=Target failed to init\"; exit 1; }")
            
            appendLine("sleep 2; am force-stop \"\$TGT_PKG\"; pkill -9 -f \"\$TGT_PKG\"; sleep 1; sync")

            // 4. IDs
            appendLine("TGT_UID=\$(stat -c '%u' \"\$TD\" 2>/dev/null || dumpsys package \"\$TGT\" | grep -m1 'userId=' | grep -oE '[0-9]+' | head -1)")
            appendLine("SRC_PROF_NAME=\$(basename \"\$SRC_PROF\"); TGT_PROF_NAME=\$(basename \"\$TGT_PROFILE\")")

            // 5. Inject User Data (History, Bookmarks, Passwords)
            appendLine("echo STEP=Injecting_Core_Data")
            val coreData = "places.sqlite favicons.sqlite cookies.sqlite logins.json key4.db cert9.db permissions.sqlite content-prefs.sqlite signedInUser.json handlers.json"
            appendLine("for f in $coreData; do [ -f \"\$SRC_PROF/\$f\" ] && cp -f \"\$SRC_PROF/\$f\" \"\$TGT_PROFILE/\$f\"; done")
            
            // 6. EXTENSION MIGRATION START
            appendLine("echo STEP=Migrating_Extensions")
            
            // 6a. Copy assets
            appendLine("[ -d \"\$SRC_PROF/extensions\" ] && { rm -rf \"\$TGT_PROFILE/extensions\"; cp -a \"\$SRC_PROF/extensions\" \"\$TGT_PROFILE/\"; }")
            appendLine("[ -f \"\$SRC_PROF/extensions.json\" ] && cp -f \"\$SRC_PROF/extensions.json\" \"\$TGT_PROFILE/\"")
            appendLine("[ -f \"\$SRC_PROF/extension-preferences.json\" ] && cp -f \"\$SRC_PROF/extension-preferences.json\" \"\$TGT_PROFILE/\"")
            appendLine("[ -f \"\$SRC_PROF/extension-settings.json\" ] && cp -f \"\$SRC_PROF/extension-settings.json\" \"\$TGT_PROFILE/\"")
            appendLine("[ -d \"\$SRC_PROF/browser-extension-data\" ] && { rm -rf \"\$TGT_PROFILE/browser-extension-data\"; cp -a \"\$SRC_PROF/browser-extension-data\" \"\$TGT_PROFILE/\"; }")
            
            // 6b. Copy Extension Storage (IndexedDB)
            appendLine("mkdir -p \"\$TGT_PROFILE/storage/default\"")
            appendLine("for d in \"\$SRC_PROF/storage/default/moz-extension\"*; do [ -d \"\$d\" ] && cp -a \"\$d\" \"\$TGT_PROFILE/storage/default/\"; done")
            appendLine("[ -f \"\$SRC_PROF/storage.sqlite\" ] && cp -f \"\$SRC_PROF/storage.sqlite\" \"\$TGT_PROFILE/\"")

            // 7. PREFS.JS INJECTION (UUID Sync)
            appendLine("echo STEP=Syncing_UUIDs")
            
            // Extract UUID mapping and migration flags
            val uuidPattern = "extensions\\.webextensions\\.uuids\\|extensions\\.webextensions\\.ExtensionStorageIDB\\|extensions\\.activeAddons\\|extensions\\.enabledAddons"
            appendLine("EXT_PREFS=\"/data/local/tmp/ext_uuid_sync.js\"")
            appendLine("grep \"user_pref(\\\"\$uuidPattern\" \"\$SRC_PROF/prefs.js\" > \"\$EXT_PREFS\" 2>/dev/null || true")
            
            appendLine("if [ -s \"\$EXT_PREFS\" ]; then")
            // Clean target prefs of conflicting entries
            appendLine("  grep -v \"user_pref(\\\"\$uuidPattern\" \"\$TGT_PROFILE/prefs.js\" > \"\$TGT_PROFILE/prefs.js.tmp\"")
            appendLine("  mv \"\$TGT_PROFILE/prefs.js.tmp\" \"\$TGT_PROFILE/prefs.js\"")
            // Append source entries
            appendLine("  echo \"\" >> \"\$TGT_PROFILE/prefs.js\"")
            appendLine("  echo \"// Migrated Extensions\" >> \"\$TGT_PROFILE/prefs.js\"")
            appendLine("  cat \"\$EXT_PREFS\" >> \"\$TGT_PROFILE/prefs.js\"")
            appendLine("  echo \"[  OK ] UUIDs injected.\"")
            appendLine("fi")
            appendLine("rm -f \"\$EXT_PREFS\"")

            // 8. PATH PATCHING (extensions.json)
            appendLine("echo STEP=Patching_Paths")
            // Need to escape dots for sed regex
            appendLine("SRC_PKG_RE=\$(echo \"\$SRC_PKG\" | sed 's/\\./\\\\./g')")
            appendLine("SRC_PROF_RE=\$(echo \"\$SRC_PROF_NAME\" | sed 's/\\./\\\\./g')")
            
            appendLine("sed -i -e \"s|\$SRC_PKG_RE|\$TGT_PKG|g\" -e \"s|\$SRC_PROF_RE|\$TGT_PROF_NAME|g\" \"\$TGT_PROFILE/extensions.json\"")
            appendLine("[ -f \"\$TGT_PROFILE/extension-settings.json\" ] && sed -i -e \"s|\$SRC_PKG_RE|\$TGT_PKG|g\" -e \"s|\$SRC_PROF_RE|\$TGT_PROF_NAME|g\" \"\$TGT_PROFILE/extension-settings.json\"")

            // 9. FINAL CLEANUP (Force Rebuild)
            appendLine("echo STEP=Clearing_Cache")
            // DELETE addonStartup.json.lz4 so Gecko rebuilds it from our patched extensions.json + prefs.js
            appendLine("rm -f \"\$TGT_PROFILE/addonStartup.json.lz4\"")
            appendLine("rm -rf \"\$TGT_PROFILE/startupCache\"")
            
            // Delete Android Components extension cache
            appendLine("rm -f \"\$TD/shared_prefs/mozac_web_extensions.xml\"")
            appendLine("rm -f \"\$TD/shared_prefs/mozac_feature_addons_installed.xml\"")

            // 10. Security
            appendLine("echo STEP=Securing_Files")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TD/files/mozilla\"")
            appendLine("chown -R \"\$TGT_UID:\$TGT_UID\" \"\$TD/shared_prefs\"")
            appendLine("restorecon -RF \"\$TD/files/mozilla\"")
            appendLine("restorecon -RF \"\$TD/shared_prefs\"")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun postProgress(l: ProgressListener, m: String) { mainHandler.post { l.onProgress(m) } }
    private fun postSuccess(l: ProgressListener, m: String) { mainHandler.post { l.onSuccess(m) } }
    private fun postError(l: ProgressListener, m: String) { mainHandler.post { l.onError(m) } }
}
