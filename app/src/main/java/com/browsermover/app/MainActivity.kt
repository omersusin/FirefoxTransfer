package com.browsermover.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.browsermover.app.core.BrowserCatalog
import com.browsermover.app.core.BrowserInspector
import com.browsermover.app.core.DataMover
import com.browsermover.app.core.ExtensionMigrator
import com.browsermover.app.core.FirefoxAccountInspector
import com.browsermover.app.core.FirefoxAccountState
import com.browsermover.app.core.InstalledBrowser
import com.browsermover.app.core.MigrationOptions
import com.browsermover.app.core.PackageValidator
import com.browsermover.app.databinding.ActivityMainBinding
import com.browsermover.app.databinding.ItemBrowserBinding
import com.browsermover.app.databinding.ItemExtensionBinding
import com.browsermover.app.databinding.ItemWarningBinding
import com.browsermover.app.databinding.SheetBrowserPickerBinding
import com.browsermover.app.databinding.SheetSettingsBinding
import com.browsermover.app.model.BrowserType
import com.browsermover.app.model.MigrationResult
import com.browsermover.app.root.RootHelper
import com.browsermover.app.root.ShizukuShell
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapes
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        const val SCREEN_HOME = 0
        const val SCREEN_REVIEW = 1
        const val SCREEN_PROGRESS = 2
        const val SCREEN_RESULT = 3
        const val SCREEN_EXTENSIONS = 4

        const val PREFS = "browser_migrator_ui"
        const val KEY_SOURCE = "source_package"
        const val KEY_TARGET = "target_package"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var mover: DataMover

    private var installedBrowsers: List<InstalledBrowser> = emptyList()
    private var sourceBrowser: InstalledBrowser? = null
    private var targetBrowser: InstalledBrowser? = null

    private var sourceAccount: FirefoxAccountState? = null
    private var targetAccount: FirefoxAccountState? = null
    private var extensionPreview: BrowserInspector.ExtensionPreview? = null

    private var preflightJob: Job? = null
    private var extensionJob: Job? = null
    private var migrationJob: Job? = null

    private var currentScreen = SCREEN_HOME
    private var extensionsFromResult = false
    private var isMigrating = false
    private var hasPrivilegedAccess = false
    private var accessLabelText = ""

    private var migratedSource: InstalledBrowser? = null
    private var migratedTarget: InstalledBrowser? = null
    private var migrationStartedAt = 0L
    private var lastResult: MigrationResult? = null

    private val logBuilder = SpannableStringBuilder()

    private val cPrimary by lazy { ContextCompat.getColor(this, R.color.md_primary) }
    private val cOnPrimaryContainer by lazy { ContextCompat.getColor(this, R.color.md_on_primary_container) }
    private val cPrimaryContainer by lazy { ContextCompat.getColor(this, R.color.md_primary_container) }
    private val cTertiaryContainer by lazy { ContextCompat.getColor(this, R.color.md_tertiary_container) }
    private val cOnTertiaryContainer by lazy { ContextCompat.getColor(this, R.color.md_on_tertiary_container) }
    private val cSurfaceHigh by lazy { ContextCompat.getColor(this, R.color.md_surface_container_high) }
    private val cOnSurface by lazy { ContextCompat.getColor(this, R.color.md_on_surface) }
    private val cOnSurfaceVariant by lazy { ContextCompat.getColor(this, R.color.md_on_surface_variant) }
    private val cSuccess by lazy { ContextCompat.getColor(this, R.color.md_success) }
    private val cWarning by lazy { ContextCompat.getColor(this, R.color.md_warning) }
    private val cError by lazy { ContextCompat.getColor(this, R.color.md_error) }
    private val cOnErrorContainer by lazy { ContextCompat.getColor(this, R.color.md_on_error_container) }
    private val cErrorContainer by lazy { ContextCompat.getColor(this, R.color.md_error_container) }
    private val cOnPrimary by lazy { ContextCompat.getColor(this, R.color.md_on_primary) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        mover = DataMover(this)
        ShizukuShell.initialize(this)

        setupListeners()
        setupBackNavigation()
        restoreSelection()
        updateUi()
        refreshAccessState()
    }

    override fun onDestroy() {
        preflightJob?.cancel()
        extensionJob?.cancel()
        migrationJob?.cancel()
        ShizukuShell.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isMigrating -> toast(getString(R.string.do_not_close))
                    currentScreen == SCREEN_EXTENSIONS -> showExtensionsBack()
                    currentScreen == SCREEN_REVIEW -> showScreen(SCREEN_HOME, forward = false)
                    currentScreen == SCREEN_RESULT -> showScreen(SCREEN_HOME, forward = false)
                    currentScreen == SCREEN_PROGRESS -> toast(getString(R.string.do_not_close))
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun showScreen(index: Int, forward: Boolean = true) {
        if (index == currentScreen) return
        b.screenFlipper.setInAnimation(
            this,
            if (forward) R.anim.slide_in_right else R.anim.slide_in_left
        )
        b.screenFlipper.setOutAnimation(
            this,
            if (forward) R.anim.slide_out_left else R.anim.slide_out_right
        )
        b.screenFlipper.displayedChild = index
        currentScreen = index
    }

    private fun showHome() {
        lastResult = null
        showScreen(SCREEN_HOME, forward = false)
        refreshAccessState()
    }

    private fun showExtensionsBack() {
        showScreen(
            if (extensionsFromResult) SCREEN_RESULT else SCREEN_REVIEW,
            forward = false
        )
    }

    // ---------------------------------------------------------------------
    // Setup and browser selection
    // ---------------------------------------------------------------------

    private fun setupListeners() {
        b.screenHome.btnHomeSettings.setOnClickListener { showSettings() }
        b.screenHome.cardSource.setOnClickListener { showBrowserPicker(isSource = true) }
        b.screenHome.cardTarget.setOnClickListener { showBrowserPicker(isSource = false) }
        b.screenHome.btnSwap.setOnClickListener { swapBrowsers() }
        b.screenHome.btnReview.setOnClickListener { openReview() }
        b.screenHome.btnAccess.setOnClickListener { requestAccess() }

        b.screenReview.btnReviewBack.setOnClickListener { showScreen(SCREEN_HOME, forward = false) }
        b.screenReview.btnReviewCancel.setOnClickListener { showScreen(SCREEN_HOME, forward = false) }
        b.screenReview.btnStart.setOnClickListener { startMigration() }

        b.screenProgress.btnToggleLog.setOnClickListener { toggleLog() }

        b.screenResult.btnViewExtensions.setOnClickListener { openExtensions() }
        b.screenResult.btnResultLog.setOnClickListener { showLogDialog() }
        b.screenResult.btnRestore.setOnClickListener { showRestoreDialog() }
        b.screenResult.btnNewMigration.setOnClickListener { showHome() }
        b.screenResult.btnShareReport.setOnClickListener { shareReport() }

        b.screenExtensions.btnExtensionsBack.setOnClickListener { showExtensionsBack() }
    }

    private fun restoreSelection() {
        installedBrowsers = BrowserCatalog.installed(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedSource = prefs.getString(KEY_SOURCE, null)
        val savedTarget = prefs.getString(KEY_TARGET, null)

        sourceBrowser = savedSource?.let { pkg -> installedBrowsers.firstOrNull { it.packageName == pkg } }
        targetBrowser = savedTarget?.let { pkg -> installedBrowsers.firstOrNull { it.packageName == pkg } }

        if (sourceBrowser == null) sourceBrowser = installedBrowsers.firstOrNull()
        if (targetBrowser == null || targetBrowser?.packageName == sourceBrowser?.packageName) {
            targetBrowser = installedBrowsers.firstOrNull {
                it.packageName != sourceBrowser?.packageName
            }
        }
        saveSelection()
    }

    private fun saveSelection() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SOURCE, sourceBrowser?.packageName)
            .putString(KEY_TARGET, targetBrowser?.packageName)
            .apply()
    }

    private fun selectBrowser(isSource: Boolean, browser: InstalledBrowser) {
        val other = if (isSource) targetBrowser else sourceBrowser
        if (other?.packageName == browser.packageName) {
            toast(getString(R.string.same_browser))
            return
        }
        if (isSource) sourceBrowser = browser else targetBrowser = browser
        saveSelection()
        clearPreflight()
        updateUi()
    }

    private fun swapBrowsers() {
        val source = sourceBrowser
        sourceBrowser = targetBrowser
        targetBrowser = source
        saveSelection()
        clearPreflight()
        updateUi()
    }

    private fun clearPreflight() {
        preflightJob?.cancel()
        extensionJob?.cancel()
        sourceAccount = null
        targetAccount = null
        extensionPreview = null
        b.screenReview.rowReviewAccount.visibility = View.GONE
        b.screenReview.txtReviewAccountNote.visibility = View.GONE
        b.screenReview.txtExtensionsCount.text = getString(R.string.move_extensions_desc)
    }

    private fun updateUi() {
        renderBrowserCard(sourceBrowser, isSource = true)
        renderBrowserCard(targetBrowser, isSource = false)

        val validPair = sourceBrowser != null &&
            targetBrowser != null &&
            sourceBrowser?.packageName != targetBrowser?.packageName

        b.screenHome.btnReview.isEnabled = validPair
        b.screenHome.btnReview.alpha = if (validPair) 1f else 0.5f
        updateStartButton()
        updateRoute()

        val geckoPair = sourceBrowser?.type == BrowserType.GECKO &&
            targetBrowser?.type == BrowserType.GECKO
        b.screenHome.cardAccount.visibility = if (geckoPair) View.VISIBLE else View.GONE
        b.screenReview.rowReviewAccount.visibility = if (geckoPair) View.VISIBLE else View.GONE
        b.screenReview.txtReviewAccountNote.visibility = if (geckoPair) View.VISIBLE else View.GONE
        if (geckoPair) {
            updateAccountUi()
        } else {
            b.screenHome.txtHomeAccountDesc.text = getString(R.string.account_not_applicable)
            b.screenReview.txtReviewAccountDesc.text = getString(R.string.account_not_applicable)
            b.screenReview.switchAccount.isChecked = false
            b.screenReview.switchAccount.isEnabled = false
        }
        updateExtensionCount()
    }

    /**
     * The account toggle is only meaningful after the source shared_prefs have
     * been inspected. Keep Start disabled until that preflight finishes so a
     * quick tap cannot silently skip the Firefox Account copy.
     */
    private fun updateStartButton() {
        val validPair = sourceBrowser != null &&
            targetBrowser != null &&
            sourceBrowser?.packageName != targetBrowser?.packageName
        val geckoPair = sourceBrowser?.type == BrowserType.GECKO &&
            targetBrowser?.type == BrowserType.GECKO
        val preflightReady = !geckoPair || sourceAccount != null
        val enabled = validPair && preflightReady && !isMigrating
        b.screenReview.btnStart.isEnabled = enabled
        b.screenReview.btnStart.alpha = if (enabled) 1f else 0.55f
    }

    private fun renderBrowserCard(browser: InstalledBrowser?, isSource: Boolean) {
        val label = if (isSource) b.screenHome.txtSourceLabel else b.screenHome.txtTargetLabel
        val meta = if (isSource) b.screenHome.txtSourceMeta else b.screenHome.txtTargetMeta
        val image = if (isSource) b.screenHome.imgSourceIcon else b.screenHome.imgTargetIcon
        val frame = if (isSource) b.screenHome.sourceIconFrame else b.screenHome.targetIconFrame

        if (browser == null) {
            label.text = getString(R.string.no_browser_selected)
            meta.text = getString(
                if (isSource) R.string.choose_source else R.string.choose_target
            )
            image.setImageResource(R.drawable.ic_browser)
            image.imageTintList = ColorStateList.valueOf(cPrimary)
            frame.backgroundTintList = null
        } else {
            val icon = browser.icon
            if (icon != null) {
                image.setImageDrawable(icon)
                image.imageTintList = null
            } else {
                image.setImageResource(R.drawable.ic_browser)
                image.imageTintList = ColorStateList.valueOf(cPrimary)
            }
            label.text = browser.label
            meta.text = getString(
                R.string.route_summary,
                engineLabel(browser.type),
                browser.packageName
            )
            frame.backgroundTintList = null
        }
    }

    private fun updateRoute() {
        val source = sourceBrowser
        val target = targetBrowser
        val card = b.screenHome.cardRoute
        val icon = b.screenHome.imgRoute

        when {
            source == null || target == null -> {
                b.screenHome.txtRoute.text = getString(R.string.route_pick_both)
                b.screenHome.txtRouteDesc.text = getString(R.string.home_subtitle)
                card.setCardBackgroundColor(cSurfaceHigh)
                b.screenHome.txtRoute.setTextColor(cOnSurface)
                b.screenHome.txtRouteDesc.setTextColor(cOnSurfaceVariant)
                icon.imageTintList = ColorStateList.valueOf(cOnSurfaceVariant)
            }
            source.packageName == target.packageName -> {
                b.screenHome.txtRoute.text = getString(R.string.same_browser)
                b.screenHome.txtRouteDesc.text = getString(R.string.route_pick_both)
                card.setCardBackgroundColor(cSurfaceHigh)
                b.screenHome.txtRoute.setTextColor(cError)
                b.screenHome.txtRouteDesc.setTextColor(cOnSurfaceVariant)
                icon.imageTintList = ColorStateList.valueOf(cError)
            }
            source.type == BrowserType.UNKNOWN || target.type == BrowserType.UNKNOWN -> {
                b.screenHome.txtRoute.text = getString(R.string.engine_unknown)
                b.screenHome.txtRouteDesc.text = getString(R.string.route_cross)
                card.setCardBackgroundColor(cTertiaryContainer)
                b.screenHome.txtRoute.setTextColor(cOnTertiaryContainer)
                b.screenHome.txtRouteDesc.setTextColor(cOnTertiaryContainer)
                icon.imageTintList = ColorStateList.valueOf(cOnTertiaryContainer)
            }
            source.type == target.type -> {
                b.screenHome.txtRoute.text = getString(R.string.route_same)
                b.screenHome.txtRouteDesc.text = engineLabel(source.type)
                card.setCardBackgroundColor(cPrimaryContainer)
                b.screenHome.txtRoute.setTextColor(cOnPrimaryContainer)
                b.screenHome.txtRouteDesc.setTextColor(cOnPrimaryContainer)
                icon.imageTintList = ColorStateList.valueOf(cOnPrimaryContainer)
            }
            else -> {
                b.screenHome.txtRoute.text = getString(R.string.route_cross)
                b.screenHome.txtRouteDesc.text = getString(
                    R.string.route_summary,
                    engineLabel(source.type),
                    engineLabel(target.type)
                )
                card.setCardBackgroundColor(cTertiaryContainer)
                b.screenHome.txtRoute.setTextColor(cOnTertiaryContainer)
                b.screenHome.txtRouteDesc.setTextColor(cOnTertiaryContainer)
                icon.imageTintList = ColorStateList.valueOf(cOnTertiaryContainer)
            }
        }
    }

    private fun engineLabel(type: BrowserType): String = when (type) {
        BrowserType.GECKO -> getString(R.string.engine_gecko)
        BrowserType.CHROMIUM -> getString(R.string.engine_chromium)
        BrowserType.UNKNOWN -> getString(R.string.engine_unknown)
    }

    // ---------------------------------------------------------------------
    // Browser picker
    // ---------------------------------------------------------------------

    private fun showBrowserPicker(isSource: Boolean) {
        val binding = SheetBrowserPickerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(binding.root)
        binding.txtPickerTitle.text = getString(
            if (isSource) R.string.choose_source else R.string.choose_target
        )

        val selected = if (isSource) sourceBrowser else targetBrowser
        if (installedBrowsers.isEmpty()) {
            binding.layoutBrowserList.addView(TextView(this).apply {
                text = getString(R.string.no_installed_browsers)
                setTextColor(cOnSurfaceVariant)
                textSize = 13f
                setPadding(8, 16, 8, 16)
            })
        } else {
            installedBrowsers.forEach { browser ->
                val item = ItemBrowserBinding.inflate(
                    layoutInflater,
                    binding.layoutBrowserList,
                    false
                )
                item.txtBrowserName.text = browser.label
                item.txtBrowserPackage.text = browser.packageName
                item.txtBrowserEngine.text = engineLabel(browser.type)
                val chipColor = when (browser.type) {
                    BrowserType.GECKO -> cTertiaryContainer
                    BrowserType.CHROMIUM -> cPrimaryContainer
                    BrowserType.UNKNOWN -> cSurfaceHigh
                }
                val chipTextColor = when (browser.type) {
                    BrowserType.GECKO -> cOnTertiaryContainer
                    BrowserType.CHROMIUM -> cOnPrimaryContainer
                    BrowserType.UNKNOWN -> cOnSurfaceVariant
                }
                item.txtBrowserEngine.backgroundTintList = ColorStateList.valueOf(chipColor)
                item.txtBrowserEngine.setTextColor(chipTextColor)
                val icon = browser.icon
                if (icon != null) {
                    item.imgBrowserIcon.setImageDrawable(icon)
                }
                item.imgBrowserCheck.visibility =
                    if (browser.packageName == selected?.packageName) View.VISIBLE else View.GONE
                item.root.setOnClickListener {
                    selectBrowser(isSource, browser)
                    dialog.dismiss()
                }
                binding.layoutBrowserList.addView(item.root)
            }
        }

        binding.btnAddCustom.setOnClickListener {
            val pkg = binding.etCustomPackage.text?.toString()?.trim().orEmpty()
            if (!PackageValidator.isValid(pkg)) {
                binding.etCustomPackage.error = getString(R.string.package_not_installed)
                return@setOnClickListener
            }
            if (!BrowserCatalog.isInstalled(this, pkg)) {
                binding.etCustomPackage.error = getString(R.string.package_not_installed)
                return@setOnClickListener
            }
            val browser = BrowserCatalog.findByPackage(this, pkg)
            if (browser == null) {
                binding.etCustomPackage.error = getString(R.string.package_not_installed)
                return@setOnClickListener
            }
            selectBrowser(isSource, browser)
            dialog.dismiss()
        }
        binding.btnCancelPicker.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ---------------------------------------------------------------------
    // Access
    // ---------------------------------------------------------------------

    private fun refreshAccessState(force: Boolean = false) {
        lifecycleScope.launch {
            if (force) RootHelper.invalidateRootCache()
            val root = RootHelper.checkRoot()
            val label = RootHelper.accessLabel()
            hasPrivilegedAccess = root
            accessLabelText = label
            updateAccessUi(root, label)
        }
    }

    private suspend fun refreshAccessNow(force: Boolean = false): Pair<Boolean, String> {
        if (force) RootHelper.invalidateRootCache()
        val root = RootHelper.checkRoot()
        val label = RootHelper.accessLabel()
        hasPrivilegedAccess = root
        accessLabelText = label
        updateAccessUi(root, label)
        return root to label
    }

    private fun updateAccessUi(root: Boolean, label: String) {
        b.screenHome.txtAccessTitle.text = getString(
            if (root) R.string.access_ready else R.string.access_missing
        )
        b.screenHome.txtAccessDesc.text = label
        b.screenHome.dotAccess.backgroundTintList =
            ColorStateList.valueOf(if (root) cSuccess else cWarning)
        b.screenHome.btnAccess.visibility = if (root) View.GONE else View.VISIBLE
        b.screenHome.btnAccess.text = getString(
            if (ShizukuShell.installed()) R.string.action_request_shizuku
            else R.string.action_retry
        )
        b.screenReview.txtReviewAccess.text = getString(
            if (root) R.string.access_ready else R.string.access_missing
        )
        b.screenReview.txtReviewAccess.backgroundTintList = ColorStateList.valueOf(
            if (root) cPrimaryContainer else cErrorContainer
        )
        b.screenReview.txtReviewAccess.setTextColor(
            if (root) cOnPrimaryContainer else cOnErrorContainer
        )
    }

    private fun requestAccess() {
        if (ShizukuShell.installed()) {
            ShizukuShell.requestPermission { granted ->
                runOnUiThread {
                    if (!granted) {
                        toast(getString(R.string.shizuku_permission_denied))
                    }
                    lifecycleScope.launch {
                        val (root, _) = refreshAccessNow(force = true)
                        if (!root && ShizukuShell.installed()) {
                            toast(getString(R.string.shizuku_adb_warning))
                        }
                    }
                }
            }
        } else {
            lifecycleScope.launch {
                val (root, _) = refreshAccessNow(force = true)
                if (!root) toast(getString(R.string.root_request_hint))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Review and preflight
    // ---------------------------------------------------------------------

    private fun openReview() {
        val source = sourceBrowser
        val target = targetBrowser
        if (source == null || target == null) {
            toast(getString(R.string.error_select_both))
            return
        }
        if (source.packageName == target.packageName) {
            toast(getString(R.string.error_same))
            return
        }
        if (!hasPrivilegedAccess) {
            toast(getString(R.string.error_root))
            refreshAccessState(force = true)
            return
        }
        loadPreflight()
        showScreen(SCREEN_REVIEW)
    }

    private fun loadPreflight() {
        preflightJob?.cancel()
        clearPreflight()
        b.screenReview.btnStart.isEnabled = false
        b.screenReview.btnStart.alpha = 0.55f

        val source = sourceBrowser ?: return
        val target = targetBrowser ?: return
        val geckoPair = source.type == BrowserType.GECKO && target.type == BrowserType.GECKO
        if (geckoPair) {
            b.screenReview.rowReviewAccount.visibility = View.VISIBLE
            b.screenReview.txtReviewAccountNote.visibility = View.VISIBLE
            b.screenReview.txtReviewAccountNote.text = getString(R.string.account_note_release)
            b.screenReview.txtReviewAccountDesc.text = getString(R.string.account_checking)
            b.screenReview.switchAccount.isEnabled = false
            b.screenReview.switchAccount.isChecked = false
        }

        preflightJob = lifecycleScope.launch {
            try {
                if (geckoPair) {
                    val sourceState = try {
                        FirefoxAccountInspector.inspect(source.packageName)
                    } catch (_: Exception) {
                        FirefoxAccountState()
                    }
                    val targetState = try {
                        FirefoxAccountInspector.inspect(target.packageName)
                    } catch (_: Exception) {
                        FirefoxAccountState()
                    }
                    if (isActive && currentPairIs(source, target)) {
                        sourceAccount = sourceState
                        targetAccount = targetState
                        updateAccountUi()
                    }
                }

                val preview = try {
                    BrowserInspector.extensions(
                        source.packageName,
                        source.type,
                        target.packageName,
                        target.type
                    )
                } catch (e: Exception) {
                    BrowserInspector.ExtensionPreview(null, e.message)
                }
                if (isActive && currentPairIs(source, target)) {
                    extensionPreview = preview
                    updateExtensionCount()
                    if (currentScreen == SCREEN_EXTENSIONS) renderExtensions()
                    if (currentScreen == SCREEN_RESULT) updateResultExtensionButtons()
                }
            } finally {
                if (isActive && currentPairIs(source, target)) {
                    updateStartButton()
                }
            }
        }
    }

    private fun currentPairIs(source: InstalledBrowser, target: InstalledBrowser): Boolean =
        sourceBrowser?.packageName == source.packageName &&
            targetBrowser?.packageName == target.packageName

    private fun updateAccountUi() {
        val source = sourceBrowser ?: return
        val target = targetBrowser ?: return
        if (source.type != BrowserType.GECKO || target.type != BrowserType.GECKO) return

        val state = sourceAccount
        val canCopy = state?.canTransfer == true
        val secure = state?.secureOnly == true
        val found = state?.found == true
        val email = state?.email

        val description = when {
            state == null -> getString(R.string.account_unknown)
            secure -> getString(R.string.account_secure)
            !found -> getString(R.string.account_missing)
            email != null -> getString(R.string.account_found, email)
            else -> getString(R.string.account_found_no_email)
        }
        val targetNote = if (found && targetAccount?.found != true) {
            " · " + getString(R.string.account_target_missing)
        } else {
            ""
        }

        b.screenHome.cardAccount.visibility = View.VISIBLE
        b.screenHome.txtHomeAccountTitle.text = getString(R.string.account_title)
        b.screenHome.txtHomeAccountDesc.text = description + targetNote
        b.screenHome.imgHomeAccountState.setImageResource(
            when {
                secure -> R.drawable.ic_lock
                found -> R.drawable.ic_check
                else -> R.drawable.ic_account
            }
        )
        b.screenHome.imgHomeAccountState.imageTintList = ColorStateList.valueOf(
            if (secure) cWarning else if (found) cSuccess else cOnSurfaceVariant
        )

        b.screenReview.rowReviewAccount.visibility = View.VISIBLE
        b.screenReview.txtReviewAccountDesc.text = description + targetNote
        b.screenReview.switchAccount.isEnabled = canCopy
        b.screenReview.switchAccount.isChecked = canCopy
        b.screenReview.txtReviewAccountNote.visibility = View.VISIBLE
        b.screenReview.txtReviewAccountNote.text = getString(
            if (secure) R.string.account_note_secure else R.string.account_note_release
        )
    }

    private fun updateExtensionCount() {
        val preview = extensionPreview
        val report = preview?.report
        b.screenReview.txtExtensionsCount.text = when {
            report != null -> getString(R.string.extensions_source_count, report.source.size) +
                " · " + getString(R.string.extensions_matched, report.matched.size)
            preview?.warning != null -> preview.warning
            else -> getString(R.string.move_extensions_desc)
        }
        updateResultExtensionButtons()
    }

    // ---------------------------------------------------------------------
    // Migration
    // ---------------------------------------------------------------------

    private fun startMigration() {
        if (isMigrating) return
        val source = sourceBrowser
        val target = targetBrowser
        if (source == null || target == null) {
            toast(getString(R.string.error_select_both))
            return
        }
        if (source.packageName == target.packageName) {
            toast(getString(R.string.error_same))
            return
        }
        if (!hasPrivilegedAccess) {
            toast(getString(R.string.error_root))
            refreshAccessState(force = true)
            return
        }
        if (!BrowserCatalog.isInstalled(this, source.packageName) ||
            !BrowserCatalog.isInstalled(this, target.packageName)
        ) {
            toast(getString(R.string.error_not_installed))
            return
        }

        preflightJob?.cancel()
        extensionJob?.cancel()
        extensionPreview = null

        val options = MigrationOptions(
            copyFirefoxAccount = source.type == BrowserType.GECKO &&
                target.type == BrowserType.GECKO &&
                b.screenReview.switchAccount.isChecked &&
                sourceAccount?.canTransfer == true,
            includeExtensions = b.screenReview.switchExtensions.isChecked,
            includeOpenTabs = b.screenReview.switchTabs.isChecked
        )

        migratedSource = source
        migratedTarget = target
        migrationStartedAt = System.currentTimeMillis()
        isMigrating = true
        updateStartButton()
        resetProgress()
        showScreen(SCREEN_PROGRESS)

        migrationJob = lifecycleScope.launch {
            val result = try {
                mover.migrate(source.packageName, target.packageName, options) { progress ->
                    updateProgress(progress)
                }
            } catch (e: Exception) {
                MigrationResult.Failure(
                    "Unexpected error: ${e.message}",
                    e.stackTraceToString().take(1200)
                )
            }
            isMigrating = false
            finishMigration(result)
        }
    }

    private fun resetProgress() {
        logBuilder.clear()
        b.screenProgress.txtLog.text = ""
        b.screenProgress.scrollLog.visibility = View.GONE
        b.screenProgress.btnToggleLog.text = getString(R.string.log_show)
        b.screenProgress.progressBar.setProgressCompat(0, false)
        b.screenProgress.txtProgressPercent.text = getString(R.string.progress_percent, 0)
        b.screenProgress.txtProgressPhase.text = getString(R.string.progress_preparing)
        b.screenProgress.txtProgressStep.text = getString(R.string.progress_step, 1, 8)
        b.screenProgress.txtProgressDetail.text = getString(R.string.progress_subtitle)
    }

    private fun updateProgress(progress: MigrationResult.Progress) {
        val percent = progress.progressPercent.coerceIn(0, 100)
        b.screenProgress.progressBar.setProgressCompat(percent, true)
        b.screenProgress.txtProgressPercent.text = getString(R.string.progress_percent, percent)
        b.screenProgress.txtProgressPhase.text = progress.phaseName.ifBlank {
            getString(R.string.progress_writing)
        }
        b.screenProgress.txtProgressStep.text = getString(
            R.string.progress_step,
            progress.phase.coerceIn(0, 7) + 1,
            8
        )
        val detail = progress.detail.trim()
        if (detail.isNotBlank()) {
            b.screenProgress.txtProgressDetail.text = detail.take(220)
            appendLogLine(detail)
        }
    }

    private fun appendLogLine(line: String) {
        if (line.isBlank()) return
        val color = when {
            line.contains("[ERR]") -> cError
            line.contains("[WARN]") -> cWarning
            line.contains("[OK]") -> cSuccess
            line.contains("PHASE") || line.contains("[INFO]") -> cPrimary
            else -> cOnSurfaceVariant
        }
        val start = logBuilder.length
        val text = line.take(600)
        logBuilder.append(text).append('\n')
        logBuilder.setSpan(
            ForegroundColorSpan(color),
            start,
            start + text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (logBuilder.length > 160_000) {
            logBuilder.delete(0, 40_000)
        }
        b.screenProgress.txtLog.text = logBuilder
        b.screenProgress.scrollLog.post {
            b.screenProgress.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun finishMigration(result: MigrationResult) {
        lastResult = result
        val elapsed = System.currentTimeMillis() - migrationStartedAt
        showResult(result, elapsed)
        if (extensionPreview == null) loadExtensionsOnly()
    }

    // ---------------------------------------------------------------------
    // Result
    // ---------------------------------------------------------------------

    private fun showResult(result: MigrationResult, elapsedMillis: Long) {
        val source = migratedSource ?: sourceBrowser
        val target = migratedTarget ?: targetBrowser
        val failure = result is MigrationResult.Failure

        val title = when {
            failure -> getString(R.string.result_failure)
            result is MigrationResult.Partial -> getString(R.string.result_partial)
            else -> getString(R.string.result_success)
        }
        b.screenResult.txtResultTitle.text = title

        val description = when (val r = result) {
            is MigrationResult.Success -> buildString {
                append(getString(R.string.result_success_desc))
                val summary = r.summary.trim()
                if (summary.isNotBlank() &&
                    !summary.equals("Migration successful!", ignoreCase = true)
                ) {
                    append("\n\n")
                    append(summary.take(600))
                }
            }
            is MigrationResult.Partial -> getString(R.string.result_partial_desc) +
                "\n\n" + r.message
            is MigrationResult.Failure -> getString(R.string.result_failure_desc) +
                "\n\n" + r.error
            is MigrationResult.Progress -> getString(R.string.result_success_desc)
        }
        b.screenResult.txtResultDesc.text = description
        b.screenResult.txtResultRoute.text = if (source != null && target != null) {
            getString(R.string.result_route, source.label, target.label)
        } else {
            getString(R.string.route_pick_both)
        }
        b.screenResult.txtResultDuration.text = getString(
            R.string.result_duration,
            formatDuration(elapsedMillis)
        )

        applyResultShape(result)
        renderWarnings(warningsFor(result))

        b.screenResult.btnViewExtensions.isEnabled = true
        b.screenResult.btnRestore.isEnabled = true
        updateResultExtensionButtons()
        showScreen(SCREEN_RESULT)
    }

    private fun applyResultShape(result: MigrationResult) {
        val (shape, container, icon, iconTint) = when (result) {
            is MigrationResult.Success -> ResultVisual(
                MaterialShapes.SOFT_BURST,
                cPrimaryContainer,
                R.drawable.ic_check,
                cOnPrimaryContainer
            )
            is MigrationResult.Partial -> ResultVisual(
                MaterialShapes.SOFT_BURST,
                cTertiaryContainer,
                R.drawable.ic_warning,
                cOnTertiaryContainer
            )
            else -> ResultVisual(
                MaterialShapes.SOFT_BOOM,
                cErrorContainer,
                R.drawable.ic_error,
                cOnErrorContainer
            )
        }
        val drawable = MaterialShapes.createShapeDrawable(shape)
        drawable.paint.color = container
        b.screenResult.resultShape.background = drawable
        b.screenResult.imgResultIcon.setImageResource(icon)
        b.screenResult.imgResultIcon.imageTintList = ColorStateList.valueOf(iconTint)
    }

    private data class ResultVisual(
        val shape: androidx.graphics.shapes.RoundedPolygon,
        val container: Int,
        val icon: Int,
        val iconTint: Int
    )

    private fun warningsFor(result: MigrationResult): List<String> = when (val r = result) {
        is MigrationResult.Success -> r.warnings
        is MigrationResult.Partial -> buildList {
            add(r.message)
            addAll(r.failedItems)
        }
        is MigrationResult.Failure -> buildList {
            add(r.error)
            r.technicalDetail.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(5)
                .forEach { add(it) }
        }
        is MigrationResult.Progress -> emptyList()
    }

    private fun renderWarnings(warnings: List<String>) {
        val container = b.screenResult.layoutWarnings
        container.removeAllViews()
        val cleaned = warnings.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        b.screenResult.txtWarningsEmpty.visibility =
            if (cleaned.isEmpty()) View.VISIBLE else View.GONE
        container.visibility = if (cleaned.isEmpty()) View.GONE else View.VISIBLE
        cleaned.take(12).forEach { warning ->
            val item = ItemWarningBinding.inflate(layoutInflater, container, false)
            item.txtWarning.text = warning.take(500)
            container.addView(item.root)
        }
    }

    private fun updateResultExtensionButtons() {
        val hasReport = extensionPreview?.report != null
        b.screenResult.btnViewExtensions.isEnabled = hasReport
        b.screenResult.btnViewExtensions.alpha = if (hasReport) 1f else 0.55f
        b.screenResult.btnShareReport.visibility =
            if (hasReport) View.VISIBLE else View.GONE
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%ds", seconds)
        }
    }

    private fun showLogDialog() {
        val text = logBuilder.toString().ifBlank { getString(R.string.progress_subtitle) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_title)
            .setMessage(text.take(24_000))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Extensions
    // ---------------------------------------------------------------------

    private fun openExtensions() {
        extensionsFromResult = currentScreen == SCREEN_RESULT
        renderExtensions()
        showScreen(SCREEN_EXTENSIONS)
        if (extensionPreview == null) loadExtensionsOnly()
    }

    private fun loadExtensionsOnly() {
        val source = sourceBrowser ?: return
        val target = targetBrowser ?: return
        extensionJob?.cancel()
        extensionJob = lifecycleScope.launch {
            val preview = BrowserInspector.extensions(
                source.packageName,
                source.type,
                target.packageName,
                target.type
            )
            if (isActive && currentPairIs(source, target)) {
                extensionPreview = preview
                updateExtensionCount()
                if (currentScreen == SCREEN_EXTENSIONS) renderExtensions()
            }
        }
    }

    private fun renderExtensions() {
        val container = b.screenExtensions.layoutExtensions
        container.removeAllViews()

        val preview = extensionPreview
        val report = preview?.report
        val sourceCount = report?.source?.size ?: 0
        val matchedCount = report?.matched?.size ?: 0
        b.screenExtensions.txtExtSource.text =
            getString(R.string.extensions_source_count, sourceCount)
        b.screenExtensions.txtExtMatched.text =
            getString(R.string.extensions_matched, matchedCount)

        if (report == null || report.source.isEmpty()) {
            b.screenExtensions.txtExtensionsEmpty.visibility = View.VISIBLE
            b.screenExtensions.txtExtensionsEmpty.text =
                preview?.warning ?: getString(R.string.extensions_empty)
            return
        }
        b.screenExtensions.txtExtensionsEmpty.visibility = View.GONE

        val targetType = targetBrowser?.type ?: report.source.first().engine
        val matchById = report.matched.associate { it.first.id to it.second }
        report.source.forEach { extension ->
            val item = ItemExtensionBinding.inflate(
                layoutInflater,
                container,
                false
            )
            item.txtExtName.text = extension.name
            item.txtExtMeta.text = buildList {
                if (extension.version.isNotBlank()) add("v${extension.version}")
                add(engineLabel(extension.engine))
                add(
                    if (extension.enabled) getString(R.string.extensions_enabled)
                    else getString(R.string.extensions_disabled)
                )
            }.joinToString(" · ")

            val match = matchById[extension.id]
            item.txtExtMatch.text = getString(
                if (match != null) R.string.extensions_matched_label
                else R.string.extensions_not_matched
            )
            item.txtExtMatch.backgroundTintList = ColorStateList.valueOf(
                if (match != null) cPrimaryContainer else cSurfaceHigh
            )
            item.txtExtMatch.setTextColor(
                if (match != null) cOnPrimaryContainer else cOnSurfaceVariant
            )

            if (extension.dataPaths.isNotEmpty()) {
                item.txtExtData.visibility = View.VISIBLE
                item.txtExtData.text = extension.dataPaths.take(3)
                    .joinToString("\n") { it.take(120) }
            }

            val openStore = {
                openUrl(ExtensionMigrator.storeLink(extension.name, targetType))
            }
            item.btnExtStore.setOnClickListener { openStore() }
            item.cardExtension.setOnClickListener { openStore() }
            container.addView(item.root)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(getString(R.string.not_available))
        }
    }

    private fun shareReport() {
        val report = extensionPreview?.report ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_report_subject))
            putExtra(Intent.EXTRA_TEXT, report.html)
        }
        startActivity(Intent.createChooser(send, getString(R.string.result_share)))
    }

    // ---------------------------------------------------------------------
    // Settings, backups, language
    // ---------------------------------------------------------------------

    private fun showSettings() {
        val binding = SheetSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(binding.root)

        binding.txtVersion.text = getString(R.string.settings_version, versionName())
        binding.txtSettingsAccess.text = getString(
            if (hasPrivilegedAccess) R.string.access_ready else R.string.access_missing
        )
        binding.txtSettingsShizuku.text = accessLabelText
        binding.txtLanguageValue.text = languageLabel()

        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.btnCloseSettings.setOnClickListener { dialog.dismiss() }
        binding.btnSettingsRefresh.setOnClickListener {
            lifecycleScope.launch {
                val (root, label) = refreshAccessNow(force = true)
                binding.txtSettingsAccess.text = getString(
                    if (root) R.string.access_ready else R.string.access_missing
                )
                binding.txtSettingsShizuku.text = label
            }
        }

        fun updateBackups() {
            lifecycleScope.launch {
                val backups = mover.listBackups()
                binding.txtSettingsBackups.text =
                    getString(R.string.settings_backups_count, backups.size)
                binding.btnDeleteBackups.isEnabled = backups.isNotEmpty()
            }
        }
        updateBackups()

        binding.btnDeleteBackups.setOnClickListener {
            confirmDeleteBackups { updateBackups() }
        }

        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun showLanguageDialog() {
        val labels = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_en),
            getString(R.string.settings_language_tr)
        )
        val tags = arrayOf<String?>(null, "en", "tr")
        val current = currentLanguageTag()
        val checked = tags.indexOf(current).let { if (it < 0) 0 else it }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                applyLanguage(tags[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyLanguage(tag: String?) {
        val locales = if (tag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun currentLanguageTag(): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return null
        return locales.toLanguageTags().substringBefore(',').substringBefore('-').ifBlank { null }
    }

    private fun languageLabel(): String = when (currentLanguageTag()) {
        "tr" -> getString(R.string.settings_language_tr)
        "en" -> getString(R.string.settings_language_en)
        else -> getString(R.string.settings_language_system)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName
            ?: getString(R.string.version_unknown)
    } catch (_: Exception) {
        getString(R.string.version_unknown)
    }

    private fun confirmDeleteBackups(onDone: (() -> Unit)? = null) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_backups)
            .setMessage(R.string.confirm_delete_backups_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val count = mover.clearBackups()
                    toast(
                        if (count > 0) getString(R.string.deleted_backups, count)
                        else getString(R.string.no_backup)
                    )
                    onDone?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRestoreDialog() {
        lifecycleScope.launch {
            val backups = mover.listBackups()
            val latest = backups.firstOrNull()
            if (latest == null) {
                toast(getString(R.string.no_backup))
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.restore_title)
                .setMessage(getString(R.string.restore_msg, latest.first))
                .setPositiveButton(R.string.restore) { _, _ ->
                    restoreBackup(latest.first)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun restoreBackup(path: String) {
        lifecycleScope.launch {
            b.screenResult.btnRestore.isEnabled = false
            val ok = mover.rollback(path) { line ->
                appendLogLine(line)
            }
            b.screenResult.btnRestore.isEnabled = true
            toast(
                getString(
                    if (ok) R.string.restored else R.string.restore_failed
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    private fun toggleLog() {
        val visible = b.screenProgress.scrollLog.visibility == View.VISIBLE
        b.screenProgress.scrollLog.visibility = if (visible) View.GONE else View.VISIBLE
        b.screenProgress.btnToggleLog.text = getString(
            if (visible) R.string.log_show else R.string.log_hide
        )
        if (!visible) {
            b.screenProgress.scrollLog.post {
                b.screenProgress.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
