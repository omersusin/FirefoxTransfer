package com.browsermover.app.core

/**
 * Android paket adı doğrulayıcı.
 * Shell injection saldırılarını önler.
 */
object PackageValidator {

    private val PACKAGE_REGEX = Regex(
        "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"
    )

    private val DANGEROUS_CHARS = charArrayOf(
        ';', '&', '|', '`', '$', '(', ')',
        '{', '}', '<', '>', '!', '\\',
        '"', '\'', '\n', '\r', '\t', ' '
    )

    fun isValid(pkg: String): Boolean {
        if (pkg.isBlank() || pkg.length > 256) return false
        if (!PACKAGE_REGEX.matches(pkg)) return false
        if (pkg.any { it in DANGEROUS_CHARS }) return false
        return true
    }

    fun validateOrThrow(pkg: String, label: String = "Paket") {
        if (!isValid(pkg)) {
            throw IllegalArgumentException(
                "$label adı geçersiz veya güvensiz: '$pkg'. " +
                "Sadece harf, rakam, nokta ve alt çizgi içermeli."
            )
        }
    }
}
