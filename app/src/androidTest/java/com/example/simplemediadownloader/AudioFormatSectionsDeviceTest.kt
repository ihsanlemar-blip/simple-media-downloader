package com.example.simplemediadownloader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioFormatSectionsDeviceTest {
    @get:Rule val composeRule = createComposeRule()
    private fun catalog(url: String) = AudioFormatOptions.augment(MediaFormatCatalog(url, "Tone", emptyList(), listOf(
        AvailableFormat("native", DownloadMode.AUDIO_ORIGINAL, "https://example.com/audio", extension = "m4a", bitrateKbps = 128, estimatedSizeBytes = 5_100_000),
    ), durationSeconds = 300))

    @Test fun youtubeShowsAllSizesAndNativeAudioWith192Default() {
        val catalog = catalog("https://youtube.com/watch?v=example")
        composeRule.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AudioFormatSections(catalog, null, true, {}) } } }
        for (bitrate in PlatformAudioPolicy.MP3_BITRATES) composeRule.onNodeWithText("$bitrate kbps MP3").assertExists()
        for (size in listOf("~4.8 MB", "~7.2 MB", "~9.6 MB", "~12.0 MB")) composeRule.onNodeWithText(size).assertExists()
        composeRule.onNodeWithTag("native_audio_section").assertExists()
        composeRule.onNodeWithText("192 kbps MP3").assertIsSelected()
        assertTrue(composeRule.onNodeWithTag("mp3_expand").fetchSemanticsNode().boundsInRoot.top <
            composeRule.onNodeWithTag("native_audio_section").fetchSemanticsNode().boundsInRoot.top)
        composeRule.onNodeWithText("Recommended • Default").assertExists()
    }
    @Test fun socialExpandsAndRestoresWithoutChangingSelectedMp3AtRtlLargeFontNarrowWidth() {
        val catalog = catalog("https://www.facebook.com/watch?v=example")
        val selected = mutableStateOf(PlatformAudioPolicy.selectAudio(catalog)!!.key)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(1f,2f)) {
                MaterialTheme { Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    AudioFormatSections(catalog, selected.value, true, { selected.value = it.key })
                } }
            }
        }
        composeRule.onNodeWithText("Native Audio • Default").assertExists()
        composeRule.onNodeWithText("192 kbps MP3").assertDoesNotExist()
        composeRule.onNodeWithTag("mp3_expand").performScrollTo().performClick()
        composeRule.onNodeWithText("192 kbps MP3").performScrollTo().performClick()
        val mp3Key = selected.value
        composeRule.onNodeWithTag("mp3_expand").performScrollTo().performClick()
        composeRule.onNodeWithText("192 kbps MP3").assertDoesNotExist()
        assertEquals(mp3Key, selected.value)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("192 kbps MP3").assertDoesNotExist()
        composeRule.onNodeWithTag("mp3_expand").performScrollTo().performClick()
        for (size in listOf("~4.8 MB", "~7.2 MB", "~9.6 MB", "~12.0 MB")) composeRule.onNodeWithText(size).assertExists()
        assertEquals(mp3Key, selected.value)
    }
}
