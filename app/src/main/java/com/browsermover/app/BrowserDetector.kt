package com.browsermover.app

import android.content.Context
import android.content.pm.PackageManager

class BrowserDetector(private val context: Context) {

    private val knownBrowsers = listOf(
        // Firefox ailesi
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

        // Chromium ailesi
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
        BrowserInfo("Bromite", "org.nicoco.nicoco", BrowserType.CHROMIUM),
        BrowserInfo("Cromite", "org.nicoco.nicoco", BrowserType.CHROMIUM),
        BrowserInfo("Vanadium", "app.vanadium.browser", BrowserType.CHROMIUM),
        BrowserInfo("DuckDuckGo", "com.duckduckgo.mobile.android", BrowserType.CHROMIUM),
        BrowserInfo("Adblock Browser", "org.adblockplus.browser", BrowserType.CHROMIUM),
        BrowserInfo("Adblock Browser Beta", "org.adblockplus.browser.beta", BrowserType.CHROMIUM),
    )

    fun detectInstalledBrowsers(): List<BrowserInfo> {
        val installed = mutableListOf<BrowserInfo>()

        // 1. Bilinen listeyi kontrol et
        for (browser in knownBrowsers) {
            if (isPackageInstalled(browser.packageName)) {
                installed.add(browser.copy(isInstalled = true))
            }
        }

        // 2. Root ile bilinmeyen tarayıcıları otomatik tespit et
        val detectedUnknown = autoDetectWithRoot()
        for (browser in detectedUnknown) {
            val alreadyInList = installed.any { it.packageName == browser.packageName }
            if (!alreadyInList) {
                installed.add(browser)
            }
        }

        return installed
    }

    fun getCompatibleTargets(source: BrowserInfo, allBrowsers: List<BrowserInfo>): List<BrowserInfo> {
        return allBrowsers.filter {
            it.packageName != source.packageName && it.type == source.type
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

    private fun autoDetectWithRoot(): List<BrowserInfo> {
        val detected = mutableListOf<BrowserInfo>()

        if (!RootHelper.isRootAvailable()) return detected

        // /data/data altindaki tum paketleri tara
        val result = RootHelper.executeCommand("ls /data/data/")
        if (!result.success) return detected

        val packages = result.output.lines().filter { it.isNotBlank() }
        val knownPackages = knownBrowsers.map { it.packageName }.toSet()

        for (pkg in packages) {
            if (pkg in knownPackages) continue
            if (!isPackageInstalled(pkg)) continue

            val type = detectBrowserType(pkg)
            if (type != BrowserType.UNKNOWN) {
                val appName = getAppName(pkg) ?: pkg
                detected.add(
                    BrowserInfo(
                        name = "$appName (Otomatik Tespit)",
                        packageName = pkg,
                        type = type,
                        isInstalled = true
                    )
                )
            }
        }

        return detected
    }

    private fun detectBrowserType(packageName: String): BrowserType {
        val dataDir = "/data/data/$packageName"

        // Firefox isaretleri: files/mozilla klasoru veya libxul.so
        val firefoxCheck = RootHelper.executeCommand(
            "ls $dataDir/files/mozilla 2>/dev/null || ls $dataDir/lib/libxul.so 2>/dev/null"
        )
        if (firefoxCheck.success && firefoxCheck.output.isNotBlank()) {
            return BrowserType.FIREFOX
        }

        // Chromium isaretleri: app_chrome klasoru veya libchrome.so
        val chromiumCheck = RootHelper.executeCommand(
            "ls $dataDir/app_chrome 2>/dev/null || ls $dataDir/lib/libchrome.so 2>/dev/null"
        )
        if (chromiumCheck.success && chromiumCheck.output.isNotBlank()) {
            return BrowserType.CHROMIUM
        }

        return BrowserType.UNKNOWN
    }

    private fun getAppName(packageName: String): String? {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            null
        }
    }
}