package com.browsermover.app

import android.content.Context
import android.content.pm.PackageManager

class BrowserDetector(private val context: Context) {

    private val knownBrowsers = listOf(
        BrowserInfo("Firefox", "org.mozilla.firefox", BrowserType.FIREFOX),
        BrowserInfo("Firefox Beta", "org.mozilla.firefox_beta", BrowserType.FIREFOX),
        BrowserInfo("Firefox Nightly", "org.mozilla.fenix", BrowserType.FIREFOX),
        BrowserInfo("Firefox Focus", "org.mozilla.focus", BrowserType.FIREFOX),
        BrowserInfo("Firefox Focus Beta", "org.mozilla.focus.beta", BrowserType.FIREFOX),
        BrowserInfo("Firefox Focus Nightly", "org.mozilla.focus.nightly", BrowserType.FIREFOX),
        BrowserInfo("Firefox Klar", "org.mozilla.klar", BrowserType.FIREFOX),
        BrowserInfo("Firefox (Android TV)", "org.mozilla.tv.firefox", BrowserType.FIREFOX),
        BrowserInfo("Firefox Lite", "org.mozilla.rocket", BrowserType.FIREFOX),
        BrowserInfo("Firefox Reality", "org.mozilla.vrbrowser", BrowserType.FIREFOX),
        BrowserInfo("Firefox Aurora", "org.mozilla.fennec_aurora", BrowserType.FIREFOX),
        BrowserInfo("Fennec F-Droid", "org.mozilla.fennec_fdroid", BrowserType.FIREFOX),
        BrowserInfo("Mull", "us.spotco.fennec_dos", BrowserType.FIREFOX),
        BrowserInfo("IronFox", "org.ironfoxoss.ironfox", BrowserType.FIREFOX),
        BrowserInfo("IronFox Nightly", "org.ironfoxoss.ironfox.nightly", BrowserType.FIREFOX),
        BrowserInfo("Iceraven", "io.github.forkmaintainers.iceraven", BrowserType.FIREFOX),
        BrowserInfo("Tor Browser", "org.torproject.torbrowser", BrowserType.FIREFOX),
        BrowserInfo("Tor Browser Alpha", "org.torproject.torbrowser_alpha", BrowserType.FIREFOX),
        BrowserInfo("Orfox", "info.guardianproject.orfox", BrowserType.FIREFOX),
        BrowserInfo("IceCatMobile", "org.gnu.icecat", BrowserType.FIREFOX),
        BrowserInfo("Waterfox", "net.waterfox.android.release", BrowserType.FIREFOX),

        BrowserInfo("Chrome", "com.android.chrome", BrowserType.CHROMIUM),
        BrowserInfo("Chrome Beta", "com.chrome.beta", BrowserType.CHROMIUM),
        BrowserInfo("Chrome Dev", "com.chrome.dev", BrowserType.CHROMIUM),
        BrowserInfo("Chrome Canary", "com.chrome.canary", BrowserType.CHROMIUM),
        BrowserInfo("Chromium", "org.chromium.chrome", BrowserType.CHROMIUM),
        BrowserInfo("Brave", "com.brave.browser", BrowserType.CHROMIUM),
        BrowserInfo("Brave Beta", "com.brave.browser_beta", BrowserType.CHROMIUM),
        BrowserInfo("Brave Nightly", "com.brave.browser_nightly", BrowserType.CHROMIUM),
        BrowserInfo("Edge", "com.microsoft.emmx", BrowserType.CHROMIUM),
        BrowserInfo("Edge Beta", "com.microsoft.emmx.beta", BrowserType.CHROMIUM),
        BrowserInfo("Edge Canary", "com.microsoft.emmx.canary", BrowserType.CHROMIUM),
        BrowserInfo("Opera", "com.opera.browser", BrowserType.CHROMIUM),
        BrowserInfo("Opera Beta", "com.opera.browser.beta", BrowserType.CHROMIUM),
        BrowserInfo("Opera Mini", "com.opera.mini.native", BrowserType.CHROMIUM),
        BrowserInfo("Samsung Internet", "com.sec.android.app.sbrowser", BrowserType.CHROMIUM),
        BrowserInfo("Samsung Internet Beta", "com.sec.android.app.sbrowser.beta", BrowserType.CHROMIUM),
        BrowserInfo("Vivaldi", "com.vivaldi.browser", BrowserType.CHROMIUM),
        BrowserInfo("Vivaldi Snapshot", "com.vivaldi.browser.snapshot", BrowserType.CHROMIUM),
        BrowserInfo("Kiwi Browser", "com.kiwibrowser.browser", BrowserType.CHROMIUM),
        BrowserInfo("Vanadium", "app.vanadium.browser", BrowserType.CHROMIUM),
        BrowserInfo("DuckDuckGo", "com.duckduckgo.mobile.android", BrowserType.CHROMIUM),
        BrowserInfo("Adblock Browser", "org.adblockplus.browser", BrowserType.CHROMIUM),
        BrowserInfo("Adblock Browser Beta", "org.adblockplus.browser.beta", BrowserType.CHROMIUM),
    )

    fun detectInstalledBrowsers(filterType: BrowserType? = null): List<BrowserInfo> {
        val installed = mutableListOf<BrowserInfo>()

        val list = if (filterType != null) {
            knownBrowsers.filter { it.type == filterType }
        } else {
            knownBrowsers
        }

        for (b in list) {
            if (isPackageInstalled(b.packageName)) {
                installed.add(b.copy(isInstalled = true))
            }
        }

        // Auto-detect unknown browsers via intent query
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse("https://example.com")
            val resolveList = context.packageManager.queryIntentActivities(intent, 0)
            val knownPkgs = knownBrowsers.map { it.packageName }.toSet()

            for (info in resolveList) {
                val pkg = info.activityInfo.packageName
                if (pkg in knownPkgs) continue
                if (installed.any { it.packageName == pkg }) continue

                val appName = try {
                    val ai = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(ai).toString()
                } catch (e: Exception) { pkg }

                // Default to UNKNOWN type, user can still try manual
                val type = BrowserType.UNKNOWN
                // Removed the filter check for UNKNOWN so they always show up
                // if (filterType != null && type != filterType) continue

                installed.add(BrowserInfo("$appName (Detected)", pkg, type, true))
            }
        } catch (e: Exception) {
            // Ignore
        }

        return installed
    }

    fun getCompatibleTargets(source: BrowserInfo, allBrowsers: List<BrowserInfo>): List<BrowserInfo> {
        return allBrowsers.filter {
            it.packageName != source.packageName && (it.type == source.type || it.type == BrowserType.UNKNOWN || source.type == BrowserType.UNKNOWN)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
