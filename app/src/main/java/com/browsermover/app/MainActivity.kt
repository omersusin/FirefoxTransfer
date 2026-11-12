package com.browsermover.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.browsermover.app.core.DataMover
import com.browsermover.app.core.PackageValidator
import com.browsermover.app.databinding.ActivityMainBinding
import com.browsermover.app.model.MigrationResult
import com.browsermover.app.root.RootHelper
import com.browsermover.app.root.ShizukuShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var mover: DataMover

    private var isMigrating = false
    private var migrationJob: Job? = null
    private var hasPrivilegedAccess = false

    private val cBlue   by lazy { ContextCompat.getColor(this, R.color.accent_blue) }
    private val cGreen  by lazy { ContextCompat.getColor(this, R.color.accent_green) }
    private val cOrange by lazy { ContextCompat.getColor(this, R.color.accent_orange) }
    private val cRed    by lazy { ContextCompat.getColor(this, R.color.accent_red) }
    private val cGray   by lazy { ContextCompat.getColor(this, R.color.text_secondary) }

    private val logBuilder = SpannableStringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        mover = DataMover(this)
        ShizukuShell.initialize(this)

        setupInputWatchers()
        b.btnStart.setOnClickListener { confirmAndStart() }
        b.btnDeleteBackups.setOnClickListener { confirmDeleteBackups() }
        b.btnShizuku.setOnClickListener { requestShizukuAccess() }
        checkRoot()
    }

    override fun onDestroy() {
        ShizukuShell.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isMigrating) {
            Toast.makeText(this, "Migration in progress! Exiting may cause data loss.", Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun setupInputWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateButton()
        }
        b.etSource.addTextChangedListener(watcher)
        b.etTarget.addTextChangedListener(watcher)
    }

    private fun updateButton() {
        val src = b.etSource.text?.toString()?.trim().orEmpty()
        val dst = b.etTarget.text?.toString()?.trim().orEmpty()
        b.btnStart.isEnabled = hasPrivilegedAccess &&
            !isMigrating &&
            PackageValidator.isValid(src) &&
            PackageValidator.isValid(dst) &&
            src != dst
    }

    private fun checkRoot() {
        lifecycleScope.launch {
            b.txtRootStatus.text = "Checking root / Shizuku access..."
            b.txtRootStatus.setTextColor(cOrange)

            if (RootHelper.checkRoot()) {
                hasPrivilegedAccess = true
                val label = RootHelper.accessLabel()
                b.txtRootStatus.text = "✓ $label access available"
                b.txtRootStatus.setTextColor(cGreen)
                b.btnShizuku.visibility = View.GONE
                updateButton()
            } else {
                hasPrivilegedAccess = false
                b.btnStart.isEnabled = false
                if (ShizukuShell.installed()) {
                    b.btnShizuku.visibility = View.VISIBLE
                    b.txtRootStatus.text = "✗ No root. ${ShizukuShell.backendLabel()}"
                    appendLog("[WARN] Shizuku is present. Grant access and use a root-started Shizuku; ADB/UID 2000 cannot read app data.", cOrange)
                } else {
                    b.btnShizuku.visibility = View.GONE
                    b.txtRootStatus.text = "✗ NO root or root-backed Shizuku"
                    appendLog("[ERR] Root not found!", cRed)
                }
                b.txtRootStatus.setTextColor(cRed)
            }
        }
    }

    private fun requestShizukuAccess() {
        ShizukuShell.requestPermission { granted ->
            lifecycleScope.launch {
                if (!granted) {
                    appendLog("[ERR] Shizuku permission was not granted.", cRed)
                    return@launch
                }
                val uid = ShizukuShell.uid()
                if (uid == 0) {
                    hasPrivilegedAccess = true
                    b.txtRootStatus.text = "✓ Shizuku root (UID 0) access available"
                    b.txtRootStatus.setTextColor(cGreen)
                    b.btnShizuku.visibility = View.GONE
                    updateButton()
                } else {
                    hasPrivilegedAccess = false
                    b.btnStart.isEnabled = false
                    b.txtRootStatus.text = "✗ Shizuku ADB (UID ${uid ?: 2000}) cannot read app data"
                    b.txtRootStatus.setTextColor(cRed)
                    appendLog(
                        "[WARN] Shizuku is running with the ADB backend. Android shell UID 2000 " +
                            "cannot read /data/user/0/<package>. Start Shizuku with root, or use su.",
                        cOrange
                    )
                }
            }
        }
    }

    private fun confirmAndStart() {
        val src = b.etSource.text?.toString()?.trim() ?: return
        val dst = b.etTarget.text?.toString()?.trim() ?: return

        if (!PackageValidator.isValid(src) || !PackageValidator.isValid(dst)) {
            Toast.makeText(this, "Invalid package name!", Toast.LENGTH_SHORT).show()
            return
        }
        if (src == dst) {
            Toast.makeText(this, "Source and target cannot be the same!", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Migration Confirmation")
            .setMessage("Source: $src\nTarget: $dst\n\nTarget data will be overwritten.\nContinue?")
            .setPositiveButton("Start") { _, _ -> startMigration(src, dst) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteBackups() {
        if (isMigrating) {
            Toast.makeText(this, "Wait for migration to finish!", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Backups")
            .setMessage("This will delete all backups from previous migrations.\nAre you sure?")
            .setPositiveButton("DELETE") { _, _ ->
                lifecycleScope.launch {
                    val count = mover.clearBackups()
                    if (count > 0) {
                        appendLog("✓ Deleted $count backup folders.", cGreen)
                        Toast.makeText(this@MainActivity, "Deleted $count backups", Toast.LENGTH_SHORT).show()
                    } else {
                        appendLog("! No backups found.", cOrange)
                        Toast.makeText(this@MainActivity, "No backups found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMigration(src: String, dst: String) {
        if (migrationJob?.isActive == true) return

        migrationJob = lifecycleScope.launch {
            isMigrating = true
            b.btnStart.isEnabled = false
            b.btnStart.text = "MIGRATION IN PROGRESS..."
            b.progressBar.visibility = View.VISIBLE
            b.progressBar.progress = 0

            logBuilder.clear()
            appendLog("═══════════════════════════════════", cBlue)
            appendLog("MIGRATION STARTING: $src -> $dst", cBlue)
            appendLog("═══════════════════════════════════", cBlue)

            val result = mover.migrate(src, dst) { p ->
                b.progressBar.progress = p.progressPercent
                val color = when {
                    "[OK]"   in p.detail -> cGreen
                    "[WARN]" in p.detail -> cOrange
                    "[ERR]"  in p.detail -> cRed
                    "PHASE"  in p.detail -> cBlue
                    else -> cGray
                }
                appendLog(p.detail, color)
            }

            appendLog("═══════════════════════════════════", cBlue)

            when (result) {
                is MigrationResult.Success -> {
                    appendLog("✓ ${result.summary}", cGreen)
                    result.warnings.forEach { appendLog("  ! $it", cOrange) }
                    setButtonResult("✓ COMPLETED", cGreen)
                }
                is MigrationResult.Partial -> {
                    appendLog("! ${result.message}", cOrange)
                    result.successItems.forEach { appendLog("  ✓ $it", cGreen) }
                    result.failedItems.forEach { appendLog("  ✗ $it", cRed) }
                    setButtonResult("! PARTIAL", cOrange)
                }
                is MigrationResult.Failure -> {
                    appendLog("✗ ${result.error}", cRed)
                    if (result.technicalDetail?.isNotBlank() == true) {
                        appendLog("--- Details ---", cGray)
                        appendLog(result.technicalDetail!!, cGray)
                    }
                    setButtonResult("✗ FAILED", cRed)
                }
                else -> {}
            }

            b.progressBar.progress = 100
            isMigrating = false

            b.btnStart.postDelayed({
                b.btnStart.text = "START MIGRATION"
                b.btnStart.backgroundTintList = ColorStateList.valueOf(cBlue)
                updateButton()
                b.progressBar.visibility = View.GONE
            }, 3000)
        }
    }

    private fun setButtonResult(text: String, color: Int) {
        b.btnStart.text = text
        b.btnStart.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun appendLog(text: String, color: Int) {
        val start = logBuilder.length
        logBuilder.append(text).append("\n")
        logBuilder.setSpan(ForegroundColorSpan(color), start, start + text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.txtLog.text = logBuilder
        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
