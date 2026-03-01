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

    companion object {
        private val GECKO_NATIVE_LIBS = setOf("libxul.so", "libmozglue.so")
        private const val GECKO_LIB_MIN_MATCH = 2

        private val CHROMIUM_NATIVE_LIBS = setOf(
            "libchrome.so", "libmonochrome.so", "libchromeview.so",
            "libstandalonebrowser.so", "libchromiumcontent.so"
        )
        private const val CHROMIUM_LIB_MIN_MATCH = 1

        private const val GECKO_PROFILES_INI = "files/mozilla/profiles.ini"
        private val CHROMIUM_DATA_MARKERS = listOf(
            "app_chrome/Default/Preferences",
            "app_chrome/Local State",
            "app_chromium/Default/Preferences",
            "app_user/Default/Preferences"
        )
    }

    fun detectInstalledBrowsers(engineFilter: BrowserType? = null): List<BrowserInfo> {
        val candidates = findBrowserCandidates()
        val webViewPackages = getWebViewProviderPackages()
        val results = mutableListOf<BrowserInfo>()

        for (candidate in candidates) {
            val pkg = candidate.activityInfo.packageName
            if (pkg in webViewPackages) continue

            val engine = detectEngine(pkg)
            if (engine == BrowserType.UNKNOWN) continue
            if (engineFilter != null && engine != engineFilter) continue

            val appLabel = candidate.loadLabel(context.packageManager).toString()
            results.add(BrowserInfo(appLabel, pkg, engine, true))
        }

        return results.sortedBy { it.name.lowercase() }
    }

    private fun findBrowserCandidates(): List<ResolveInfo> {
        val pm = context.packageManager
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val webHandlers = pm.queryIntentActivities(webIntent, PackageManager.MATCH_ALL)
        val webHandlerPackages = webHandlers.map { it.activityInfo.packageName }.toSet()

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val launcherApps = pm.queryIntentActivities(launcherIntent, 0)
        val launcherPackages = launcherApps.map { it.activityInfo.packageName }.toSet()

        val validPackages = webHandlerPackages.intersect(launcherPackages)
        return webHandlers.filter { it.activityInfo.packageName in validPackages }
            .distinctBy { it.activityInfo.packageName }
    }

    private fun getWebViewProviderPackages(): Set<String> {
        val packages = mutableSetOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys webviewupdate 2>/dev/null"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            Regex("""packageName\s*[=:]\s*(\S+)""").findAll(output).forEach { packages.add(it.groupValues[1].trim()) }
        } catch (_: Exception) {}
        return packages
    }

    private fun detectEngine(packageName: String): BrowserType {
        // 1. Native Libs
        val nativeEngine = detectEngineByNativeLibs(packageName)
        if (nativeEngine != BrowserType.UNKNOWN) return nativeEngine

        // 2. Data Dir
        val dataEngine = detectEngineByDataDir(packageName)
        if (dataEngine != BrowserType.UNKNOWN) return dataEngine

        return BrowserType.UNKNOWN
    }

    private fun detectEngineByNativeLibs(packageName: String): BrowserType {
        val pm = context.packageManager
        val apkPaths = mutableListOf<String>()
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appInfo.sourceDir?.let { apkPaths.add(it) }
            appInfo.splitSourceDirs?.forEach { apkPaths.add(it) }
        } catch (_: Exception) { return BrowserType.UNKNOWN }

        val foundLibs = mutableSetOf<String>()
        for (path in apkPaths) {
            try {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                        .forEach { foundLibs.add(it.name.substringAfterLast('/')) }
                }
            } catch (_: Exception) {}
        }

        if (foundLibs.count { it in GECKO_NATIVE_LIBS } >= GECKO_LIB_MIN_MATCH) return BrowserType.GECKO
        if (foundLibs.count { it in CHROMIUM_NATIVE_LIBS } >= CHROMIUM_LIB_MIN_MATCH) return BrowserType.CHROMIUM
        return BrowserType.UNKNOWN
    }

    private fun detectEngineByDataDir(packageName: String): BrowserType {
        val dataDir = "/data/data/$packageName"
        if (RootHelper.executeCommand("[ -f '$dataDir/$GECKO_PROFILES_INI' ]").success) return BrowserType.GECKO
        for (marker in CHROMIUM_DATA_MARKERS) {
            if (RootHelper.executeCommand("[ -f '$dataDir/$marker' ]").success) return BrowserType.CHROMIUM
        }
        return BrowserType.UNKNOWN
    }

    fun getCompatibleTargets(source: BrowserInfo, allBrowsers: List<BrowserInfo>): List<BrowserInfo> {
        // Only show browsers of the same family (GECKO -> GECKO, CHROMIUM -> CHROMIUM)
        return allBrowsers.filter { it.packageName != source.packageName && it.type == source.type }
    }
}
