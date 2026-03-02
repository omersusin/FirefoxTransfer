package com.browsermover.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.browsermover.app.model.BrowserInfo
import com.browsermover.app.model.BrowserType
import com.browsermover.app.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BrowserDetector(private val context: Context) {

    companion object {
        private val KNOWN_GECKO = setOf(
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.fenix.nightly",
            "org.mozilla.focus",
            "org.mozilla.klar",
            "org.mozilla.reference.browser",
            "org.mozilla.fennec_fdroid",
            "org.torproject.torbrowser",
            "org.torproject.torbrowser_alpha",
            "us.spotco.fennec_dos",
            "org.gnu.icecat",
            "io.github.nicothin.nicofox"
        )

        private val KNOWN_CHROMIUM = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser",
            "com.vivaldi.browser.snapshot",
            "com.microsoft.emmx",
            "com.microsoft.emmx.beta",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser.beta",
            "com.duckduckgo.mobile.android"
        )
    }

    suspend fun detectAllBrowsers(): List<BrowserInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = discoverBrowserPackages(pm)
        val browsers = mutableListOf<BrowserInfo>()

        for (pkg in packages) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null }
                val version = try {
                    pm.getPackageInfo(pkg, 0).versionName ?: ""
                } catch (_: Exception) { "" }

                browsers.add(
                    BrowserInfo(
                        packageName = pkg,
                        appName = appName,
                        browserType = detectEngineType(pkg),
                        icon = icon,
                        versionName = version
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) { }
        }

        browsers.sortedWith(compareBy({ it.browserType.ordinal }, { it.appName.lowercase() }))
    }

    suspend fun detectEngineType(packageName: String): BrowserType {
        if (packageName in KNOWN_GECKO) return BrowserType.GECKO
        if (packageName in KNOWN_CHROMIUM) return BrowserType.CHROMIUM
        return detectEngineByFileSystem(packageName)
    }

    suspend fun createManualBrowserInfo(packageName: String): BrowserInfo {
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            BrowserInfo(
                packageName = packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                browserType = detectEngineType(packageName),
                icon = try { pm.getApplicationIcon(appInfo) } catch (_: Exception) { null },
                versionName = try {
                    pm.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }
            )
        } catch (_: PackageManager.NameNotFoundException) {
            BrowserInfo(
                packageName = packageName,
                appName = packageName,
                browserType = BrowserType.UNKNOWN,
                isInstalled = false
            )
        }
    }

    private fun discoverBrowserPackages(pm: PackageManager): Set<String> {
        val packages = mutableSetOf<String>()

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName) packages.add(pkg)
            }
        } catch (_: Exception) { }

        for (pkg in KNOWN_GECKO + KNOWN_CHROMIUM) {
            try {
                pm.getApplicationInfo(pkg, 0)
                packages.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) { }
        }

        return packages
    }

    private suspend fun detectEngineByFileSystem(pkg: String): BrowserType {
        try {
            val geckoCheck = RootHelper.exec(
                "[ -d /data/data/$pkg/files/mozilla ] && echo GECKO"
            )
            if ("GECKO" in geckoCheck.stdout) return BrowserType.GECKO

            val chromiumCheck = RootHelper.exec(
                """for d in app_chrome app_chromium app_brave app_vivaldi; do
                    [ -d "/data/data/$pkg/${'$'}d" ] && echo CHROMIUM && break
                done"""
            )
            if ("CHROMIUM" in chromiumCheck.stdout) return BrowserType.CHROMIUM
        } catch (_: Exception) { }

        return BrowserType.UNKNOWN
    }
}
