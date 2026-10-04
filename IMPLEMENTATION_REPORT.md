# Universal MP3 implementation report

Starting main commit: `4ea3400cdc4543f84ec1a198ea1f04f64f7d2c34`.
This report records validation completed before pushing the implementation; no GitHub Release was published.

The starting commit's Android CI and Android Release Validation runs were successful:
- https://github.com/ihsanlemar-blip/simple-media-downloader/actions/runs/37029822262
- https://github.com/ihsanlemar-blip/simple-media-downloader/actions/runs/37029826782

| Requested item | Implementation / evidence |
| --- | --- |
| 1. Starting SHA | `4ea3400cdc4543f84ec1a198ea1f04f64f7d2c34`, fetched from current main before editing. |
| 2. Files added | See inventory below; the complete unchanged LAME source tree is enumerated by its SHA-256 manifest. |
| 3. Files modified | See inventory below. |
| 4. Platform defaults | Central `PlatformAudioPolicy`: YouTube MP3; Facebook, Instagram, TikTok, X/Twitter, Reddit and unknown/future platforms Native Audio. Unit-tested. |
| 5. YouTube MP3 default | 192 kbps; user preference supports 128/192/256/320 and affects newly created requests only. |
| 6. Other-platform defaults | Native Audio; separate optional MP3 conversion section. |
| 7. Architecture | Extractors discover sources → existing OkHttp full private download/size validation → shared `Mp3AudioTranscoder` → MediaExtractor audio track → MediaCodec PCM decoder → JNI/libmp3lame CBR → strict validation → MediaStore. No video decoding, WAV intermediate, or network piping into LAME. |
| 8. Exact LAME version/revision | Exact upstream 3.100 release archive; no moving Git revision. |
| 9. Provenance/license | SourceForge `lame/3.100/lame-3.100.tar.gz`; archive SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`. Complete upstream source unchanged. `COPYING` is GNU Library GPL v2 (June 1991); library headers allow v2 or later. `LICENSE`, notices, and packaged license assets retained. Separate shared LAME library supports replacement/relinking; distributor obligations documented without blanket legal certification. |
| 10. ABIs | arm64-v8a, armeabi-v7a, x86, x86_64; NDK 27.2.12479018 and CMake 3.22.1 pinned. |
| 11. Source selection | Audio-only first, highest available quality tier (at least 80% of maximum advertised bitrate), AAC/M4A preference, then WebM/Opus, other audio; smallest source within the tier. Progressive video containing audio only when standalone audio is absent. |
| 12. Native Audio | Original source copy or existing lossless MediaMuxer audio demux; no LAME transcode. Native/source MP3 is distinct from a conversion request. |
| 13. MP3 bitrates | Exactly 128, 192, 256, 320 kbps CBR; 320 is “Maximum MP3 bitrate”, never “Best Quality”. |
| 14. Size formula | `durationSeconds * bitrateKbps * 1000 / 8`; overflow/unknown/non-positive duration returns unavailable. Decimal MB display is approximate and never based on source size. |
| 15. 300-second sizes | 128: 4,800,000 bytes/~4.8 MB; 192: 7,200,000/~7.2 MB; 256: 9,600,000/~9.6 MB; 320: 12,000,000/~12.0 MB. |
| 16. UI expansion | YouTube MP3 first, configured default first (shipped 192), native separate. Other platforms native first; MP3 initially collapsed. `rememberSaveable` persists expansion; collapse retains selected MP3. RTL/200% font/narrow layout/restoration tested. |
| 17. PCM | Streaming reusable signed 16-bit buffers; float PCM clamped safely (including NaN/infinity). Other PCM encodings fail explicitly. |
| 18. Sample rates | 32/44.1/48 kHz preserved; 8/11.025/12/16/22.05/24/88.2 kHz → 44.1 kHz; 96 kHz → 48 kHz through LAME's built-in band-limited resampler. Other rates fail explicitly. |
| 19. Channels | Mono/stereo; >2 channels fail with unsupported-layout error, no guessed downmix. |
| 20. Direct MP3 | Structural and extractor validation plus all-frame CBR target match → buffered lossless copy; other bitrates/VBR re-encode. Fixture test checks byte equality for genuine 192 kbps source. |
| 21. Validation | All complete MPEG Layer III frames, legal layer/version/rate/bitrate fields and complete file bounds; MediaExtractor requires `audio/mpeg`. ID3 tags alone, AAC ADTS, renamed M4A/WebM fail. Device tests also decode resulting MP3 PCM and check duration. |
| 22. Progress | Explicit source-download 100% after full transfer; conversion starts at 0, uses decoded PTS/duration (indeterminate if unknown), reaches 100 only after flush/validation/finalization; repository saves before Completed. Conversion is separate from muxing. |
| 23. Cancellation | Coroutine and explicit task checks during waiting/setup/decoding/PCM/encoding/flush/validation/copy. Decoder/extractor/native encoder released; engine returns Cancelled; no export before conversion success. MediaStore writer removes pending row on failure/cancellation. |
| 24. Cleanup | Converter deletes `.part` and failed output; engine deletes raw source in finally; repository cleans workspace; subsequent encode succeeds after cancellation. Native IDs safely close twice. |
| 25. MediaStore | `.mp3`, `audio/mpeg`, `Music/MediaDownloader/`; existing sanitization/collision naming retained. Device test verifies real exported metadata, IS_PENDING=0 and saved-file decoding, then removes the test entry. |
| 26. Unit tests | Platform policy/options/source selection/size; PCM/header/sample-rate rules; preference/bitrate persistence; task source/target round-trip; Room 5→6/full migration; source refresh/quality selection; strict fake-MP3 rejection. Normal CI live TikTok test replaced by deterministic HTML/interceptor fixture. |
| 27. Instrumentation | New `Mp3ConversionDeviceTest` (7 tests) and `AudioFormatSectionsDeviceTest` (2), alongside 14 existing tests. Generated legal PCM and local AAC/M4A/Opus/MP4/MP3 fixtures, no live YouTube dependency. |
| 28. testDebugUnitTest | PASS on final 2.6.0/260: 219 tests, 0 failures/errors/skips. |
| 29. lintDebug | PASS: 0 errors, 52 warnings. |
| 30. lintRelease | PASS: 0 errors, 52 warnings. |
| 31. assembleDebug | PASS on final 2.6.0/260. |
| 32. assembleRelease | PASS with R8; unsigned APK because no production signing key was supplied. |
| 33. bundleRelease | PASS on final 2.6.0/260. |
| 34. API 29 | PASS on final 2.6.0/260: 23 tests, 0 failures/errors/skips, via connectedDebugAndroidTest. |
| 35. API 33 | PASS: 23 tests, 0 failures/errors/skips, via connectedDebugAndroidTest. |
| 36. API 35 | PASS: 23 tests, 0 failures/errors/skips, via connectedDebugAndroidTest; see environment/retry details below. |
| 37. Native packaging | PASS: both libsmd_mp3.so and libmp3lame.so present for all 4 ABIs in debug APK, release APK, and AAB. Source hash manifest validated. ELF LOAD alignment verified at 16 KiB for both libraries/all ABIs. Minified release DEX retains bridge class plus all four exact JNI method names/signatures. |
| 38. Version | versionName 2.6.0; versionCode 260. README still distinguishes published v2.5.0 from current source. |
| 39. Physical verification remaining | ARM64/ARMv7/x86 runtime execution, physical-device codec/audio playback and timing, 16 KiB-page hardware, live authorized YouTube/social-media matrix (including very long media, download/conversion/save cancellation), real rotation/process death and recovery. These have not been claimed as passed. |
| 40. Known limitations | Only signed 16-bit/float PCM and mono/stereo; unsupported rates/layouts fail explicitly. Decoder availability depends on device. Duration missing from discovery yields Size unavailable for every MP3 option. Strict MP3 validation rejects malformed/free-format or unsupported trailing structures. Recovery restarts conversion; partial encoding is not resumed. Live extractors depend on website availability. Emulator runtime tests use x86_64; other ABIs were built/packaged rather than executed on physical hardware. |
| 41. No release | No GitHub Release or tag was created. The user separately authorized committing and pushing the source changes. |

## Validation evidence and environment

Final builds ran serially with JDK 17, Android SDK 35, NDK 27.2.12479018,
CMake 3.22.1, Gradle SHA-256 verification enabled, and no additional Maven dependencies.
`gradle/verification-metadata.xml` is unchanged. All requested build/lint tasks
and `assembleDebugAndroidTest` succeeded in the final combined run.
Reports are saved outside the repository at `/workspace/validation/build260` and
`/workspace/validation/api29`, `api33`, `api35`. These results were obtained locally before pushing. The equivalent local tasks
were executed; GitHub workflow results must be checked for the pushed commit.

This workspace has two CPU cores and no `/dev/kvm`; emulators used software TCG.
API 35's first two attempts failed before test discovery with Android startup ANRs.
The ANR main thread was in ART's DexFileVerifier, before application or MP3 code;
System UI, phone and media-provider processes showed the same startup failures.
Increasing guest RAM alone did not resolve it. The successful retry used 2 GB RAM,
disabled unused Bluetooth, and Android's ordinary ahead-of-time compilation
(`cmd package compile -m speed -f` for system packages; `pm.dexopt.install*` set to
`speed` for APK installation). Verification and test assertions remained enabled.
All 23 tests subsequently passed through the original Gradle task. Failed attempt
reports are preserved separately in `/workspace/validation/api35-first-attempt`
and `/workspace/validation/api35-second-attempt`.

For a normal local/CI rerun, use KVM acceleration (the existing release emulator
workflow already enables it), finish emulator boot, and run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:bundleRelease
./gradlew :app:connectedDebugAndroidTest
python3 scripts/verify_native_packaging.py \
  app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk \
  app/build/outputs/bundle/release/*.aab
```

