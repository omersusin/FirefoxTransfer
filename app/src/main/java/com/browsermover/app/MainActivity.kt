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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var mover: DataMover

    private var isMigrating = false
    private var migrationJob: Job? = null

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

        setupInputWatchers()
        b.btnStart.setOnClickListener { confirmAndStart() }
        checkRoot()
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
        b.btnStart.isEnabled = !isMigrating &&
            PackageValidator.isValid(src) &&
            PackageValidator.isValid(dst) &&
            src != dst
    }

    private fun checkRoot() {
        lifecycleScope.launch {
            b.txtRootStatus.text = "Checking root access..."
            b.txtRootStatus.setTextColor(cOrange)

            if (RootHelper.checkRoot()) {
                b.txtRootStatus.text = "✓ Root access available"
                b.txtRootStatus.setTextColor(cGreen)
                updateButton()
            } else {
                b.txtRootStatus.text = "✗ NO root access"
                b.txtRootStatus.setTextColor(cRed)
                b.btnStart.isEnabled = false
                appendLog("[ERR] Root not found!", cRed)
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
                    "FAZA"   in p.detail -> cBlue
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
