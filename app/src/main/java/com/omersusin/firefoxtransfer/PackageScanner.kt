package com.omersusin.firefoxtransfer

import android.content.Context
import android.content.pm.PackageManager

object PackageScanner {

  private val known = linkedMapOf(
    "org.mozilla.firefox" to "Firefox",
    "org.mozilla.firefox_beta" to "Firefox Beta",
    "org.mozilla.fenix" to "Firefox Nightly",
    "org.mozilla.fennec_fdroid" to "Fennec F-Droid",
    "us.spotco.fennec_dos" to "Mull",
    "io.github.forkmaintainers.iceraven" to "Iceraven",
    "org.ironfoxoss.ironfox" to "IronFox",
    "org.ironfoxoss.ironfox.nightly" to "IronFox Nightly",
    "org.torproject.torbrowser" to "Tor Browser",
    "org.torproject.torbrowser_alpha" to "Tor Browser Alpha",
    "org.mozilla.focus" to "Firefox Focus",
    "org.mozilla.klar" to "Firefox Klar",
    "net.waterfox.android.release" to "Waterfox",
    "org.adblockplus.browser" to "Adblock Browser",
    "org.adblockplus.browser.beta" to "Adblock Browser Beta"
  )

  data class Entry(val pkg: String, val label: String) {
    override fun toString(): String = "$label ($pkg)"
  }

  fun scan(context: Context): List<Entry> {
    val pm = context.packageManager
    val result = mutableListOf<Entry>()

    // 1) Bilinenleri dene
    for ((pkg, label) in known) {
      try {
        pm.getPackageInfo(pkg, 0)
        result.add(Entry(pkg, label))
      } catch (_: PackageManager.NameNotFoundException) {}
    }

    // 2) Heuristic: isimde mozilla/firefox/fennec/iceraven/tor geçenler
    val apps = pm.getInstalledApplications(0)
    for (a in apps) {
      val p = a.packageName.lowercase()
      if (p.contains("mozilla") || p.contains("firefox") || p.contains("fennec") ||
          p.contains("iceraven") || p.contains("niceraven") || p.contains("torbrowser")) {
        if (result.none { it.pkg == a.packageName }) {
          val label = pm.getApplicationLabel(a).toString()
          result.add(Entry(a.packageName, label))
        }
      }
    }

    return result.sortedBy { it.label.lowercase() }
  }
}
