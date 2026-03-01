package com.browsermover.app

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

data class CommandResult(
    val success: Boolean,
    val output: String,
    val error: String = ""
)

object RootHelper {

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("echo root_test\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()

            output.contains("root_test")
        } catch (e: Exception) {
            false
        }
    }

    fun executeCommand(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))

            val output = stdout.readText()
            val error = stderr.readText()

            process.waitFor()

            CommandResult(
                success = process.exitValue() == 0,
                output = output.trim(),
                error = error.trim()
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Bilinmeyen hata"
            )
        }
    }

    fun executeCommands(commands: List<String>): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            for (cmd in commands) {
                os.writeBytes("$cmd\n")
            }
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))

            val output = stdout.readText()
            val error = stderr.readText()

            process.waitFor()

            CommandResult(
                success = process.exitValue() == 0,
                output = output.trim(),
                error = error.trim()
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                error = e.message ?: "Bilinmeyen hata"
            )
        }
    }
}