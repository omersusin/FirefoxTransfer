package com.omersusin.firefoxtransfer

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private fun normalizePkg(raw: String): String {
        // Spinner item bazen "label\n(pkg)" veya "pkg (Label)" gibi gelebilir.
        val t = raw.trim()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // "(...)" varsa öncesini al
        return t.substringBefore("(", t).trim()
    }



  private lateinit var tvRoot: TextView
  private lateinit var tvLog: TextView
  private lateinit var btnScan: Button
  private lateinit var btnBackup: Button
  private lateinit var btnTransfer: Button
  private lateinit var spSource: Spinner
  private lateinit var spTarget: Spinner
  private lateinit var etSource: EditText
  private lateinit var etTarget: EditText
  private lateinit var rbProfile: RadioButton
  private lateinit var rbFull: RadioButton

  private var entries: List<PackageScanner.Entry> = emptyList()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    tvRoot = findViewById(R.id.tvRoot)
    tvLog = findViewById(R.id.tvLog)
    btnScan = findViewById(R.id.btnScan)
    btnBackup = findViewById(R.id.btnBackup)
    btnTransfer = findViewById(R.id.btnTransfer)
    spSource = findViewById(R.id.spSource)
    spTarget = findViewById(R.id.spTarget)
    etSource = findViewById(R.id.etSource)
    etTarget = findViewById(R.id.etTarget)
    rbProfile = findViewById(R.id.rbProfile)
    rbFull = findViewById(R.id.rbFull)

    Thread {
      val ok = RootHelper.hasRoot()
      runOnUiThread {
        tvRoot.text = if (ok) "✅ Root var" else "❌ Root yok (Magisk/SU gerekli)"
        btnScan.isEnabled = ok
        btnBackup.isEnabled = ok
        btnTransfer.isEnabled = ok
      }
    }.start()

    btnScan.setOnClickListener { scan() }
    btnBackup.setOnClickListener { backupOnly() }
    btnTransfer.setOnClickListener { transfer() }
  }

  private fun scan() {
    log("Scanning installed browsers...")
    Thread {
      entries = PackageScanner.scan(this)
      runOnUiThread {
        if (entries.isEmpty()) {
          log("No browser found. Use manual package fields.")
          val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("-- none --"))
          spSource.adapter = adapter
          spTarget.adapter = adapter
        } else {
          val items = listOf("-- seç --") + entries.map { it.toString() }
          val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
          adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
          spSource.adapter = adapter
          spTarget.adapter = adapter
          log("Found ${entries.size} apps.")
        }
      }
    }.start()
  }

  private fun selectedPkg(sp: Spinner, manual: EditText): String {
    val m = manual.text.toString().trim()
    if (m.isNotEmpty()) return m
    val pos = sp.selectedItemPosition
    return if (pos > 0 && pos - 1 < entries.size) entries[pos - 1].pkg else ""
  }

  private fun backupOnly() {
    val target = selectedPkg(spTarget, etTarget)
    if (target.isBlank()) {
      toast("Hedef seç veya paket yaz")
      return
    }
    runJob("Backup $target") {
      val plan = TransferEngine.Plan(sourcePkg = target, targetPkg = target, mode = TransferEngine.Mode.PROFILE)
      // Sadece backup: scriptin backup kısmını kullanmak için küçük hile: source=target, kopyalamayı skip eder
      val (ok, out) = TransferEngine.run(plan)
      ok to out
    }
  }

  private fun transfer() {
    val source = selectedPkg(spSource, etSource)
    val target = selectedPkg(spTarget, etTarget)

    if (source.isBlank() || target.isBlank()) {
      toast("Kaynak ve hedef seç veya paket yaz")
      return
    }
    if (source == target) {
      toast("Kaynak=Hedef olamaz")
      return
    }

    val mode = if (rbFull.isChecked) TransferEngine.Mode.FULL else TransferEngine.Mode.PROFILE

    runJob("Transfer $source -> $target ($mode)") {
      val (ok, out) = TransferEngine.run(TransferEngine.Plan(source, target, mode))
      ok to out
    }
  }

  private fun runJob(title: String, block: () -> Pair<Boolean, String>) {
    btnScan.isEnabled = false
    btnBackup.isEnabled = false
    btnTransfer.isEnabled = false
    tvLog.text = ""
    log("=== $title ===")
    Thread {
      val (ok, out) = block()
      runOnUiThread {
        log(out.trim())
        log(if (ok) "✅ SUCCESS" else "❌ FAILED")
        btnScan.isEnabled = true
        btnBackup.isEnabled = true
        btnTransfer.isEnabled = true
      }
    }.start()
  }

  private fun log(s: String) {
    tvLog.append(s + "\n")
  }

  private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
