package com.browsermover.app

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var detector: BrowserDetector
    private lateinit var dataMover: DataMover

    private lateinit var tvRootIcon: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var rvSourceBrowsers: RecyclerView
    private lateinit var rvTargetBrowsers: RecyclerView
    private lateinit var cardTarget: MaterialCardView
    private lateinit var tvTargetDescription: TextView
    private lateinit var btnTransfer: MaterialButton
    private lateinit var btnManualTransfer: MaterialButton
    private lateinit var switchBackup: SwitchMaterial
    private lateinit var cardLog: MaterialCardView
    private lateinit var tvLog: TextView
    private lateinit var etSourcePackage: TextInputEditText
    private lateinit var etTargetPackage: TextInputEditText

    private var installedBrowsers: List<BrowserInfo> = emptyList()
    private var selectedSource: BrowserInfo? = null
    private var selectedTarget: BrowserInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        detector = BrowserDetector(this)
        dataMover = DataMover()

        initViews()
        checkRoot()
    }

    private fun initViews() {
        tvRootIcon = findViewById(R.id.tvRootIcon)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        rvSourceBrowsers = findViewById(R.id.rvSourceBrowsers)
        rvTargetBrowsers = findViewById(R.id.rvTargetBrowsers)
        cardTarget = findViewById(R.id.cardTarget)
        tvTargetDescription = findViewById(R.id.tvTargetDescription)
        btnTransfer = findViewById(R.id.btnTransfer)
        btnManualTransfer = findViewById(R.id.btnManualTransfer)
        switchBackup = findViewById(R.id.switchBackup)
        cardLog = findViewById(R.id.cardLog)
        tvLog = findViewById(R.id.tvLog)
        etSourcePackage = findViewById(R.id.etSourcePackage)
        etTargetPackage = findViewById(R.id.etTargetPackage)

        rvSourceBrowsers.layoutManager = LinearLayoutManager(this)
        rvTargetBrowsers.layoutManager = LinearLayoutManager(this)

        btnTransfer.setOnClickListener { onTransferClicked() }
        btnManualTransfer.setOnClickListener { onManualTransferClicked() }
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootHelper.isRootAvailable()
            runOnUiThread {
                if (hasRoot) {
                    tvRootIcon.text = "✅"
                    tvRootStatus.text = "Root erişimi mevcut"
                    loadBrowsers()
                } else {
                    tvRootIcon.text = "❌"
                    tvRootStatus.text = "Root erişimi bulunamadı! Bu uygulama root gerektirir."
                }
            }
        }.start()
    }

    private fun loadBrowsers() {
        Thread {
            val browsers = detector.detectInstalledBrowsers()
            runOnUiThread {
                installedBrowsers = browsers
                if (browsers.isEmpty()) {
                    tvRootStatus.text = "Root mevcut ama yüklü tarayıcı bulunamadı."
                } else {
                    val adapter = BrowserAdapter(browsers, true) { browser ->
                        onSourceSelected(browser)
                    }
                    rvSourceBrowsers.adapter = adapter
                }
            }
        }.start()
    }

    private fun onSourceSelected(source: BrowserInfo) {
        selectedSource = source
        selectedTarget = null

        val targets = detector.getCompatibleTargets(source, installedBrowsers)

        cardTarget.visibility = View.VISIBLE
        tvTargetDescription.text = "${source.name} ile uyumlu tarayıcılar (${source.type.label})"

        if (targets.isEmpty()) {
            tvTargetDescription.text = "Uyumlu başka yüklü tarayıcı bulunamadı. Manuel giriş kullanın."
            rvTargetBrowsers.adapter = null
            btnTransfer.visibility = View.GONE
        } else {
            val adapter = BrowserAdapter(targets, false) { browser ->
                onTargetSelected(browser)
            }
            rvTargetBrowsers.adapter = adapter
        }

        // Source paket adini otomatik doldur
        etSourcePackage.setText(source.packageName)
    }

    private fun onTargetSelected(target: BrowserInfo) {
        selectedTarget = target
        btnTransfer.visibility = View.VISIBLE
        btnTransfer.isEnabled = true
        btnTransfer.text = "🚀 ${selectedSource?.name} → ${target.name}"

        // Target paket adini otomatik doldur
        etTargetPackage.setText(target.packageName)
    }

    private fun onTransferClicked() {
        val source = selectedSource ?: return
        val target = selectedTarget ?: return

        showConfirmDialog(source.name, target.name, source.packageName, target.packageName)
    }

    private fun onManualTransferClicked() {
        val sourcePkg = etSourcePackage.text.toString().trim()
        val targetPkg = etTargetPackage.text.toString().trim()

        if (sourcePkg.isEmpty() || targetPkg.isEmpty()) {
            Toast.makeText(this, "Lütfen her iki paket adını da girin", Toast.LENGTH_SHORT).show()
            return
        }

        if (sourcePkg == targetPkg) {
            Toast.makeText(this, "Kaynak ve hedef aynı olamaz", Toast.LENGTH_SHORT).show()
            return
        }

        showConfirmDialog(sourcePkg, targetPkg, sourcePkg, targetPkg)
    }

    private fun showConfirmDialog(
        sourceName: String,
        targetName: String,
        sourcePackage: String,
        targetPackage: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Transfer Onayı")
            .setMessage(
                "Bu işlem hedef tarayıcının tüm verilerini silecek ve kaynak verilerle değiştirecek.\n\n" +
                "Kaynak: $sourceName\n($sourcePackage)\n\n" +
                "Hedef: $targetName\n($targetPackage)\n\n" +
                "Devam etmek istiyor musunuz?"
            )
            .setPositiveButton("Evet, Transfer Et") { _, _ ->
                startTransfer(sourcePackage, targetPackage)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun startTransfer(sourcePackage: String, targetPackage: String) {
        cardLog.visibility = View.VISIBLE
        tvLog.text = ""
        btnTransfer.isEnabled = false
        btnManualTransfer.isEnabled = false

        val source = BrowserInfo(
            name = selectedSource?.name ?: sourcePackage,
            packageName = sourcePackage,
            type = selectedSource?.type ?: BrowserType.UNKNOWN,
            isInstalled = true
        )

        val target = BrowserInfo(
            name = selectedTarget?.name ?: targetPackage,
            packageName = targetPackage,
            type = selectedTarget?.type ?: BrowserType.UNKNOWN,
            isInstalled = true
        )

        val backup = switchBackup.isChecked

        dataMover.moveData(source, target, backup, object : DataMover.ProgressListener {
            override fun onProgress(message: String) {
                appendLog("⏳ $message")
            }

            override fun onSuccess(message: String) {
                appendLog("✅ $message")
                btnTransfer.isEnabled = true
                btnManualTransfer.isEnabled = true

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✅ Başarılı!")
                    .setMessage(message)
                    .setPositiveButton("Tamam", null)
                    .show()
            }

            override fun onError(message: String) {
                appendLog("❌ $message")
                btnTransfer.isEnabled = true
                btnManualTransfer.isEnabled = true

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("❌ Hata")
                    .setMessage(message)
                    .setPositiveButton("Tamam", null)
                    .show()
            }
        })
    }

    private fun appendLog(message: String) {
        val current = tvLog.text.toString()
        tvLog.text = if (current.isEmpty()) message else "$current\n$message"
    }

    // ===================== RecyclerView Adapter =====================

    inner class BrowserAdapter(
        private val browsers: List<BrowserInfo>,
        private val isSourceList: Boolean,
        private val onClick: (BrowserInfo) -> Unit
    ) : RecyclerView.Adapter<BrowserAdapter.ViewHolder>() {

        private var selectedPosition = -1

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardBrowser)
            val ivIcon: ImageView = view.findViewById(R.id.ivBrowserIcon)
            val tvName: TextView = view.findViewById(R.id.tvBrowserName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
            val tvType: TextView = view.findViewById(R.id.tvBrowserType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_browser, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val browser = browsers[position]

            holder.tvName.text = browser.name
            holder.tvPackage.text = browser.packageName

            // Tur etiketi
            holder.tvType.text = browser.type.label
            holder.tvType.setBackgroundResource(
                if (browser.type == BrowserType.FIREFOX)
                    R.drawable.badge_firefox
                else
                    R.drawable.badge_chromium
            )

            // Uygulama ikonu
            val icon = getAppIcon(browser.packageName)
            if (icon != null) {
                holder.ivIcon.setImageDrawable(icon)
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_launcher_foreground)
            }

            // Secim durumu
            val isSelected = position == selectedPosition
            holder.card.strokeWidth = if (isSelected) 3 else 1
            holder.card.strokeColor = if (isSelected) {
                getColor(R.color.selected_stroke)
            } else {
                getColor(R.color.card_stroke)
            }

            holder.card.setOnClickListener {
                val oldPos = selectedPosition
                selectedPosition = holder.adapterPosition
                if (oldPos >= 0) notifyItemChanged(oldPos)
                notifyItemChanged(selectedPosition)
                onClick(browser)
            }
        }

        override fun getItemCount() = browsers.size
    }

    private fun getAppIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}