If startup ANRs recur in software-only emulation, use a KVM host. AOT compilation
can move verification work out of the startup deadline, but does not replace
physical-device performance testing.

## Preference and Room decisions

Room 5→6 interprets old AUDIO_MP3 rows as source-only native MP3 requests, which
was their historical behavior. They remain native and are not newly encoded.
Historical stored preference `MP3` becomes the explicit MP3 action. The existing
ORIGINAL_AUDIO preference is now the Audio (platform default) action: YouTube
intentionally follows the new MP3 default; other sites stay native. Already
rewritten ORIGINAL_AUDIO values are not guessed into explicit MP3 on every site.
This policy is documented in README; future preference changes do not alter
queued source/target/bitrate fields.

## Artifacts

| Final artifact | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `403429749700acc1977ac80c805eccaa6b12954a7f2c68a9b4f3ec5a70c12b40` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `5b15bf079a680a640ee4ae94dcebb778b1879dc0e15af72db592721fcf5a4a9f` |
| `app/build/outputs/bundle/release/app-release.aab` | `2e652bfaa520ab70096ad678ac5be2af7a42acd0bb09c5514ca0342135650352` |

## File inventory

Added files (plus this report):

- `THIRD_PARTY_NOTICES.md`
- `app/schemas/com.example.simplemediadownloader.DownloadDatabase/6.json`
- `app/src/androidTest/assets/audio/README.txt`
- `app/src/androidTest/assets/audio/tone.aac`
- `app/src/androidTest/assets/audio/tone.m4a`
- `app/src/androidTest/assets/audio/tone.mp3`
- `app/src/androidTest/assets/audio/tone.mp4`
- `app/src/androidTest/assets/audio/tone.webm`
- `app/src/androidTest/java/com/example/simplemediadownloader/AudioFormatSectionsDeviceTest.kt`
- `app/src/androidTest/java/com/example/simplemediadownloader/Mp3ConversionDeviceTest.kt`
- `app/src/main/assets/licenses/lame/COPYING`
- `app/src/main/assets/licenses/lame/LICENSE`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/config.h`
- `app/src/main/cpp/mp3_encoder_jni.cpp`
- `app/src/main/cpp/third_party/lame-3.100.sha256`
- `app/src/main/java/com/example/simplemediadownloader/AudioFormatSections.kt`
- `app/src/main/java/com/example/simplemediadownloader/Mp3AudioTranscoder.kt`
- `app/src/main/java/com/example/simplemediadownloader/Mp3EncoderBridge.kt`
- `app/src/main/java/com/example/simplemediadownloader/Mp3Validation.kt`
- `app/src/main/java/com/example/simplemediadownloader/PlatformAudioPolicy.kt`
- `app/src/test/java/com/example/simplemediadownloader/Mp3FramesAndPcmTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/PlatformAudioPolicyTest.kt`
- `scripts/verify_native_packaging.py`
- `app/src/main/cpp/third_party/lame/**`: 326 unchanged upstream files; exact per-file inventory and hashes in `app/src/main/cpp/third_party/lame-3.100.sha256`.

Modified files:

- `.github/workflows/android-release-validation.yml`
- `.github/workflows/ci.yml`
- `.gitignore`
- `README.md`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/main/java/com/example/simplemediadownloader/DownloadDatabase.kt`
- `app/src/main/java/com/example/simplemediadownloader/DownloadEngine.kt`
- `app/src/main/java/com/example/simplemediadownloader/DownloadModels.kt`
- `app/src/main/java/com/example/simplemediadownloader/DownloadOptions.kt`
- `app/src/main/java/com/example/simplemediadownloader/DownloadPreferences.kt`
- `app/src/main/java/com/example/simplemediadownloader/DownloadRepository.kt`
- `app/src/main/java/com/example/simplemediadownloader/DownloadTaskEntity.kt`
- `app/src/main/java/com/example/simplemediadownloader/FormatDiscoveryEngine.kt`
- `app/src/main/java/com/example/simplemediadownloader/FormatPickerBottomSheet.kt`
- `app/src/main/java/com/example/simplemediadownloader/InstagramExtractor.kt`
- `app/src/main/java/com/example/simplemediadownloader/MainActivity.kt`
- `app/src/main/java/com/example/simplemediadownloader/MainViewModel.kt`
- `app/src/main/java/com/example/simplemediadownloader/RedditExtractor.kt`
- `app/src/main/java/com/example/simplemediadownloader/SettingsScreen.kt`
- `app/src/main/java/com/example/simplemediadownloader/ShareDownloadActivity.kt`
- `app/src/main/java/com/example/simplemediadownloader/ShareDownloadViewModel.kt`
- `app/src/main/java/com/example/simplemediadownloader/StorageExporter.kt`
- `app/src/main/java/com/example/simplemediadownloader/TikTokExtractor.kt`
- `app/src/test/java/com/example/simplemediadownloader/ApplicationVersionAndIdentityTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/DownloadDatabaseMigrationTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/DownloadEngineCancellationTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/DownloadEngineValidationTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/DownloadPreferencesTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/DownloadRepositoryTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/DownloadTaskDaoTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/QualityMatchingAndCacheRefreshTest.kt`
- `app/src/test/java/com/example/simplemediadownloader/SocialMediaMultiFormatAndAudioSizingTest.kt`
