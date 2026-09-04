package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpProgressParserTest {
    private val videoFormat = AvailableFormat(
        key = "video:22",
        mode = DownloadMode.VIDEO,
        formatId = "22",
        extension = "mp4",
        height = 720,
    )

    @Test
    fun `structured transfer reports trustworthy bytes speed eta and video stage`() {
        val update = YtDlpProgressParser(videoFormat).parse(
            line = "download:${YtDlpProgressParser.PROGRESS_MARKER}250|1000|NA|125|6|h264|aac",
            libraryProgress = 0f,
            libraryEtaSeconds = -1L,
        )

        assertTrue(update.state is DownloadState.Downloading)
        assertEquals(DownloadTransferKind.VIDEO, (update.state as DownloadState.Downloading).transferKind)
        assertEquals(250L, update.progress.downloadedBytes)
        assertEquals(1_000L, update.progress.totalBytes)
        assertEquals(125L, update.progress.speedBytesPerSecond)
        assertEquals(6L, update.progress.etaSeconds)
        assertEquals(25f, update.progress.percentage)
        assertTrue(update.progress.isDeterminate)
    }

    @Test
    fun `structured audio transfer with unknown total never invents a percentage`() {
        val audioFormat = videoFormat.copy(
            key = "audio:140",
            mode = DownloadMode.AUDIO_ORIGINAL,
            formatId = "140",
            extension = "m4a",
        )
        val update = YtDlpProgressParser(audioFormat).parse(
            line = "download:${YtDlpProgressParser.PROGRESS_MARKER}250|NA|NA|125|NA|none|aac",
            libraryProgress = 72f,
            libraryEtaSeconds = 4L,
        )

        assertEquals(DownloadTransferKind.AUDIO, (update.state as DownloadState.Downloading).transferKind)
        assertNull(update.progress.totalBytes)
        assertNull(update.progress.percentage)
        assertFalse(update.progress.isDeterminate)
    }

    @Test
    fun `legacy output is parsed only when its total makes percentage trustworthy`() {
        val parser = YtDlpProgressParser(videoFormat)
        val known = parser.parse(
            line = "[download] 25.0% of 8.00MiB at 2.00MiB/s ETA 00:03",
            libraryProgress = 25f,
            libraryEtaSeconds = 3L,
        )
        val unknown = parser.parse(
            line = "[download] 25.0% at 2.00MiB/s ETA 00:03",
            libraryProgress = 25f,
            libraryEtaSeconds = 3L,
        )

        assertEquals(8L * 1024 * 1024, known.progress.totalBytes)
        assertEquals(2L * 1024 * 1024, known.progress.speedBytesPerSecond)
        assertEquals(3L, known.progress.etaSeconds)
        assertEquals(25f, known.progress.percentage)
        assertTrue(known.progress.isDeterminate)
        assertNull(unknown.progress.percentage)
        assertFalse(unknown.progress.isDeterminate)
    }

    @Test
    fun `processing stages are distinct and always indeterminate`() {
        val parser = YtDlpProgressParser(videoFormat.copy(requiresDownscale = true))
        val merging = parser.parse("[Merger] Merging formats", 100f, 0L)
        val converting = parser.parse("[VideoConvertor] Converting video", 100f, 0L)
        val saving = parser.parse("[MoveFiles] Moving file", 100f, 0L)

        assertTrue(merging.state is DownloadState.Merging)
        assertTrue(converting.state is DownloadState.Converting)
        assertTrue(saving.state is DownloadState.Saving)
        listOf(merging, converting, saving).forEach {
            assertNull(it.progress.percentage)
            assertFalse(it.progress.isDeterminate)
        }
    }
}
