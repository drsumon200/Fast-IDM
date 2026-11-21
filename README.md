# Fast IDM

An open-source Internet Download Manager built with Python and a browser extension.

[![Download Fast IDM](https://shields.io)](https://github.com)

## Features
- Fast and lightweight download manager
- Seamless browser integration via custom extension
- Ready-to-use Windows executable (`.exe`) available
- Open-source and easy to customize

## Download & Quick Start (Windows)
1. Click the **Download** button above or go to the [Releases](https://github.com) page and download `FastDownloadManager.exe`.
2. Double-click `FastDownloadManager.exe` to launch the app.
3. To capture browser downloads automatically, open `chrome://extensions/`, enable **Developer mode**, click **Load unpacked**, and select the `Extension` folder from this repository.

## Run from Source (For Developers)
1. Install Python on your computer.
2. Run the main application:
   ```bash
   python fast_idm.py
   ```

## Author & Copyright
- **Developer:** Mahmudul Hasan Sumon
- **GitHub:** [@drsumon200](https://github.com)
- **License:** MIT License

Copyright © 2026 **Mahmudul Hasan Sumon**. All rights reserved.


## Author & Copyright
- **Developer:** Mahmudul Hasan Sumon
- **GitHub:** [@drsumon200](https://github.com)
- **License:** MIT License

Copyright © 2026 **Mahmudul Hasan Sumon**. All rights reserved.


## Android edition (starter)

A native Kotlin + Jetpack Compose Android project is available in the `android/` directory on the `android-starter` branch. It currently provides the initial UI and Android Share-menu link receiving. The actual HTTP and yt-dlp download engines are not implemented yet.

The **Build Fast IDM Android APK** GitHub Actions workflow builds a debug APK. Open the repository's Actions tab, select the workflow, and download the `Fast-IDM-debug` artifact after a successful run.
