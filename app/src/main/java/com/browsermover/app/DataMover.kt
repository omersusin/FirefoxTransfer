package com.browsermover.app

import android.os.Handler
import android.os.Looper

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

    private fun findDataDir(packageName: String): String? {
        // Method 1: dumpsys package -> dataDir
        val dump = RootHelper.executeCommand(
            "dumpsys package $packageName | grep 'dataDir=' | head -1"
        )
        if (dump.success && dump.output.contains("dataDir=")) {
            val path = dump.output.substringAfter("dataDir=").trim()
            if (path.isNotBlank()) {
                val check = RootHelper.executeCommand("test -d '$path' && echo YES || echo NO")
                if (check.output.contains("YES")) return path
            }
        }

        // Method 2: Try common paths
        val paths = listOf(
            "/data/user/0/$packageName",
            "/data/data/$packageName",
            "/data/user_de/0/$packageName",
            "/mnt/expand/*/user/0/$packageName"
        )

        for (path in paths) {
            val check = RootHelper.executeCommand("test -d '$path' && echo YES || echo NO")
            if (check.output.contains("YES")) return path
        }

        // Method 3: Find with wildcard
        val findResult = RootHelper.executeCommand(
            "find /data -maxdepth 4 -type d -name '$packageName' 2>/dev/null | head -1"
        )
        if (findResult.success && findResult.output.isNotBlank()) {
            return findResult.output.trim()
        }

        return null
    }

    fun moveData(
        source: BrowserInfo,
        target: BrowserInfo,
        backupFirst: Boolean,
        listener: ProgressListener
    ) {
        Thread {
            try {
                postProgress(listener, "Checking root access...")
                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root access not found!")
                    return@Thread
                }
                postProgress(listener, "Root access confirmed.")

                // Verify packages exist
                postProgress(listener, "Verifying packages...")
                val srcPmCheck = RootHelper.executeCommand("pm path ${source.packageName}")
                postProgress(listener, "Source pm path: ${srcPmCheck.output.take(200)}")
                if (!srcPmCheck.success || srcPmCheck.output.isBlank()) {
                    postError(listener, "Source package not installed: ${source.packageName}")
                    return@Thread
                }

                val dstPmCheck = RootHelper.executeCommand("pm path ${target.packageName}")
                postProgress(listener, "Target pm path: ${dstPmCheck.output.take(200)}")
                if (!dstPmCheck.success || dstPmCheck.output.isBlank()) {
                    postError(listener, "Target package not installed: ${target.packageName}")
                    return@Thread
                }

                // Find actual data directories
                postProgress(listener, "Finding source data directory...")
                val srcDir = findDataDir(source.packageName)
                postProgress(listener, "Source data dir: $srcDir")
                if (srcDir == null) {
                    postError(listener, "Could not find source data directory!\nPackage: ${source.packageName}")
                    return@Thread
                }

                postProgress(listener, "Finding target data directory...")
                val dstDir = findDataDir(target.packageName)
                postProgress(listener, "Target data dir: $dstDir")
                if (dstDir == null) {
                    postError(listener, "Could not find target data directory!\nPackage: ${target.packageName}")
                    return@Thread
                }

                // List source contents for debug
                postProgress(listener, "Source contents:")
                val lsSrc = RootHelper.executeCommand("ls -la '$srcDir/' | head -20")
                postProgress(listener, lsSrc.output.take(500))

                // Force stop both
                postProgress(listener, "Stopping browsers...")
                RootHelper.executeCommand("am force-stop ${source.packageName}")
                RootHelper.executeCommand("am force-stop ${target.packageName}")
                Thread.sleep(1500)
                postProgress(listener, "Browsers stopped.")

                // Backup
                if (backupFirst) {
                    postProgress(listener, "Creating backup of target...")
                    RootHelper.executeCommand("mkdir -p $BACKUP_DIR")
                    val ts = System.currentTimeMillis()
                    val backupFile = "$BACKUP_DIR/${target.packageName}_$ts.tar.gz"
                    val bkResult = RootHelper.executeCommand(
                        "tar -czf '$backupFile' -C '$dstDir' . 2>&1"
                    )
                    if (bkResult.success) {
                        postProgress(listener, "Backup saved: $backupFile")
                    } else {
                        postProgress(listener, "Warning: Backup failed - ${bkResult.error.take(200)}")
                    }
                }

                // Clear target
                postProgress(listener, "Clearing target data...")
                RootHelper.executeCommand("rm -rf '$dstDir'/*")
                postProgress(listener, "Target cleared.")

                // Copy data
                postProgress(listener, "Copying data... (this may take a while)")
                val cpResult = RootHelper.executeCommand("cp -a '$srcDir'/* '$dstDir'/ 2>&1")
                postProgress(listener, "Copy done. Success: ${cpResult.success}")
                if (cpResult.error.isNotBlank()) {
                    postProgress(listener, "Copy notes: ${cpResult.error.take(300)}")
                }

                // Fix ownership
                postProgress(listener, "Fixing ownership...")
                val uidResult = RootHelper.executeCommand(
                    "dumpsys package ${target.packageName} | grep 'userId=' | head -1"
                )
                postProgress(listener, "UID lookup: ${uidResult.output.take(200)}")

                val uidMatch = Regex("userId=(\\d+)").find(uidResult.output)
                if (uidMatch != null) {
                    val uid = uidMatch.groupValues[1].toInt()
                    val userName = "u0_a${uid - 10000}"
                    RootHelper.executeCommand("chown -R $userName:$userName '$dstDir'")
                    postProgress(listener, "Ownership set to: $userName (uid=$uid)")
                } else {
                    // Fallback: get owner from target dir parent info
                    postProgress(listener, "Trying alternative ownership method...")
                    val statR = RootHelper.executeCommand("stat -c '%u:%g' '$dstDir' 2>/dev/null")
                    val ow = statR.output.trim().replace("'", "")
                    if (ow.isNotBlank() && !ow.startsWith("0")) {
                        RootHelper.executeCommand("chown -R $ow '$dstDir'")
                        postProgress(listener, "Ownership set via stat: $ow")
                    } else {
                        postProgress(listener, "Warning: Could not fix ownership automatically")
                    }
                }

                // Fix SELinux
                postProgress(listener, "Fixing SELinux context...")
                RootHelper.executeCommand("restorecon -RF '$dstDir' 2>/dev/null")

                // Verify
                postProgress(listener, "Verifying transfer...")
                val verifyResult = RootHelper.executeCommand("ls '$dstDir/' | head -15")
                postProgress(listener, "Target contents after transfer:\n${verifyResult.output}")

                postSuccess(
                    listener,
                    "Transfer successful!\n\n" +
                    "Source: ${source.name}\n($srcDir)\n\n" +
                    "Target: ${target.name}\n($dstDir)\n\n" +
                    "You can now open ${target.name}."
                )

            } catch (e: Exception) {
                postError(listener, "Unexpected error:\n${e.message}")
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
