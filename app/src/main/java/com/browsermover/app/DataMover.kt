package com.browsermover.app

import android.os.Handler
import android.os.Looper

class DataMover {

    companion object {
        private const val BACKUP_DIR = "/sdcard/BrowserDataMover/backups"
    }

    interface ProgressListener {
        fun onProgress(message: String)
        fun onSuccess(message: String)
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun moveData(
        source: BrowserInfo,
        target: BrowserInfo,
        backupFirst: Boolean,
        listener: ProgressListener
    ) {
        Thread {
            try {
                // 1. Root kontrolu
                postProgress(listener, "Root erişimi kontrol ediliyor...")
                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root erişimi bulunamadı!")
                    return@Thread
                }

                val sourceDir = "/data/data/${source.packageName}"
                val targetDir = "/data/data/${target.packageName}"

                // 2. Kaynak veri kontrolu - test -d kullan
                postProgress(listener, "Kaynak kontrol ediliyor: ${source.name}")
                val sourceCheck = RootHelper.executeCommand(
                    "if [ -d \"$sourceDir\" ]; then echo EXISTS; else echo NOTFOUND; fi"
                )
                postProgress(listener, "Kaynak kontrol sonucu: ${sourceCheck.output}")

                if (!sourceCheck.output.contains("EXISTS")) {
                    postError(listener, "Kaynak tarayıcı verisi bulunamadı:\n${source.packageName}\n\nKontrol: ${sourceCheck.output}\nHata: ${sourceCheck.error}")
                    return@Thread
                }

                // 3. Hedef veri kontrolu
                postProgress(listener, "Hedef kontrol ediliyor: ${target.name}")
                val targetCheck = RootHelper.executeCommand(
                    "if [ -d \"$targetDir\" ]; then echo EXISTS; else echo NOTFOUND; fi"
                )
                postProgress(listener, "Hedef kontrol sonucu: ${targetCheck.output}")

                if (!targetCheck.output.contains("EXISTS")) {
                    postError(listener, "Hedef tarayıcı yüklü değil veya verisi yok:\n${target.packageName}\n\nKontrol: ${targetCheck.output}\nHata: ${targetCheck.error}")
                    return@Thread
                }

                // 4. Kaynak icerigini listele (debug)
                postProgress(listener, "Kaynak içeriği listeleniyor...")
                val lsSource = RootHelper.executeCommand("ls -la $sourceDir/ 2>&1 | head -20")
                postProgress(listener, "Kaynak içerik:\n${lsSource.output}")

                // 5. Uygulamalari durdur
                postProgress(listener, "Tarayıcılar durduruluyor...")
                RootHelper.executeCommand("am force-stop ${source.packageName}")
                RootHelper.executeCommand("am force-stop ${target.packageName}")
                Thread.sleep(1000)

                // 6. Yedekleme
                if (backupFirst) {
                    postProgress(listener, "Hedef tarayıcı yedekleniyor...")
                    val backupResult = createBackup(target.packageName)
                    if (!backupResult) {
                        postProgress(listener, "⚠️ Yedekleme başarısız oldu, devam ediliyor...")
                    } else {
                        postProgress(listener, "Yedekleme tamamlandı.")
                    }
                }

                // 7. Hedef veriyi sil
                postProgress(listener, "Hedef tarayıcı verileri temizleniyor...")
                RootHelper.executeCommand("rm -rf $targetDir/*")

                // 8. Veriyi kopyala
                postProgress(listener, "Veriler kopyalanıyor... (Bu biraz sürebilir)")
                val cpResult = RootHelper.executeCommand(
                    "cp -a $sourceDir/* $targetDir/ 2>&1"
                )
                postProgress(listener, "Kopyalama çıktısı: ${cpResult.output.take(500)}")

                if (!cpResult.success && cpResult.error.isNotBlank()) {
                    postProgress(listener, "⚠️ Kopyalama uyarısı: ${cpResult.error.take(300)}")
                }

                // 9. Sahiplik ve izinleri duzelt
                postProgress(listener, "İzinler düzeltiliyor...")

                // Yontem 1: stat ile
                var owner = ""
                val statResult = RootHelper.executeCommand(
                    "stat -c '%U' $targetDir 2>/dev/null"
                )
                owner = statResult.output.trim().replace("'", "")

                // Yontem 2: ls ile
                if (owner.isBlank() || owner == "root") {
                    val lsResult = RootHelper.executeCommand(
                        "ls -ld /data/data/${target.packageName} | awk '{print \$3}'"
                    )
                    val lsOwner = lsResult.output.trim()
                    if (lsOwner.isNotBlank() && lsOwner != "root") {
                        owner = lsOwner
                    }
                }

                // Yontem 3: dumpsys ile uid bul
                if (owner.isBlank() || owner == "root") {
                    val dumpResult = RootHelper.executeCommand(
                        "dumpsys package ${target.packageName} | grep userId= | head -1"
                    )
                    val uidMatch = Regex("userId=(\\d+)").find(dumpResult.output)
                    if (uidMatch != null) {
                        val uid = uidMatch.groupValues[1]
                        owner = "u0_a${uid.toInt() - 10000}"
                        postProgress(listener, "UID bulundu: $uid → $owner")
                    }
                }

                if (owner.isNotBlank() && owner != "root") {
                    RootHelper.executeCommand("chown -R $owner:$owner $targetDir")
                    postProgress(listener, "Sahiplik düzeltildi: $owner")
                } else {
                    postProgress(listener, "⚠️ Sahiplik otomatik belirlenemedi.")
                    // Son cozum: hedef paketi kullanarak set et
                    RootHelper.executeCommand(
                        "chown -R \$(stat -c '%U' /data/data/${target.packageName}):\$(stat -c '%G' /data/data/${target.packageName}) $targetDir 2>/dev/null"
                    )
                }

                // 10. SELinux context duzelt
                postProgress(listener, "SELinux bağlamı düzeltiliyor...")
                RootHelper.executeCommand("restorecon -R $targetDir 2>/dev/null")

                // 11. Dogrulama
                postProgress(listener, "Transfer doğrulanıyor...")
                val verifyResult = RootHelper.executeCommand("ls $targetDir/ 2>&1 | head -10")
                postProgress(listener, "Hedef içerik:\n${verifyResult.output}")

                // 12. Basarili
                postSuccess(
                    listener,
                    "Transfer başarılı!\n\n" +
                    "Kaynak: ${source.name}\n" +
                    "Hedef: ${target.name}\n\n" +
                    "Şimdi ${target.name} uygulamasını açabilirsiniz."
                )

            } catch (e: Exception) {
                postError(listener, "Beklenmeyen hata:\n${e.message}\n${e.stackTraceToString().take(500)}")
            }
        }.start()
    }

