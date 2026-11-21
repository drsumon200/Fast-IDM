# Fast IDM — Android Edition

This is the native Android companion app in the `android/` directory. The original desktop/Python project is kept separate.

## Current Android build (0.2.0)

- Native Kotlin + Jetpack Compose UI (no WebView).
- Receives shared text links from Android's Share menu and opens HTTP(S) links.
- Direct HTTP/HTTPS downloads using Android DownloadManager.
- System download notification; Android manages retries and resuming interrupted transfers where the server/device supports it.
- Live queue/status/progress/history view.
- Saves files under **Downloads/FastIDM**.
- Remove/cancel an active item from the list.

## Current limitations

This is a usable direct-file downloader, not yet a full desktop IDM/yt-dlp replacement.

- Site extraction for YouTube and other video platforms is **not implemented**.
- No yt-dlp runtime, FFmpeg merge, format/quality selector, segmented/multi-connection engine, or app-managed pause/resume controls yet.
- DownloadManager handles transfer lifecycle; behavior depends on server support (including HTTP range support) and Android's system download service.
- Some servers require cookies, authentication, or custom headers; this UI currently accepts a URL only.
- Test on a physical Android device before relying on it for important downloads.

## Build without Android Studio

The GitHub Actions workflow builds a debug APK on pushes to `android-starter` that change `android/**`, or on manual dispatch.

1. Open the repository's **Actions** tab.
2. Open the latest **Build Fast IDM Android APK** run.
3. Download the `Fast-IDM-debug` artifact.
4. Extract it and install `app-debug.apk` on an Android device. You may need to allow installation from that source.

## Project setup

- Package: `com.drsumon.fastidm`
- Minimum Android: API 26 (Android 8.0)
- Compile/target SDK: 35
- JDK: 17
- Gradle: 8.9
