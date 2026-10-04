# YouTube playlist batch support

Starting main: `3e61a0c63408826b328f5c9016066378750d3526`.
Prompt 1's Android CI and API 29/33/35 release-validation matrix were green before changes.

## Input and discovery

Typed, pasted and Android ACTION_SEND URLs all use `SourceUrlClassifier`. A valid,
single `list` query value on `/playlist`, `/watch` or a youtu.be link takes precedence
over a video ID. Invalid/empty/ambiguous playlist context stays a single-media link.
The adapter normalizes supported collection inputs to the canonical playlist URL
before invoking NewPipe. No social-profile discovery or HTML scraper was added.

`YouTubePlaylistExtractorAdapter` uses NewPipe `PlaylistInfo.getInfo/getMoreItems`
through the existing secured downloader. Metadata includes optional title, uploader,
thumbnail and count; missing optional values do not prevent discovery. The injected
`PlaylistSource` boundary exists for deterministic fixtures, not media transfer.

Pages are limited to 50 discovered items in the repository and 100 in the adapter.
A continuation retains upstream Page fields and the offset within a page, preserving
order when an upstream page is larger than the requested limit. Cursor size,
continuation URL and authenticated-cookie restrictions remain enforced. NewPipe
blocking extraction runs on IO in `runInterruptible`; Stop cancels the analysis job.
Each page is persisted before requesting the next. Cancellation retains completed
pages, selections and the last committed cursor, without enqueueing children.

## Preview, selection and queue

Preview shows playlist title/channel, found/total/selected counts, checkboxes, item
positions, available thumbnails and known durations. Users can discover the next 50
or analyze all remaining pages. Thumbnail networking uses SafeDns, URL/redirect
validation and no cookies. Unknown duration stays unknown.

Known private/removed/unavailable markers, invalid media URLs and NewPipe availability
restrictions become persisted reasons. Such rows remain visible, cannot be selected,
and are excluded from Select All, estimates and child creation. Unknown availability
still uses normal per-item discovery; a failed child leaves other children queueable.
NewPipe may omit entries upstream; the app can display only entries it returns.

Batch format choices remain best video/1080p/720p/480p, Native Audio, and MP3
128/192/256/320. Selecting Audio applies YouTube MP3 192 by default, or the saved user
bitrate. Native Audio remains separate. Existing capped video matching/downscaling,
MP3 conversion, duplicate detection, estimates and storage preflight are reused.
Review displays selected count and format alongside new/downloaded/queued counts and
the known total plus unknown-size count. No download starts before confirmation.

Optional playlist numbering uses the larger of discovered count, reported total and
current position, with at least two digits. Existing sanitization/collision handling
remain unchanged. Normal DownloadRequest children retain batchIndex and source item
identity. Existing transactional child insertion and durable admission gating prevent
partially prepared batches from starting early. No coroutine per playlist item or
second downloader exists.

## Persistence and recovery

Room schema 8 adds parent author/thumbnail/total_item_count and item unavailable_reason.
Migration 7→8 adds nullable columns and preserves existing batches, selection, cursors,
format choices and downloads. Existing parent controls, aggregate child progress,
COMPLETED_WITH_ERRORS, interruption recovery and completed-media retention remain in
charge. Recovery of created children requires no playlist rediscovery.

## Deterministic coverage

Eleven new unit tests cover metadata/optional fields, pagination across partial pages,
order, invalid/unavailable rows, restricted continuations, blocking-call cancellation,
typed/pasted/shared classification, numbering/durations, selection persistence,
cancel/resume analysis, failure isolation/recovery and migration 7→8. Existing tests
continue covering MP3/native children, capped video fallback, duplicate skips,
aggregate progress, batch controls and process recovery.

Four device tests cover analysis progress/Stop, typed input, clipboard input, and
ACTION_SEND watch+playlist routing. Activity tests inject fake collection discovery,
verify preview before child creation, MP3 192/Native Audio selection, unavailable-item
skipping and Select All/Deselect All. Normal CI never calls live YouTube.

Local build results and final GitHub CI/matrix links are reported in the completion
response. Version remains 2.6.0 / 260. No dependency, native encoder, dependency
verification metadata or workflow matrix changes. No GitHub Release or tag is created.

## Remaining manual verification

Live public playlists, YouTube app sharing on a physical device, upstream playlist
changes during analysis, very large playlists and Android background restrictions
still need manual verification. Private/authenticated collections are unsupported.
Pause retains the existing restart behavior, rather than byte-level resume.

## Local validation

- `:app:testDebugUnitTest`: PASS, 246 tests, zero failures/errors/skips.
- `:app:lintDebug` and `:app:lintRelease`: PASS, zero errors, 52 existing warnings each.
- `:app:assembleDebug`: PASS.
- `:app:assembleRelease`: PASS, minified R8 APK.
- `:app:bundleRelease`: PASS.
- `:app:assembleDebugAndroidTest`: PASS; emulator execution is verified on GitHub.
- Native packaging/source provenance verification: PASS, all four intended ABIs in APKs/AAB.

## Device test selector correction

The first emulator run passed the shared-playlist and analysis-stop tests, but the
two new Gateway routing tests looked for an unused “Choose Quality” label. The
existing button is “Formats”. The tests now use `R.string.action_explore_formats`,
preserving the real typed/pasted input and preview assertions. Final CI results
are checked for the corrected commit; production routing was unchanged.
