package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressAggregationAndStoragePolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `parallel track progress updates produce strictly non-decreasing downloaded bytes`() = runBlocking {
        val emittedStates = CopyOnWriteArrayList<DownloadProgress>()
        val aggregator = TaskProgressAggregator(
            onState = { state ->
                if (state is DownloadState.Downloading) {
                    emittedStates.add(state.progress)
                }
            },
            transferKind = DownloadTransferKind.VIDEO,
        )

        // Simulate concurrent interleaved progress updates from video and audio streams
        val videoSteps = (1..100).map { it * 1000L }
        val audioSteps = (1..100).map { it * 200L }

        val videoJob = async(Dispatchers.Default) {
            for (bytes in videoSteps) {
                aggregator.updateVideo(
                    DownloadProgress(
                        downloadedBytes = bytes,
                        totalBytes = 100_000L,
                    ),
                )
            }
        }

        val audioJob = async(Dispatchers.Default) {
            for (bytes in audioSteps) {
                aggregator.updateAudio(
                    DownloadProgress(
                        downloadedBytes = bytes,
                        totalBytes = 20_000L,
                    ),
                )
            }
        }

        awaitAll(videoJob, audioJob)

        assertTrue("Expected progress updates", emittedStates.isNotEmpty())

        var previousBytes = 0L
        var previousPercent = 0f
        for (progress in emittedStates) {
            val currentBytes = progress.downloadedBytes ?: 0L
            assertTrue(
                "Downloaded bytes must be non-decreasing: current=$currentBytes, previous=$previousBytes",
                currentBytes >= previousBytes,
            )
            previousBytes = currentBytes

            if (progress.percentage != null) {
                assertTrue(
                    "Percentage must be non-decreasing: current=${progress.percentage}, previous=$previousPercent",
                    progress.percentage!! >= previousPercent,
                )
                previousPercent = progress.percentage!!
            }
        }

        val last = emittedStates.last()
        assertEquals(120_000L, last.downloadedBytes)
        assertEquals(120_000L, last.totalBytes)
        assertEquals(100f, last.percentage)
    }

    @Test
    fun `aggregator discards ETA when totals are unknown and avoids fluctuating between tracks`() {
        val emittedStates = mutableListOf<DownloadProgress>()
        var virtualTime = 1_000_000L
        val aggregator = TaskProgressAggregator(
            onState = { state ->
                if (state is DownloadState.Downloading) {
                    emittedStates.add(state.progress)
                }
            },
            transferKind = DownloadTransferKind.VIDEO,
            clock = { virtualTime },
        )

        // 1. Initial updates where totalBytes is unknown for one or both tracks
        aggregator.updateVideo(DownloadProgress(downloadedBytes = 10_000L, totalBytes = null))
        assertNull("ETA must be null when video total is unknown", emittedStates.last().etaSeconds)
        assertNull("Percentage must be null when total is unknown", emittedStates.last().percentage)
        assertEquals(10_000L, emittedStates.last().downloadedBytes)

        aggregator.updateAudio(DownloadProgress(downloadedBytes = 1_000L, totalBytes = 5_000L))
        assertNull("ETA must remain null while video total is still unknown", emittedStates.last().etaSeconds)
        assertNull("Percentage must remain null while combined total is incomplete", emittedStates.last().percentage)
        assertEquals(11_000L, emittedStates.last().downloadedBytes)

        // 2. Video total becomes known
        virtualTime += 1_000L // +1 second
        aggregator.updateVideo(DownloadProgress(downloadedBytes = 20_000L, totalBytes = 50_000L))
        val latest = emittedStates.last()
        assertEquals(21_000L, latest.downloadedBytes)
        assertEquals(55_000L, latest.totalBytes)
        assertNotNull("Percentage is computed once both totals are known", latest.percentage)
        assertNotNull("ETA is computed once combined total and speed are available", latest.etaSeconds)
    }

    @Test
    fun `sanitizeDisplayName with 200-character Arabic and Pashto strings containing emoji`() {
        // Arabic & Pashto text with complex Unicode, ZWNJ, and surrogate pair emojis
        val arabicPashtoWithEmoji = "د افغانستان د کرکټ ملي لوبډله او د فوټبال اتلان 🏏🏆🇦🇫 د بریا په لور ګامونه پورته کوي د سولې او ورورولۍ پيغام خپروي "
            .repeat(3) // > 200 characters

        assertTrue("Source string must have >= 200 chars", arabicPashtoWithEmoji.length >= 200)

        val sanitized = MediaExportPolicy.sanitizeDisplayName(arabicPashtoWithEmoji, "mp4")

        // 1. Total byte length in UTF-8 must be <= 255
        val utf8Bytes = sanitized.toByteArray(Charsets.UTF_8)
        assertTrue("UTF-8 byte length must be <= 255 but was ${utf8Bytes.size}", utf8Bytes.size <= 255)

        // 2. Must end with .mp4
        assertTrue("Must end with extension .mp4", sanitized.endsWith(".mp4"))

        // 3. Must be valid UTF-8 and have no dangling surrogate halves
        var index = 0
        while (index < sanitized.length) {
            val char = sanitized[index]
            if (char.isHighSurrogate()) {
                assertTrue("High surrogate must be followed by low surrogate", index + 1 < sanitized.length)
                assertTrue("Expected low surrogate", sanitized[index + 1].isLowSurrogate())
                index += 2
            } else {
                assertTrue("Unexpected orphaned low surrogate", !char.isLowSurrogate())
                index += 1
            }
        }

        // Verify decoding doesn't throw MalformedInputException
        val decoder = Charsets.UTF_8.newDecoder()
        val byteBuffer = java.nio.ByteBuffer.wrap(utf8Bytes)
        val charBuffer = decoder.decode(byteBuffer)
        assertEquals(sanitized, charBuffer.toString())

        // 4. Multilingual RTL text is preserved
        assertTrue("Sanitized name must preserve Pashto/Arabic characters", sanitized.contains("افغانستان"))
    }

    @Test
    fun `truncateUtf8Bytes handles exact boundaries and never splits surrogate pairs`() {
        // 4-byte emoji (surrogate pair: 2 UTF-16 Chars)
        val emojiString = "😀😁😂😃😄😅😆😇😈😉" // 10 emojis * 4 bytes = 40 UTF-8 bytes
        val truncatedTo15Bytes = MediaExportPolicy.truncateUtf8Bytes(emojiString, 15)
        // 15 bytes can fit at most 3 emojis (12 bytes), since the 4th would be 16 bytes
        assertEquals("😀😁😂", truncatedTo15Bytes)
        assertTrue(truncatedTo15Bytes.toByteArray(Charsets.UTF_8).size <= 15)
        assertEquals(12, truncatedTo15Bytes.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `prepareDestination enforces default reserve when estimatedSizeBytes is null`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspaceRoot = temporaryFolder.newFolder("workspaces-reserve")
        var availableStorage = 500L * 1024 * 1024 // 500 MB

        val exporter = DownloadsStorageExporter(
            context = context,
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            workspaceRoot = workspaceRoot,
            mediaStoreWriter = object : MediaStoreWriter {
                override suspend fun write(source: File, requestedDisplayName: String, mimeType: String, taskId: String, title: String?) =
                    DownloadOutput("content://media/1", mimeType, 1024L, requestedDisplayName)
                override suspend fun exists(contentUri: String) = true
            },
            availableBytes = { availableStorage },
        )

        val videoRequestWithoutEstimate = DownloadRequest(
            id = "task-video-null-est",
            url = "https://example.test/video",
            title = "Test Video",
            format = AvailableFormat(
                key = "video",
                mode = DownloadMode.VIDEO,
                formatId = "18",
                extension = "mp4",
                estimatedSizeBytes = null,
            ),
        )

        // 1. With 500 MB available, 250 MB default reserve passes
        val dest1 = exporter.prepareDestination(videoRequestWithoutEstimate)
        assertTrue(dest1.isSuccess)
        exporter.cleanup(dest1.getOrThrow())

        // 2. With only 150 MB available, 250 MB video reserve fails
        availableStorage = 150L * 1024 * 1024
        val dest2 = exporter.prepareDestination(videoRequestWithoutEstimate)
        assertTrue(dest2.isFailure)
        assertTrue(dest2.exceptionOrNull()!!.message!!.contains("Not enough available storage"))

        // 3. But an audio request needing 50 MB reserve (with 100 MB floor) succeeds with 150 MB
        val audioRequestWithoutEstimate = DownloadRequest(
            id = "task-audio-null-est",
            url = "https://example.test/audio",
            title = "Test Audio",
            format = AvailableFormat(
                key = "audio",
                mode = DownloadMode.AUDIO_MP3,
                formatId = "140",
                extension = "mp3",
                estimatedSizeBytes = null,
            ),
        )
        val dest3 = exporter.prepareDestination(audioRequestWithoutEstimate)
        assertTrue(dest3.isSuccess)
        exporter.cleanup(dest3.getOrThrow())

        // 4. With only 80 MB available, audio request fails due to 100 MB free disk floor
        availableStorage = 80L * 1024 * 1024
        val dest4 = exporter.prepareDestination(audioRequestWithoutEstimate)
        assertTrue(dest4.isFailure)
        assertTrue(dest4.exceptionOrNull()!!.message!!.contains("Not enough available storage"))
    }
}
