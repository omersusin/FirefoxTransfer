package com.omersusin.firefoxtransfer

import java.io.BufferedReader
import java.io.InputStreamReader

object RootHelper {

  fun hasRoot(): Boolean {
    return try {
      val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
      val out = p.inputStream.bufferedReader().readText()
      p.waitFor()
      out.contains("uid=0")
    } catch (_: Throwable) {
      false
    }
  }

  fun su(cmd: String): Pair<Int, String> {
    return try {
      val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
      val stdout = p.inputStream.bufferedReader().readText()
      val stderr = p.errorStream.bufferedReader().readText()
      val code = p.waitFor()
      code to (stdout + stderr)
    } catch (t: Throwable) {
      999 to ("[EXCEPTION] ${t.message}")
    }
  }
}
