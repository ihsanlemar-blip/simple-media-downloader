package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {
    private val progress = DownloadProgress(42f, 8L, "Working")

    @Test
    fun `all lifecycle phases remain active until a terminal state`() {
        val activeStates = listOf(
            DownloadState.Queued,
            DownloadState.Preparing(progress),
            DownloadState.Inspecting(progress),
            DownloadState.Downloading(progress),
            DownloadState.Merging(progress),
            DownloadState.Converting(progress),
            DownloadState.Saving(progress),
        )

        activeStates.forEach { state ->
            assertFalse(state.isTerminal)
            assertNull(state.result)
        }

        val output = DownloadOutput(
            contentUri = "content://downloads/saved.mp4",
            mimeType = "video/mp4",
            fileSizeBytes = 42L,
            displayName = "saved.mp4",
        )
        val terminalStates = listOf(
            DownloadState.Completed(output),
            DownloadState.Cancelled,
            DownloadState.Failed("failed"),
        )
        terminalStates.forEach { assertTrue(it.isTerminal) }
        assertEquals(100f, terminalStates.first().progress.percentage)
    }

    @Test
    fun `download task derives compatibility fields from its state`() {
        val format = AvailableFormat(
            key = "video:18",
            mode = DownloadMode.VIDEO,
            formatId = "18",
            extension = "mp4",
        )
        val queued = DownloadTask("id", "https://example.test", "Video", format)
        val completed = queued.copy(
            state = DownloadState.Completed(
                DownloadOutput(
                    "content://downloads/saved.mp4",
                    "video/mp4",
                    42L,
                    "saved.mp4",
                ),
            ),
        )

        assertTrue(queued.isActive)
        assertEquals("Queued...", queued.progress.status)
        assertFalse(completed.isActive)
        assertTrue(completed.result is DownloadResult.Success)
    }

    @Test
    fun `progress is determinate only when percentage and positive total are trustworthy`() {
        assertTrue(
            DownloadProgress(
                percentage = 40f,
                downloadedBytes = 400L,
                totalBytes = 1_000L,
            ).isDeterminate,
        )
        assertFalse(DownloadProgress(percentage = 40f, totalBytes = null).isDeterminate)
        assertFalse(DownloadProgress(percentage = null, totalBytes = 1_000L).isDeterminate)
        assertFalse(DownloadProgress(percentage = Float.NaN, totalBytes = 1_000L).isDeterminate)
    }
}