    fun restoreBackup(packageName: String, backupFile: String, listener: ProgressListener) {
        Thread {
            try {
                postProgress(listener, "Root kontrol ediliyor...")
                if (!RootHelper.isRootAvailable()) {
                    postError(listener, "Root erişimi bulunamadı!")
                    return@Thread
                }

                val targetDir = "/data/data/$packageName"

                postProgress(listener, "Uygulama durduruluyor...")
                RootHelper.executeCommand("am force-stop $packageName")
                Thread.sleep(500)

                postProgress(listener, "Mevcut veri temizleniyor...")
                RootHelper.executeCommand("rm -rf $targetDir/*")

                postProgress(listener, "Yedek geri yükleniyor...")
                val tarResult = RootHelper.executeCommand(
                    "tar -xzf $backupFile -C $targetDir"
                )
                if (!tarResult.success) {
                    postError(listener, "Geri yükleme başarısız:\n${tarResult.error}")
                    return@Thread
                }

                postProgress(listener, "İzinler düzeltiliyor...")
                val ownerResult = RootHelper.executeCommand(
                    "ls -ld $targetDir | awk '{print \$3}'"
                )
                val owner = ownerResult.output.trim()
                if (owner.isNotBlank()) {
                    RootHelper.executeCommand("chown -R $owner:$owner $targetDir")
                }

                RootHelper.executeCommand("restorecon -R $targetDir 2>/dev/null")

                postSuccess(listener, "Yedek başarıyla geri yüklendi!")

            } catch (e: Exception) {
                postError(listener, "Hata: ${e.message}")
            }
        }.start()
    }

    fun listBackups(): List<String> {
        val result = RootHelper.executeCommand("ls $BACKUP_DIR/*.tar.gz 2>/dev/null")
        if (!result.success || result.output.isBlank()) return emptyList()
        return result.output.lines().filter { it.isNotBlank() }
    }

    private fun createBackup(packageName: String): Boolean {
        val timestamp = System.currentTimeMillis()
        val backupFile = "$BACKUP_DIR/${packageName}_$timestamp.tar.gz"

        RootHelper.executeCommand("mkdir -p $BACKUP_DIR")

        val result = RootHelper.executeCommand(
            "tar -czf $backupFile -C /data/data/$packageName . 2>&1"
        )
        return result.success
    }

    private fun postProgress(listener: ProgressListener, message: String) {
        mainHandler.post { listener.onProgress(message) }
    }

    private fun postSuccess(listener: ProgressListener, message: String) {
        mainHandler.post { listener.onSuccess(message) }
    }

    private fun postError(listener: ProgressListener, message: String) {
        mainHandler.post { listener.onError(message) }
    }
}
