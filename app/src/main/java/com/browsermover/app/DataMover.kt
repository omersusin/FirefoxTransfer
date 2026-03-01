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

            // Validate
            appendLine("if [ -z \"\$SRCDIR\" ]; then echo \"ERROR=Source directory not found\"; exit 1; fi")
            appendLine("if [ -z \"\$DSTDIR\" ]; then echo \"ERROR=Target directory not found\"; exit 1; fi")
            appendLine("")

            // Check source has mozilla profile
            appendLine("echo STEP=Checking_source_profile")
            appendLine("if [ ! -d \"\$SRCDIR/files/mozilla\" ]; then")
            appendLine("  echo \"ERROR=No Firefox profile found in source. Open ${srcPkg} first.\"")
            appendLine("  echo \"DEBUG_LS=\$(ls -la \$SRCDIR/files/ 2>&1 | head -10)\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("")

            // Show source profile info
            appendLine("echo \"SRC_PROFILES=\$(ls \$SRCDIR/files/mozilla/ 2>/dev/null)\"")
            appendLine("SRC_PROFILE_DIR=\$(ls -d \$SRCDIR/files/mozilla/*.default* 2>/dev/null | head -1)")
            appendLine("if [ -z \"\$SRC_PROFILE_DIR\" ]; then")
            appendLine("  SRC_PROFILE_DIR=\$(ls -d \$SRCDIR/files/mozilla/*/ 2>/dev/null | grep -v 'Crash' | head -1)")
            appendLine("fi")
            appendLine("echo \"SRC_PROFILE=\$SRC_PROFILE_DIR\"")
            appendLine("")

            // Show what data exists
            appendLine("echo \"SRC_HAS_PLACES=\$(test -f \$SRC_PROFILE_DIR/places.sqlite && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_LOGINS=\$(test -f \$SRC_PROFILE_DIR/logins.json && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_COOKIES=\$(test -f \$SRC_PROFILE_DIR/cookies.sqlite && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_KEYS=\$(test -f \$SRC_PROFILE_DIR/key4.db && echo YES || echo NO)\"")
            appendLine("echo \"SRC_HAS_EXTENSIONS=\$(test -d \$SRC_PROFILE_DIR/extensions && echo YES || echo NO)\"")
            appendLine("")

            // Get target owner BEFORE changes
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

            // Find or create target mozilla dir
            appendLine("echo STEP=Preparing_target_profile")
            appendLine("mkdir -p \"\$DSTDIR/files/mozilla\"")
            appendLine("")

            // Find target profile dir
            appendLine("DST_PROFILE_DIR=\$(ls -d \$DSTDIR/files/mozilla/*.default* 2>/dev/null | head -1)")
            appendLine("if [ -z \"\$DST_PROFILE_DIR\" ]; then")
            appendLine("  DST_PROFILE_DIR=\$(ls -d \$DSTDIR/files/mozilla/*/ 2>/dev/null | grep -v 'Crash' | head -1)")
            appendLine("fi")
            appendLine("")

            // If target has no profile, use source profile name
            appendLine("if [ -z \"\$DST_PROFILE_DIR\" ]; then")
            appendLine("  SRC_PROFILE_NAME=\$(basename \$SRC_PROFILE_DIR)")
            appendLine("  DST_PROFILE_DIR=\"\$DSTDIR/files/mozilla/\$SRC_PROFILE_NAME\"")
            appendLine("  mkdir -p \"\$DST_PROFILE_DIR\"")
            appendLine("  echo \"CREATED_PROFILE=\$DST_PROFILE_DIR\"")
            appendLine("fi")
            appendLine("echo \"DST_PROFILE=\$DST_PROFILE_DIR\"")
            appendLine("")

            // Clear target profile content (keep the dir)
            appendLine("echo STEP=Clearing_target_profile")
            appendLine("rm -rf \"\$DST_PROFILE_DIR\"/*")
            appendLine("")

            // Copy ONLY the profile data files (not session files)
            appendLine("echo STEP=Copying_profile_data")
            appendLine("")

            // List of important files to copy
            val importantFiles = listOf(
                "places.sqlite", "places.sqlite-wal", "places.sqlite-shm",
                "cookies.sqlite", "cookies.sqlite-wal", "cookies.sqlite-shm",
                "logins.json",
                "key4.db",
                "cert9.db",
                "permissions.sqlite",
                "content-prefs.sqlite",
                "formhistory.sqlite",
                "protections.sqlite",
                "storage.sqlite",
                "webappsstore.sqlite",
                "favicons.sqlite", "favicons.sqlite-wal", "favicons.sqlite-shm",
                "prefs.js",
                "search.json.mozlz4",
                "handlers.json",
                "xulstore.json",
                "SiteSecurityServiceState.txt",
                "addonStartup.json.lz4",
                "compatibility.ini"
            )

            for (f in importantFiles) {
                appendLine("cp -a \"\$SRC_PROFILE_DIR/$f\" \"\$DST_PROFILE_DIR/\" 2>/dev/null")
            }
            appendLine("")

            // Copy extensions directory
            appendLine("if [ -d \"\$SRC_PROFILE_DIR/extensions\" ]; then")
            appendLine("  cp -a \"\$SRC_PROFILE_DIR/extensions\" \"\$DST_PROFILE_DIR/\"")
            appendLine("  echo EXTENSIONS=COPIED")
            appendLine("fi")
            appendLine("")

            // Copy extension storage
            appendLine("if [ -d \"\$SRC_PROFILE_DIR/browser-extension-data\" ]; then")
            appendLine("  cp -a \"\$SRC_PROFILE_DIR/browser-extension-data\" \"\$DST_PROFILE_DIR/\"")
            appendLine("  echo EXT_DATA=COPIED")
            appendLine("fi")
            appendLine("")

            // Copy storage (for extensions)
            appendLine("if [ -d \"\$SRC_PROFILE_DIR/storage\" ]; then")
            appendLine("  cp -a \"\$SRC_PROFILE_DIR/storage\" \"\$DST_PROFILE_DIR/\"")
            appendLine("  echo STORAGE=COPIED")
            appendLine("fi")
            appendLine("")

            // Copy profiles.ini and adjust
            appendLine("cp -a \"\$SRCDIR/files/mozilla/profiles.ini\" \"\$DSTDIR/files/mozilla/\" 2>/dev/null")
            appendLine("cp -a \"\$SRCDIR/files/mozilla/installs.ini\" \"\$DSTDIR/files/mozilla/\" 2>/dev/null")
            appendLine("")

            // DO NOT copy these (they cause crashes):
            // - sessionstore.jsonlz4
            // - sessionstore-backups/
            // - .browser_state/
            // - session.json
            // - shared_prefs/
            appendLine("echo SESSION_SKIP=Skipped_session_files_intentionally")
            appendLine("")

            // Verify copied data
            appendLine("echo STEP=Verifying_copied_data")
            appendLine("echo \"DST_HAS_PLACES=\$(test -f \$DST_PROFILE_DIR/places.sqlite && echo YES || echo NO)\"")
            appendLine("echo \"DST_HAS_LOGINS=\$(test -f \$DST_PROFILE_DIR/logins.json && echo YES || echo NO)\"")
            appendLine("echo \"DST_HAS_COOKIES=\$(test -f \$DST_PROFILE_DIR/cookies.sqlite && echo YES || echo NO)\"")
            appendLine("echo \"DST_HAS_KEYS=\$(test -f \$DST_PROFILE_DIR/key4.db && echo YES || echo NO)\"")
            appendLine("echo \"DST_PROFILE_FILES=\$(ls \$DST_PROFILE_DIR/ 2>/dev/null | head -15)\"")
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

            appendLine("echo TRANSFER_COMPLETE")
        }
    }

    private fun buildChromiumScript(srcPkg: String, dstPkg: String, backup: Boolean): String {
        return buildString {
            appendLine("echo STEP=Debug")
            appendLine("echo \"ROOT_ID=\$(id)\"")
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

            appendLine("if [ -z \"\$SRCDIR\" ]; then echo \"ERROR=Source not found\"; exit 1; fi")
            appendLine("if [ -z \"\$DSTDIR\" ]; then echo \"ERROR=Target not found\"; exit 1; fi")
            appendLine("")

            // Get owner
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

            // Stop
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

            // Full copy for Chromium (they're more compatible)
            appendLine("echo STEP=Clearing_target")
            appendLine("rm -rf \"\$DSTDIR\"/*")
            appendLine("")

            appendLine("echo STEP=Copying_data")
            appendLine("cp -a \"\$SRCDIR\"/* \"\$DSTDIR\"/ 2>&1 | tail -3")
            appendLine("echo COPY=DONE")
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

            // Verify
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
                t.startsWith("SRC_PROFILES=") -> postProgress(listener, "Profiles: ${t.removePrefix("SRC_PROFILES=")}")
                t.startsWith("SRC_PROFILE=") -> postProgress(listener, "Source profile: ${t.removePrefix("SRC_PROFILE=")}")
                t.startsWith("DST_PROFILE=") -> postProgress(listener, "Target profile: ${t.removePrefix("DST_PROFILE=")}")
                t.startsWith("CREATED_PROFILE=") -> postProgress(listener, "Created profile: ${t.removePrefix("CREATED_PROFILE=")}")
                t.startsWith("SRC_HAS_") -> {
                    val key = t.substringBefore("=").removePrefix("SRC_HAS_")
                    val value = t.substringAfter("=")
                    postProgress(listener, "Source $key: $value")
                }
                t.startsWith("DST_HAS_") -> {
                    val key = t.substringBefore("=").removePrefix("DST_HAS_")
                    val value = t.substringAfter("=")
                    postProgress(listener, "Target $key: $value")
                }
                t.startsWith("DST_PROFILE_FILES=") -> postProgress(listener, "Copied files: ${t.removePrefix("DST_PROFILE_FILES=").take(150)}")
                t.startsWith("DST_FILES=") -> postProgress(listener, "Target files: ${t.removePrefix("DST_FILES=").take(100)}")
                t.startsWith("BACKUP=") -> postProgress(listener, if (t == "BACKUP=OK") "✅ Backup saved" else "⚠️ Backup failed")
                t == "COPY=DONE" -> postProgress(listener, "✅ Copy complete")
                t == "EXTENSIONS=COPIED" -> postProgress(listener, "✅ Extensions copied")
                t == "EXT_DATA=COPIED" -> postProgress(listener, "✅ Extension data copied")
                t == "STORAGE=COPIED" -> postProgress(listener, "✅ Storage copied")
                t.startsWith("SESSION_SKIP=") -> postProgress(listener, "⏭️ Session files skipped (prevents crash)")
                t.startsWith("CHOWN=") -> {
                    val v = t.removePrefix("CHOWN=")
                    postProgress(listener, if (v == "SKIP") "⚠️ Ownership skipped" else "✅ Owner: $v")
                }
                t.startsWith("ERROR=") -> {
                    hasError = true
                    postError(listener, t.removePrefix("ERROR="))
                }
                t.startsWith("DEBUG_") -> postProgress(listener, t)
                t == "TRANSFER_COMPLETE" -> {
                    if (!hasError) {
                        val transferredItems = mutableListOf<String>()
                        if (output.contains("DST_HAS_PLACES=YES")) transferredItems.add("Bookmarks & History")
                        if (output.contains("DST_HAS_LOGINS=YES")) transferredItems.add("Saved Logins")
                        if (output.contains("DST_HAS_COOKIES=YES")) transferredItems.add("Cookies")
                        if (output.contains("DST_HAS_KEYS=YES")) transferredItems.add("Encryption Keys")
                        if (output.contains("EXTENSIONS=COPIED")) transferredItems.add("Extensions")

                        postSuccess(listener,
                            "Transfer successful!\n\n" +
                            "From: ${source.name}\n($srcDir)\n\n" +
                            "To: ${target.name}\n($dstDir)\n\n" +
                            "Transferred:\n${transferredItems.joinToString("\n") { "• $it" }}\n\n" +
                            "Skipped (prevents crashes):\n• Open tabs / Session data\n• App preferences\n\n" +
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
