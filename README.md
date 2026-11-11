# 🌐 Browser Data Migrator for Android

**The bridge for your browser data.**  
Move bookmarks and history between Android browsers, or copy a full profile
between two browsers that use the same engine—without cloud sync or manual
exports.

---

## 🤔 What is this?
**Browser Data Migrator** is an open-source tool for rooted Android phones. It
copies and patches browser data files directly, so you can move from **Firefox
to Iceraven**, **Chrome to Brave**, or even **Chrome to Firefox** without losing
your bookmarks and history.

> [!IMPORTANT]
> **Root access is required** (Magisk, KernelSU, or SuperSU) to access protected
> browser data directories.

---

## ✨ Key Features
- **🚀 One-Tap Migration:** Enter source and target package names, press start.
- **🔀 Same-Engine Transfers:** Full profile migration between two Chromium
  browsers or two Gecko browsers.
- **↔️ Cross-Engine Transfers:** Chromium → Gecko and Gecko → Chromium for
  bookmarks and history, converted on-device without needing an external
  `sqlite3` binary.
- **🔧 Smart Patching:** Updates internal paths and package names in SQLite
  databases (when `sqlite3` is available) and JSON configuration files.
- **🛡️ Safety First:** Automatic target backups are created before any change,
  and the rollback action restores them.
- **🧹 Built-in Cleanup:** One-tap button to delete old migration backups.
- **🌍 Wide Compatibility:** Supports both **Gecko** (Firefox-based) and
  **Chromium** (Chrome-based) engines, including de-Googled Chromium builds such
  as Bare Browser.

---

## 📦 Supported Data

| Data Type | Same engine | Chromium ↔ Gecko | Notes |
| :--- | :---: | :---: | :--- |
| **Bookmarks** | ✅ | ✅ | Folder structure is preserved. |
| **History** | ✅ | ✅ | URL, title, visit count and visit times. |
| **Passwords** | ✅* | ❌ | Engine-specific encryption; not converted cross-engine. |
| **Cookies** | ✅ | ❌ | Engine-specific encryption; not converted cross-engine. |
| **Extensions** | ✅* | ❌ | Chromium extensions do not exist in Gecko. |
| **Site Permissions** | ✅ | ❌ | Same-engine only. |
| **Tabs** | ⚠️ | ❌ | Same-engine tab files are copied where compatible. |

\* Same-engine password/extension migration depends on the target app using
compatible encryption and extension IDs. Chromium passwords are tied to the
source app's Android Keystore key, so they often cannot be decrypted by a
different browser package.

---

## 🛠 Prerequisites
1. **Root Access:** Essential for reading/writing `/data/data/`.
2. **Both Browsers Installed:** Open each browser at least once before migrating
   so it creates its profile.
3. **Storage:** Free space for temporary copies and backups (roughly the size of
   the target profile).

---

## 🚀 How to Use
1. **Open the app** and grant root permission when prompted.
2. **Source:** Enter the package name of the browser you are moving from
   (for example `com.chrome.beta` or `org.mozilla.firefox_beta`).
3. **Target:** Enter the package name of the browser you are moving to
   (for example `org.barebrowser` or `com.chrome.beta`).
4. **Start:** Tap **START MIGRATION** and watch the log.
5. **Verify:** Open the target browser and check its bookmarks/history.
6. **Cleanup:** Use **DELETE BACKUPS** when you no longer need the safety copies.

### Package-name examples
| Browser | Package |
| :--- | :--- |
| Chrome Beta | `com.chrome.beta` |
| Firefox Beta | `org.mozilla.firefox_beta` |
| Brave | `com.brave.browser` |
| Bare Browser (Chromium) | `org.barebrowser` |
| Iceraven | `io.github.forkmaintainers.iceraven` |

---

## 🔄 Cross-Engine Details
Cross-engine migrations use the app's own SQLite/JSON support instead of
shelling out to `sqlite3`, which is missing on many Android systems.

- **Chromium → Firefox:** reads `History` and `Bookmarks`, then inserts into
  Firefox's `places.sqlite` (places, visits, bookmark roots/folders).
- **Firefox → Chromium:** reads `places.sqlite`, then merges into Chromium's
  `History` and `Bookmarks` JSON.
- Only `http`, `https`, and `ftp` URLs are migrated.
- Passwords, cookies, extensions and tabs are not moved between engines because
  their storage and encryption are engine-specific.
- After a cross-engine transfer, launch the target browser once more so it can
  finish rebuilding its caches/favicons.

---

## 🔒 Security & Privacy
- **Local Only:** No data leaves the device. No analytics, no cloud, no internet
  required.
- **Input Validation:** Strict regex filtering on package names prevents shell
  injection.
- **Controlled Execution:** Scripts run under `/data/local/tmp` and are removed
  with their backups when you clear them.
- **SELinux Aware:** File contexts are restored with `restorecon` when it is
  available.

---

## ⚠️ Known Limitations
- **Cross-engine scope:** Bookmarks and history only.
- **Chromium passwords:** Android Keystore encryption binds them to the source
  app; moving to a different package may make them undecryptable.
- **Missing optional files:** A fresh Chrome profile may not have `Bookmarks`,
  `Login Data`, or extension folders. These are reported as skipped, not as a
  failed migration.
- **`sqlite3` missing:** Same-engine data is still copied; only the optional
  package-path rewriting inside SQLite databases is skipped. Cross-engine
  conversion does not depend on `sqlite3`.
- **Tabs:** Cross-engine tabs are intentionally skipped to avoid session-format
  crashes.

---

## 🏗 Build from Source
```bash
git clone https://github.com/omersusin/FirefoxTransfer.git
cd FirefoxTransfer
./gradlew assembleRelease
```

---
*Developed with ❤️ for the Android community.*
