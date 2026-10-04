package com.example.simplemediadownloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Exercises real activities with fake collection discovery; no live YouTube requests. */
class YouTubePlaylistRoutingDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as SimpleMediaDownloaderApp
    private lateinit var original: BatchRepository
    private var oldBitrate = 192
    private lateinit var source: String
    private var scenario: ActivityScenario<*>? = null

    @Before fun setup() {
        source = "https://www.youtube.com/watch?v=fixture&list=PLfixture_${System.nanoTime()}"
        original = app.batchRepository
        oldBitrate = app.downloadPreferenceStore.youtubeMp3BitrateKbps.value
        runBlocking { app.downloadPreferenceStore.setYoutubeMp3BitrateKbps(192) }
        val extractor = object : CollectionExtractor {
            override suspend fun canHandle(url: String) = SourceUrlClassifier.classify(url) == SourceUrlType.YOUTUBE_PLAYLIST
            override suspend fun getInfo(url: String) = CollectionInfo(url, "YouTube", CollectionType.YOUTUBE_PLAYLIST, "Fixture Course", 3, "Fixture Teacher")
            override suspend fun getItems(url: String, limit: Int?, continuation: String?) = CollectionPage(listOf(
                CollectionItem("one", "https://www.youtube.com/watch?v=one", "First lesson", "Fixture Teacher", null, 300, 0),
                CollectionItem("private", "https://www.youtube.com/watch?v=private", "Private lesson", null, null, null, 1, "Private video"),
                CollectionItem("three", "https://www.youtube.com/watch?v=three", "Third lesson", null, null, 60, 2)), null, false)
        }
        app.batchRepository = BatchRepository(app.downloadDatabase, app.downloadRepository, CollectionExtractorRegistry(listOf(extractor)))
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach { it.finish() }
        }
        scenario?.close()
        runBlocking {
            app.batchRepository.batches.first().filter { it.sourceUrl == source }.forEach { app.batchRepository.deleteHistory(it.batchId) }
        }
        app.batchRepository = original
        runBlocking { app.downloadPreferenceStore.setYoutubeMp3BitrateKbps(oldBitrate) }
    }

    private fun parent(): BatchDownloadEntity? = runBlocking { app.batchRepository.batches.first().firstOrNull { it.sourceUrl == source } }
    private fun preview() {
        compose.waitUntil(15_000) { parent()?.discoveredCount == 3 }
        compose.onNodeWithText("Fixture Course").assertIsDisplayed()
        compose.onNodeWithText("Fixture Teacher").assertIsDisplayed()
        compose.onNodeWithText("3 of 3 items found · 0 selected").assertIsDisplayed()
        runBlocking { assertTrue(app.downloadDatabase.batchDao().children(parent()!!.batchId).isEmpty()) }
    }

    @Test fun sharedWatchWithPlaylistOpensPreviewBeforeDownloads() {
        scenario = ActivityScenario.launch<ShareDownloadActivity>(Intent(app, ShareDownloadActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, source)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        preview()
        compose.onNodeWithTag("batch_list").performScrollToNode(hasText("Audio"))
        compose.onNodeWithText("Audio").performClick()
        compose.waitUntil { parent()?.downloadMode == "AUDIO_MP3" }
        assertEquals(192, parent()!!.mp3BitrateKbps)
        compose.onNodeWithTag("batch_list").performScrollToNode(hasText("MP3 192 kbps"))
        compose.onNodeWithText("MP3 192 kbps").assertIsSelected()
        compose.onNodeWithTag("batch_list").performScrollToNode(hasText("Native Audio"))
        compose.onNodeWithText("Native Audio").performClick()
        compose.waitUntil { parent()?.downloadMode == "AUDIO_ORIGINAL" }
        compose.onNodeWithTag("batch_list").performScrollToNode(hasTestTag("batch_select_all"))
        compose.onNodeWithTag("batch_select_all").performClick()
        compose.waitUntil { parent()?.selectedCount == 2 }
        compose.onNodeWithTag("batch_list").performScrollToNode(hasTestTag("batch_item_1"))
        compose.onNodeWithText("Skipped: Private video").assertIsDisplayed()
        compose.onNodeWithTag("batch_list").performScrollToNode(hasTestTag("batch_item_0"))
        compose.onNodeWithText("5:00").assertIsDisplayed()
        compose.onNodeWithTag("batch_list").performScrollToNode(hasText("Deselect all"))
        compose.onNodeWithText("Deselect all").performClick()
        compose.waitUntil { parent()?.selectedCount == 0 }
        runBlocking { assertTrue(app.downloadDatabase.batchDao().children(parent()!!.batchId).isEmpty()) }
    }

    @Test fun typedPlaylistUsesGatewayClassificationAndPreview() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasSetTextAction()).performTextReplacement(source)
        compose.onNodeWithText(app.getString(R.string.action_explore_formats)).performScrollTo().performClick()
        preview()
    }

    @Test fun pastedPlaylistUsesGatewayClassificationAndPreview() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync {
            app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Playlist", source))
        }
        compose.onNodeWithContentDescription("Paste from clipboard").performClick()
        compose.onNodeWithText(app.getString(R.string.action_explore_formats)).performScrollTo().performClick()
        preview()
    }
}
