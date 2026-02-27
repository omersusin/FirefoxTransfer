package com.omersusin.firefoxtransfer

object TransferEngine {
  enum class Mode { PROFILE, FULL }

  data class Plan(val sourcePkg: String, val targetPkg: String, val mode: Mode)

  fun buildScript(p: Plan): String {
    val S = "\${'$'}" // shell $ literal
    val src = "/data/data/${p.sourcePkg}"
    val dst = "/data/data/${p.targetPkg}"

    val copyList = when (p.mode) {
      Mode.FULL -> listOf("files", "databases", "shared_prefs")
      Mode.PROFILE -> listOf("files/mozilla", "databases", "shared_prefs")
    }

    val copyBlock = copyList.joinToString("\n") { rel ->
      """
      if [ -d "$S{SRC}/$rel" ]; then
        rm -rf "$S{DST}/$rel" 2>/dev/null || true
        mkdir -p "$(dirname "$S{DST}/$rel")"
        cp -a "$S{SRC}/$rel" "$S{DST}/" 2>/dev/null || cp -r "$S{SRC}/$rel" "$S{DST}/"
        echo "COPIED_DIR:$rel"
      else
        echo "SKIP:$rel"
      fi
      """.trimIndent()
    }

    return """
      set -e
      SRC="$src"
      DST="$dst"
      TS="$(date +%Y%m%d_%H%M%S)"
      BACKUP="/sdcard/FirefoxTransfer_Backups/${p.targetPkg}_$S{TS}"

      echo "SRC=$S{SRC}"
      echo "DST=$S{DST}"
      echo "BACKUP=$S{BACKUP}"

      am force-stop ${p.targetPkg} 2>/dev/null || true

      if [ ! -d "$S{SRC}" ]; then echo "ERROR: source missing"; exit 20; fi
      if [ ! -d "$S{DST}" ]; then echo "ERROR: target missing (open target once)"; exit 21; fi

      mkdir -p "$S{BACKUP}"
      cp -a "$S{DST}/databases" "$S{BACKUP}/" 2>/dev/null || true
      cp -a "$S{DST}/shared_prefs" "$S{BACKUP}/" 2>/dev/null || true
      cp -a "$S{DST}/files" "$S{BACKUP}/" 2>/dev/null || true
      echo "BACKUP_OK"

      $copyBlock

      OWNER="$(stat -c '%u:%g' "$S{DST}" 2>/dev/null || true)"
      if [ -n "$S{OWNER}" ]; then chown -R "$S{OWNER}" "$S{DST}" 2>/dev/null || true; fi
      restorecon -R "$S{DST}" 2>/dev/null || true

      echo "DONE"
    """.trimIndent()
  }

  fun run(plan: Plan): Pair<Boolean, String> {
    val script = buildScript(plan)
    // tek komutla çalıştır: sh -c '...'
    val cmd = "sh -c ${escapeForSingleQuotes(script)}"
    val (code, out) = RootHelper.su(cmd)
    return (code == 0) to out
  }

  private fun escapeForSingleQuotes(s: String): String {
    // sh -c '...': tek tırnak içindeki tek tırnakları parçala
    val escaped = s.replace("'", "'\"'\"'")
    return "'$escaped'"
  }
}
