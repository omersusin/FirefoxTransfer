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

    private fun findDataDir(packageName: String, listener: ProgressListener): String? {

        // Method 1: dumpsys package -> dataDir
        postProgress(listener, "  Method 1: dumpsys package...")
        val dumpResult = RootHelper.executeCommand(
            "dumpsys package $packageName"
        )
        if (dumpResult.success) {
            for (line in dumpResult.output.lines()) {
                if (line.trimStart().startsWith("dataDir=")) {
                    val path = line.trimStart().removePrefix("dataDir=").trim()
                    postProgress(listener, "  dumpsys found: $path")
                    if (path.isNotBlank() && path.startsWith("/data")) {
                        // Verify it exists
                        val verify = RootHelper.executeCommand("ls -d $path 2>/dev/null")
                        if (verify.output.trim() == path) {
                            postProgress(listener, "  Verified: $path EXISTS")
                            return path
                        }
                    }
                }
            }
        }

        // Method 2: Direct path check - /data/user/0/
        postProgress(listener, "  Method 2: checking /data/user/0/...")
        val path2 = "/data/user/0/$packageName"
        val check2 = RootHelper.executeCommand("ls -d $path2 2>/dev/null")
        if (check2.output.trim() == path2) {
            postProgress(listener, "  Found: $path2")
            return path2
        }

        // Method 3: Direct path check - /data/data/
        postProgress(listener, "  Method 3: checking /data/data/...")
        val path3 = "/data/data/$packageName"
        val check3 = RootHelper.executeCommand("ls -d $path3 2>/dev/null")
        if (check3.output.trim() == path3) {
            postProgress(listener, "  Found: $path3")
            return path3
        }

        // Method 4: Use pm to get install location, derive data path
        postProgress(listener, "  Method 4: deriving from pm path...")
        val pmResult = RootHelper.executeCommand("pm path $packageName")
        if (pmResult.success && pmResult.output.contains("package:")) {
            // If app is installed, data dir SHOULD be /data/user/0/pkg
            // Maybe the app was never opened - let's create data dir
            postProgress(listener, "  App installed but no data dir. Trying to create...")
            RootHelper.executeCommand("mkdir -p $path2")
            val recheck = RootHelper.executeCommand("ls -d $path2 2>/dev/null")
            if (recheck.output.trim() == path2) {
                // Set correct ownership
                val uidResult = RootHelper.executeCommand(
                    "dumpsys package $packageName"
                )
                val uidMatch = Regex("userId=(\\d+)").find(uidResult.output)
                if (uidMatch != null) {
                    val uid = uidMatch.groupValues[1].toInt()
                    val userName = "u0_a${uid - 10000}"
                    RootHelper.executeCommand("chown -R $userName:$userName $path2")
                    RootHelper.executeCommand("chmod 771 $path2")
                }
                postProgress(listener, "  Created and configured: $path2")
                return path2
            }
        }

        // Method 5: List all under /data/user/0/ and grep
        postProgress(listener, "  Method 5: listing /data/user/0/...")
        val listResult = RootHelper.executeCommand("ls /data/user/0/ | grep '$packageName'")
        if (listResult.success && listResult.output.trim() == packageName) {
            postProgress(listener, "  Found via listing: /data/user/0/$packageName")
            return "/data/user/0/$packageName"
        }

        postProgress(listener, "  All methods failed!")
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

                // Verify packages are installed
                postProgress(listener, "Verifying packages...")
                val srcPm = RootHelper.executeCommand("pm path ${source.packageName}")
                if (!srcPm.success || srcPm.output.isBlank()) {
                    postError(listener, "Source package not installed: ${source.packageName}")
                    return@Thread
                }
                postProgress(listener, "Source installed: OK")

                val dstPm = RootHelper.executeCommand("pm path ${target.packageName}")
                if (!dstPm.success || dstPm.output.isBlank()) {
                    postError(listener, "Target package not installed: ${target.packageName}")
                    return@Thread
                }
                postProgress(listener, "Target installed: OK")

                // Find source data directory
                postProgress(listener, "Finding source data directory...")
                val srcDir = findDataDir(source.packageName, listener)
                if (srcDir == null) {
                    postError(listener, "Could not find source data directory!\nPackage: ${source.packageName}\n\nMake sure you have opened ${source.name} at least once.")
                    return@Thread
                }
                postProgress(listener, "Source dir: $srcDir")

                // Check source has actual data
                val srcContent = RootHelper.executeCommand("ls $srcDir/")
                if (srcContent.output.isBlank()) {
                    postError(listener, "Source data directory is empty!\n$srcDir\n\nOpen ${source.name} first to generate data.")
                    return@Thread
                }
                postProgress(listener, "Source has data: ${srcContent.output.lines().size} items")

                // Find target data directory
                postProgress(listener, "Finding target data directory...")
                val dstDir = findDataDir(target.packageName, listener)
                if (dstDir == null) {
                    postError(listener, "Could not find target data directory!\nPackage: ${target.packageName}\n\nPlease open ${target.name} once, then try again.")
                    return@Thread
                }
                postProgress(listener, "Target dir: $dstDir")

                // Force stop both browsers
                postProgress(listener, "Stopping browsers...")
                RootHelper.executeCommand("am force-stop ${source.packageName}")
                RootHelper.executeCommand("am force-stop ${target.packageName}")
                Thread.sleep(1500)
                postProgress(listener, "Browsers stopped.")

                // Backup target
                if (backupFirst) {
                    postProgress(listener, "Creating backup of target...")
                    RootHelper.executeCommand("mkdir -p $BACKUP_DIR")
                    val ts = System.currentTimeMillis()
                    val bkFile = "$BACKUP_DIR/${target.packageName}_$ts.tar.gz"
                    val bk = RootHelper.executeCommand("tar -czf $bkFile -C $dstDir . 2>&1")
                    if (bk.success) {
                        postProgress(listener, "Backup saved: $bkFile")
                    } else {
                        postProgress(listener, "Backup warning: ${bk.error.take(200)}")
                    }
                }

                // Clear target
                postProgress(listener, "Clearing target data...")
                RootHelper.executeCommand("rm -rf $dstDir/*")
                postProgress(listener, "Target cleared.")

                // Copy data
                postProgress(listener, "Copying data... (this may take a while)")
                val cpResult = RootHelper.executeCommand("cp -a $srcDir/* $dstDir/")
                postProgress(listener, "Copy complete. Success: ${cpResult.success}")
                if (cpResult.error.isNotBlank()) {
                    postProgress(listener, "Copy notes: ${cpResult.error.take(300)}")
                }

                // Fix ownership using dumpsys
                postProgress(listener, "Fixing ownership...")
                val dumpTarget = RootHelper.executeCommand("dumpsys package ${target.packageName}")
                val uidMatch = Regex("userId=(\\d+)").find(dumpTarget.output)

                if (uidMatch != null) {
                    val uid = uidMatch.groupValues[1].toInt()
                    val user = "u0_a${uid - 10000}"
                    RootHelper.executeCommand("chown -R $user:$user $dstDir")
                    postProgress(listener, "Ownership fixed: $user (uid=$uid)")
                } else {
                    postProgress(listener, "Warning: Could not determine UID, trying alternative...")
                    // Get owner of the directory before we changed it
                    val lsResult = RootHelper.executeCommand("ls -ld /data/user/0/ | head -1")
                    postProgress(listener, "Fallback: ${lsResult.output}")
                }

                // Fix SELinux context
                postProgress(listener, "Fixing SELinux context...")
                RootHelper.executeCommand("restorecon -RF $dstDir 2>/dev/null")

                // Verify
                postProgress(listener, "Verifying...")
                val verify = RootHelper.executeCommand("ls $dstDir/ | head -15")
                postProgress(listener, "Target after transfer:\n${verify.output}")

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
