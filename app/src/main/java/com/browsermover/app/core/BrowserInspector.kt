package com.browsermover.app.core

import com.browsermover.app.model.BrowserType

/** Read-only preflight information shown before a migration starts. */
internal object BrowserInspector {

    data class ExtensionPreview(
        val report: ExtensionMigrator.Report?,
        val warning: String? = null
    )

    suspend fun extensions(
        sourcePkg: String,
        sourceType: BrowserType,
        targetPkg: String,
        targetType: BrowserType
    ): ExtensionPreview {
        if (sourceType == BrowserType.UNKNOWN || targetType == BrowserType.UNKNOWN) {
            return ExtensionPreview(null, "Unknown browser engine")
        }
        return try {
            val source = when (sourceType) {
                BrowserType.GECKO -> BrowserPaths.geckoProfile(sourcePkg)
                    ?.let { ExtensionMigrator.collectGecko(it) }
                BrowserType.CHROMIUM -> BrowserPaths.chromiumProfile(sourcePkg)
                    ?.let { ExtensionMigrator.collectChromium(it) }
                else -> null
            } ?: return ExtensionPreview(null, "Source profile is not available")
            val target = when (targetType) {
                BrowserType.GECKO -> BrowserPaths.geckoProfile(targetPkg)
                    ?.let { ExtensionMigrator.collectGecko(it) }
                BrowserType.CHROMIUM -> BrowserPaths.chromiumProfile(targetPkg)
                    ?.let { ExtensionMigrator.collectChromium(it) }
                else -> null
            } ?: emptyList()
            ExtensionPreview(
                ExtensionMigrator.buildReport(sourceType, targetType, source, target)
            )
        } catch (e: Exception) {
            ExtensionPreview(null, e.message)
        }
    }
}
