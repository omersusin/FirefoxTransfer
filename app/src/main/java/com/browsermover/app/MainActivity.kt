package com.browsermover.app

import android.os.Bundle
import android.view.View
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
    private lateinit var btnManualTransfer: MaterialButton
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
        btnManualTransfer = findViewById(R.id.btnManualTransfer)
        cardLog = findViewById(R.id.cardLog)
        tvLog = findViewById(R.id.tvLog)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        switchBackup = findViewById(R.id.switchBackup)

        // Hide auto-detect UI elements
        findViewById<MaterialCardView>(R.id.cardCategory).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardSource).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardTarget).visibility = View.GONE
        findViewById<MaterialButton>(R.id.btnTransfer).visibility = View.GONE
        
        // Show only manual UI
        findViewById<MaterialCardView>(R.id.cardManual).visibility = View.VISIBLE
        findViewById<MaterialCardView>(R.id.cardBackup).visibility = View.VISIBLE

        btnManualTransfer.setOnClickListener { onTransferClicked() }
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootHelper.isRootAvailable()
            runOnUiThread {
                if (hasRoot) {
                    tvRootStatus.text = "✅ Root Access Granted"
                } else {
                    tvRootStatus.text = "❌ Root Required!"
                    btnManualTransfer.isEnabled = false
                }
            }
        }.start()
    }

    private fun onTransferClicked() {
        val src = etSourcePackage.text.toString().trim()
        val dst = etTargetPackage.text.toString().trim()

        if (src.isEmpty() || dst.isEmpty()) {
            Toast.makeText(this, "Please enter both package names", Toast.LENGTH_SHORT).show()
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("Confirm Clone")
            .setMessage("Source: $src\nTarget: $dst\n\nProceed with full data migration?")
            .setPositiveButton("Yes, Start") { _, _ -> startTransfer(src, dst) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startTransfer(src: String, dst: String) {
        cardLog.visibility = View.VISIBLE
        tvLog.text = "Initializing experts script..."
        btnManualTransfer.isEnabled = false

        // Manual mode defaults to GECKO logic as it's the primary target
        val sourceInfo = BrowserInfo("Manual", src, BrowserType.GECKO, true)
        val targetInfo = BrowserInfo("Manual", dst, BrowserType.GECKO, true)

        dataMover.moveData(sourceInfo, targetInfo, switchBackup.isChecked, object : DataMover.ProgressListener {
            override fun onProgress(message: String) { appendLog(message) }
            override fun onSuccess(message: String) {
                appendLog("✅ DONE!")
                btnManualTransfer.isEnabled = true
                AlertDialog.Builder(this@MainActivity).setTitle("Success").setMessage(message).setPositiveButton("OK", null).show()
            }
            override fun onError(message: String) {
                appendLog("❌ ERROR: $message")
                btnManualTransfer.isEnabled = true
            }
        })
    }

    private fun appendLog(msg: String) {
        val current = tvLog.text.toString()
        tvLog.text = if (current.isEmpty()) msg else "$current\n$msg"
    }
}
