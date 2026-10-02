# Simple Media Downloader

[![Latest Release](https://img.shields.io/github/v/release/ihsanlemar-blip/simple-media-downloader?style=for-the-badge)](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/latest)
[![Releases](https://img.shields.io/badge/GitHub-Releases-blue?style=for-the-badge&logo=github)](https://github.com/ihsanlemar-blip/simple-media-downloader/releases)

> 📦 **Downloads & Releases**:
> * **Last Published Release**: [v2.5.0 on GitHub Releases](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/tag/v2.5.0) ([SimpleMediaDownloader-v2.5.0-universal.apk](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/download/v2.5.0/SimpleMediaDownloader-v2.5.0-universal.apk)).
> * **Current Source (`main`)**: Version 2.5.1 (versionCode 251) contains subsequent reliability, security, and verification fixes prepared for the next release. To run the latest code, build from source following the [Building](#building) instructions below.

Simple Media Downloader is a flagship Kotlin and Jetpack Compose Android application for saving publicly accessible media from TikTok, Instagram Reels, Facebook, YouTube, X (Twitter), and Reddit with full metadata and original title preservation. Format extraction runs on-device using TeamNewPipe's NewPipeExtractor alongside specialized direct network scrapers. Media streams are downloaded with OkHttp (featuring RFC 7233 range-request validation, automatic continuous fallback, and destination security policies), and audio/video track merging and extraction are processed natively on-device using platform `android.media.MediaMuxer`, `MediaExtractor`, and `MediaCodec` APIs without any external binaries or runtimes. In-app media preview playback is powered by AndroidX Media3 (ExoPlayer and UI).

The app does not bypass DRM, private accounts, authentication, paywalls, or website policy. Source support changes as websites and extractor definitions evolve.

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
      (NewPipe + Direct Scrapers)
                                      |
                               DownloadService
                        foreground queue owner (2 active)
                                      |
                   OkHttpDownloadEngine / MediaStreamMuxer (MediaMuxer)
                                      |
                 direct CDN streams + native container validation
```

- `SimpleMediaDownloaderApp` constructs the dependency graph without a DI framework. OkHttpClient is hardened with an RFC 6265 destination-scoped cookie jar, DNS rebinding/SSRF protection, and cleartext enforcement.
- `MainViewModel` and `ShareDownloadViewModel` enqueue commands and observe repository `Flow`s. They do not own downloader processes.
- `DownloadRepository` coordinates discovery, durable state transitions, export, retry, deletion, duplicate detection, and process-death recovery.
- `DownloadService` owns queue admission, active OkHttp/MediaStreamMuxer work, cancellation, and rate-limited notifications. The default concurrency is two downloads.
- `DownloadDatabase` stores durable tasks and history through Room. A running task recovered after process death is requeued instead of remaining incorrectly marked as downloading.
- `DownloadsStorageExporter` writes into dedicated app cache workspaces first, then streams the completed file into MediaStore with `IS_PENDING` publication. Failed, cancelled, and abandoned pending exports are cleaned up.

## User experience

- Paste, type, or share a web URL while the embedded engine initializes.
- Use a saved default choice or choose Simple/Advanced formats.
- Common presets appear immediately; exact formats and estimated sizes replace them after discovery.
- See video/audio transfer stage, downloaded and total bytes when known, speed, ETA, merging, saving, completion, and categorized failures.
- Retry failed tasks, cancel one task, confirm Cancel All, open/share results, remove history, or explicitly delete saved media.
- A shared `text/plain` link opens only the centered share window. Enqueueing is one-shot, and the foreground service continues after that window closes.
- The interface follows system light/dark mode and Android 12+ dynamic color.

## Storage

Media streams and temporary muxing files never write directly to a public filesystem path. Work files are isolated in private app cache workspaces and are safely removed after success, failure, or cancellation.

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

- `INTERNET` — extractor metadata, direct media transfer, and optional user-consented gateway fallbacks.
- `POST_NOTIFICATIONS` — progress and completion notifications on Android 13+.
- `FOREGROUND_SERVICE` — foreground queue execution.
- `FOREGROUND_SERVICE_DATA_SYNC` — the target-SDK-required data-transfer service type.

No storage, overlay, cookie, account, location, camera, or microphone permission is requested. App backup is disabled so Room history and source URLs are not copied into device/cloud backup.

`MainActivity` is exported only as the launcher. `ShareDownloadActivity` is exported for `ACTION_SEND` with exactly `text/plain`, validates the action, MIME type, length, scheme, and host, and is excluded from Recents. `DownloadService`, Room services, and AndroidX providers are not exported. The Profile Installer receiver supplied by AndroidX is permission-protected.

There is no custom trust manager or permissive network security configuration. Framework networking therefore uses the target-SDK platform defaults and system trust store. User-provided URLs may be HTTP or HTTPS for extractor compatibility; HTTPS sources are preferred.

## Privacy and External Service Policy

- **No Remote Processing by Default**: The application performs media stream extraction directly between the client device and the target platform using NewPipeExtractor and direct web scrapers.
- **Third-Party Fallback Gateways (Opt-In Only)**: If direct extraction fails (for instance, when a platform updates anti-scraping protections), the user may choose to enable external fallback gateways (such as Cobalt, TikWM, or FxTwitter) under **Settings → Privacy & External Services**. When enabled, the media URL or post ID is transmitted to the chosen service to discover stream URLs. This toggle is **disabled by default**.
- **Credential Isolation**: Session cookies and authorization tokens are strictly bound to their origin domain via an RFC 6265 compliant cookie jar and are never transmitted to third-party gateways or across domain boundaries.
- **SSRF and Rebinding Defenses**: All media requests and redirects are validated against a strict `NetworkSecurityPolicy` / `SafeDns` policy that blocks loopback, link-local, RFC 1918 private IPv4, IPv6 ULA, and cloud metadata endpoints.
- **Zero Diagnostic Leakage**: Release builds do not write downloader exceptions, source URLs, local paths, or technical output to Logcat. Technical failure detail is kept locally and accessible only when explicitly opened by the user.

## Build

Requirements:

- JDK 17
- Android SDK 35
- network access for the first Gradle dependency resolution

Linux / macOS commands:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

Windows commands:

```powershell
$env:ANDROID_HOME = 'C:\Users\you\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:bundleRelease
```

Outputs:

- installable debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- minified release APK (universal): `app/build/outputs/apk/release/app-release-unsigned.apk`
- release App Bundle: `app/build/outputs/bundle/release/app-release.aab`

The release build generates a universal APK supporting all Android architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) because format extraction, OkHttp downloading, and container muxing operate via pure Java/Kotlin and platform MediaCodec/MediaMuxer APIs without architecture-bound native `.so` libraries. When distributing through app stores supporting Android App Bundles, the release App Bundle (`app-release.aab`) enables store-managed delivery.

### Application ID & Upgrade Compatibility

The application ID is retained as `com.example.simplemediadownloader` to guarantee backward upgrade compatibility with existing distributed builds (such as `v2.5.0` on GitHub Releases). Modifying the application ID would break in-place updates on installed devices, orphaning existing Room database records, stored download queues, and preferences within the sandbox. A future migration to a new production application ID (such as `com.simplemediadownloader.app`) would require a deliberate new-app migration strategy or an export/import mechanism for Room database records and preferences.

Release builds use `proguard-android-optimize.txt`, R8 minification, and resource shrinking. Room supplies its own `RoomDatabase` consumer rule, while Compose is statically linked. A Baseline Profile is not included because one has not been generated and validated from representative journeys on a physical device.

### Production Signing & Release Readiness

Normal CI and local builds produce **unsigned release artifacts** (`app-release-unsigned.apk` and `app-release.aab`) to guarantee reproducible builds and avoid storing secrets in the repository. Keystores, passwords, and private keys must never be checked into version control.

To produce an officially signed production release:
1. Store the release keystore securely outside the repository (or in encrypted GitHub Actions secrets).
2. Supply the following environment variables during build execution:
   * `RELEASE_KEYSTORE_FILE`: Absolute path to the keystore file (`.jks` / `.keystore`).
   * `RELEASE_KEYSTORE_PASSWORD`: Keystore password.
   * `RELEASE_KEY_ALIAS`: Key alias.
   * `RELEASE_KEY_PASSWORD`: Key password.
3. When these variables are detected, Gradle automatically attaches the release signing configuration. If absent, builds remain safely unsigned. Do not distribute builds signed with the Android debug key.

### Release Validation Workflow

In addition to standard CI (which runs unit tests, debug & release lint, debug/release assembly, and API 29 minimum-SDK emulator tests on every push/PR), a dedicated release validation workflow is configured in `.github/workflows/android-release-validation.yml`.

Triggered via `workflow_dispatch` or version tags (`v*`), it runs:
* Comprehensive unit and Room migration tests
* Android Lint on both Debug and Release variants (`:app:lintDebug` and `:app:lintRelease`)
* Minified R8 release APK and release App Bundle generation
* Multi-version Android emulator matrix testing across **API 29** (Android 10), **API 33** (Android 13), and **API 35** (Android 15)

## Dependencies

- Kotlin 2.1.20 and Android Gradle Plugin 8.11.1
- Jetpack Compose BOM 2025.04.01 and Material 3
- AndroidX Activity Compose and Lifecycle 2.9.0
- Room 2.8.4 with KSP, coroutine, and Flow support
- `com.github.TeamNewPipe:NewPipeExtractor:v0.26.5`
- `com.squareup.okhttp3:okhttp:4.12.0`
- `androidx.media3:media3-exoplayer:1.5.1` and `androidx.media3:media3-ui:1.5.1` (preview playback)
- Native Android platform `android.media.MediaMuxer`, `MediaExtractor`, and `MediaCodec` APIs for container muxing and audio extraction

Native platform `android.media.MediaMuxer` handles track muxing and audio extraction on-device, and AndroidX Media3 ExoPlayer provides lightweight in-app media preview playback. External Python/QuickJS/yt-dlp runtimes are not used.

## Known limitations

- Site support depends on platform web changes and NewPipe extractor updates. Login-required, private, removed, DRM-protected, or rate-limited media cannot be downloaded.
- Audio and video stream muxing requires local processing via platform `android.media.MediaMuxer`.
- Android 15 can impose a time budget on `dataSync` foreground services; interrupted tasks are requeued for recovery rather than silently reported as complete.
- Play Store and individual website policies may restrict downloader distribution or use. Compliance remains the distributor's and user's responsibility.

## Automated verification

Local tests cover queue scheduling and recovery, DAO transitions, retries and deletion, repository boundaries, cancellation isolation, format preferences/cache suppression, share parsing and one-shot handoff, filename/MIME/collision/storage rules, progress parsing, failure categorization, notification ID separation, and important Compose accessibility controls.

## Physical-device checklist

Use authorized public test media and test at least API 29, 33, 34, and 35:

1. Install the universal APK and confirm immediate first composition while NewPipe and extraction engines initialize asynchronously.
2. Verify normal paste/type input and browser `text/plain` sharing. Confirm only the compact share window opens and malformed, oversized, multiple, missing-MIME, and non-text shares are safe.
3. Deny and grant notification permission. Confirm user-driven enqueueing and foreground-service disclosure behave correctly in both cases.
4. Start two downloads, close the activity, cancel one from its notification, and confirm the other continues.
5. Force-stop or kill the process during download, reopen through a user action, and confirm recovery does not leave a task falsely marked downloading.
6. Exercise progressive video, separate video/audio merging, authentic audio streams, extracted audio demuxing, and a format with unknown total. Verify truthful progress, playable output, and native MediaStreamMuxer processing.
7. Cancel during transfer, merge, audio extraction, and MediaStore saving. Confirm no public partial row or cache workspace remains.
8. Open and Share both audio and video outputs. Confirm URI grants work without filesystem permission.
9. Delete output inside the app and outside the app. Confirm explicit deletion and missing-output history behavior.
10. Verify light/dark themes, dynamic color, TalkBack announcements, large font, tablet share-window width, predictive back, and touch targets.
11. Run a long Android 15 data-transfer session or controlled timeout test and verify interruption cleanup and retry.
12. Validate the release build across target devices (e.g. `arm64-v8a` and `x86_64`) before distribution.
