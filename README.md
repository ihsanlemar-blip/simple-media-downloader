# Simple Media Downloader

[![Latest Release](https://img.shields.io/github/v/release/ihsanlemar-blip/simple-media-downloader?style=for-the-badge)](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/latest)
[![Releases](https://img.shields.io/badge/GitHub-Releases-blue?style=for-the-badge&logo=github)](https://github.com/ihsanlemar-blip/simple-media-downloader/releases)

> 📦 **Downloads & Releases**:
> * **Last Published Release**: [v2.5.0 on GitHub Releases](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/tag/v2.5.0) ([SimpleMediaDownloader-v2.5.0-universal.apk](https://github.com/ihsanlemar-blip/simple-media-downloader/releases/download/v2.5.0/SimpleMediaDownloader-v2.5.0-universal.apk)).
> * **Current Source (`main`)**: Version 2.6.0 (versionCode 260) adds universal MP3 conversion, platform audio defaults, and reusable batch collection management, alongside the subsequent reliability, security, and verification fixes. This source version has not been published as a GitHub Release. To run the latest code, build from source following the [Building](#building) instructions below.

Simple Media Downloader is a flagship Kotlin and Jetpack Compose Android application for saving publicly accessible media from TikTok, Instagram Reels, Facebook, YouTube, X (Twitter), and Reddit with full metadata and original title preservation. Format extraction runs on-device using TeamNewPipe's NewPipeExtractor alongside specialized direct network scrapers. Media streams are downloaded with OkHttp (featuring RFC 7233 range-request validation, automatic continuous fallback, and destination security policies), and audio/video track merging and extraction are processed natively on-device using platform `android.media.MediaMuxer`, `MediaExtractor`, and `MediaCodec` APIs with bundled libmp3lame for real MP3 encoding and no external processing runtimes. In-app media preview playback is powered by AndroidX Media3 (ExoPlayer and UI).

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
- Android NDK 27.2.12479018 and CMake 3.22.1
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

The release build generates a universal APK supporting all Android architectures (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) with `libsmd_mp3.so` and dynamically linked `libmp3lame.so` packaged for every listed ABI. When distributing through app stores supporting Android App Bundles, the release App Bundle (`app-release.aab`) enables store-managed delivery.

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

## Audio outputs

Extraction uses NewPipe + platform adapters; networking uses OkHttp; muxing
uses Android MediaMuxer; MP3 conversion uses MediaExtractor + MediaCodec +
bundled libmp3lame; preview uses AndroidX Media3 ExoPlayer.

The Audio action uses MP3 on YouTube (192 kbps shipped default, configurable
128/192/256/320) and Native Audio on Facebook, Instagram, TikTok, X/Twitter,
Reddit, and unknown/future platforms. Native Audio remains available separately
on YouTube. Other platforms expose MP3 in an expandable Convert to MP3 section.
Expanding/collapsing never changes a selected output. Video defaults remain
controlled by the existing default-download preference.

MP3 uses CBR. Each estimate is `durationSeconds * bitrateKbps * 1000 / 8`
bytes, displayed approximately in decimal MB: 300 seconds yields ~4.8, ~7.2,
~9.6, and ~12.0 MB respectively. Unknown duration shows Size unavailable.
192 kbps balances compatibility, size, and quality; 320 kbps means maximum
bitrate and cannot recover information lost in AAC/Opus source audio.

The converter consumes a fully downloaded, validated private local file,
decodes only its audio track, streams bounded PCM into LAME, flushes into an
MP3 .part file, validates Layer III frames and audio/mpeg, and only then exports
to Music/MediaDownloader/. No WAV intermediate or video decoding is used.
One conversion runs at a time, independently of network download concurrency.
Native Audio is copied/demuxed without lossy encoding. Already-valid CBR MP3
at the requested bitrate is copied without re-encoding after content validation.

Supported PCM is signed 16-bit or clamped float, mono/stereo. 32/44.1/48 kHz
are preserved. 8/11.025/12/16/22.05/24/88.2/96 kHz inputs use LAME’s bundled
band-limited resampler to 44.1 or 48 kHz, allowing all four MPEG1 bitrates.
Other channel layouts/sample rates fail explicitly; no sample dropping is used.
Decoder availability depends on the Android device. Process-death recovery
restarts a task using the existing queue mechanism, rather than resuming a
partial encode. Source streams are selected from the highest quality audio-only
tier with M4A/AAC preference, then WebM/Opus and other audio; video is a fallback
only when standalone audio is absent. Existing security and HTTP range checks
still run before local conversion.

Room schema 6 persists source extension, source size, duration, and target
bitrate. Migration 5→6 preserves existing records and maps old AUDIO_MP3 source-only
rows to Native Audio, so queued legacy source downloads are not newly transcoded. Historical preference `MP3`
now maps to an explicit Convert to MP3 action on all platforms, restoring its
original intent. The existing `ORIGINAL_AUDIO` preference is now labelled
Audio (platform default): its YouTube action intentionally changes to MP3 under
the new product policy, while other platforms retain Native Audio. A value
previously rewritten to ORIGINAL_AUDIO cannot be distinguished from a deliberate
Audio selection, so it is not promoted to explicit MP3 conversion on every site.
Native Audio remains separately selectable; already queued tasks are unaffected.

LAME source/version, archive hash, license, and shared-library build details:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

### Batch collections

Batch download support organizes normal download tasks; it does not introduce another
network downloader. The flow is `CollectionExtractor → BatchRepository → DownloadRequest
→ DownloadRepository → DownloadService → DownloadEngine`. The existing native audio,
MP3, muxing, ranged HTTP, MediaStore and cancellation paths handle every child.

Type, paste or share a YouTube playlist link to open the collection preview. A valid
`list` parameter on a watch link opens the playlist rather than the single video;
remove the playlist context to download only that video. The preview shows available
playlist title/channel/count, item thumbnails, positions and known durations. Discover
50 items at a time or analyze the remaining playlist, with progress and a Stop button
that retains discovered pages, selections and the continuation. Select individual
items or all available discovered items, choose one
format, then review duplicates and the estimated total before confirming. The Batches
button opens saved previews and batch controls. Video choices are best available,
1080p, 720p and 480p; a lower available resolution is accepted, and an existing
video downscale path handles a source available only above the chosen cap. Audio
choices are Native Audio and MP3 128/192/256/320. Selecting Audio applies the same
platform defaults: YouTube MP3 with the configured bitrate (192 initially), other
platforms Native Audio.

YouTube playlist discovery uses `YouTubePlaylistExtractorAdapter`, NewPipe playlist
metadata/continuations and the existing secured networking adapter. Known private,
removed, paid, members-only, upcoming and invalid-URL entries are shown with a skip
reason and cannot be selected. Unknown availability is handled by the normal child
extractor; an individual failure does not stop other children. Thumbnail requests
use SafeDns, redirect validation and no cookies. Typed,
pasted and shared profile links use the same classifier and collection entry point.
Public profile discovery is described below; individual media URLs continue to use
their existing single-item extractors.
`CollectionExtractor` and `CollectionExtractorRegistry` provide the extension point
for profile and other collection adapters, without adding download engines.

Room schema 7 adds durable batch parents, paged discovery items, selection and
continuation cursors, and optional batch identity/order/source-item fields on normal
tasks. Schema 8 adds optional playlist author/thumbnail/total count and per-item
unavailability reasons; migration 7→8 preserves existing selection, cursors and
format intent. Migration 6→7 preserves existing task data and MP3 intent. Paused parents
exclude their queued children from admission; active work is cancelled, cleaned up,
and requeued for a full restart on resume. Recovery uses the existing interruption
logic and cannot admit paused or cancelled batches. Child counts derive from SQL
aggregates rather than persisted copies of progress. A preview interrupted during
child preparation retains its format/selection and can finish preparing safely.

Duplicate checks use canonical media URL plus the normal format key. Already queued
items are always skipped; completed items are skipped by default with an explicit
re-download option. MP3 estimates use duration × target bitrate. Other estimates use
normal catalog sizes, and unknown items remain separately counted. Storage preflight
allows twice the known output estimate plus 64 MiB for temporary data; unknown sizes
never automatically block confirmation. Actual per-item storage checks still apply.

Playlist filenames are numbered by default (`01 - Title.mp3`); profiles default to
unnumbered names. Numbering can be disabled, and uses sufficient zero padding for
the known playlist count (for example `001 - Title` in a 120-item playlist). Existing sanitization and collision handling remain in charge.
Batch controls pause, resume, cancel and retry failed children. Cancel keeps completed
media. Deleting batch history detaches retained children and keeps media and individual
history; individual cancel/retry/open/share/delete actions remain in Transfers/Vault.
Batch work uses the user's existing network concurrency setting (it never increases
it), one collection discovery operation, and the existing single MP3 conversion slot.

### Social profile latest-N rollout

Profile links open a count chooser before any crawling: Latest 10/20/50/100 or a
validated custom value from 1 to 100. The default is 20. Social profile batches
start with Native Audio; optional MP3 uses the same expandable picker and common
conversion engine as individual downloads. Discovery is serialized, paged, deduplicated
by post ID, cancellable and persisted in Room schema 9, including dates/page cursors.
Known dates retain newest-first order; unknown dates retain the public feed order.

TikTok, Instagram, Facebook, X/Twitter and Reddit have independent profile adapters.
TikTok reads public profile metadata and the public recent-post endpoint. If direct
access is limited, its existing third-party gateway opt-in can permit TikWM fallback
for a profile verified public. The chooser discloses this: username, count and cursor
are sent, never origin cookies. Private profiles never use that fallback. Normal
single-post extraction and its existing gateway policy remain unchanged.

Instagram uses its public web profile/timeline metadata endpoints, skips photo-only
posts, and returns canonical post URLs. Private profiles and sign-in requirements
produce a safe error; no gateway or user cookies are used for profile discovery.

Facebook reads bounded public timeline payloads and video attachments. Pagination
uses only an exposed first-party next-page URL; missing public continuations are
reported rather than synthesized. No login or private feed is bypassed.

X/Twitter reads the platform-owned public syndication timeline, filters out
photo-only/promoted/other-author entries and returns canonical status URLs. Only
exposed next-page URLs are followed. The feed may be empty or shorter than requested.
Discovery sends the username to Twitter, not a third party. Individual X downloads
still require the existing FxTwitter/Fixupx opt-in, which discovery never enables.

Reddit uses paginated public submissions sorted newest first, returns canonical
individual post URLs and filters for Reddit-hosted videos supported by the existing
single-post extractor. API access restrictions are reported without bypassing them.
All adapters use at most three attempts with exponential backoff, small jitter and
a capped Retry-After delay. Private/sign-in-required responses stop immediately.
Public endpoint contracts can change; reaching the requested N is not guaranteed.
