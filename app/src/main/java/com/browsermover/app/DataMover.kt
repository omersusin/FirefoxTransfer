package com.browsermover.app

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class DataMover {

    companion object {
        private const val BACKUP_DIR = "/sdcard/BrowserDataMover/backups"
    }

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
                postProgress(listener, "Starting transfer...")
                postProgress(listener, "From: ${source.name} (${source.packageName})")
                postProgress(listener, "To: ${target.name} (${target.packageName})")

                val srcPkg = source.packageName
                val dstPkg = target.packageName

                // ALL commands in ONE su session
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)

                fun w(s: String) = os.writeBytes(s + "\n")

                // ---- Find source dir ----
                w("echo STEP_FIND_SRC")
                w("SRC_DIR=\"\"")
                w("for d in /data/user/0/$srcPkg /data/data/$srcPkg; do")
                w("  if [ -d \"\$d\" ]; then SRC_DIR=\"\$d\"; break; fi")
                w("done")
                w("echo SRCDIR=\$SRC_DIR")

                // ---- Find target dir ----
                w("echo STEP_FIND_DST")
                w("DST_DIR=\"\"")
                w("for d in /data/user/0/$dstPkg /data/data/$dstPkg; do")
                w("  if [ -d \"\$d\" ]; then DST_DIR=\"\$d\"; break; fi")
                w("done")
                w("echo DSTDIR=\$DST_DIR")

                // ---- Validate ----
                w("if [ -z \"\$SRC_DIR\" ]; then echo ERROR_NO_SRC; exit 1; fi")
                w("if [ -z \"\$DST_DIR\" ]; then echo ERROR_NO_DST; exit 1; fi")

                // ---- Show source contents (debug) ----
                w("echo SRC_CONTENT_START")
                w("ls \$SRC_DIR/ 2>&1 | head -20")
                w("echo SRC_CONTENT_END")

                // ---- Get owner BEFORE any changes ----
                w("echo STEP_GET_OWNER")
                w("UGROUP=\$(ls -ld \$DST_DIR | awk '{print \$3}')")
                w("UGROUPG=\$(ls -ld \$DST_DIR | awk '{print \$4}')")
                w("echo OWNER=\$UGROUP:\$UGROUPG")

                // ---- If owner is root or empty, try dumpsys ----
                w("if [ -z \"\$UGROUP\" ] || [ \"\$UGROUP\" = \"root\" ]; then")
                w("  DUMP_UID=\$(dumpsys package $dstPkg | grep 'userId=' | head -1 | sed 's/.*userId=//' | sed 's/[^0-9].*//')")
                w("  if [ -n \"\$DUMP_UID\" ]; then")
                w("    CALC=\$((\$DUMP_UID - 10000))")
                w("    UGROUP=\"u0_a\$CALC\"")
                w("    UGROUPG=\"u0_a\$CALC\"")
                w("    echo OWNER_FROM_DUMP=\$UGROUP")
                w("  fi")
                w("fi")

                // ---- Stop both apps ----
                w("echo STEP_STOP")
                w("am force-stop $srcPkg 2>/dev/null")
                w("am force-stop $dstPkg 2>/dev/null")
                w("sleep 2")

                // ---- Backup ----
                if (backupFirst) {
                    w("echo STEP_BACKUP")
                    w("mkdir -p $BACKUP_DIR")
                    w("TS=\$(date +%s)")
                    w("tar -czf $BACKUP_DIR/${dstPkg}_\$TS.tar.gz -C \$DST_DIR . 2>/dev/null && echo BACKUP_OK || echo BACKUP_FAIL")
                }

                // ---- Clear target ----
                w("echo STEP_CLEAR")
                w("rm -rf \$DST_DIR/*")

                // ---- Copy data ----
                w("echo STEP_COPY")
                w("cp -a \$SRC_DIR/* \$DST_DIR/ 2>&1 | tail -5")
                w("echo COPY_DONE")

                // ---- Fix ownership ----
                w("echo STEP_CHOWN")
                w("if [ -n \"\$UGROUP\" ] && [ \"\$UGROUP\" != \"root\" ]; then")
                w("  chown -R \$UGROUP:\$UGROUPG \$DST_DIR")
                w("  echo CHOWN_OK=\$UGROUP")
                w("else")
                w("  echo CHOWN_SKIP")
                w("fi")

                // ---- SELinux ----
                w("echo STEP_SELINUX")
                w("restorecon -RF \$DST_DIR 2>/dev/null")

                // ---- Verify ----
                w("echo STEP_VERIFY")
                w("echo DST_CONTENT_START")
                w("ls \$DST_DIR/ 2>&1 | head -15")
                w("echo DST_CONTENT_END")

                w("echo TRANSFER_COMPLETE")
                w("exit")
                os.flush()
                os.close()

                // Read output
                val stdout = BufferedReader(InputStreamReader(process.inputStream))
                val stderr = BufferedReader(InputStreamReader(process.errorStream))
                val output = stdout.readText()
                val error = stderr.readText()
                stdout.close()
                stderr.close()
                process.waitFor(180, TimeUnit.SECONDS)

                // Parse output
                var srcDir = ""
                var dstDir = ""
                val srcFiles = mutableListOf<String>()
                val dstFiles = mutableListOf<String>()
                var inSrcContent = false
                var inDstContent = false

                for (line in output.lines()) {
                    if (line.isBlank()) continue

                    when {
                        line.startsWith("SRCDIR=") -> {
                            srcDir = line.removePrefix("SRCDIR=")
                            postProgress(listener, "Source: $srcDir")
                        }
                        line.startsWith("DSTDIR=") -> {
                            dstDir = line.removePrefix("DSTDIR=")
                            postProgress(listener, "Target: $dstDir")
                        }
                        line == "ERROR_NO_SRC" -> {
                            postError(listener,
                                "Source data directory not found!\n\n" +
                                "Package: $srcPkg\n\n" +
                                "Please open ${source.name} at least once first.")
                            return@Thread
                        }
                        line == "ERROR_NO_DST" -> {
                            postError(listener,
                                "Target data directory not found!\n\n" +
                                "Package: $dstPkg\n\n" +
                                "Please open ${target.name} once, close it, then try again.")
                            return@Thread
                        }
                        line == "SRC_CONTENT_START" -> inSrcContent = true
                        line == "SRC_CONTENT_END" -> {
                            inSrcContent = false
                            postProgress(listener, "Source data (${srcFiles.size} items): ${srcFiles.take(5).joinToString(", ")}")
                        }
                        inSrcContent -> srcFiles.add(line)
                        line.startsWith("OWNER=") -> postProgress(listener, "Original owner: ${line.removePrefix("OWNER=")}")
                        line.startsWith("OWNER_FROM_DUMP=") -> postProgress(listener, "Owner via UID: ${line.removePrefix("OWNER_FROM_DUMP=")}")
                        line == "STEP_STOP" -> postProgress(listener, "Stopping browsers...")
                        line == "STEP_BACKUP" -> postProgress(listener, "Creating backup...")
                        line == "BACKUP_OK" -> postProgress(listener, "✅ Backup saved")
                        line == "BACKUP_FAIL" -> postProgress(listener, "⚠️ Backup failed, continuing...")
                        line == "STEP_CLEAR" -> postProgress(listener, "Clearing target...")
                        line == "STEP_COPY" -> postProgress(listener, "Copying data... (please wait)")
                        line == "COPY_DONE" -> postProgress(listener, "Copy complete")
                        line == "STEP_CHOWN" -> postProgress(listener, "Fixing ownership...")
                        line.startsWith("CHOWN_OK=") -> postProgress(listener, "✅ Owner set: ${line.removePrefix("CHOWN_OK=")}")
                        line == "CHOWN_SKIP" -> postProgress(listener, "⚠️ Could not determine owner")
                        line == "STEP_SELINUX" -> postProgress(listener, "Fixing SELinux...")
                        line == "DST_CONTENT_START" -> inDstContent = true
                        line == "DST_CONTENT_END" -> {
                            inDstContent = false
                            postProgress(listener, "Target after transfer (${dstFiles.size} items): ${dstFiles.take(5).joinToString(", ")}")
                        }
                        inDstContent -> dstFiles.add(line)
                        line == "TRANSFER_COMPLETE" -> {
                            postSuccess(listener,
                                "Transfer successful!\n\n" +
                                "From: ${source.name}\n($srcDir)\n\n" +
                                "To: ${target.name}\n($dstDir)\n\n" +
                                "You can now open ${target.name}.")
                            return@Thread
                        }
                    }
                }

                if (!output.contains("TRANSFER_COMPLETE")) {
                    postError(listener,
                        "Transfer may have failed.\n\n" +
                        "Shell output:\n${output.take(1500)}\n\n" +
                        "Errors:\n${error.take(500)}")
                }

            } catch (e: Exception) {
                postError(listener, "Error: ${e.message}")
            }
        }.start()
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
