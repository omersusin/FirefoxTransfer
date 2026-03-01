# Browser Data Mover 🚀

A powerful Android tool for transferring data between **Firefox-based** and **Chromium-based** browsers with **Root access**.

Easily migrate your bookmarks, logins, history, and cookies from Firefox to forks like **Iceraven**, **Mull**, or **Fennec**, or between Chrome, Brave, and other Chromium browsers.

## 🌟 Features

*   **Root Access Required:** Uses root privileges to access and transfer protected data directories (`/data/data/`).
*   **Cross-Fork Compatibility:** Specialized fixes for Firefox forks (Iceraven, Mull, etc.) to prevent crashes:
    *   Patches absolute paths in `prefs.js`.
    *   Cleans incompatible session data and lock files (`parent.lock`, `compatibility.ini`).
    *   Syncs `profiles.ini` correctly to ensure profile recognition.
*   **Supported Browsers:**
    *   **Firefox Family:** Firefox (Stable, Beta, Nightly), Iceraven, Mull, Fennec, Tor Browser, Waterfox, and more.
    *   **Chromium Family:** Chrome, Brave, Edge, Vivaldi, Kiwi, Bromite, Vanadium, and more.
*   **Backup System:** Option to automatically backup the target browser's data to `/sdcard/BrowserDataMover/backups/` before overwriting.
*   **Safety First:** Cleans crash reports, cache, and temporary files during transfer to ensure a clean slate.

## 🛠️ Installation

1.  Download the latest APK from the [Releases](https://github.com/omersusin/FirefoxTransfer/releases) page.
2.  Install the APK on your rooted Android device.
3.  Grant **Root** permissions when prompted.

## 📖 Usage

1.  **Select Family:** Choose between "Firefox Family" or "Chromium Family".
2.  **Select Source:** Choose the browser you want to copy data *from*.
3.  **Select Target:** Choose the browser you want to copy data *to*.
    *   *Note: The target browser's data will be overwritten.*
4.  **Transfer:** Tap the transfer button. The app will stop both browsers, backup the target (if selected), and migrate your data.
5.  **Done:** Open the target browser and enjoy your synced data!

## ⚠️ Disclaimer

*   **Root Required:** This application **requires a rooted device** to function.
*   **Data Overwrite:** The transfer process **deletes all existing data** in the target browser. Use the "Backup" option if you're unsure.
*   **Use at Your Own Risk:** While we strive for safety, always backup important data. The developers are not responsible for data loss.

## 🤝 Contributing

Contributions are welcome! Please fork the repository and submit a Pull Request.

1.  Fork the project.
2.  Create your feature branch (`git checkout -b feature/AmazingFeature`).
3.  Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4.  Push to the branch (`git push origin feature/AmazingFeature`).
5.  Open a Pull Request.
