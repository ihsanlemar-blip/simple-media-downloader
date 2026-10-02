package com.example.simplemediadownloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VaultAdaptiveAndAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleTask = DownloadTask(
        id = "sample-1",
        url = "https://example.com/video",
        title = "Accessibility and Adaptive Sample Media",
        platform = "YouTube",
        format = AvailableFormat(
            key = "video:22",
            mode = DownloadMode.VIDEO,
            formatId = "22",
            extension = "mp4",
            height = 720,
        ),
        state = DownloadState.Completed(
            output = DownloadOutput(
                contentUri = "content://media/external/video/media/10",
                displayName = "sample.mp4",
                mimeType = "video/mp4",
                fileSizeBytes = 12_500_000L,
            ),
        ),
    )

    @Test
    fun vaultMultiSelectBatchToolbarDisplaysAndHandlesActions() {
        var allSelected = false
        var cleared = false

        val state = MainUiState(
            tasks = listOf(sampleTask),
            selectedVaultTaskIds = setOf("sample-1"),
            isMultiSelectActive = true,
        )

        composeRule.setContent {
            MaterialTheme {
                VaultScreen(
                    state = state,
                    onSearchQueryChange = {},
                    onSelectPlatform = {},
                    onToggleViewMode = {},
                    onSelectMediaType = {},
                    onToggleTaskSelection = {},
                    onSelectAllTasks = { allSelected = true },
                    onClearSelection = { cleared = true },
                    onDeleteSelectedTasks = {},
                    onPreviewMedia = {},
                    onShareMedia = {},
                    onDeleteTask = {},
                    onRemoveTask = {},
                    onRetryTask = {},
                    onCopyDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Select All").performClick()
        assertEquals(true, allSelected)
    }

    @Test
    fun vaultRendersGracefullyUnderRtlLayout() {
        val state = MainUiState(
            tasks = listOf(sampleTask),
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    VaultScreen(
                        state = state,
                        onSearchQueryChange = {},
                        onSelectPlatform = {},
                        onToggleViewMode = {},
                        onSelectMediaType = {},
                        onToggleTaskSelection = {},
                        onSelectAllTasks = {},
                        onClearSelection = {},
                        onDeleteSelectedTasks = {},
                        onPreviewMedia = {},
                        onShareMedia = {},
                        onDeleteTask = {},
                        onRemoveTask = {},
                        onRetryTask = {},
                        onCopyDetails = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithText("Videos").assertIsDisplayed()
        composeRule.onNodeWithText("Audio").assertIsDisplayed()
    }

    @Test
    fun vaultRendersGracefullyUnderLargeFontScale() {
        val state = MainUiState(
            tasks = listOf(sampleTask),
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2.0f)) {
                MaterialTheme {
                    VaultScreen(
                        state = state,
                        onSearchQueryChange = {},
                        onSelectPlatform = {},
                        onToggleViewMode = {},
                        onSelectMediaType = {},
                        onToggleTaskSelection = {},
                        onSelectAllTasks = {},
                        onClearSelection = {},
                        onDeleteSelectedTasks = {},
                        onPreviewMedia = {},
                        onShareMedia = {},
                        onDeleteTask = {},
                        onRemoveTask = {},
                        onRetryTask = {},
                        onCopyDetails = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithText("Accessibility and Adaptive Sample Media", substring = true).assertIsDisplayed()
    }
}
