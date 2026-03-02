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
import androidx.recyclerview.widget.LinearLayoutManager
import com.browsermover.app.core.BrowserDetector
import com.browsermover.app.core.DataMover
import com.browsermover.app.core.PackageValidator
import com.browsermover.app.databinding.ActivityMainBinding
import com.browsermover.app.model.BrowserInfo
import com.browsermover.app.model.BrowserType
import com.browsermover.app.model.MigrationResult
import com.browsermover.app.root.RootHelper
import com.browsermover.app.ui.BrowserAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var detector: BrowserDetector
    private lateinit var mover: DataMover

    private lateinit var sourceAdapter: BrowserAdapter
    private lateinit var targetAdapter: BrowserAdapter

    private var selectedSource: BrowserInfo? = null
    private var selectedTarget: BrowserInfo? = null
    private var isManualMode = false
    private var isMigrating = false
    private var migrationJob: Job? = null

    private val cInfo by lazy { ContextCompat.getColor(this, R.color.accent_blue) }
    private val cOk   by lazy { ContextCompat.getColor(this, R.color.accent_green) }
    private val cWarn by lazy { ContextCompat.getColor(this, R.color.accent_orange) }
    private val cErr  by lazy { ContextCompat.getColor(this, R.color.accent_red) }
    private val cGray by lazy { ContextCompat.getColor(this, R.color.text_secondary) }

    private val logBuilder = SpannableStringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detector = BrowserDetector(this)
        mover = DataMover(this)

        setupRecyclerViews()
        setupButtons()
        setupManualInputWatchers()
        checkRootAndLoad()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isMigrating) {
            Toast.makeText(this, "⚠ Göç devam ediyor! Veri kaybı riski.", Toast.LENGTH_LONG).show()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun setupRecyclerViews() {
        sourceAdapter = BrowserAdapter { b ->
            selectedSource = b
            binding.txtSourceSelected.text = "✅ ${b.displayLabel}"
            binding.txtSourceSelected.setTextColor(cInfo)
            updateStartButton()
        }
        binding.rvSourceBrowsers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sourceAdapter
            isNestedScrollingEnabled = false
        }

        targetAdapter = BrowserAdapter { b ->
            selectedTarget = b
            binding.txtTargetSelected.text = "✅ ${b.displayLabel}"
            binding.txtTargetSelected.setTextColor(cOk)
            updateStartButton()
        }
        binding.rvTargetBrowsers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = targetAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun setupButtons() {
        binding.btnToggleManual.setOnClickListener {
            isManualMode = !isManualMode
            binding.layoutManualInput.visibility = if (isManualMode) View.VISIBLE else View.GONE
            binding.rvSourceBrowsers.visibility = if (isManualMode) View.GONE else View.VISIBLE
            binding.rvTargetBrowsers.visibility = if (isManualMode) View.GONE else View.VISIBLE
            binding.btnToggleManual.text = if (isManualMode) "📋 Liste Modu" else "⌨️ Manuel Paket Girişi"
            updateStartButton()
        }

        binding.btnStartMigration.setOnClickListener { if (!isMigrating) confirmAndStart() }
        binding.btnRollback.setOnClickListener { if (!isMigrating) showRollbackDialog() }
        binding.btnClearLog.setOnClickListener { logBuilder.clear(); binding.txtLog.text = "" }
    }

    private fun setupManualInputWatchers() {
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateStartButton() }
        }
        binding.etSourcePackage.addTextChangedListener(w)
        binding.etTargetPackage.addTextChangedListener(w)
    }

    private fun checkRootAndLoad() {
        lifecycleScope.launch {
            binding.txtRootStatus.text = "⏳ Root kontrol ediliyor..."
            if (RootHelper.checkRoot()) {
                binding.txtRootStatus.text = "✅ Root erişimi mevcut"
                binding.txtRootStatus.setTextColor(cOk)
                loadBrowsers()
            } else {
                binding.txtRootStatus.text = "❌ Root erişimi YOK!"
                binding.txtRootStatus.setTextColor(cErr)
                appendLog("[ERR] Root bulunamadı!", cErr)
            }
        }
    }

    private fun loadBrowsers() {
        lifecycleScope.launch {
            val list = detector.detectAllBrowsers()
            sourceAdapter.submitList(list)
            targetAdapter.submitList(list)
            appendLog("[OK] ${list.size} tarayıcı bulundu.", cOk)
        }
    }

    private fun updateStartButton() {
        val ready = if (isManualMode) {
            val s = binding.etSourcePackage.text?.toString()?.trim().orEmpty()
            val d = binding.etTargetPackage.text?.toString()?.trim().orEmpty()
            PackageValidator.isValid(s) && PackageValidator.isValid(d) && s != d
        } else {
            selectedSource != null && selectedTarget != null && selectedSource!!.packageName != selectedTarget!!.packageName
        }
        binding.btnStartMigration.isEnabled = ready && !isMigrating
    }

    private fun confirmAndStart() {
        val src = if (isManualMode) binding.etSourcePackage.text.toString().trim() else selectedSource?.packageName ?: return
        val dst = if (isManualMode) binding.etTargetPackage.text.toString().trim() else selectedTarget?.packageName ?: return
        
        AlertDialog.Builder(this)
            .setTitle("⚠️ Göç Onayı")
            .setMessage("📤 $src\n📥 $dst\n\nHedef verilerin üzerine yazılacak. Devam?")
            .setPositiveButton("🚀 Başlat") { _, _ -> startMigration(src, dst) }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun startMigration(srcPkg: String, dstPkg: String) {
        if (migrationJob?.isActive == true) return
        migrationJob = lifecycleScope.launch {
            isMigrating = true
            binding.btnStartMigration.isEnabled = false
            binding.btnRollback.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = 0

            logBuilder.clear()
            appendLog("═══════════════════════════════════", cInfo)
            appendLog("  GÖÇ BAŞLIYOR: $srcPkg -> $dstPkg", cInfo)
            appendLog("═══════════════════════════════════", cInfo)

            val source = if (isManualMode) detector.createManualBrowserInfo(srcPkg) else selectedSource!!
            val target = if (isManualMode) detector.createManualBrowserInfo(dstPkg) else selectedTarget!!

            val result = mover.migrate(source, target) { p ->
                binding.progressBar.progress = p.progressPercent
                binding.txtProgressDetail.text = "Faza ${p.phase}: ${p.phaseName}"
                appendLog(p.detail, if ("[OK]" in p.detail) cOk else if ("[WARN]" in p.detail) cWarn else if ("[ERR]" in p.detail) cErr else cGray)
            }

            when (result) {
                is MigrationResult.Success -> { appendLog("✅ ${result.summary}", cOk); setButton("TAMAMLANDI", cOk) }
                is MigrationResult.Failure -> { appendLog("❌ ${result.error}", cErr); setButton("BAŞARISIZ", cErr) }
                else -> {}
            }

            isMigrating = false
            binding.btnRollback.isEnabled = true
            binding.btnStartMigration.postDelayed({ 
                setButton("GÖÇÜ BAŞLAT", cInfo)
                updateStartButton()
                binding.progressBar.visibility = View.GONE
            }, 3000)
        }
    }

    private fun showRollbackDialog() {
        lifecycleScope.launch {
            val backups = mover.listBackups()
            if (backups.isEmpty()) { Toast.makeText(this@MainActivity, "Yedek bulunamadı!", Toast.LENGTH_SHORT).show(); return@launch }
            val items = backups.map { "📁 ${it.second}" }.toTypedArray()
            AlertDialog.Builder(this@MainActivity).setTitle("⏪ Geri Alma").setItems(items) { _, i -> performRollback(backups[i].first) }.show()
        }
    }

    private fun performRollback(path: String) {
        lifecycleScope.launch {
            appendLog("GERİ ALMA BAŞLIYOR...", cWarn)
            if (mover.rollback(path) { appendLog(it, cGray) }) {
                appendLog("✅ Geri alma başarılı!", cOk)
            } else {
                appendLog("❌ Geri alma başarısız!", cErr)
            }
        }
    }

    private fun setButton(text: String, color: Int) {
        binding.btnStartMigration.text = text
        binding.btnStartMigration.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun appendLog(text: String, color: Int) {
        val start = logBuilder.length
        logBuilder.append(text).append("\n")
        logBuilder.setSpan(ForegroundColorSpan(color), start, start + text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.txtLog.text = logBuilder
        binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }
}
