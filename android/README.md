# Fast IDM — Android Edition

Native Kotlin + Jetpack Compose companion app. The original Python desktop project remains untouched.

## Android build 0.3.1

### Direct file downloads
- Receives shared text links from Android's Share menu and opens HTTP(S) links.
- Direct HTTP/HTTPS downloads use Android DownloadManager.
- System download notification, queue, progress and completed history.
- Saves direct files to `Downloads/FastIDM`; Android's DownloadManager manages retries/resume where server support permits.

### Website video and audio downloads
- Uses yt-dlp Android (`io.github.junkfood02.youtubedl-android:library:0.18.1`) for supported sites.
- Bundled FFmpeg companion library is initialized to merge separate audio/video streams and extract MP3 audio.
- Quality presets: Best, 1080p, 720p, 480p, Audio MP3.
- Runs as an Android foreground data-sync service with an ongoing notification and Stop action.
- Publishes completed outputs into the public `Downloads/FastIDM` folder on Android 10+ via MediaStore.

## Important limitations
- Site support and available resolutions are controlled by yt-dlp extractors and the target service. A site may require cookies/login or block requests; quality presets are ceilings, not guarantees.
- The library's bundled yt-dlp version is updated through a dependency release; app does not currently expose an in-app yt-dlp updater.
- Android may stop long-running background work under system restrictions; foreground notification improves reliability but cannot guarantee completion in every vendor ROM/power-saving configuration.
- Current video service runs one yt-dlp task at a time. Direct DownloadManager tasks may run in parallel.
- No app-managed persistent database/queue for yt-dlp tasks yet; current video status is stored in app preferences.
- No browser cookie import, custom headers/proxy UI, playlist batch queue, subtitles, or aria2 multi-connection integration yet.
- Large native dependencies increase APK size. The yt-dlp Android wrapper bundles Python/yt-dlp/QuickJS; the FFmpeg companion bundles native FFmpeg.
- Verify functionality on a physical device. CI can compile the app but cannot verify website extraction or real device storage behavior.

## Build without Android Studio
1. Open the repository's **Actions** tab.
2. Open the latest **Build Fast IDM Android APK** run.
3. Download the `Fast-IDM-debug` artifact and extract it.
4. Install `app-debug.apk` on the phone. Allow installation from that source if Android asks.

## Project setup
- Package: `com.drsumon.fastidm`
- Minimum Android: API 26 (Android 8.0)
- ABI: arm64-v8a (optimized for modern ARM64 phones; 32-bit and x86 devices are not supported by this APK)
- Compile/target SDK: 35
- JDK: 17
- Gradle: 8.9


## Download history and playback

The Android app includes a full-screen download history with a visible Back action. Audio items open in the bundled Soul Player; video items open in the in-app Media3 player. The Windows `.exe` / Python desktop app remains unchanged.

APK build workflow: `.github/workflows/android.yml`.

APK build triggered after the playback/history integration updates.
