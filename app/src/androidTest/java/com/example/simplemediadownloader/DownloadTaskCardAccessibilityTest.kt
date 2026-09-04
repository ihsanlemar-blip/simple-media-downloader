package com.example.simplemediadownloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DownloadTaskCardAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val format = AvailableFormat(
        key = "video:22",
        mode = DownloadMode.VIDEO,
        formatId = "22",
        extension = "mp4",
        height = 720,
    )

    @Test
    fun activeDownloadAnnouncesRealProgressAndExposesCancel() {
        var cancelled = 0
        val task = DownloadTask(
            id = "active",
            url = "https://youtu.be/example",
            title = "Example video",
            platform = "YouTube",
            format = format,
            state = DownloadState.Downloading(
                DownloadProgress(
                    percentage = 25f,
                    downloadedBytes = 5L * 1024 * 1024,
                    totalBytes = 20L * 1024 * 1024,
                    speedBytesPerSecond = 2L * 1024 * 1024,
                    etaSeconds = 8L,
                    status = "Downloading video…",
                ),
            ),
        )

        setTask(task, onCancel = { cancelled++ })

        composeRule.onNodeWithContentDescription("Download task for Example video")
            .assertIsDisplayed()
        composeRule.onNodeWithText("25.0%", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertHasClickAction().performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun unknownProcessingDurationIsIndeterminate() {
        val merging = DownloadTask(
            id = "merging",
            url = "https://example.test/video",
            title = "Merge example",
            platform = "Example",
            format = format,
            state = DownloadState.Merging(
                DownloadProgress(status = "Merging video and audio…"),
            ),
        )
        setTask(merging)
        composeRule.onNodeWithText("Merging video and audio…").assertIsDisplayed()
    }

    @Test
    fun failedTaskExposesFriendlyErrorDetailsAndRecoveryControls() {
        val failed = DownloadTask(
            id = "failed",
            url = "https://example.test/video",
            title = "Failed example",
            platform = "Example",
            format = format,
            state = DownloadState.Failed(
                message = DownloadFailureCategory.NETWORK_INTERRUPTED.userMessage,
                category = DownloadFailureCategory.NETWORK_INTERRUPTED,
                technicalDetail = "ERROR: connection reset",
            ),
        )
        setTask(failed)

        composeRule.onNodeWithText("Network interrupted").assertIsDisplayed()
        composeRule.onNodeWithText("Technical details").assertHasClickAction().performClick()
        composeRule.onNodeWithText("ERROR: connection reset").assertIsDisplayed()
        composeRule.onNodeWithText("Copy details").assertHasClickAction()
        composeRule.onNodeWithText("Retry").assertHasClickAction()
        composeRule.onNodeWithText("Remove history").assertHasClickAction()
    }

    private fun setTask(
        task: DownloadTask,
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                DownloadTaskCard(
                    task = task,
                    onCancel = onCancel,
                    onRetry = {},
                    onOpen = {},
                    onShare = {},
                    onRemove = {},
                    onDelete = {},
                    onCopyDetails = {},
                )
            }
        }
    }
}
