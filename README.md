# 🌐 Browser Data Migrator for Android

**The bridge for your browser data.**  
Migrate your bookmarks, history, passwords, and extensions between Android browsers instantly—no cloud sync or manual exporting required.

---

## 🤔 What is this?
**Browser Data Migrator** is a powerful open-source tool designed for power users who want to switch browsers on Android without losing their digital life. Whether you're moving from **Firefox to Iceraven** or **Chrome to Brave**, this tool handles the heavy lifting by copying and patching internal database files directly.

> [!IMPORTANT]
> **Root access is required** (Magisk, KernelSU, or SuperSU) to access protected browser data directories.

---

## ✨ Key Features
- **🚀 One-Tap Migration:** Select source and target, press start—done.
- **📁 Comprehensive Data Support:** Moves Bookmarks, History, Cookies, and even Extensions.
- **🔧 Smart Patching:** Automatically updates internal paths and package names within database files (SQLite) and JSON configs.
- **🛡️ Safety First:** Automatic backups are created before any modification. 
- **🧹 Built-in Cleanup:** One-tap button to clear migration backups and free up space.
- **🌍 Wide Compatibility:** Supports both **Gecko** (Firefox-based) and **Chromium** (Chrome-based) engines.

---

## 📦 Supported Data & Engines

| Data Type | Gecko 🦊 | Chromium 🌐 | Notes |
| :--- | :---: | :---: | :--- |
| **Bookmarks** | ✅ | ✅ | Full history & star sync. |
| **History** | ✅ | ✅ | Your browsing timeline. |
| **Passwords** | ✅ | ⚠️ | Chromium: Limited by Android Keystore. |
| **Cookies** | ✅ | ✅ | Stay logged in on your favorite sites. |
| **Extensions** | ✅ | ✅ | Preserves extension data and settings. |
| **Site Permissions** | ✅ | ✅ | Notifications, location, etc. |

---

## 🛠 Prerequisites
1. **Root Access:** Essential for reading/writing to `/data/data/`.
2. **Target Browser Installed:** The app you are moving *to* must be installed.
3. **Storage:** ~100MB of free space for temporary processing and backups.

---

## 🚀 How to Use
1. **Open the App:** Grant Root permissions when prompted.
2. **Select Source:** Type or paste the package name of your current browser (e.g., `org.mozilla.firefox`).
3. **Select Target:** Type or paste the package name of your new browser (e.g., `com.brave.browser`).
4. **Start:** Tap **START MIGRATION** and watch the real-time log.
5. **Verify:** Open your new browser and verify your data is there.
6. **Cleanup:** Use **DELETE BACKUPS** to remove the temporary safety copies.

---

## 🔒 Security & Privacy
- **Local Only:** No data ever leaves your device. No analytics, no cloud, no internet required.
- **Input Validation:** Strict regex filtering on package names to prevent shell injection.
- **Sandboxed Execution:** Scripts run in a controlled environment under `/data/local/tmp`.
- **SELinux Aware:** Automatically restores file contexts using `restorecon` to prevent "App Not Responding" or permission issues.

---

## ⚠️ Known Limitations
- **Chromium Passwords:** Due to Android Keystore encryption, passwords moved between different apps (e.g., Chrome -> Brave) may not be decryptable. Migration works best when reinstalling the *same* app or moving within identical signature families.
- **Tabs:** Intentionally skipped to prevent session crashes caused by version mismatches.

---

## 🏗 Build from Source
```bash
git clone https://github.com/omersusin/FirefoxTransfer.git
cd FirefoxTransfer
./gradlew assembleRelease
```

---
*Developed with ❤️ for the Android community.*
