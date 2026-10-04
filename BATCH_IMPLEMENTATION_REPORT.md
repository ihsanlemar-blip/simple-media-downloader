# Reusable batch download implementation

Starting main commit: `c24769b3ca2b2ec4b2d43bf5d9a8c5c15d210273`.
No GitHub Release or tag is created by this work. Version remains 2.6.0 / 260.

## Architecture and persistence

`CollectionExtractor → BatchRepository → normal DownloadRequest → DownloadRepository
→ DownloadService → DownloadEngine`. No second download engine or batch encoder exists.
The existing HTTP/security/cookies, range integrity, retries, native audio, MP3,
muxing, MediaStore, R8 and native packaging paths remain shared.

Room schema 7 contains `download_batches` and `batch_items`. Migration 6→7 adds
nullable `batch_id`, `batch_index`, `source_item_id` and `filename_prefix` fields to
existing tasks without changing existing data, plus batch and order indexes.
A null batch identity continues to admit single downloads normally. Child identity,
format, original source and final-output intent persist through the existing task
entity/record/request round trip. Items retain order, selection, a child link or a
skip reason, and the parent retains its continuation, policy and control status.
Actual child counts are SQL aggregates exposed as a Flow; effective RUNNING,
COMPLETED and COMPLETED_WITH_ERRORS statuses derive from child state. Parent status
is the durable admission/control state, not a second copy of child progress.

Discovery and preparation operate in pages of 50 items. Public YouTube playlists
use NewPipe with the existing secured downloader. Continuations preserve an offset
within an upstream page, so a requested limit does not lose the rest of a page.
Tokens are bounded; authenticated continuation cookies are explicitly unsupported.
Selections default to empty and no media starts during preview. Deterministic child
IDs and persisted child links let an interrupted preparation finish without inserting
children again. Once any children are prepared, selection/format cannot change until
preparation finishes or the batch is cancelled, avoiding mutable queued intent.

## Behavior

- One batch format: best video, 1080p, 720p, 480p, Native Audio, or MP3 128/192/256/320.
- Video selects the highest available resolution at/below the cap. If only larger
  sources exist, the existing downscale path supplies the capped output. Stream
  refresh preserves explicit downscale intent; ordinary single matching is unchanged.
- Audio applies the existing platform policy: YouTube MP3 with the saved bitrate
  (192 initially), other platforms Native Audio. Every child uses the existing pipeline.
- Canonical URL plus normal format key detects duplicates. Already queued/running
  items are always skipped. Completed media is skipped by default only when it still
  exists; an explicit option permits re-download of completed media.
- Review shows new/already-downloaded/already-queued counts. MP3 sizes use the common
  duration × target bitrate helper. Native/video use catalog estimates; missing
  estimates are counted separately. Storage preflight warns based on twice known
  output size plus 64 MiB. Unknown size does not block confirmation.
- Playlist filenames are numbered by default, profiles are not. Sanitization and
  collision handling remain in the existing MediaStore exporter.
- Pause removes children from admission, cancels/joins active service jobs to complete
  cleanup, and keeps them queued for a full restart on resume. Cancel immediately records cancellation through the normal repository and keeps completed
  media. Retrying failed children in a paused parent preserves PAUSED until Resume. Retry failed reuses existing retry/reset logic and duplicate protection.
- Recovery reuses running→interrupted→queued logic, excludes paused/preview parents,
  and durably cancels outstanding children of cancelled parents before requeueing.
- Stale attempt results cannot overwrite a paused child's state or publish an obsolete
  output. Cancellation also covers setup/export when no HTTP call is registered.
- Deleting batch history detaches retained child history and never deletes saved media.
  Existing individual actions remain in Transfers and Vault. An explicit individual
  retry after batch cancellation reopens admission for that child; other cancelled
  children remain cancelled, and paused parents remain paused.
- Network concurrency remains the user's existing setting; batches do not raise it.
  Collection discovery/planning has one slot; MP3 retains its existing single slot.
- Batches button opens stored previews and controls. Typed, pasted and shared collection
  links use one classifier. ACTION_SEND still validates through ShareIntentParser first.

