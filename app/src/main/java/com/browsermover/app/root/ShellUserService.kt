package com.browsermover.app.root

import com.browsermover.app.IShellService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's app_process. It is not an Android Service: Shizuku
 * starts this class in a separate process with the UID of the Shizuku backend.
 * If Shizuku was started with root that UID is 0 and the normal root scripts
 * can run; with the ADB backend it is 2000.
 */
class ShellUserService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(90, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "MIGRATOR_EXIT:-1\nCommand timed out\n"
            } else {
                "MIGRATOR_EXIT:${process.exitValue()}\n$output"
            }
        } catch (e: Exception) {
            "MIGRATOR_EXIT:-1\n${e.message ?: "shell error"}\n"
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
