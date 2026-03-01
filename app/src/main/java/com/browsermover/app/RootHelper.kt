package com.browsermover.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String = ""
)

object RootHelper {

    fun isRootAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            process.waitFor(10, TimeUnit.SECONDS)
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun executeCommand(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))

            val output = stdout.readText()
            val error = stderr.readText()

            stdout.close()
            stderr.close()

            process.waitFor(60, TimeUnit.SECONDS)

            CommandResult(
                success = process.exitValue() == 0,
                output = output.trim(),
                error = error.trim()
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Unknown error"
            )
        }
    }

    fun executeMultiCommand(commands: List<String>): CommandResult {
        val joined = commands.joinToString(" && ")
        return executeCommand(joined)
    }
}
