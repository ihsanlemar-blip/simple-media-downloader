package com.example.simplemediadownloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileSelectionDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun countChooserDefaultsTo20AndRejectsOversizedCustomWithoutStartingDiscovery() {
        var requested = 0
        compose.setContent { MaterialTheme { ProfileCountDialog(false, { requested = it }, {}) } }
        compose.onNodeWithText("Latest 20").assertIsSelected()
        assertEquals(0, requested)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithTag("profile_custom_count").performTextReplacement("101")
        compose.onNodeWithTag("profile_start").assertIsNotEnabled()
        compose.onNodeWithTag("profile_custom_count").performTextReplacement("50")
        compose.onNodeWithTag("profile_start").performClick()
        assertEquals(50, requested)
    }
    @Test fun nativeDefaultAndSharedExpandableMp3OptionsRemainSeparate() {
        val parent = BatchDownloadEntity("profile", "https://tiktok.com/@teacher", "TikTok", "SOCIAL_PROFILE", "Teacher", 20, 1, 0, 0, "READY", downloadMode = "AUDIO_ORIGINAL")
        var choice: BatchFormatChoice? = null
        compose.setContent { MaterialTheme {
            BatchScreen(BatchUiState(snapshot = BatchSnapshot(parent, BatchProgress(1, 0, 0, 0, 0, 0, 0))), {}, {}, { _, _ -> }, {}, {}, { choice = it }, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("batch_list").performScrollToNode(hasTestTag("mp3_expand"))
        compose.onNodeWithText("Convert to MP3").assertExists()
        compose.onNodeWithTag("mp3_expand").performClick()
        // The same picker is covered by AudioFormatSectionsDeviceTest; verify expansion itself does not change intent.
        assertNull(choice)
        compose.onNodeWithTag("mp3_expand").performClick()
        assertNull(choice)
    }
}
