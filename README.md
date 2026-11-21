# Fast IDM

An open-source download manager with a Python desktop application, browser extension, and native Android edition.

## Desktop edition (Windows)

The original desktop project is built with Python and includes browser integration through the `Extension/` folder.

1. Install Python.
2. Run the application with `python fast_idm.py`.
3. To connect the browser extension, open `chrome://extensions/`, enable **Developer mode**, choose **Load unpacked**, and select the repository's `Extension/` directory.

## Android edition

The native Kotlin + Jetpack Compose app is maintained on the [`android-starter` branch](https://github.com/drsumon200/Fast-IDM/tree/android-starter/android).

### Features

- Receive HTTP/HTTPS links from Android's Share menu or open links directly.
- Download direct files with Android DownloadManager.
- View queue, progress, status, system notifications, and completed direct-download entries.
- Save direct downloads to `Downloads/FastIDM`.
- Use Android DownloadManager's supported retry and interrupted-download recovery behavior.
- Download supported website media through the bundled yt-dlp Android and FFmpeg libraries.
- Choose Best, 1080p, 720p, 480p, or Audio MP3 presets.
- Run media downloads in a foreground service with progress notification and Stop action.
- Publish completed media to the public `Downloads/FastIDM` folder on Android 10+.

### Download the Android APK

The latest verified CI build is [Build #20 — successful](https://github.com/drsumon200/Fast-IDM/actions/runs/44873920618).

1. Open the [successful Android build](https://github.com/drsumon200/Fast-IDM/actions/runs/44873920618).
2. Under **Artifacts**, download **Fast-IDM-debug**.
3. Extract the ZIP and install `app-debug.apk` on an ARM64 Android device.

This is a **debug APK**, not a Play Store release. The current build targets ARM64 devices and requires Android 8.0 (API 26) or newer.

### Android build notes and limitations

- Build locally with JDK 17 and Gradle 8.9: `gradle --no-daemon -p android assembleDebug`.
- Website media extraction depends on yt-dlp extractors and the target website. Some sites may block requests or require account access; only download content you are authorized to save.
- The current video engine processes one yt-dlp task at a time. Direct DownloadManager tasks can run in parallel.
- The app does not yet provide an app-managed persistent database for video-task history, browser-cookie import, playlist batch queue, subtitle controls, or aria2 integration.
- CI confirms that the APK compiles and packages; real-device behavior and website compatibility still need device testing.

## License and author

- **Developer:** Mahmudul Hasan Sumon
- **GitHub:** [@drsumon200](https://github.com/drsumon200)
- **License:** MIT License

Copyright © 2026 Mahmudul Hasan Sumon.
