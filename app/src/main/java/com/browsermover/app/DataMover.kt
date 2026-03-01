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

                // 2. Kaynak veri kontrolu
                val sourceDir = "/data/data/${source.packageName}"
                val targetDir = "/data/data/${target.packageName}"

                postProgress(listener, "Kaynak kontrol ediliyor: ${source.name}")
                val sourceCheck = RootHelper.executeCommand("ls $sourceDir")
                if (!sourceCheck.success || sourceCheck.output.isBlank()) {
                    postError(listener, "Kaynak tarayıcı verisi bulunamadı:\n${source.packageName}")
                    return@Thread
                }

                // 3. Hedef veri kontrolu
                postProgress(listener, "Hedef kontrol ediliyor: ${target.name}")
                val targetCheck = RootHelper.executeCommand("ls $targetDir")
                if (!targetCheck.success || targetCheck.output.isBlank()) {
                    postError(listener, "Hedef tarayıcı yüklü değil:\n${target.packageName}")
                    return@Thread
                }

                // 4. Uygulamalari durdur
                postProgress(listener, "Tarayıcılar durduruluyor...")
                RootHelper.executeCommand("am force-stop ${source.packageName}")
                RootHelper.executeCommand("am force-stop ${target.packageName}")
                Thread.sleep(1000)

                // 5. Yedekleme
                if (backupFirst) {
                    postProgress(listener, "Hedef tarayıcı yedekleniyor...")
                    val backupResult = createBackup(target.packageName)
                    if (!backupResult) {
                        postError(listener, "Yedekleme başarısız oldu!")
                        return@Thread
                    }
                    postProgress(listener, "Yedekleme tamamlandı.")
                }

                // 6. Hedef veriyi sil
                postProgress(listener, "Hedef tarayıcı verileri temizleniyor...")
                val rmResult = RootHelper.executeCommand("rm -rf $targetDir/*")
                if (!rmResult.success) {
                    postError(listener, "Hedef temizleme başarısız:\n${rmResult.error}")
                    return@Thread
                }

                // 7. Veriyi kopyala
                postProgress(listener, "Veriler kopyalanıyor... (Bu biraz sürebilir)")
                val cpResult = RootHelper.executeCommand(
                    "cp -Rvf $sourceDir/* $targetDir/ 2>&1"
                )
                if (!cpResult.success) {
                    postError(listener, "Kopyalama başarısız:\n${cpResult.error}")
                    return@Thread
                }

                // 8. Sahiplik ve izinleri duzelt
                postProgress(listener, "İzinler düzeltiliyor...")
                val ownerResult = RootHelper.executeCommand(
                    "stat -c '%U' $targetDir"
                )

                var owner = ownerResult.output.trim()

                // stat calismaz ise alternatif yontem
                if (owner.isBlank() || !ownerResult.success) {
                    val lsResult = RootHelper.executeCommand(
                        "ls -ld $targetDir | awk '{print \$3}'"
                    )
                    owner = lsResult.output.trim()
                }

                if (owner.isNotBlank()) {
                    RootHelper.executeCommand("chown -R $owner:$owner $targetDir")
                    postProgress(listener, "Sahiplik düzeltildi: $owner")
                } else {
                    postProgress(listener, "Uyarı: Sahiplik belirlenemedi, elle düzeltmeniz gerekebilir.")
                }

                // 9. SELinux context duzelt
                postProgress(listener, "SELinux bağlamı düzeltiliyor...")
                RootHelper.executeCommand("restorecon -R $targetDir 2>/dev/null")

                // 10. Basarili
                postSuccess(
                    listener,
                    "Transfer başarılı!\n\n" +
                    "Kaynak: ${source.name}\n" +
                    "Hedef: ${target.name}\n\n" +
                    "Şimdi ${target.name} uygulamasını açabilirsiniz."
                )

            } catch (e: Exception) {
                postError(listener, "Beklenmeyen hata:\n${e.message}")
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