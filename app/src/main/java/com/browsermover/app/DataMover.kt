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

                val srcDir = "/data/data/${source.packageName}"
                val dstDir = "/data/data/${target.packageName}"

                // Check source exists
                postProgress(listener, "Checking source: ${source.packageName}")
                val srcCheck = RootHelper.executeCommand("ls $srcDir/")
                postProgress(listener, "Source check output: '${srcCheck.output.take(200)}'")
                postProgress(listener, "Source check error: '${srcCheck.error.take(200)}'")
                postProgress(listener, "Source check success: ${srcCheck.success}")

                if (srcCheck.output.isBlank() && !srcCheck.success) {
                    postError(listener, "Source browser data not found!\n\nPackage: ${source.packageName}\nError: ${srcCheck.error}")
                    return@Thread
                }

                // Check target exists
                postProgress(listener, "Checking target: ${target.packageName}")
                val dstCheck = RootHelper.executeCommand("ls $dstDir/")
                if (dstCheck.output.isBlank() && !dstCheck.success) {
                    postError(listener, "Target browser not installed!\n\nPackage: ${target.packageName}\nError: ${dstCheck.error}")
                    return@Thread
                }
                postProgress(listener, "Target confirmed.")

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
                        "tar -czf $backupFile -C $dstDir . 2>&1"
                    )
                    if (bkResult.success) {
                        postProgress(listener, "Backup saved: $backupFile")
                    } else {
                        postProgress(listener, "Warning: Backup failed, continuing... ${bkResult.error}")
                    }
                }

                // Clear target
                postProgress(listener, "Clearing target data...")
                RootHelper.executeCommand("rm -rf $dstDir/*")
                postProgress(listener, "Target cleared.")

                // Copy data
                postProgress(listener, "Copying data... (this may take a while)")
                val cpResult = RootHelper.executeCommand("cp -a $srcDir/* $dstDir/")
                postProgress(listener, "Copy result - success: ${cpResult.success}")
                if (cpResult.error.isNotBlank()) {
                    postProgress(listener, "Copy warnings: ${cpResult.error.take(300)}")
                }

                // Fix ownership - get UID from package manager
                postProgress(listener, "Fixing ownership...")
                val uidResult = RootHelper.executeCommand(
                    "dumpsys package ${target.packageName} | grep userId= | head -1"
                )
                postProgress(listener, "UID lookup: ${uidResult.output.take(200)}")

                val uidMatch = Regex("userId=(\\d+)").find(uidResult.output)
                if (uidMatch != null) {
                    val uid = uidMatch.groupValues[1].toInt()
                    val userName = "u0_a${uid - 10000}"
                    RootHelper.executeCommand("chown -R $userName:$userName $dstDir")
                    postProgress(listener, "Ownership set to: $userName (uid=$uid)")
                } else {
                    // Fallback: use stat on target parent
                    postProgress(listener, "UID not found via dumpsys, trying stat...")
                    val statResult = RootHelper.executeCommand(
                        "stat -c '%u:%g' $dstDir"
                    )
                    val statOwner = statResult.output.trim().replace("'", "")
                    if (statOwner.isNotBlank() && statOwner != "0:0") {
                        RootHelper.executeCommand("chown -R $statOwner $dstDir")
                        postProgress(listener, "Ownership set via stat: $statOwner")
                    } else {
                        postProgress(listener, "Warning: Could not determine ownership automatically.")
                    }
                }

                // Fix SELinux
                postProgress(listener, "Fixing SELinux context...")
                RootHelper.executeCommand("restorecon -RF $dstDir")

                // Verify
                postProgress(listener, "Verifying transfer...")
                val verifyResult = RootHelper.executeCommand("ls $dstDir/ | head -15")
                postProgress(listener, "Target contents:\n${verifyResult.output}")

                postSuccess(
                    listener,
                    "Transfer successful!\n\n" +
                    "Source: ${source.name}\n(${source.packageName})\n\n" +
                    "Target: ${target.name}\n(${target.packageName})\n\n" +
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
