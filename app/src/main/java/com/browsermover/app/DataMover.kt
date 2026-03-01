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
            appendLine("echo STEP=Debug")
            appendLine("echo \"ROOT_ID=\$(id)\"")
            appendLine("echo \"SELINUX=\$(getenforce 2>/dev/null || echo unknown)\"")
            appendLine("")

            // Find directories
            appendLine("echo STEP=Finding_directories")
            appendLine("SRCDIR=\"\"")
            appendLine("if [ -d \"/data/data/$srcPkg\" ]; then SRCDIR=\"/data/data/$srcPkg\"")
            appendLine("elif [ -d \"/data/user/0/$srcPkg\" ]; then SRCDIR=\"/data/user/0/$srcPkg\"; fi")
            appendLine("echo \"SRCDIR=\$SRCDIR\"")
            appendLine("")

            appendLine("DSTDIR=\"\"")
            appendLine("if [ -d \"/data/data/$dstPkg\" ]; then DSTDIR=\"/data/data/$dstPkg\"")
            appendLine("elif [ -d \"/data/user/0/$dstPkg\" ]; then DSTDIR=\"/data/user/0/$dstPkg\"; fi")
            appendLine("echo \"DSTDIR=\$DSTDIR\"")
            appendLine("")

            appendLine("if [ -z \"\$SRCDIR\" ]; then echo \"ERROR=Source directory not found\"; exit 1; fi")
            appendLine("if [ -z \"\$DSTDIR\" ]; then echo \"ERROR=Target directory not found\"; exit 1; fi")
            appendLine("")

            // Check source profile
            appendLine("echo STEP=Checking_source_profile")
            appendLine("if [ ! -d \"\$SRCDIR/files/mozilla\" ]; then")
            appendLine("  echo \"ERROR=No Firefox profile found in source\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("")

            // Find source profile dir
            appendLine("SRC_PROFILE_DIR=\$(ls -d \$SRCDIR/files/mozilla/*.default* 2>/dev/null | head -1)")
            appendLine("if [ -z \"\$SRC_PROFILE_DIR\" ]; then")
            appendLine("  SRC_PROFILE_DIR=\$(ls -d \$SRCDIR/files/mozilla/*/ 2>/dev/null | grep -v 'Crash' | head -1)")
            appendLine("fi")
            appendLine("if [ -z \"\$SRC_PROFILE_DIR\" ]; then")
            appendLine("  echo \"ERROR=No profile directory found in source\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("echo \"SRC_PROFILE=\$SRC_PROFILE_DIR\"")
            appendLine("")

            // Show source data
            appendLine("echo \"SRC_HAS_PLACES=\$(test -f \$SRC_PROFILE_DIR/places.sqlite && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_LOGINS=\$(test -f \$SRC_PROFILE_DIR/logins.json && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_COOKIES=\$(test -f \$SRC_PROFILE_DIR/cookies.sqlite && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_KEYS=\$(test -f \$SRC_PROFILE_DIR/key4.db && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_EXTENSIONS=\$(test -d \$SRC_PROFILE_DIR/extensions && echo YES || echo NO)\"")
            appendLine("")

            // Get owner BEFORE changes
            appendLine("echo STEP=Getting_owner")
            appendLine("UGROUP=\$(ls -ld \"\$DSTDIR\" | awk '{print \$3}')")
            appendLine("UGROUPG=\$(ls -ld \"\$DSTDIR\" | awk '{print \$4}')")
            appendLine("echo \"OWNER=\$UGROUP:\$UGROUPG\"")
            appendLine("")

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

            // ==========================================
            // CRITICAL: Delete ALL session/state files from TARGET
            // BEFORE copying anything
            // ==========================================
            appendLine("echo STEP=Clearing_target_session_data")
            appendLine("")

            // Android Components browser state (THIS IS THE CRASH CULPRIT)
            appendLine("rm -rf \"\$DSTDIR/files/.browser_state\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/.session_state\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/session.json\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/session.json.bak\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/session_state.json\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/snapshots\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/tab_state\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/recently_closed_tabs\" 2>/dev/null")
            appendLine("")

            // Also nuke any state files that may be anywhere in files/
            appendLine("find \"\$DSTDIR/files/\" -maxdepth 1 -name '*session*' -exec rm -rf {} \\; 2>/dev/null")
            appendLine("find \"\$DSTDIR/files/\" -maxdepth 1 -name '*state*' -exec rm -rf {} \\; 2>/dev/null")
            appendLine("find \"\$DSTDIR/files/\" -maxdepth 1 -name '*browser*' -exec rm -rf {} \\; 2>/dev/null")
            appendLine("")

            // Clear shared_prefs (may contain session references)
            appendLine("rm -rf \"\$DSTDIR/shared_prefs\" 2>/dev/null")
            appendLine("")

            // Clear any app databases that reference sessions
            appendLine("rm -f \"\$DSTDIR/databases/fenix_bookmarks.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/fenix_reader_view.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/fenix.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/focus.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/iceraven.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/browser.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/history.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/metrics.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/tabs.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/top_sites.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/recent_tabs.db\"* 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/databases/recently_closed.db\"* 2>/dev/null")
            appendLine("")

            // Clear GeckoView junk
            appendLine("rm -rf \"\$DSTDIR/files/mozilla/Crash Reports\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/files/mozilla/updates\" 2>/dev/null")
            appendLine("")

            // Nuke cache
            appendLine("rm -rf \"\$DSTDIR/cache\" 2>/dev/null")
            appendLine("rm -rf \"\$DSTDIR/code_cache\" 2>/dev/null")
            appendLine("")

            appendLine("echo SESSION_CLEAN=DONE")
            appendLine("")

            // ==========================================
            // Now copy ALL profiles to ensure profiles.ini match
            // ==========================================
            appendLine("echo STEP=Preparing_target_profiles")
            // Wipe target mozilla dir to ensure clean slate
            appendLine("rm -rf \"\$DSTDIR/files/mozilla\"")
            appendLine("mkdir -p \"\$DSTDIR/files/mozilla\"")
            appendLine("")
            
            // Copy profiles.ini (Crucial: maps profile names to dirs)
            appendLine("cp -a \"\$SRCDIR/files/mozilla/profiles.ini\" \"\$DSTDIR/files/mozilla/\" 2>/dev/null")
            
            // Define files to copy per profile
            val importantFiles = listOf(
                "places.sqlite", "places.sqlite-wal", "places.sqlite-shm",
                "cookies.sqlite", "cookies.sqlite-wal", "cookies.sqlite-shm",
                "logins.json", "key4.db", "cert9.db", "permissions.sqlite",
                "content-prefs.sqlite", "formhistory.sqlite", "protections.sqlite",
                "storage.sqlite", "webappsstore.sqlite",
                "favicons.sqlite", "favicons.sqlite-wal", "favicons.sqlite-shm",
                "prefs.js", "search.json.mozlz4", "handlers.json", "xulstore.json",
                "SiteSecurityServiceState.txt"
            )

            // Loop through all directories in Source mozilla folder
            appendLine("echo STEP=Copying_profiles")
            appendLine("find \"\$SRCDIR/files/mozilla\" -mindepth 1 -maxdepth 1 -type d -not -name 'Crash Reports' -not -name 'updates' -not -name 'extensions' | while read SRC_P_PATH; do")
            appendLine("  P_NAME=\$(basename \"\$SRC_P_PATH\")")
            appendLine("  DST_P_PATH=\"\$DSTDIR/files/mozilla/\$P_NAME\"")
            appendLine("  echo \"PROCESSING=\$P_NAME\"")
            appendLine("  mkdir -p \"\$DST_P_PATH\"")
            
            // Copy files
            for (f in importantFiles) {
                appendLine("  cp -a \"\$SRC_P_PATH/$f\" \"\$DST_P_PATH/\" 2>/dev/null")
            }
            
            // Copy directories
            appendLine("  cp -a \"\$SRC_P_PATH/extensions\" \"\$DST_P_PATH/\" 2>/dev/null")
            appendLine("  cp -a \"\$SRC_P_PATH/browser-extension-data\" \"\$DST_P_PATH/\" 2>/dev/null")
            appendLine("  cp -a \"\$SRC_P_PATH/storage\" \"\$DST_P_PATH/\" 2>/dev/null")
            
            // Cleanup and Patch
            appendLine("  rm -f \"\$DST_P_PATH/compatibility.ini\"")
            appendLine("  rm -f \"\$DST_P_PATH/lock\"")
            appendLine("  rm -f \"\$DST_P_PATH/.parentlock\"")
            
            // Patch prefs.js
            appendLine("  if [ -f \"\$DST_P_PATH/prefs.js\" ]; then")
            appendLine("    sed -i \"s|\$SRCDIR|\$DSTDIR|g\" \"\$DST_P_PATH/prefs.js\"")
            appendLine("    echo \"PREFS_FIXED=DONE\"")
            appendLine("  fi")
            appendLine("done")
            appendLine("")

            // Copy signedInUser.json if exists (Account Info)
            appendLine("cp -a \"\$SRCDIR/files/mozilla/signedInUser.json\" \"\$DSTDIR/files/mozilla/\" 2>/dev/null")
            appendLine("")

            // Verify
            appendLine("echo STEP=Verifying")
            appendLine("echo \"DST_MOZ_CONTENT=\$(ls \"\$DSTDIR/files/mozilla/\" 2>/dev/null)\"")
            appendLine("")
            
            // Final session cleanup in root
            appendLine("echo STEP=Final_session_cleanup")
            appendLine("rm -rf \"\$DSTDIR/files/.browser_state\" 2>/dev/null")
            appendLine("rm -f \"\$DSTDIR/files/session.json\" 2>/dev/null")
            appendLine("echo FINAL_CLEAN=DONE")
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

            // SELinux
            appendLine("echo STEP=SELinux")
            appendLine("restorecon -RF \"\$DSTDIR\" 2>/dev/null")
            appendLine("")
            
            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun buildChromiumScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("echo STEP=Debug")
            appendLine("echo \"ROOT_ID=\$(id)\"")
            appendLine("")

            appendLine("echo STEP=Finding_directories")
            appendLine("SRCDIR=\"\"")
            appendLine("if [ -d \"/data/data/$srcPkg\" ]; then SRCDIR=\"/data/data/$srcPkg\"")
            appendLine("elif [ -d \"/data/user/0/$srcPkg\" ]; then SRCDIR=\"/data/user/0/$srcPkg\"; fi")
            appendLine("echo \"SRCDIR=\$SRCDIR\"")
            appendLine("")

            appendLine("DSTDIR=\"\"")
            appendLine("if [ -d \"/data/data/$dstPkg\" ]; then DSTDIR=\"/data/data/$dstPkg\"")
            appendLine("elif [ -d \"/data/user/0/$dstPkg\" ]; then DSTDIR=\"/data/user/0/$dstPkg\"; fi")
            appendLine("echo \"DSTDIR=\$DSTDIR\"")
            appendLine("")

            appendLine("if [ -z \"\$SRCDIR\" ]; then echo \"ERROR=Source not found\"; exit 1; fi")
            appendLine("if [ -z \"\$DSTDIR\" ]; then echo \"ERROR=Target not found\"; exit 1; fi")
            appendLine("")

            appendLine("echo STEP=Getting_owner")
            appendLine("UGROUP=\$(ls -ld \"\$DSTDIR\" | awk '{print \$3}')")
            appendLine("UGROUPG=\$(ls -ld \"\$DSTDIR\" | awk '{print \$4}')")
            appendLine("echo \"OWNER=\$UGROUP:\$UGROUPG\"")
            appendLine("")

            appendLine("if [ -z \"\$UGROUP\" ] || [ \"\$UGROUP\" = \"root\" ]; then")
            appendLine("  DUMP_UID=\$(dumpsys package $dstPkg | grep 'userId=' | head -1 | sed 's/.*userId=//' | sed 's/[^0-9].*//')")
            appendLine("  if [ -n \"\$DUMP_UID\" ]; then")
            appendLine("    CALC=\$((\$DUMP_UID - 10000))")
            appendLine("    UGROUP=\"u0_a\$CALC\"")
            appendLine("    UGROUPG=\"u0_a\$CALC\"")
            appendLine("  fi")
            appendLine("fi")
            appendLine("")

            appendLine("echo STEP=Stopping_browsers")
            appendLine("am force-stop $srcPkg 2>/dev/null")
            appendLine("am force-stop $dstPkg 2>/dev/null")
            appendLine("sleep 2")
            appendLine("")

            if (backup) {
                appendLine("echo STEP=Backup")
                appendLine("mkdir -p /sdcard/BrowserDataMover/backups")
                appendLine("TIMESTAMP=\$(date +%s)")
                appendLine("tar -czf \"/sdcard/BrowserDataMover/backups/${dstPkg}_\$TIMESTAMP.tar.gz\" -C \"\$DSTDIR\" . 2>/dev/null && echo BACKUP=OK || echo BACKUP=FAIL")
                appendLine("")
            }

            appendLine("echo STEP=Clearing_target")
            appendLine("rm -rf \"\$DSTDIR\"/*")
            appendLine("")

            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRCDIR\"/* \"\$DSTDIR\"/ 2>&1 | tail -3")
            appendLine("echo COPY=DONE")
            appendLine("")

            appendLine("echo STEP=Fixing_ownership")
            appendLine("if [ -n \"\$UGROUP\" ] && [ \"\$UGROUP\" != \"root\" ]; then")
            appendLine("  chown -R \"\$UGROUP:\$UGROUPG\" \"\$DSTDIR\"")
            appendLine("  echo \"CHOWN=\$UGROUP\"")
            appendLine("else")
            appendLine("  echo CHOWN=SKIP")
            appendLine("fi")
            appendLine("")

            appendLine("echo STEP=SELinux")
            appendLine("restorecon -RF \"\$DSTDIR\" 2>/dev/null")
            appendLine("")

            appendLine("echo STEP=Verify")
            appendLine("echo \"DST_FILES=\$(ls \"\$DSTDIR\"/ 2>/dev/null | head -10)\"")
            appendLine("")

            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun parseOutput(output: String, error: String, source: BrowserInfo, target: BrowserInfo, listener: ProgressListener) {
        var srcDir = ""
        var dstDir = ""
        var hasError = false

        for (line in output.lines()) {
            val t = line.trim()
            if (t.isBlank()) continue

            when {
                t.startsWith("STEP=") -> postProgress(listener, t.removePrefix("STEP=").replace("_", " "))
                t.startsWith("ROOT_ID=") -> postProgress(listener, "Root: ${t.removePrefix("ROOT_ID=").take(60)}")
                t.startsWith("SELINUX=") -> postProgress(listener, "SELinux: ${t.removePrefix("SELINUX=")}")
                t.startsWith("SRCDIR=") -> { srcDir = t.removePrefix("SRCDIR="); postProgress(listener, "Source: $srcDir") }
                t.startsWith("DSTDIR=") -> { dstDir = t.removePrefix("DSTDIR="); postProgress(listener, "Target: $dstDir") }
                t.startsWith("OWNER=") -> postProgress(listener, "Owner: ${t.removePrefix("OWNER=")}")
                t.startsWith("OWNER_DUMP=") -> postProgress(listener, "Owner (UID): ${t.removePrefix("OWNER_DUMP=")}")
                t.startsWith("SRC_PROFILE=") -> postProgress(listener, "Source profile: ${t.removePrefix("SRC_PROFILE=")}")
                t.startsWith("DST_PROFILE=") -> postProgress(listener, "Target profile: ${t.removePrefix("DST_PROFILE=")}")
                t.startsWith("CREATED_PROFILE=") -> postProgress(listener, "Created: ${t.removePrefix("CREATED_PROFILE=")}")
                t.startsWith("SRC_HAS_") -> postProgress(listener, "Src ${t.substringBefore("=").removePrefix("SRC_HAS_")}: ${t.substringAfter("=")}")
                t.startsWith("DST_HAS_") -> postProgress(listener, "Dst ${t.substringBefore("=").removePrefix("DST_HAS_")}: ${t.substringAfter("=")}")
                t.startsWith("DST_PROFILE_FILES=") -> postProgress(listener, "Profile files: ${t.removePrefix("DST_PROFILE_FILES=")}")
                t.startsWith("DST_FILES=") -> postProgress(listener, "Target files: ${t.removePrefix("DST_FILES=")}")
                t.startsWith("DST_TOP_FILES=") -> postProgress(listener, "Target top: ${t.removePrefix("DST_TOP_FILES=")}")
                t.startsWith("DST_FILES_DIR=") -> postProgress(listener, "Target files/: ${t.removePrefix("DST_FILES_DIR=")}")
                t.startsWith("CHECK_") -> postProgress(listener, "✓ ${t.replace("=", ": ")}")
                t.startsWith("BACKUP=") -> postProgress(listener, if (t == "BACKUP=OK") "✅ Backup saved" else "⚠️ Backup failed")
                t == "COPY=DONE" -> postProgress(listener, "✅ Copy complete")
                t == "PREFS_FIXED=DONE" -> postProgress(listener, "✅ Patched absolute paths")
                t == "SESSION_CLEAN=DONE" -> postProgress(listener, "✅ Session data cleared")
                t == "FINAL_CLEAN=DONE" -> postProgress(listener, "✅ Final cleanup done")
                t == "EXTENSIONS=COPIED" -> postProgress(listener, "✅ Extensions copied")
                t == "EXT_DATA=COPIED" -> postProgress(listener, "✅ Extension data copied")
                t == "STORAGE=COPIED" -> postProgress(listener, "✅ Storage copied")
                t.startsWith("SESSION_SKIP=") -> postProgress(listener, "⏭️ Session files skipped")
                t.startsWith("REMOVING_") -> postProgress(listener, "🗑️ ${t.substringAfter("=")}")
                t.startsWith("CHOWN=") -> {
                    val v = t.removePrefix("CHOWN=")
                    postProgress(listener, if (v == "SKIP") "⚠️ Ownership skipped" else "✅ Owner: $v")
                }
                t.startsWith("ERROR=") -> { hasError = true; postError(listener, t.removePrefix("ERROR=")) }
                t.startsWith("DEBUG_") -> postProgress(listener, t)
                t == "TRANSFER_COMPLETE" -> {
                    if (!hasError) {
                        val items = mutableListOf<String>()
                        if (output.contains("DST_HAS_PLACES=YES")) items.add("Bookmarks & History")
                        if (output.contains("DST_HAS_LOGINS=YES")) items.add("Saved Logins")
                        if (output.contains("DST_HAS_COOKIES=YES")) items.add("Cookies")
                        if (output.contains("DST_HAS_KEYS=YES")) items.add("Encryption Keys")
                        if (output.contains("EXTENSIONS=COPIED")) items.add("Extensions")
                        if (output.contains("EXT_DATA=COPIED")) items.add("Extension Data")

                        postSuccess(listener,
                            "Transfer successful!\n\n" +
                            "From: ${source.name}\n\n" +
                            "To: ${target.name}\n\n" +
                            "Transferred:\n${items.joinToString("\n") { "• $it" }}\n\n" +
                            "Note: Open tabs were not transferred (prevents crashes).\n\n" +
                            "You can now open ${target.name}.")
                    }
                    return
                }
            }
        }

        if (!hasError) {
            postError(listener, "Transfer may have failed.\n\nOutput:\n${output.take(2000)}\n\nErrors:\n${error.take(500)}")
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
