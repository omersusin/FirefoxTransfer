package com.browsermover.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
) {
    val outputLines: List<String>
        get() = stdout.lines().filter { it.isNotBlank() }
}

/**
 * Runs privileged commands through one of two backends:
 *
 *  - su (root), or
 *  - Shizuku when the Shizuku server itself is running as UID 0.
 *
 * Shizuku with the normal ADB backend runs as UID 2000. It is useful for
 * package/activity control, but Linux permissions still block access to
 * /data/user/0/<package>. [accessLabel] reports that honestly; the UI does not
 * enable migration in that mode.
 */
object RootHelper {

    /**
     * FIX: Mount namespace isolation bypass.
     * On Android, su shell runs in the app's mount namespace.
     * nsenter -t 1 -m enters PID 1's (init) mount namespace.
     */
    private const val ENTER_GLOBAL_NS =
        "command -v nsenter >/dev/null 2>&1 && exec nsenter -t 1 -m -- /system/bin/sh"

    @Volatile private var suChecked: Boolean? = null

    /** Clear the cached su result so an explicit "Retry" can prompt again. */
    fun invalidateRootCache() {
        suChecked = null
    }

    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        if (checkSu()) return@withContext true
        if (!ShizukuShell.isRootBackend()) return@withContext false
        withTimeoutOrNull(15_000) { ShizukuShell.connectAndWait() } == true
    }

    /** Human-readable access mode for the status line. */
    suspend fun accessLabel(): String = withContext(Dispatchers.IO) {
        when {
            checkSu() -> "Root (su)"
            ShizukuShell.isRootBackend() -> "Shizuku root (UID 0)"
            ShizukuShell.installed() && !ShizukuShell.hasPermission() ->
                "Shizuku found; permission not granted"
            ShizukuShell.installed() ->
                "Shizuku ${ShizukuShell.backendLabel()} — app-private data is not readable"
            else -> "No root or Shizuku root access"
        }
    }

    private suspend fun checkSu(): Boolean = withContext(Dispatchers.IO) {
        suChecked?.let { return@withContext it }
        val result = try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor(10, TimeUnit.SECONDS)
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
        suChecked = result
        result
    }

    suspend fun exec(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (checkSu()) {
            suExec(command)
        } else {
            shizukuExec(command)
        }
    }

    suspend fun execMultiple(commands: List<String>): CommandResult =
        withContext(Dispatchers.IO) {
            if (checkSu()) {
                suExec(commands.joinToString("\n"))
            } else {
                shizukuExec(commands.joinToString("\n"))
            }
        }

    suspend fun execStreaming(
        scriptPath: String,
        args: List<String> = emptyList(),
        onLine: suspend (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        if (!checkSu()) {
            val fullCommand = buildString {
                append("sh \"$scriptPath\"")
                args.forEach { append(" \"$it\"") }
            }
            val result = withTimeoutOrNull(600_000) { ShizukuShell.exec(fullCommand) }
            if (result == null) {
                withContext(Dispatchers.Main) {
                    onLine("[EXCEPTION] Shizuku shell timed out")
                }
                return@withContext -1
            }
            for (line in result.stdout.lineSequence()) {
                withContext(Dispatchers.Main) { onLine(line) }
            }
            return@withContext result.exitCode
        }

        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            os.writeBytes("$ENTER_GLOBAL_NS\n")

            val fullCommand = buildString {
                append("sh \"$scriptPath\"")
                args.forEach { append(" \"$it\"") }
            }
            os.writeBytes("$fullCommand 2>&1\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let {
                    withContext(Dispatchers.Main) {
                        onLine(it)
                    }
                }
            }
            reader.close()

            process.waitFor()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onLine("[EXCEPTION] ${e.message}")
            }
            -1
        }
    }

    private suspend fun shizukuExec(command: String): CommandResult {
        val result = withTimeoutOrNull(600_000) { ShizukuShell.exec(command) }
            ?: return CommandResult(
                -1, "", "Shizuku shell timed out or is unavailable.", false
            )
        return result
    }

    private suspend fun suExec(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            os.writeBytes("$ENTER_GLOBAL_NS\n")
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stderrJob = launch {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    reader.forEachLine { stderrBuilder.appendLine(it) }
                    reader.close()
                } catch (_: Exception) {}
            }

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            stdoutReader.forEachLine { stdoutBuilder.appendLine(it) }
            stdoutReader.close()

            stderrJob.join()
            val exitCode = process.waitFor()

            CommandResult(
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim(),
                success = exitCode == 0
            )
        } catch (e: Exception) {
            CommandResult(-1, "", e.message ?: "Error", false)
        }
    }
}
