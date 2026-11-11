package com.browsermover.app.core

import com.browsermover.app.root.RootHelper
import kotlinx.coroutines.delay
import java.io.File

/**
 * Shared, root-aware browser data discovery.
 *
 * Every method talks to the device through [RootHelper]; the app process itself
 * cannot read /data/data directly.
 */
data class ChromiumProfile(
    val dataDir: String,
    val baseDir: String,
    val profileDir: String
) {
    val history: String get() = "$profileDir/History"
    val bookmarks: String get() = "$profileDir/Bookmarks"
    val preferences: String get() = "$profileDir/Preferences"
    val securePreferences: String get() = "$profileDir/Secure Preferences"
    val networkCookies: String get() = "$profileDir/Network/Cookies"
    val legacyCookies: String get() = "$profileDir/Cookies"
}

data class GeckoProfile(
    val dataDir: String,
    val filesDir: String,
    val profileDir: String?,
    val placesDb: String
)

object BrowserPaths {

    private val CHROMIUM_BASE_DIRS = listOf(
        "app_chrome", "app_chromium", "app_brave", "app_vivaldi",
        "app_edge", "app_sbrowser", "app_kiwi", "app_opera",
        "app_ddg", "app_yandex", "app_mi", "app_uc", "app_quark"
    )

    /** Shell-quote a value so it is safe inside a single-quoted string. */
    fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun exists(path: String): Boolean {
        val r = RootHelper.exec("test -e ${q(path)} && echo YES || echo NO")
        return r.stdout.contains("YES")
    }

    suspend fun dataDir(pkg: String): String? {
        for (candidate in listOf("/data/data/$pkg", "/data/user/0/$pkg")) {
            if (exists(candidate)) return candidate
        }
        // Fall back to PackageManager, which also handles secondary users.
        val r = RootHelper.exec(
            "dumpsys package ${q(pkg)} 2>/dev/null | grep -m1 'dataDir=' | " +
                "sed 's/.*dataDir=//' | tr -d ' \\r'"
        )
        for (line in r.stdout.lineSequence()) {
            val path = line.trim()
            if (path.startsWith("/") && exists(path)) return path
        }
        return null
    }

    suspend fun chromiumProfile(pkg: String): ChromiumProfile? {
        val dd = dataDir(pkg) ?: return null

        var base: String? = null
        for (name in CHROMIUM_BASE_DIRS) {
            val candidate = "$dd/$name"
            if (exists(candidate)) {
                base = candidate
                break
            }
        }

        if (base == null) {
            val found = RootHelper.exec(
                "find ${q(dd)} -maxdepth 4 -type f \\( -name History -o -name Bookmarks \\) " +
                    "2>/dev/null | head -1"
            ).stdout.lineSequence().firstOrNull { it.startsWith("/") }?.trim()
            if (found != null) {
                base = File(found).parentFile?.parentFile?.absolutePath
            }
        }
        if (base == null) return null

        val profile = selectChromiumProfile(base) ?: return null
        return ChromiumProfile(dd, base, profile)
    }

    /**
     * Pick the profile that actually contains data instead of blindly using
     * "Default". Chrome creates "Profile 1", "Profile 2", ... for extra users.
     */
    private suspend fun selectChromiumProfile(base: String): String? {
        val default = "$base/Default"
        val candidates = mutableListOf<String>()

        val listed = RootHelper.exec(
            "find ${q(base)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null"
        ).outputLines.filter { it.startsWith("/") }.filter { dir ->
            val name = dir.substringAfterLast('/')
            name == "Default" || name.startsWith("Profile")
        }
        if (listed.isNotEmpty()) candidates.addAll(listed)
        if (exists(default) && default !in candidates) candidates.add(0, default)

        if (candidates.isEmpty()) return null

        var best: String? = null
        var bestScore = -1L
        for (dir in candidates) {
            if (!exists(dir)) continue
            val size = RootHelper.exec(
                "for f in ${q("$dir/History")} ${q("$dir/Bookmarks")} ${q("$dir/Preferences")}; do " +
                    "[ -f \"\$f\" ] && stat -c '%s' \"\$f\" 2>/dev/null; done | " +
                    "awk '{s+=\$1} END {print s+0}'"
            ).stdout.trim().toLongOrNull() ?: 0L
            // A profile with real data wins. Default wins ties.
            val score = if (dir == default && size > 0) size + 1 else size
            if (score > bestScore) {
                bestScore = score
                best = dir
            }
        }
        return best
    }

