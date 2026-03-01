package com.browsermover.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

class BrowserDetector(private val context: Context) {

    private companion object {
        val GECKO_LIBS = setOf("libxul.so", "libmozglue.so")
        val CHROMIUM_LIBS = setOf(
            "libchrome.so", "libmonochrome.so",
            "libchromeview.so", "libstandalonebrowser.so",
            "libchromiumcontent.so"
        )
        const val GECKO_MARKER = "files/mozilla/profiles.ini"
        val CHROMIUM_MARKERS = listOf(
            "app_chrome/Default/Preferences",
            "app_chrome/Local State",
            "app_chromium/Default/Preferences"
        )
    }

    fun detectInstalledBrowsers(filterType: BrowserType? = null): List<BrowserInfo> {
        val webViewPkgs = getWebViewProviders()
        val results = mutableListOf<BrowserInfo>()

        getBrowserPackages()
            .filter { it !in webViewPkgs }
            .forEach { pkg ->
                val engine = identifyEngine(pkg)
                if (engine != BrowserType.UNKNOWN) {
                    if (filterType == null || engine == filterType) {
                        val appLabel = getAppLabel(pkg)
                        results.add(BrowserInfo(appLabel, pkg, engine, true))
                    }
                }
            }

        return results.sortedBy { it.name.lowercase() }
    }

    private fun getBrowserPackages(): Set<String> {
        val pm = context.packageManager
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val webPkgs = pm.queryIntentActivities(webIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPkgs = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }.toSet()

        return webPkgs.intersect(launcherPkgs)
    }

    private fun getWebViewProviders(): Set<String> {
        val pkgs = mutableSetOf<String>()
        try {
            val out = RootHelper.executeCommand("dumpsys webviewupdate 2>/dev/null").output
            Regex("""packageName\s*[=:]\s*(\S+)""").findAll(out).forEach { pkgs.add(it.groupValues[1]) }
        } catch (_: Exception) {}
        return pkgs
    }

    private fun identifyEngine(pkg: String): BrowserType {
        // 1. Native Libs
        val libEngine = checkNativeLibs(pkg)
        if (libEngine != BrowserType.UNKNOWN) return libEngine

        // 2. Data Markers
        val dataEngine = checkDataMarkers(pkg)
        if (dataEngine != BrowserType.UNKNOWN) return dataEngine

        // 3. Heuristics
        return checkHeuristics(pkg)
    }

    private fun checkNativeLibs(pkg: String): BrowserType {
        val pm = context.packageManager
        val appInfo = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return BrowserType.UNKNOWN }
        val apks = mutableListOf<String>()
        appInfo.sourceDir?.let { apks.add(it) }
        appInfo.splitSourceDirs?.forEach { apks.add(it) }

        val libs = mutableSetOf<String>()
        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                        .forEach { libs.add(it.name.substringAfterLast('/')) }
                }
            } catch (_: Exception) { continue }
        }

        if (libs.containsAll(GECKO_LIBS)) return BrowserType.GECKO
        if (libs.any { it in CHROMIUM_LIBS }) return BrowserType.CHROMIUM
        return BrowserType.UNKNOWN
    }

    private fun checkDataMarkers(pkg: String): BrowserType {
        if (RootHelper.executeCommand("[ -e '/data/data/$pkg/$GECKO_MARKER' ]").success) return BrowserType.GECKO
        for (m in CHROMIUM_MARKERS) {
            if (RootHelper.executeCommand("[ -e '/data/data/$pkg/$m' ]").success) return BrowserType.CHROMIUM
        }
        return BrowserType.UNKNOWN
    }

    private fun checkHeuristics(pkg: String): BrowserType {
        val data = "/data/data/$pkg"
        var geckoScore = 0
        var chromiumScore = 0
        if (RootHelper.executeCommand("[ -f '$data/files/mozilla/installs.ini' ]").success) geckoScore++
        if (RootHelper.executeCommand("[ -f '$data/shared_prefs/GeckoPreferences.xml' ]").success) geckoScore++
        if (RootHelper.executeCommand("grep -rql 'gecko\\|GeckoView' '$data/shared_prefs/' 2>/dev/null").success) geckoScore++

        if (RootHelper.executeCommand("[ -d '$data/app_tabs' ]").success) chromiumScore++
        if (RootHelper.executeCommand("[ -f '$data/databases/ChromeData.db' ]").success) chromiumScore++

        if (geckoScore >= 2) return BrowserType.GECKO
        if (chromiumScore >= 2) return BrowserType.CHROMIUM
        return BrowserType.UNKNOWN
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    fun getCompatibleTargets(source: BrowserInfo, allBrowsers: List<BrowserInfo>): List<BrowserInfo> {
        return allBrowsers.filter { it.packageName != source.packageName && it.type == source.type }
    }
}
