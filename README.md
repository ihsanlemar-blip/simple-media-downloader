# Simple Media Downloader

A deliberately small, single-screen Android application that downloads one publicly accessible media URL at a time. It uses the bundled `yt-dlp`, Python, QuickJS, and FFmpeg components supplied by youtubedl-android, so the user does not need Termux or any separately installed runtime.

Only download content that you own or are authorized to save. The app does not bypass DRM, authentication, private accounts, or paywalls.

## Architecture

The app has one compact path through the code:

```text
MainActivity / Compose UI
        ↓
MainViewModel
        ↓
MediaDownloader
        ↓
youtubedl-android (yt-dlp + QuickJS) / FFmpeg
```

- `SimpleMediaDownloaderApp` initializes `YoutubeDL` and `FFmpeg` once on an IO coroutine at application startup.
- `MainActivity` handles launcher intents plus `ACTION_SEND` / `text/plain` in both `onCreate` and `onNewIntent`.
- `MainViewModel` owns format discovery, the picker state, clipboard handling, download lifecycle, progress, cancellation, and the optional stable yt-dlp update action.
- `MediaDownloader` is the only class that inspects media or creates and executes yt-dlp requests.
- Downloads are written to `Downloads/MediaDownloader/` with yt-dlp's restricted filename handling.

## Build

Requirements:

- JDK 17
- Android SDK 35
- An internet connection for the first dependency resolution

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug check
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The build prioritizes `arm64-v8a` and also packages `x86_64` for a modern emulator. The minimum Android version is Android 10 (API 29); the target and compile SDK are 35.

## Dependencies

- Kotlin 2.1.20
- Android Gradle Plugin 8.11.1
- Jetpack Compose BOM 2025.04.01
- Material 3
- AndroidX Activity Compose 1.10.1
- AndroidX Lifecycle 2.9.0
- `io.github.junkfood02.youtubedl-android:library:0.18.1`
- `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`
- JUnit 4.13.2

Aria2c is intentionally not included.

## Supported functionality

- One URL per download
- An immediate common-quality picker after tapping **Download** or sharing a link; exact source formats and sizes replace the presets in the background
- A source-aware video quality ladder ordered from the highest native resolution down through 144p, with FPS, container, and estimated size
- Native resolutions are used directly; missing lower standard resolutions are created locally with FFmpeg. Resolutions above the source are never offered
- Every audio-only format reported by yt-dlp, ordered from highest to lowest bitrate, with estimated MP3 output size
- Separate video and audio streams paired by exact format ID and merged with FFmpeg when needed
- MP3 extraction using the bitrate of the selected audio representation (capped at 320K to avoid artificial upscaling)
- Live download progress, percentage, ETA when supplied by yt-dlp, and cancellation by unique process ID
- An ongoing Android notification mirrors the live percentage and ETA, then reports completion or failure
- Paste and clear controls
- Receive an HTTP/HTTPS URL through the Android share menu and automatically open its format picker; downloading starts only after the user selects a format
- Open or share a completed file through Android using a `FileProvider`
- Optional stable yt-dlp update from the overflow menu; update failure leaves the bundled engine usable
- Official EJS challenge scripts may be fetched from the `yt-dlp-ejs` GitHub release when needed; they run through the bundled QuickJS runtime

URLs are passed to yt-dlp's extractor system rather than accepted or rejected by hostname. Actual source support depends on the bundled or updated yt-dlp version, the public accessibility of the media, and upstream website changes.

## Known limitations

- Downloads stop if Android terminates the app process; version 1 intentionally has no foreground service or persistent queue.
- On Android 13 and newer, notification permission is requested when a format is selected. If permission is denied, the download still runs and progress remains visible inside the app.
- There is no playlist, bulk download, history database, login, cookie import, or account support.
- Some websites require authentication, cookies, a newer extractor, or JavaScript behavior that the bundled QuickJS/EJS setup cannot satisfy. YouTube is also rolling out per-video PO-token enforcement for some formats; the app does not collect or generate PO tokens. The extractor error is shown to the user.
- Format sizes are based on yt-dlp's exact or approximate metadata and may change slightly after stream merging or MP3 conversion. Some extractors do not report enough information to calculate a size.
- Locally created lower resolutions require video re-encoding, which is slower and uses more battery than downloading a native format.
- Quick presets are labeled **Up to** because they are available before source inspection completes. They never upscale a lower-resolution source.
- Output is requested as MP4 where practical with MKV as a merge fallback, but the selected source codecs and container determine the final result without unnecessary transcoding.
- Mode, resolution/bitrate, and format-ID suffixes keep different selections from consuming one another. Re-downloading the same URL and format follows yt-dlp's normal existing-file behavior.
- Google Play and individual websites may restrict downloader applications or downloading under their policies; distribution and usage compliance remain the distributor's and user's responsibility.

## Tests

Automated local unit tests cover:

- first URL extraction from shared text
- first URL extraction from clipboard-like text
- selected video/audio formats to exact yt-dlp format selectors
- discovered MP3 bitrate mapping and human-readable size labels
- resolution-ladder ordering, 144p generation, no-upscale behavior, and FFmpeg downscale arguments

Device test procedure:

1. Install the debug APK on an API 29+ `arm64-v8a` device or `x86_64` emulator.
2. Confirm the startup status says yt-dlp and FFmpeg initialized.
3. Copy ordinary text containing a public media URL, tap **Paste**, and confirm only the first URL is inserted.
4. Share a link from another Android app to **Simple Media Downloader**. Confirm the URL appears, the format picker opens automatically, and no download begins until a format is selected. Repeat while the activity is already open.
5. Download one authorized public YouTube video, one authorized public TikTok video, and one authorized public video from another yt-dlp-supported source. Do not put those test URLs in production source.
6. For video, confirm all reported formats are ordered highest to lowest and show sizes, then select at least two resolutions. Confirm progress, ETA when available, and the final playable file under `Downloads/MediaDownloader/`.
7. For audio, confirm all reported audio formats are ordered highest to lowest and show sizes, then select one. Confirm FFmpeg extraction and a playable `.mp3` file.
8. Start another download, tap **Cancel**, and confirm the native yt-dlp process stops and the UI reports **Cancelled**.
9. Enter an invalid or unsupported public URL and confirm a useful extractor error appears.
10. Open and share a completed file from the success card.

Network media tests must use content you are authorized to download and are therefore manual rather than hard-coded in the test suite.
