package com.browsermover.app.core

/**
 * User-selectable parts of a migration.
 *
 * The backend always keeps a target backup; [copyFirefoxAccount] only applies
 * to Gecko -> Gecko transfers. Firefox release/beta keep the account state in
 * the plain-text `fxaAppState.xml` preference file. Nightly/debug builds use a
 * Keystore-bound store which cannot be moved between app UIDs.
 */
data class MigrationOptions(
    val copyFirefoxAccount: Boolean = true,
    val includeExtensions: Boolean = true,
    val includeOpenTabs: Boolean = true
)
