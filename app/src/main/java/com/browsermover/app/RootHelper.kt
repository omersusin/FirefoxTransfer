package com.browsermover.app

import java.io.BufferedReader
import java.io.DataOutputStream
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
            val process = Runtime.getRuntime().exec("su -c echo root_test")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor(10, TimeUnit.SECONDS)
            output.contains("root_test")
        } catch (e: Exception) {
            // Yedek yontem
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText()
                process.waitFor(10, TimeUnit.SECONDS)
                output.contains("uid=0")
            } catch (e2: Exception) {
                false
            }
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

            process.waitFor(60, TimeUnit.SECONDS)

            stdout.close()
            stderr.close()

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

            process.waitFor(120, TimeUnit.SECONDS)

            stdout.close()
            stderr.close()

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
