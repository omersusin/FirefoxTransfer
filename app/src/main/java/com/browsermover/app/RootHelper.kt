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
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            os.writeBytes("echo ROOT_OK\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            val output = reader.readText()
            reader.close()
            process.waitFor(10, TimeUnit.SECONDS)

            output.contains("ROOT_OK")
        } catch (e: Exception) {
            false
        }
    }

    fun executeCommand(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

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
}
