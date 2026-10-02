package com.example.simplemediadownloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

        composeRule.onNodeWithText("Example video").assertIsDisplayed()
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
        composeRule.onNodeWithText("Merge example").assertIsDisplayed()
        composeRule.onNodeWithText("Merging video and audio…").assertIsDisplayed()
    }

    private fun setTask(
        task: DownloadTask,
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                ActiveDownloadCard(
                    task = task,
                    onCancel = onCancel,
                )
            }
        }
    }
}
