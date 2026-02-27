package com.omersusin.firefoxtransfer

object TransferEngine {

  enum class Mode { PROFILE, FULL }

  data class Plan(
    val sourcePkg: String,
    val targetPkg: String,
    val mode: Mode
  )

  fun buildScript(p: Plan): String {

    val src = "/data/user/0/${p.sourcePkg}"
    val dst = "/data/user/0/${p.targetPkg}"

    val folders = when (p.mode) {
      Mode.FULL -> listOf("files", "databases", "shared_prefs")
      Mode.PROFILE -> listOf("files/mozilla", "databases", "shared_prefs")
    }

    val copyBlock = folders.joinToString("\n") { rel ->
      """
      if [ -d "$src/$rel" ]; then
        rm -rf "$dst/$rel" 2>/dev/null || true
        cp -a "$src/$rel" "$dst/" 2>/dev/null || cp -r "$src/$rel" "$dst/"
        echo "COPIED:$rel"
      else
        echo "SKIP:$rel"
      fi
      """.trimIndent()
    }

    return """
      set -e

      SRC="$src"
      DST="$dst"

      echo "SRC=$src"
      echo "DST=$dst"

      am force-stop ${p.targetPkg} 2>/dev/null || true

      if [ ! -d "$src" ]; then echo "ERROR: source missing"; exit 20; fi
      if [ ! -d "$dst" ]; then echo "ERROR: target missing"; exit 21; fi

      BACKUP="/sdcard/FirefoxTransfer_Backups/${p.targetPkg}_$(date +%Y%m%d_%H%M%S)"
      mkdir -p "${'$'}BACKUP"
      cp -a "$dst/databases" "${'$'}BACKUP/" 2>/dev/null || true
      cp -a "$dst/shared_prefs" "${'$'}BACKUP/" 2>/dev/null || true
      cp -a "$dst/files" "${'$'}BACKUP/" 2>/dev/null || true
      echo "BACKUP_OK"

      $copyBlock

      OWNER="$(stat -c '%u:%g' "$dst" 2>/dev/null || true)"
      if [ -n "${'$'}OWNER" ]; then chown -R "${'$'}OWNER" "$dst" 2>/dev/null || true; fi

      restorecon -R "$dst" 2>/dev/null || true

      echo "DONE"
    """.trimIndent()
  }

  fun run(plan: Plan): Pair<Boolean, String> {
    val script = buildScript(plan)

    val (code, out) = RootHelper.su(script)
    return (code == 0) to out
  }
}
