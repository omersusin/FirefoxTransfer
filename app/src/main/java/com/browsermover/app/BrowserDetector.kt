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
        val pm = context.packageManager

        // 1. Query all apps that can handle http/https intents
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("https://example.com")
        }
        val resolveList = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val processedPackages = mutableSetOf<String>()

        for (resolveInfo in resolveList) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg in processedPackages) continue
            processedPackages.add(pkg)

            // Skip self
            if (pkg == context.packageName) continue

            val appName = resolveInfo.loadLabel(pm).toString()
            val engineType = identifyEngine(pkg)

            // If filtering, allow matches or Unknowns (might be a fork)
            if (filterType != null && engineType != filterType && engineType != BrowserType.UNKNOWN) {
                continue
            }

            installed.add(BrowserInfo(appName, pkg, engineType, true))
        }

        return installed
    }

    private fun identifyEngine(pkg: String): BrowserType {
        // Markers for Architecture A (Gecko)
        val geckoMarkers = listOf(
            "/data/data/$pkg/files/mozilla/profiles.ini",
            "/data/user/0/$pkg/files/mozilla/profiles.ini"
        )
        for (path in geckoMarkers) {
            if (java.io.File(path).exists()) return BrowserType.FIREFOX
        }

        // Fallback: Check native libraries if root allowed or via public path
        // For simplicity in this logic, we use RootHelper to check if markers exist if normal File access fails
        val rootResult = RootHelper.executeCommand("ls /data/data/$pkg/files/mozilla/profiles.ini")
        if (rootResult.success) return BrowserType.FIREFOX

        // Markers for Architecture B (Chromium)
        val chromiumMarkers = listOf(
            "/data/data/$pkg/app_chrome",
            "/data/data/$pkg/app_chromium",
            "/data/user/0/$pkg/app_chrome",
            "/data/user/0/$pkg/app_chromium"
        )
        for (path in chromiumMarkers) {
            if (java.io.File(path).exists()) return BrowserType.CHROMIUM
        }
        
        val rootChromium = RootHelper.executeCommand("ls -d /data/data/$pkg/app_chrome /data/data/$pkg/app_chromium")
        if (rootChromium.success) return BrowserType.CHROMIUM

        // Fallback to name-based guessing for apps not yet launched
        val lowPkg = pkg.lowercase()
        return when {
            lowPkg.contains("firefox") || lowPkg.contains("fennec") || lowPkg.contains("fenix") || 
            lowPkg.contains("iceraven") || lowPkg.contains("mull") -> BrowserType.FIREFOX
            lowPkg.contains("chrome") || lowPkg.contains("chromium") || lowPkg.contains("brave") || 
            lowPkg.contains("opera") || lowPkg.contains("vivaldi") || lowPkg.contains("edge") -> BrowserType.CHROMIUM
            else -> BrowserType.UNKNOWN
        }
    }

    fun getCompatibleTargets(source: BrowserInfo, allBrowsers: List<BrowserInfo>): List<BrowserInfo> {
        // Be permissive: show ALL other browsers. The engine check happens during transfer.
        return allBrowsers.filter { it.packageName != source.packageName }
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
