package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaPreviewLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `calculateEffectiveAspectRatio handles landscape, portrait, square, and rotated video`() {
        // Landscape 16:9
        val landscapeRatio = calculateEffectiveAspectRatio(1920, 1080, 0)
        assertEquals(16f / 9f, landscapeRatio, 0.01f)

        // Portrait 9:16 (Shorts/Reels)
        val portraitRatio = calculateEffectiveAspectRatio(1080, 1920, 0)
        assertEquals(9f / 16f, portraitRatio, 0.01f)

        // Square 1:1
        val squareRatio = calculateEffectiveAspectRatio(720, 720, 0)
        assertEquals(1.0f, squareRatio, 0.001f)

        // Landscape dimensions with 90-degree unapplied rotation (portrait capture)
        val rotated90Ratio = calculateEffectiveAspectRatio(1920, 1080, 90)
        assertEquals(1080f / 1920f, rotated90Ratio, 0.01f)

        // Landscape dimensions with 270-degree unapplied rotation
        val rotated270Ratio = calculateEffectiveAspectRatio(1920, 1080, 270)
        assertEquals(1080f / 1920f, rotated270Ratio, 0.01f)

        // Invalid or zero dimensions fall back to default 16:9
        assertEquals(16f / 9f, calculateEffectiveAspectRatio(0, 0, 0), 0.01f)
        assertEquals(16f / 9f, calculateEffectiveAspectRatio(-10, 100, 0), 0.01f)
        assertEquals(16f / 9f, calculateEffectiveAspectRatio(100, -10, 0), 0.01f)
    }

    @Test
    fun `isMediaUriAccessible correctly detects existing versus missing files`() {
        // Existing file
        val existingFile = tempFolder.newFile("sample_media.mp4")
        assertTrue(isMediaUriAccessible(context, existingFile.toURI().toString()))

        // Missing file
        val missingFile = File(tempFolder.root, "does_not_exist.mp4")
        assertFalse(isMediaUriAccessible(context, missingFile.toURI().toString()))

        // Invalid URI format
        assertFalse(isMediaUriAccessible(context, "invalid://[bad-uri"))
    }

    @Test
    fun `MainViewModel preview position tracking preserves and resets position per uri`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeExporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) = Result.failure<ExportDestination>(UnsupportedOperationException())
            override suspend fun exportCompletedFile(request: DownloadRequest, destination: ExportDestination, commandOutput: String) = Result.failure<DownloadOutput>(UnsupportedOperationException())
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = false
            override suspend fun clearDisposableCache(): Long = 0L
        }

        val fakeDiscoveryEngine = object : FormatDiscoveryEngine {
            override fun quickFormatCatalog(url: String): MediaFormatCatalog = MediaFormatCatalog(url, "test", emptyList(), emptyList())
            override fun fastVideoPreset(): AvailableFormat = AvailableFormat(key = "video:18", mode = DownloadMode.VIDEO, formatId = "18", extension = "mp4")
            override suspend fun discoverFormats(url: String): FormatDiscoveryResult = FormatDiscoveryResult.Failure("unused")
            override fun clearCache() {}
        }

        val fakeDownloadEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                outputDirectory: File,
                onState: (DownloadState) -> Unit,
            ): DownloadExecutionResult = DownloadExecutionResult.Success("ok")

            override suspend fun cancel(processId: String): Boolean = true
        }

        val historyStore = object : DownloadHistoryStore {
            override val activeTasks: Flow<List<DownloadRecord>> = MutableStateFlow(emptyList<DownloadRecord>())
            override val recentHistory: Flow<List<DownloadRecord>> = MutableStateFlow(emptyList<DownloadRecord>())

            override suspend fun insert(record: DownloadRecord) {}
            override suspend fun update(record: DownloadRecord) {}
            override suspend fun get(taskId: String): DownloadRecord? = null
            override suspend fun recoverRunningTasks(interruptedAt: Long, technicalDetail: String): Int = 0
            override suspend fun requeueInterruptedTasks(): Int = 0
            override suspend fun interruptTask(taskId: String, interruptedAt: Long, technicalDetail: String): Boolean = false
            override suspend fun retry(taskId: String): Boolean = false
            override suspend fun removeHistoryEntry(taskId: String): Boolean = false
            override suspend fun clearCompletedHistory(): Int = 0
        }

        val repository = DownloadRepository(
            formatDiscoveryEngine = fakeDiscoveryEngine,
            downloadEngine = fakeDownloadEngine,
            historyStore = historyStore,
            storageExporter = fakeExporter,
        )

        val viewModel = MainViewModel(
            application = app,
            repository = repository,
            preferenceStore = FakeDownloadPreferenceStore(),
            dispatchers = AppDispatchers(kotlinx.coroutines.Dispatchers.Unconfined),
            storageExporter = fakeExporter,
            formatDiscoveryEngine = fakeDiscoveryEngine,
        )

        val uriA = "content://media/external/video/media/101"
        val uriB = "content://media/external/video/media/102"

        // Initially 0 for both
        assertEquals(0L, viewModel.getSavedPreviewPosition(uriA))
        assertEquals(0L, viewModel.getSavedPreviewPosition(uriB))

        // Save position for URI A
        viewModel.savePreviewPosition(uriA, 14500L)
        assertEquals(14500L, viewModel.getSavedPreviewPosition(uriA))
        assertEquals(0L, viewModel.getSavedPreviewPosition(uriB))

        // Save position for URI B
        viewModel.savePreviewPosition(uriB, 32000L)
        assertEquals(14500L, viewModel.getSavedPreviewPosition(uriA))
        assertEquals(32000L, viewModel.getSavedPreviewPosition(uriB))

        // Reset URI A (e.g. playback completed)
        viewModel.savePreviewPosition(uriA, 0L)
        assertEquals(0L, viewModel.getSavedPreviewPosition(uriA))
        assertEquals(32000L, viewModel.getSavedPreviewPosition(uriB))
    }
}
