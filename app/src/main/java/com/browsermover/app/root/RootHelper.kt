package com.browsermover.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

object RootHelper {

    /**
     * DÜZELTME: Mount namespace izolasyonu bypass
     * Android'de su shell'i uygulamanın mount namespace'inde çalışır.
     * nsenter -t 1 -m: PID 1'in (init) mount namespace'ine girer.
     */
    private const val ENTER_GLOBAL_NS =
        "command -v nsenter >/dev/null 2>&1 && exec nsenter -t 1 -m -- /system/bin/sh"

    suspend fun checkRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
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
    }

    suspend fun exec(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            // Global namespace'e geç
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

    suspend fun execMultiple(commands: List<String>): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)

                // Global namespace'e geç
                os.writeBytes("$ENTER_GLOBAL_NS\n")

                for (cmd in commands) {
                    os.writeBytes("$cmd\n")
                }
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

    suspend fun execStreaming(
        scriptPath: String,
        args: List<String> = emptyList(),
        onLine: suspend (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            // Global namespace'e geç
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
}
