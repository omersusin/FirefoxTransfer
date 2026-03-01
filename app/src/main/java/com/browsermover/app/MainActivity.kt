package com.browsermover.app

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var dataMover: DataMover
    private lateinit var etSourcePackage: TextInputEditText
    private lateinit var etTargetPackage: TextInputEditText
    private lateinit var btnTransfer: MaterialButton
    private lateinit var cardLog: MaterialCardView
    private lateinit var tvLog: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var switchBackup: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dataMover = DataMover()
        initViews()
        checkRoot()
    }

    private fun initViews() {
        etSourcePackage = findViewById(R.id.etSourcePackage)
        etTargetPackage = findViewById(R.id.etTargetPackage)
        btnTransfer = findViewById(R.id.btnTransfer)
        cardLog = findViewById(R.id.cardLog)
        tvLog = findViewById(R.id.tvLog)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        switchBackup = findViewById(R.id.switchBackup)

        // Hide unused auto-detect views
        findViewById<MaterialCardView>(R.id.cardCategory).visibility = android.view.View.GONE
        findViewById<MaterialCardView>(R.id.cardSource).visibility = android.view.View.GONE
        findViewById<MaterialCardView>(R.id.cardTarget).visibility = android.view.View.GONE
        
        // Show manual card
        findViewById<MaterialCardView>(R.id.cardManual).visibility = android.view.View.VISIBLE
        findViewById<MaterialCardView>(R.id.cardBackup).visibility = android.view.View.VISIBLE

        btnTransfer.visibility = android.view.View.VISIBLE
        btnTransfer.text = "Start Transfer"
        
        // Pre-fill for testing if needed
        // etSourcePackage.setText("org.mozilla.firefox")
        
        btnTransfer.setOnClickListener { onTransferClicked() }
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootHelper.isRootAvailable()
            runOnUiThread {
                if (hasRoot) {
                    tvRootStatus.text = "✅ Root Access Granted"
                } else {
                    tvRootStatus.text = "❌ Root Required!"
                    btnTransfer.isEnabled = false
                }
            }
        }.start()
    }

    private fun onTransferClicked() {
        val src = etSourcePackage.text.toString().trim()
        val dst = etTargetPackage.text.toString().trim()

        if (src.isEmpty() || dst.isEmpty()) {
            Toast.makeText(this, "Enter both package names", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("Confirm Transfer")
            .setMessage("From: $src\nTo: $dst\n\nThis will wipe data in the target app.")
            .setPositiveButton("Start") { _, _ -> startTransfer(src, dst) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTransfer(src: String, dst: String) {
        cardLog.visibility = android.view.View.VISIBLE
        tvLog.text = "Initializing..."
        btnTransfer.isEnabled = false

        // Manual mode assumes Gecko logic for safety (covers most use cases)
        // or uses a generic copier. Let's use the robust Gecko script 
        // as it handles generic files too.
        val dummyInfo = BrowserInfo("Manual", src, BrowserType.GECKO, true)
        val targetInfo = BrowserInfo("Manual", dst, BrowserType.GECKO, true)

        dataMover.moveData(dummyInfo, targetInfo, switchBackup.isChecked, object : DataMover.ProgressListener {
            override fun onProgress(message: String) { appendLog(message) }
            override fun onSuccess(message: String) {
                appendLog("✅ DONE!")
                btnTransfer.isEnabled = true
                AlertDialog.Builder(this@MainActivity).setTitle("Success").setMessage(message).setPositiveButton("OK", null).show()
            }
            override fun onError(message: String) {
                appendLog("❌ ERROR: $message")
                btnTransfer.isEnabled = true
            }
        })
    }

    private fun appendLog(msg: String) {
        val current = tvLog.text.toString()
        tvLog.text = if (current.isEmpty()) msg else "$current\n$msg"
    }
}
