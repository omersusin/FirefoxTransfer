package com.browsermover.app.core

import com.browsermover.app.root.RootHelper

/**
 * Reads only the small, non-secret account metadata needed by the UI.
 *
 * Firefox release/beta stores the Firefox Account state as plain XML in
 * `shared_prefs/fxaAppState.xml` (shared preference name `fxaAppState`, key
 * `fxaState`). Nightly/debug uses SecureAbove22Preferences and a Keystore key
 * bound to the source UID, so it cannot be transplanted between packages.
 */
data class FirefoxAccountState(
    val found: Boolean = false,
    val email: String? = null,
    val secureOnly: Boolean = false
) {
    val canTransfer: Boolean get() = found && !secureOnly
}

object FirefoxAccountInspector {

    suspend fun inspect(packageName: String): FirefoxAccountState {
        val dataDir = findDataDir(packageName) ?: return FirefoxAccountState()
        val prefs = "$dataDir/shared_prefs"
        val plain = "$prefs/fxaAppState.xml"
        val secureA = "$prefs/fxaStateAC.xml"
        val secureB = "$prefs/fxaStatePrefAC.xml"

        val hasPlain = BrowserPaths.exists(plain)
        val hasSecure = BrowserPaths.exists(secureA) || BrowserPaths.exists(secureB)
        val secureChannel = packageName.contains("nightly", ignoreCase = true) ||
            packageName.contains("debug", ignoreCase = true)
        val plainHasState = hasPlain && try {
            RootHelper.exec(
                "grep -q 'name=\"fxaState\"' ${BrowserPaths.q(plain)} 2>/dev/null && echo YES || echo NO"
            ).stdout.contains("YES")
        } catch (_: Exception) {
            false
        }

        if (hasSecure && (!plainHasState || secureChannel)) {
            return FirefoxAccountState(found = true, secureOnly = true)
        }
        if (!plainHasState) {
            return FirefoxAccountState()
        }

        val raw = try {
            RootHelper.exec("cat ${BrowserPaths.q(plain)}").stdout
        } catch (_: Exception) {
            ""
        }
        return FirefoxAccountState(
            found = true,
            email = extractEmail(raw),
            secureOnly = false
        )
    }

    private suspend fun findDataDir(packageName: String): String? {
        val result = RootHelper.exec(
            """for p in "/data/data/${packageName}" "/data/user/0/${packageName}"; do
                [ -d "${'$'}p" ] && echo "${'$'}p" && exit 0
            done"""
        )
        return result.outputLines.firstOrNull { it.startsWith("/") }
    }

    private fun extractEmail(raw: String): String? {
        // Quotes may be XML-escaped depending on the platform's XmlSerializer.
        val normalized = raw
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
        val patterns = listOf(
            Regex("\"email\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"email\"\\s*:\\s*\"([^\"]+)\""),
            Regex("email=([^,\\s]+)")
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(normalized)?.groupValues?.getOrNull(1)
                ?.takeIf { it.contains('@') }
        }
    }
}
