# Simple Media Downloader

[![Download Universal APK](https://img.shields.io/badge/Download-Universal%20APK-brightgreen?style=for-the-badge&logo=android)](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/download/v2.5.0/SimpleMediaDownloader-v2.5.0-universal.apk)
[![Latest Release](https://img.shields.io/github/v/release/ihsanlemar-blip/simple-media-downloader?style=for-the-badge)](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/latest)

> 🚀 **Direct Download**: Grab the latest ready-to-install signed universal APK from GitHub Releases:
> **[SimpleMediaDownloader-v2.5.0-universal.apk](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/download/v2.5.0/SimpleMediaDownloader-v2.5.0-universal.apk)** *(Supports `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)*

Simple Media Downloader is a flagship Kotlin and Jetpack Compose Android application for saving publicly accessible media from TikTok, Instagram Reels, Facebook, YouTube, X (Twitter), and Reddit with full metadata and original title preservation. Processing stays on the device through the embedded youtubedl-android stack: yt-dlp, Python, QuickJS, and FFmpeg. It does not use Cobalt or another remote media-processing backend.

The app does not bypass DRM, private accounts, authentication, paywalls, or website policy. Source support changes as websites and the bundled extractor change.

## Architecture

```text
MainActivity                         ShareDownloadActivity
single-screen Compose UI            compact ACTION_SEND text/plain window
        |                                      |
        +---------- ViewModels (commands and Flow UI state) ----------+
                                      |
                              DownloadRepository
                         /            |             \
             format discovery   Room queue/history   MediaStore export
                                      |
                              DownloadService
                       foreground queue owner (2 active)
                                      |
                  YtDlpDownloadEngine / lazy FFmpeg initialization
                                      |
                embedded yt-dlp + Python + QuickJS + FFmpeg processes
```

- `SimpleMediaDownloaderApp` constructs the dependency graph without a DI framework. yt-dlp initializes asynchronously; FFmpeg initializes only for merging or conversion.
- `MainViewModel` and `ShareDownloadViewModel` enqueue commands and observe repository `Flow`s. They do not own downloader processes.
- `DownloadRepository` coordinates discovery, durable state transitions, export, retry, deletion, duplicate detection, and process-death recovery.
- `DownloadService` owns queue admission, active yt-dlp/FFmpeg work, cancellation, and rate-limited notifications. The default concurrency is two downloads.
- `DownloadDatabase` stores durable tasks and history through Room. A running task recovered after process death is requeued instead of remaining incorrectly marked as downloading.
- `YtDlpProgressParser` is the single parser for structured and legacy yt-dlp progress. Unknown-duration processing is deliberately indeterminate.
- `DownloadsStorageExporter` writes into an app cache workspace first, then streams the completed file into MediaStore with `IS_PENDING` publication. Failed, cancelled, and abandoned pending exports are cleaned up.

## User experience

- Paste, type, or share a web URL while the embedded engine initializes.
- Use a saved default choice or choose Simple/Advanced formats.
- Common presets appear immediately; exact formats and estimated sizes replace them after discovery.
- See video/audio transfer stage, downloaded and total bytes when known, speed, ETA, merging, conversion, saving, completion, and categorized failures.
- Retry failed tasks, cancel one task, confirm Cancel All, open/share results, remove history, or explicitly delete saved media.
- A shared `text/plain` link opens only the centered share window. Enqueueing is one-shot, and the foreground service continues after that window closes.
- The interface follows system light/dark mode and Android 12+ dynamic color.

## Storage

yt-dlp and FFmpeg never write directly to a public filesystem path. Work files are created under the app cache and are removed after success, failure, or cancellation.

Completed files are streamed through `ContentResolver` into:

- video: `Movies/MediaDownloader/`
- audio: `Music/MediaDownloader/`
- unclassified output: `Downloads/MediaDownloader/`

History stores a `content://` URI, not a filesystem path. Open and Share use temporary read grants. If the user deletes a file outside the app, history detects the missing URI and presents a recoverable missing-output state.

## Foreground service and notifications

The foreground service starts only from a user-driven enqueue, cancel, refresh, or notification action and calls `startForeground` before database or engine work. Closing or dismissing `MainActivity` does not stop active downloads.

The service declares the `dataSync` foreground-service type for target SDK 35. Android 15 may time-limit this service type; the timeout callback cancels embedded processes, records affected tasks as interrupted, releases notifications and coroutines, and makes the tasks recoverable. Notification updates are limited to once per second per task and once per second for the queue summary.

Android 13+ notification permission is requested at the download action. If the user denies it, foreground-service disclosure still follows Android system behavior and task state remains visible in the app.

## Permissions and exported components

The manifest requests only:

- `INTERNET` — extractor metadata, media transfer, and the optional user-triggered yt-dlp/EJS network operations.
- `POST_NOTIFICATIONS` — progress and completion notifications on Android 13+.
- `FOREGROUND_SERVICE` — foreground queue execution.
- `FOREGROUND_SERVICE_DATA_SYNC` — the target-SDK-required data-transfer service type.

No storage, overlay, cookie, account, location, camera, or microphone permission is requested. App backup is disabled so Room history and source URLs are not copied into device/cloud backup.

`MainActivity` is exported only as the launcher. `ShareDownloadActivity` is exported for `ACTION_SEND` with exactly `text/plain`, validates the action, MIME type, length, scheme, and host, and is excluded from Recents. `DownloadService`, Room services, and AndroidX providers are not exported. The Profile Installer receiver supplied by AndroidX is permission-protected.

There is no custom trust manager or permissive network security configuration. Framework networking therefore uses the target-SDK platform defaults and system trust store. User-provided URLs may be HTTP or HTTPS for extractor compatibility; HTTPS sources are preferred.

## Privacy and production logging

Release builds do not write downloader exceptions, source URLs, local paths, or technical output to Logcat. Friendly failure categories are shown normally; bounded technical detail is stored locally with the task only so the user can explicitly expand and copy it. No API keys, cookies, credentials, or test media URLs are packaged by the application code.

## Build

Requirements:

- JDK 17
- Android SDK 35
- network access for the first Gradle dependency resolution

Windows commands:

```powershell
$env:ANDROID_HOME = 'C:\Users\you\AppData\Local\Android\Sdk'
.\gradlew.bat :app:test :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:bundleRelease
```

Outputs:

- installable debug APKs: `app/build/outputs/apk/debug/`
- minified release APKs: `app/build/outputs/apk/release/`
- release App Bundle: `app/build/outputs/bundle/release/app-release.aab`

APK builds contain separate `arm64-v8a` and `x86_64` artifacts. A universal APK is intentionally disabled. The App Bundle lets the store generate ABI-targeted delivery artifacts.

Release builds use `proguard-android-optimize.txt`, R8 minification, and resource shrinking. The youtubedl-android/FFmpeg Java/native bridge packages are explicitly retained because their AARs do not provide consumer rules. Room supplies its own `RoomDatabase` consumer rule, while Compose is statically linked and needs no broad keep rule. A Baseline Profile is not included because one has not been generated and validated from representative journeys on a physical device.

Production release APKs and the App Bundle are unsigned unless a distributor supplies a signing configuration. Do not distribute an APK signed with the debug key.

## Dependencies

- Kotlin 2.1.20 and Android Gradle Plugin 8.11.1
- Jetpack Compose BOM 2025.04.01 and Material 3
- AndroidX Activity Compose and Lifecycle
- Room 2.8.4 with KSP, coroutine, and Flow support
- `io.github.junkfood02.youtubedl-android:library:0.18.1`
- `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1`

FFmpeg, QuickJS, Python, and yt-dlp remain embedded. Aria2c and remote processing backends are intentionally not included. Fragment concurrency remains four.

## Known limitations

- Site support depends on yt-dlp extractors and upstream website behavior. Login-required, private, removed, DRM-protected, rate-limited, or PO-token-restricted media may not download.
- The optional EJS component may fetch official challenge scripts from the `yt-dlp-ejs` GitHub release and execute them with embedded QuickJS. This is extractor support, not remote media processing.
- Exact totals and ETA are unavailable for some protocols. The UI never fabricates a percentage when the total is untrusted.
- Merging and MP3 conversion require lazy FFmpeg initialization and additional temporary storage.
- Android 15 can impose a time budget on `dataSync` foreground services; interrupted tasks are requeued for recovery rather than silently reported as complete.
- Play Store and individual website policies may restrict downloader distribution or use. Compliance remains the distributor's and user's responsibility.

## Automated verification

Local tests cover queue scheduling and recovery, DAO transitions, retries and deletion, repository boundaries, cancellation isolation, format preferences/cache suppression, share parsing and one-shot handoff, filename/MIME/collision/storage rules, progress parsing, failure categorization, notification ID separation, and important Compose accessibility controls.

## Physical-device checklist

Use authorized public test media and test at least API 29, 33, 34, and 35:

1. Install the APK matching the device ABI and confirm immediate first composition while yt-dlp initializes asynchronously.
2. Verify normal paste/type input and browser `text/plain` sharing. Confirm only the compact share window opens and malformed, oversized, multiple, missing-MIME, and non-text shares are safe.
3. Deny and grant notification permission. Confirm user-driven enqueueing and foreground-service disclosure behave correctly in both cases.
4. Start two downloads, close the activity, cancel one from its notification, and confirm the other continues.
5. Force-stop or kill the process during download, reopen through a user action, and confirm recovery does not leave a task falsely marked downloading.
6. Exercise progressive video, separate video/audio merging, original audio, MP3 conversion, and a format with unknown total. Verify truthful progress, playable output, and lazy FFmpeg startup.
7. Cancel during transfer, merge, conversion, and MediaStore saving. Confirm no public partial row or cache workspace remains.
8. Open and Share both audio and video outputs. Confirm URI grants work without filesystem permission.
9. Delete output inside the app and outside the app. Confirm explicit deletion and missing-output history behavior.
10. Verify light/dark themes, dynamic color, TalkBack announcements, large font, tablet share-window width, predictive back, and touch targets.
11. Run a long Android 15 data-transfer session or controlled timeout test and verify interruption cleanup and retry.
12. Validate the final R8-signed production build on both `arm64-v8a` and `x86_64` before distribution.
