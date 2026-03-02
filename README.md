# Browser Data Migrator (v3)
============================================================

Open-source tool that migrates **all data** (bookmarks, passwords, history, extensions) between Android browsers using root access.

| Engine   | Browsers                                         |
|----------|--------------------------------------------------|
| **Gecko**| Firefox, Mull, Iceraven, Fennec, Tor, Focus      |
| **Chromium** | Chrome, Brave, Kiwi, Vivaldi, Opera, Edge, Samsung |

## 📦 Migrated Data
| Data Type      | Gecko | Chromium | Notes                            |
|----------------|-------|----------|----------------------------------|
| Bookmarks      | ✅    | ✅       |                                  |
| History        | ✅    | ✅       |                                  |
| Passwords      | ✅    | ⚠️       | Chromium: Keystore dependent     |
| Cookies        | ✅    | ✅       |                                  |
| Extensions     | ✅    | ✅       | Within same engine family        |
| Site Permissions| ✅    | ✅       |                                  |
| Tabs           | ❌    | ❌       | Intentional: crash prevention    |

## 🛠 Prerequisites
- **Root access** (Magisk / KernelSU / SuperSU)
- ~100MB free storage

## 🚀 Usage
1. Install and open the APK.
2. Grant Root permission.
3. Select Source browser (where data is coming from).
4. Select Target browser (where data will be written).
5. Press "START MIGRATION".
6. (Optional) Use "DELETE BACKUPS" to clear storage once verified.
7. Open the target browser and enjoy your data.

## ⚠️ Known Limitations
- **Chromium Passwords**: Moving between different package UIDs may cause password decryption issues as the Android Keystore keys change. No issues when reinstalling the same package.
- **Cross-engine**: Extensions cannot be moved between Gecko → Chromium or vice-versa due to different database schemas.
- **Tabs**: Intentionally not migrated due to format incompatibilities. This prevents the target browser from crashing.

## 🔒 Security
- Package names are validated with regex (shell injection prevention).
- All temporary files are kept under `/data/local/tmp`.
- Backups are written to root-only areas, not the SD card.
- JSON patching is done with base64/temp-file (no heredoc vulnerabilities).
- SELinux contexts are fixed with `restorecon`.

## ⚙️ Build
```bash
./gradlew assembleRelease
```