    suspend fun ensureChromiumProfile(pkg: String): ChromiumProfile? {
        // A directory alone is not enough: a fresh Chromium fork can create
        // app_chrome/Default without ever writing History or Bookmarks. Start
        // it once so there is a real database to merge into.
        val existing = chromiumProfile(pkg)
        if (existing != null && exists(existing.history)) {
            return existing
        }

        startAndStop(pkg)
        delay(8_000)
        val afterStart = chromiumProfile(pkg)
        if (afterStart != null && exists(afterStart.history)) {
            return afterStart
        }

        // Last resort: create the conventional directory. The browser fills in
        // the rest on its next launch; the migration only needs the directory.
        val dd = dataDir(pkg) ?: return null
        val base = "$dd/${guessChromiumBase(pkg)}"
        val profile = "$base/Default"
        RootHelper.execMultiple(listOf(
            "mkdir -p ${q(profile)}",
            "chmod 700 ${q(profile)}"
        ))
        return chromiumProfile(pkg)
    }

    private fun guessChromiumBase(pkg: String): String = when {
        pkg.contains("brave") -> "app_brave"
        pkg.contains("vivaldi") -> "app_vivaldi"
        pkg.contains("emmx") || pkg.contains("edge") -> "app_edge"
        pkg.contains("sbrowser") -> "app_sbrowser"
        pkg.contains("kiwi") -> "app_kiwi"
        pkg.contains("opera") -> "app_opera"
        pkg.contains("duckduckgo") -> "app_ddg"
        else -> "app_chrome"
    }

    suspend fun geckoProfile(pkg: String): GeckoProfile? {
        val dd = dataDir(pkg) ?: return null
        val files = "$dd/files"
        val mozilla = "$files/mozilla"

        var profile: String? = null
        val ini = "$mozilla/profiles.ini"
        if (exists(ini)) {
            val path = RootHelper.exec(
                "grep -m1 '^Path=' ${q(ini)} 2>/dev/null | cut -d= -f2- | tr -d '\\r'"
            ).stdout.trim()
            if (path.isNotEmpty() && exists("$mozilla/$path")) {
                profile = "$mozilla/$path"
            }
        }

        if (profile == null && exists(mozilla)) {
            val dirs = RootHelper.exec(
                "find ${q(mozilla)} -mindepth 1 -maxdepth 1 -type d 2>/dev/null"
            ).outputLines.filter { it.startsWith("/") }
            profile = dirs.firstOrNull { it.contains(".default") } ?: dirs.firstOrNull()
        }

        val places = when {
            exists("$files/places.sqlite") -> "$files/places.sqlite"
            profile != null && exists("$profile/places.sqlite") -> "$profile/places.sqlite"
            else -> null
        }

        if (profile == null && places == null) return null
        return GeckoProfile(dd, files, profile, places ?: "$files/places.sqlite")
    }

    suspend fun ensureGeckoProfile(pkg: String): GeckoProfile? {
        geckoProfile(pkg)?.let { if (exists(it.placesDb)) return it }

        startAndStop(pkg)
        delay(10_000)
        geckoProfile(pkg)?.let { if (exists(it.placesDb)) return it }

        // Create a conventional profile only if one is completely absent. A
        // browser will initialise it on the next launch.
        val dd = dataDir(pkg) ?: return null
        val mozilla = "$dd/files/mozilla"
        if (!exists("$mozilla/profiles.ini")) {
            val name = "migrator.${System.currentTimeMillis() / 1000}.default"
            val profile = "$mozilla/$name"
            RootHelper.execMultiple(listOf(
                "mkdir -p ${q(profile)}",
                "cat > ${q("$mozilla/profiles.ini")} <<'EOF'\n" +
                    "[Profile0]\n" +
                    "Name=default\n" +
                    "IsRelative=1\n" +
                    "Path=$name\n" +
                    "Default=1\n" +
                    "[General]\n" +
                    "StartWithLastProfile=1\n" +
                    "Version=2\n" +
                    "EOF"
            ))
        }
        return geckoProfile(pkg)
    }

    suspend fun startAndStop(pkg: String): Boolean {
        val r = RootHelper.execMultiple(listOf(
            "am force-stop ${q(pkg)}",
            "act=\$(cmd package resolve-activity --brief ${q(pkg)} 2>/dev/null | tail -1)",
            "if [ -n \"\$act\" ]; then am start -n \"\$act\" >/dev/null 2>&1; " +
                "else monkey -p ${q(pkg)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; fi"
        ))
        return r.success
    }

    suspend fun uid(pkg: String): String? {
        val dd = dataDir(pkg) ?: return null
        val r = RootHelper.exec("stat -c '%u' ${q(dd)} 2>/dev/null")
        return r.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }
}
