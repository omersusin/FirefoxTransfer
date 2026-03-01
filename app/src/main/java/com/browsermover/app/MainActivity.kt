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
    private lateinit var cardCategory: MaterialCardView
    private lateinit var btnFirefox: MaterialButton
    private lateinit var btnChromium: MaterialButton
    private lateinit var cardSource: MaterialCardView
    private lateinit var tvSourceTitle: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var rvSourceBrowsers: RecyclerView
    private lateinit var cardTarget: MaterialCardView
    private lateinit var tvTargetDescription: TextView
    private lateinit var rvTargetBrowsers: RecyclerView
    private lateinit var cardManual: MaterialCardView
    private lateinit var etSourcePackage: TextInputEditText
    private lateinit var etTargetPackage: TextInputEditText
    private lateinit var btnManualTransfer: MaterialButton
    private lateinit var cardBackup: MaterialCardView
    private lateinit var switchBackup: SwitchMaterial
    private lateinit var btnTransfer: MaterialButton
    private lateinit var cardLog: MaterialCardView
    private lateinit var tvLog: TextView

    private var selectedType: BrowserType? = null
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
        cardCategory = findViewById(R.id.cardCategory)
        btnFirefox = findViewById(R.id.btnFirefox)
        btnChromium = findViewById(R.id.btnChromium)
        cardSource = findViewById(R.id.cardSource)
        tvSourceTitle = findViewById(R.id.tvSourceTitle)
        btnBack = findViewById(R.id.btnBack)
        rvSourceBrowsers = findViewById(R.id.rvSourceBrowsers)
        cardTarget = findViewById(R.id.cardTarget)
        tvTargetDescription = findViewById(R.id.tvTargetDescription)
        rvTargetBrowsers = findViewById(R.id.rvTargetBrowsers)
        cardManual = findViewById(R.id.cardManual)
        etSourcePackage = findViewById(R.id.etSourcePackage)
        etTargetPackage = findViewById(R.id.etTargetPackage)
        btnManualTransfer = findViewById(R.id.btnManualTransfer)
        cardBackup = findViewById(R.id.cardBackup)
        switchBackup = findViewById(R.id.switchBackup)
        btnTransfer = findViewById(R.id.btnTransfer)
        cardLog = findViewById(R.id.cardLog)
        tvLog = findViewById(R.id.tvLog)

        rvSourceBrowsers.layoutManager = LinearLayoutManager(this)
        rvTargetBrowsers.layoutManager = LinearLayoutManager(this)

        btnFirefox.setOnClickListener { onCategorySelected(BrowserType.FIREFOX) }
        btnChromium.setOnClickListener { onCategorySelected(BrowserType.CHROMIUM) }
        btnBack.setOnClickListener { goBackToCategory() }
        btnTransfer.setOnClickListener { onTransferClicked() }
        btnManualTransfer.setOnClickListener { onManualTransferClicked() }
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootHelper.isRootAvailable()
            runOnUiThread {
                if (hasRoot) {
                    tvRootIcon.text = "✅"
                    tvRootStatus.text = "Root access available"
                    cardCategory.visibility = View.VISIBLE
                } else {
                    tvRootIcon.text = "❌"
                    tvRootStatus.text = "Root access not found! This app requires root."
                }
            }
        }.start()
    }

    private fun onCategorySelected(type: BrowserType) {
        selectedType = type
        selectedSource = null
        selectedTarget = null

        cardCategory.visibility = View.GONE
        cardSource.visibility = View.VISIBLE
        cardManual.visibility = View.VISIBLE
        cardBackup.visibility = View.VISIBLE
        cardTarget.visibility = View.GONE
        btnTransfer.visibility = View.GONE

        tvSourceTitle.text = if (type == BrowserType.FIREFOX)
            "🦊 Firefox Family — Select Source" else "🌐 Chromium Family — Select Source"

        Thread {
            val browsers = detector.detectInstalledBrowsers(type)
            runOnUiThread {
                installedBrowsers = browsers
                if (browsers.isEmpty()) {
                    Toast.makeText(this, "No installed ${type.label} browsers found.", Toast.LENGTH_LONG).show()
                } else {
                    rvSourceBrowsers.adapter = BrowserAdapter(browsers) { browser ->
                        onSourceSelected(browser)
                    }
                }
            }
        }.start()
    }

    private fun goBackToCategory() {
        selectedType = null
        selectedSource = null
        selectedTarget = null
        installedBrowsers = emptyList()

        cardCategory.visibility = View.VISIBLE
        cardSource.visibility = View.GONE
        cardTarget.visibility = View.GONE
        cardManual.visibility = View.GONE
        cardBackup.visibility = View.GONE
        btnTransfer.visibility = View.GONE
        cardLog.visibility = View.GONE
    }

    private fun onSourceSelected(source: BrowserInfo) {
        selectedSource = source
        selectedTarget = null

        etSourcePackage.setText(source.packageName)

        val targets = detector.getCompatibleTargets(source, installedBrowsers)

        cardTarget.visibility = View.VISIBLE
        tvTargetDescription.text = "Compatible with ${source.name} (${source.type.label})"

        if (targets.isEmpty()) {
            tvTargetDescription.text = "No compatible target found. Use manual entry."
            rvTargetBrowsers.adapter = null
            btnTransfer.visibility = View.GONE
        } else {
            rvTargetBrowsers.adapter = BrowserAdapter(targets) { browser ->
                onTargetSelected(browser)
            }
        }
    }

    private fun onTargetSelected(target: BrowserInfo) {
        selectedTarget = target
        etTargetPackage.setText(target.packageName)
        btnTransfer.visibility = View.VISIBLE
        btnTransfer.isEnabled = true
        btnTransfer.text = "🚀 ${selectedSource?.name} → ${target.name}"
    }

    private fun onTransferClicked() {
        val source = selectedSource ?: return
        val target = selectedTarget ?: return
        showConfirmDialog(source.name, target.name, source.packageName, target.packageName)
    }

    private fun onManualTransferClicked() {
        val srcPkg = etSourcePackage.text.toString().trim()
        val dstPkg = etTargetPackage.text.toString().trim()

        if (srcPkg.isEmpty() || dstPkg.isEmpty()) {
            Toast.makeText(this, "Please enter both package names", Toast.LENGTH_SHORT).show()
            return
        }
        if (srcPkg == dstPkg) {
            Toast.makeText(this, "Source and target cannot be the same", Toast.LENGTH_SHORT).show()
            return
        }
        showConfirmDialog(srcPkg, dstPkg, srcPkg, dstPkg)
    }

    private fun showConfirmDialog(srcName: String, dstName: String, srcPkg: String, dstPkg: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Transfer Confirmation")
            .setMessage(
                "This will DELETE all data of the target browser and replace it with source data.\n\n" +
                "Source: $srcName\n($srcPkg)\n\n" +
                "Target: $dstName\n($dstPkg)\n\n" +
                "Do you want to continue?"
            )
            .setPositiveButton("Yes, Transfer") { _, _ ->
                startTransfer(srcPkg, dstPkg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTransfer(srcPkg: String, dstPkg: String) {
        cardLog.visibility = View.VISIBLE
        tvLog.text = ""
        btnTransfer.isEnabled = false
        btnManualTransfer.isEnabled = false

        val source = BrowserInfo(
            name = selectedSource?.name ?: srcPkg,
            packageName = srcPkg,
            type = selectedType ?: BrowserType.UNKNOWN,
            isInstalled = true
        )
        val target = BrowserInfo(
            name = selectedTarget?.name ?: dstPkg,
            packageName = dstPkg,
            type = selectedType ?: BrowserType.UNKNOWN,
            isInstalled = true
        )

        dataMover.moveData(source, target, switchBackup.isChecked, object : DataMover.ProgressListener {
            override fun onProgress(message: String) {
                appendLog("⏳ $message")
            }

            override fun onSuccess(message: String) {
                appendLog("✅ $message")
                btnTransfer.isEnabled = true
                btnManualTransfer.isEnabled = true
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✅ Success!")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }

            override fun onError(message: String) {
                appendLog("❌ $message")
                btnTransfer.isEnabled = true
                btnManualTransfer.isEnabled = true
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("❌ Error")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        })
    }

    private fun appendLog(msg: String) {
        val current = tvLog.text.toString()
        tvLog.text = if (current.isEmpty()) msg else "$current\n$msg"
    }

    // ========== RecyclerView Adapter ==========

    inner class BrowserAdapter(
        private val browsers: List<BrowserInfo>,
        private val onClick: (BrowserInfo) -> Unit
    ) : RecyclerView.Adapter<BrowserAdapter.VH>() {

        private var selectedPos = -1

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val card: MaterialCardView = view.findViewById(R.id.cardBrowser)
            val ivIcon: ImageView = view.findViewById(R.id.ivBrowserIcon)
            val tvName: TextView = view.findViewById(R.id.tvBrowserName)
            val tvPkg: TextView = view.findViewById(R.id.tvPackageName)
            val tvType: TextView = view.findViewById(R.id.tvBrowserType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_browser, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = browsers[position]

            holder.tvName.text = b.name
            holder.tvPkg.text = b.packageName
            holder.tvType.text = b.type.label
            holder.tvType.setBackgroundResource(
                if (b.type == BrowserType.FIREFOX) R.drawable.badge_firefox
                else R.drawable.badge_chromium
            )

            val icon = getAppIcon(b.packageName)
            if (icon != null) holder.ivIcon.setImageDrawable(icon)
            else holder.ivIcon.setImageResource(R.drawable.ic_launcher_foreground)

            val selected = position == selectedPos
            holder.card.strokeWidth = if (selected) 3 else 1
            holder.card.strokeColor = getColor(
                if (selected) R.color.selected_stroke else R.color.card_stroke
            )

            holder.card.setOnClickListener {
                val old = selectedPos
                selectedPos = holder.adapterPosition
                if (old >= 0) notifyItemChanged(old)
                notifyItemChanged(selectedPos)
                onClick(b)
            }
        }

        override fun getItemCount() = browsers.size
    }

    private fun getAppIcon(pkg: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
