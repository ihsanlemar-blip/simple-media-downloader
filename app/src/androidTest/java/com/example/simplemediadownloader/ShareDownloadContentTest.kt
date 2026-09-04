package com.example.simplemediadownloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class ShareDownloadContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactShareContentShowsPlatformSelectionAndActions() {
        val format = AvailableFormat(
            key = "video-720",
            mode = DownloadMode.VIDEO,
            formatId = "22",
            extension = "mp4",
            height = 720,
            estimatedSizeBytes = 20L * 1024 * 1024,
            sizeIsApproximate = false,
        )
        val state = ShareDownloadUiState(
            sourceUrl = "https://youtu.be/example",
            displayUrl = "https://youtu.be/example",
            platform = "YouTube",
            catalog = MediaFormatCatalog(
                sourceUrl = "https://youtu.be/example",
                title = "Example",
                videoFormats = listOf(format),
                audioFormats = emptyList(),
            ),
            selectedFormatKey = format.key,
            enginePreparing = false,
            engineReady = true,
        )

        composeRule.setContent {
            MaterialTheme {
                ShareDownloadContent(state, {}, {}, {}, {}, {})
            }
        }

        composeRule.onNodeWithTag("share_download_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("share_platform").assertIsDisplayed()
        composeRule.onNodeWithTag("share_selected_size").assertIsDisplayed()
        composeRule.onNodeWithTag("share_download").assertIsDisplayed()
        composeRule.onNodeWithTag("share_cancel").assertIsDisplayed()
    }
}