## Validation

Local validation commands and results are recorded below. GitHub validation is run
against the pushed source commit and linked in the final implementation response.

## Limitations and manual verification

Only a YouTube playlist discovery adapter is supplied in this first architecture
increment. Social-profile URL classification and the reusable extractor contract
exist, but TikTok/Instagram/Facebook/X/Reddit profile enumeration is explicitly
unavailable until adapters are added. No platform-specific download engine is needed
for those adapters. Live playlist extraction, private/authenticated collections,
very large collections, real device background restrictions, and user interaction
with a long-running paused batch still need manual verification. Normal CI uses fake
catalogs, generated/legal local media and programmatic PCM, never live YouTube.
Pause restarts an active transfer from its normal queued state after cleanup; it is
not a byte-resume feature. Estimates are warnings, not a reservation of storage.
Skipped existing downloads are item records with skip reasons rather than duplicate
child tasks. Completed media is inspected among the latest 20 matching records.

## Changed files

Added: `BatchModels.kt`, `BatchPersistence.kt`, `CollectionDiscovery.kt`,
`BatchRepository.kt`, `BatchViewModel.kt`, `BatchActivity.kt`, Room schema `7.json`,
`BatchRepositoryTest.kt`, `BatchPolicyTest.kt`, `WorkingFileLocatorTest.kt`,
`BatchDeviceTest.kt`, `CollectionShareRoutingDeviceTest.kt`, and this report.
Updated: Room database/migrations/task DAO/entity/history store, normal request/task/
record models, repository admission/recovery/cancellation, foreground service controls,
MediaStore filename prefix, application wiring, main/share routing, migration tests,
existing real MP3/native audio device tests, and README. No dependency, verification
metadata, native source, ABI, or workflow matrix changes.

## Local validation results

- `:app:testDebugUnitTest`: PASS, 235 tests, zero failures/errors/skips. Sixteen
  tests were added (ten repository/lifecycle, four policy, one migration, one export-marker regression).
- `:app:lintDebug`: PASS, zero errors, 52 warnings.
- `:app:lintRelease`: PASS, zero errors, 52 warnings.
- `:app:assembleDebug`: PASS.
- `:app:assembleRelease`: PASS, minified/R8 unsigned APK.
- `:app:bundleRelease`: PASS.
- `:app:assembleDebugAndroidTest`: PASS.
- Native APK/AAB packaging and unchanged LAME source hashes: PASS, all four ABIs.
- Local API 29 `connectedDebugAndroidTest`: PASS, 25 tests, before final control
  hardening. Final-source API 29/33/35 validation is checked on GitHub after push.
- Device coverage adds selection-preview and actual SQLite reopen tests, and extends
  the real encoder/engine/export tests with batch-linked MP3/native requests and
  numbered MP3 export. Normal CI continues using deterministic fixtures.

One local validation attempt was terminated by the workspace memory limit. The
fresh-process retry completed all build, unit and lint tasks successfully; logs are
preserved at `/workspace/validation/batch`. The existing Android CI and Android
Release Validation workflows, including API 29/33/35, remain unchanged. No GitHub
Release or tag is created; workflow artifacts are build/test outputs only.

## CI fixture correction

The first pushed validation passed both build jobs and API 35, but API 29/33 exposed
a nondeterministic fixture: its exporter call supplied a bare path instead of the
engine's `OUTPUT_MARKER` contract. The fixture left both input M4A and encoded MP3
in the workspace; identical millisecond modification times could select the input
through the legacy fallback. The correction supplies the engine marker and adds an
exact-timestamp regression test. Production file selection, source validation and
MP3 validation are unchanged. Final workflow results are verified for the corrected
commit and linked in the final response.

Native batch source selection is independent of the MP3 bitrate preference; a regression
test preserves the catalog's preferred native source when MP3 is set to 128 kbps.

Collection shares launch the batch screen in the normal app task before removing
its isolated share-dialog task. A device regression uses an unsupported profile
(discovery fails locally without network calls) to verify the preview survives
`finishAndRemoveTask`; single-media share behavior is unchanged.
