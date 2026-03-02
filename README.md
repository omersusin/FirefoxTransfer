# 🌐 Browser Data Migrator

Root yetkisi kullanarak Android tarayıcıları arasında
**tüm verileri** (yer imleri, şifreler, geçmiş, eklentiler)
eksiksiz taşıyan açık kaynak araç.

## ✨ Desteklenen Motorlar

| Motor    | Tarayıcılar                                      |
|----------|--------------------------------------------------|
| 🦎 Gecko | Firefox, Fennec, Mull, Iceraven, Tor Browser     |
| ⚡ Chromium | Chrome, Brave, Kiwi, Vivaldi, Edge, Opera, Samsung |

## 📦 Taşınan Veriler

| Veri Tipi      | Gecko | Chromium | Not                              |
|----------------|:-----:|:--------:|----------------------------------|
| Yer İmleri     | ✅    | ✅       |                                  |
| Geçmiş         | ✅    | ✅       |                                  |
| Şifreler       | ✅    | ⚠️       | Chromium: Keystore bağımlı       |
| Çerezler       | ✅    | ✅       |                                  |
| Eklentiler     | ✅    | ✅       | Aynı motor ailesi içinde         |
| Form Verisi    | ✅    | ✅       |                                  |
| Site İzinleri  | ✅    | ✅       |                                  |
| Sertifikalar   | ✅    | —        |                                  |
| Sekmeler       | ❌    | ❌       | Kasıtlı: çökme önlemi           |

## ⚙️ Gereksinimler

- **Root erişimi** (Magisk / KernelSU / SuperSU)
- Android 7.0+ (API 24)
- ~100MB boş depolama

## 🚀 Kullanım

1. APK'yı yükleyin ve açın
2. Root izni verin
3. Kaynak tarayıcıyı seçin (veriler buradan alınacak)
4. Hedef tarayıcıyı seçin (veriler buraya yazılacak)
5. "GÖÇÜ BAŞLAT" butonuna basın
6. İşlem tamamlanınca hedef tarayıcıyı açıp doğrulayın

## ⚠️ Bilinen Sınırlamalar

- **Chromium şifreleri**: Farklı UID'ler arası taşımada
  Android Keystore anahtarları değiştiği için şifreler
  çözülemeyebilir. Aynı paket yeniden kurulumunda sorun yoktur.

- **Çapraz motor**: Gecko → Chromium veya tersi taşımada
  veritabanı şemaları farklı olduğu için eklentiler taşınamaz.

- **Sekmeler**: Format uyumsuzlukları nedeniyle kasıtlı olarak
  taşınmaz. Bu, hedef tarayıcının çökmesini önler.

## 🏗️ Derleme

```bash
git clone https://github.com/user/BrowserMigrator.git
cd BrowserMigrator
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

## 🔒 Güvenlik

- Paket adları regex ile doğrulanır (shell injection önlemi)
- Tüm geçici dosyalar `/data/local/tmp` altında tutulur
- Yedekler SD karta değil root-only alana yazılır
- JSON yamalama base64/temp-file ile yapılır (heredoc açığı yok)
- SELinux bağlamları `restorecon` ile düzeltilir

## 📄 Lisans

MIT License
