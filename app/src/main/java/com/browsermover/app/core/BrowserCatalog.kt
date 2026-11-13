package com.browsermover.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.browsermover.app.model.BrowserType

/** A browser that is actually installed on the device. */
data class InstalledBrowser(
    val packageName: String,
    val label: String,
    val type: BrowserType,
    val icon: Drawable?,
    val isKnown: Boolean = true
)

/**
 * Known browser packages plus a package-manager lookup. The manifest declares
 * these packages in <queries>, so no QUERY_ALL_PACKAGES permission is needed.
 */
object BrowserCatalog {

    private data class Known(val pkg: String, val label: String, val type: BrowserType)

    private val KNOWN = listOf(
        Known("org.mozilla.firefox", "Firefox", BrowserType.GECKO),
        Known("org.mozilla.firefox_beta", "Firefox Beta", BrowserType.GECKO),
        Known("org.mozilla.fenix.nightly", "Firefox Nightly", BrowserType.GECKO),
        Known("org.mozilla.fenix", "Firefox Preview", BrowserType.GECKO),
        Known("org.mozilla.focus", "Firefox Focus", BrowserType.GECKO),
        Known("org.mozilla.klar", "Klar", BrowserType.GECKO),
        Known("org.mozilla.fennec_fdroid", "Fennec F-Droid", BrowserType.GECKO),
        Known("org.torproject.torbrowser", "Tor Browser", BrowserType.GECKO),
        Known("org.torproject.torbrowser_alpha", "Tor Browser Alpha", BrowserType.GECKO),
        Known("us.spotco.fennec_dos", "Mull", BrowserType.GECKO),
        Known("io.github.forkmaintainers.iceraven", "Iceraven", BrowserType.GECKO),
        Known("org.gnu.icecat", "IceCat", BrowserType.GECKO),
        Known("com.android.chrome", "Chrome", BrowserType.CHROMIUM),
        Known("com.chrome.beta", "Chrome Beta", BrowserType.CHROMIUM),
        Known("com.chrome.dev", "Chrome Dev", BrowserType.CHROMIUM),
        Known("com.chrome.canary", "Chrome Canary", BrowserType.CHROMIUM),
        Known("org.barebrowser", "Bare Browser", BrowserType.CHROMIUM),
        Known("com.brave.browser", "Brave", BrowserType.CHROMIUM),
        Known("com.brave.browser_beta", "Brave Beta", BrowserType.CHROMIUM),
        Known("com.kiwibrowser.browser", "Kiwi Browser", BrowserType.CHROMIUM),
        Known("com.vivaldi.browser", "Vivaldi", BrowserType.CHROMIUM),
        Known("com.microsoft.emmx", "Microsoft Edge", BrowserType.CHROMIUM),
        Known("com.opera.browser", "Opera", BrowserType.CHROMIUM),
        Known("com.sec.android.app.sbrowser", "Samsung Internet", BrowserType.CHROMIUM),
        Known("com.duckduckgo.mobile.android", "DuckDuckGo", BrowserType.CHROMIUM),
        Known("com.yandex.browser", "Yandex Browser", BrowserType.CHROMIUM),
        Known("com.mi.globalbrowser", "Mi Browser", BrowserType.CHROMIUM),
        Known("com.uc.browser.en", "UC Browser", BrowserType.CHROMIUM),
        Known("com.quark.browser", "Quark", BrowserType.CHROMIUM)
    )

    private val knownByPackage = KNOWN.associateBy { it.pkg }

    fun installed(context: Context): List<InstalledBrowser> {
        val pm = context.packageManager
        return KNOWN.mapNotNull { known ->
            val info = applicationInfo(pm, known.pkg) ?: return@mapNotNull null
            InstalledBrowser(
                packageName = known.pkg,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(known.label),
                type = known.type,
                icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
            )
        }.sortedWith(compareBy({ it.type.ordinal }, { it.label.lowercase() }))
    }

    fun findByPackage(context: Context, pkg: String): InstalledBrowser? {
        val pm = context.packageManager
        val info = applicationInfo(pm, pkg) ?: return null
        val known = knownByPackage[pkg]
        return InstalledBrowser(
            packageName = pkg,
            label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(known?.label ?: pkg),
            type = known?.type ?: BrowserType.UNKNOWN,
            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
            isKnown = known != null
        )
    }

    fun labelFor(pkg: String): String = knownByPackage[pkg]?.label ?: pkg

    fun typeFor(pkg: String): BrowserType = knownByPackage[pkg]?.type ?: BrowserType.UNKNOWN

    fun isInstalled(context: Context, pkg: String): Boolean =
        applicationInfo(context.packageManager, pkg) != null

    private fun applicationInfo(pm: PackageManager, pkg: String): android.content.pm.ApplicationInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        }.getOrNull()
}
