package com.browsermover.app.core

import android.content.Context
import com.browsermover.app.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class JsonPatcher(private val context: Context) {

    companion object {
        private const val WORK_DIR = "/data/local/tmp/browser_migrator"
    }

    data class PatchResult(
        val success: Boolean,
        val message: String
    )

    private suspend fun rootReadFile(path: String): String? =
        withContext(Dispatchers.IO) {
            val result = RootHelper.exec("cat '$path' 2>/dev/null")
            if (result.success && result.stdout.isNotBlank()) result.stdout else null
        }

    private suspend fun rootWriteFile(path: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val stamp = System.currentTimeMillis()
                val tempFile = File(context.cacheDir, "jpatch_$stamp.tmp")
                tempFile.writeText(content, Charsets.UTF_8)

                val result = RootHelper.execMultiple(listOf(
                    "cp -f '${tempFile.absolutePath}' '$path'",
                    "chmod 600 '$path'"
                ))

                tempFile.delete()
                result.success
            } catch (_: Exception) {
                false
            }
        }

    suspend fun neutralizeSecurePreferences(
        securePrefsPath: String,
        srcPkg: String,
        dstPkg: String
    ): PatchResult = withContext(Dispatchers.IO) {
        try {
            val raw = rootReadFile(securePrefsPath)
                ?: return@withContext PatchResult(false,
                    "Could not read Secure Preferences: $securePrefsPath")

            val json = try { JSONObject(raw) }
            catch (e: Exception) {
                return@withContext PatchResult(false,
                    "Secure Prefs JSON parse error: ${e.message}")
            }

            var s = json.toString()
            s = s.replace("/data/data/$srcPkg/", "/data/data/$dstPkg/")
            s = s.replace(srcPkg, dstPkg)

            val patched = JSONObject(s)

            val removed = mutableListOf<String>()
            for (key in listOf("protection", "super_mac", "gaia_cookie", "account_id_hash")) {
                if (patched.has(key)) {
                    patched.remove(key)
                    removed.add(key)
                }
            }

            val written = rootWriteFile(securePrefsPath, patched.toString(2))
            PatchResult(written,
                if (written) "Secure Prefs patched. Removed: ${removed.joinToString()}"
                else "Could not write Secure Prefs")
        } catch (e: Exception) {
            PatchResult(false, "Secure Prefs error: ${e.message}")
        }
    }

    suspend fun patchPreferences(
        prefsPath: String,
        srcPkg: String,
        dstPkg: String,
        srcBaseDir: String = "app_chrome",
        dstBaseDir: String = "app_chrome"
    ): PatchResult = withContext(Dispatchers.IO) {
        try {
            val raw = rootReadFile(prefsPath)
                ?: return@withContext PatchResult(false, "Could not read Preferences")

            var s = raw
            s = s.replace("/data/data/$srcPkg/", "/data/data/$dstPkg/")
            s = s.replace(srcPkg, dstPkg)
            if (srcBaseDir != dstBaseDir) s = s.replace(srcBaseDir, dstBaseDir)

            val validated = try { JSONObject(s) }
            catch (e: Exception) {
                return@withContext PatchResult(false,
                    "Patched Preferences invalid: ${e.message}")
            }

            patchDownloadPaths(validated, srcPkg, dstPkg)

            val written = rootWriteFile(prefsPath, validated.toString(2))
            PatchResult(written,
                if (written) "Preferences patched" else "Write failed")
        } catch (e: Exception) {
            PatchResult(false, "Preferences error: ${e.message}")
        }
    }

    suspend fun patchLocalState(
        localStatePath: String,
        srcPkg: String,
        dstPkg: String
    ): PatchResult = withContext(Dispatchers.IO) {
        try {
            val raw = rootReadFile(localStatePath)
                ?: return@withContext PatchResult(false, "Could not read Local State")

            val s = raw.replace(srcPkg, dstPkg)

            try { JSONObject(s) }
            catch (e: Exception) {
                return@withContext PatchResult(false,
                    "Local State invalid: ${e.message}")
            }

            val written = rootWriteFile(localStatePath, s)
            PatchResult(written,
                if (written) "Local State patched" else "Write failed")
        } catch (e: Exception) {
            PatchResult(false, "Local State error: ${e.message}")
        }
    }

    suspend fun patchGeckoExtensionsJson(
        extensionsJsonPath: String,
        srcPkg: String,
        dstPkg: String,
        srcProfileName: String,
        dstProfileName: String
    ): PatchResult = withContext(Dispatchers.IO) {
        try {
            val raw = rootReadFile(extensionsJsonPath)
                ?: return@withContext PatchResult(false, "Could not read extensions.json")

            var s = raw
            val srcPath = "/data/data/$srcPkg/files/mozilla/$srcProfileName"
            val dstPath = "/data/data/$dstPkg/files/mozilla/$dstProfileName"
            s = s.replace(srcPath, dstPath)
            s = s.replace("/data/data/$srcPkg/", "/data/data/$dstPkg/")

            try { JSONObject(s) }
            catch (e: Exception) {
                return@withContext PatchResult(false,
                    "extensions.json invalid: ${e.message}")
            }

            val written = rootWriteFile(extensionsJsonPath, s)
            PatchResult(written,
                if (written) "extensions.json patched" else "Write failed")
        } catch (e: Exception) {
            PatchResult(false, "extensions.json error: ${e.message}")
        }
    }

    suspend fun syncGeckoUuids(
        srcPrefsPath: String,
        dstPrefsPath: String
    ): PatchResult = withContext(Dispatchers.IO) {
        try {
            val srcContent = rootReadFile(srcPrefsPath)
                ?: return@withContext PatchResult(false, "Source prefs.js could not be read")

            var dstContent = rootReadFile(dstPrefsPath) ?: ""

            val targetPrefs = listOf(
                "extensions.webextensions.uuids",
                "extensions.webextensions.ExtensionStorageIDB.enabled",
                "extensions.enabledScopes",
                "extensions.webextensions.enabledScopes"
            )

            var injectedCount = 0

            for (prefName in targetPrefs) {
                val srcLine = srcContent.lines().find {
                    it.trimStart().startsWith("user_pref(\"$prefName\"")
                } ?: continue

                dstContent = dstContent.lines()
                    .filter { !it.trimStart().startsWith("user_pref(\"$prefName\"") }
                    .joinToString("\n")

                dstContent = dstContent.trimEnd() + "\n$srcLine\n"
                injectedCount++
            }

            if (injectedCount == 0) {
                return@withContext PatchResult(true,
                    "UUID sync: Extension UUID not found in source")
            }

            val written = rootWriteFile(dstPrefsPath, dstContent)
            PatchResult(written,
                if (written) "UUID sync: $injectedCount pref injected"
                else "Could not write prefs.js")
        } catch (e: Exception) {
            PatchResult(false, "UUID sync error: ${e.message}")
        }
    }

    private fun patchDownloadPaths(json: JSONObject, srcPkg: String, dstPkg: String) {
        try {
            for (key in listOf("download", "savefile")) {
                if (json.has(key)) {
                    val obj = json.optJSONObject(key) ?: continue
                    if (obj.has("default_directory")) {
                        obj.put("default_directory",
                            obj.getString("default_directory").replace(srcPkg, dstPkg))
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
