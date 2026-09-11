package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
class SafeCacheAndVaultDeletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testFormat = AvailableFormat(
        key = "video:18",
        mode = DownloadMode.VIDEO,
        formatId = "18",
        extension = "mp4",
        height = 360,
    )

    private fun testRequest(id: String) = DownloadRequest(
        id = id,
        url = "https://example.test/video/$id",
        title = "Video $id",
        format = testFormat,
    )

    private fun testOutput(id: String, bytes: Long = 4096L) = DownloadOutput(
        contentUri = "content://media/external_primary/video/media/$id",
        mimeType = "video/mp4",
        fileSizeBytes = bytes,
        displayName = "video-$id.mp4",
    )

    @Test
    fun `clearDisposableCache leaves active workspace intact and deletes abandoned workspaces and cache files`() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val cacheFolder = temporaryFolder.newFolder("cache")
        val workspacesFolder = cacheFolder.resolve("downloads_workspaces")

        val wrappedContext = object : ContextWrapper(baseContext) {
            override fun getCacheDir(): File = cacheFolder
        }

        val exporter = DownloadsStorageExporter(
            context = wrappedContext,
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            workspaceRoot = workspacesFolder,
            mediaStoreWriter = object : MediaStoreWriter {
                override suspend fun write(source: File, requestedDisplayName: String, mimeType: String, taskId: String, title: String?) = testOutput(taskId)
                override suspend fun exists(contentUri: String) = true
            },
            availableBytes = { Long.MAX_VALUE },
        )

        // 1. Prepare an active task
        val activeRequest = testRequest("active-task-1")
        val activeDest = exporter.prepareDestination(activeRequest).getOrThrow()
        assertTrue(exporter.isTaskActive("active-task-1"))
        val activeFile = File(activeDest.directory, "video.mp4.part").apply {
            writeBytes(ByteArray(1200))
        }

        // 2. Create an abandoned task workspace inside workspacesFolder
        val abandonedWorkspace = File(workspacesFolder, "task-abandoned_task").apply {
            mkdirs()
            File(this, "abandoned.tmp").writeBytes(ByteArray(2500))
        }

        // 3. Create a temporary cache file outside workspaceRoot inside cacheDir
        val cacheFile = File(cacheFolder, "disposable_image.png").apply {
            writeBytes(ByteArray(800))
        }

        // Perform safe cache cleaning
        val freedBytes = exporter.clearDisposableCache()

        // Verify active task's workspace is preserved intact
        assertEquals(3300L, freedBytes) // 2500 from abandoned + 800 from cacheFile
        assertTrue(activeDest.directory.exists())
        assertTrue(activeFile.exists())
        assertEquals(1200L, activeFile.length())
        assertTrue(exporter.isTaskActive("active-task-1"))

        // Verify abandoned workspace and outside cache file are deleted
        assertFalse(abandonedWorkspace.exists())
        assertFalse(cacheFile.exists())
        assertTrue(workspacesFolder.exists())

        // Cleanup active task
        exporter.cleanup(activeDest)
        assertFalse(exporter.isTaskActive("active-task-1"))
        assertFalse(activeDest.directory.exists())
    }

    @Test
    fun `MainViewModel clearAppCache clears disposable cache, invalidates discovery cache, and updates message`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeExporter = object : StorageExporter {
            var clearCalled = false
            override suspend fun prepareDestination(request: DownloadRequest) = Result.failure<ExportDestination>(UnsupportedOperationException())
            override suspend fun exportCompletedFile(request: DownloadRequest, destination: ExportDestination, commandOutput: String) = Result.failure<DownloadOutput>(UnsupportedOperationException())
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = false
            override suspend fun clearDisposableCache(): Long {
                clearCalled = true
                return 5_242_880L // 5 MB
            }
        }

        var discoveryCacheCleared = false
        val fakeDiscoveryEngine = object : FormatDiscoveryEngine {
            override fun quickFormatCatalog(url: String): MediaFormatCatalog = MediaFormatCatalog(url, "test", emptyList(), emptyList())
            override fun fastVideoPreset(): AvailableFormat = testFormat
            override suspend fun discoverFormats(url: String): FormatDiscoveryResult = FormatDiscoveryResult.Failure("unused")
            override fun clearCache() {
                discoveryCacheCleared = true
            }
        }

        val historyStore = TestHistoryStore()
        val repository = DownloadRepository(
            formatDiscoveryEngine = fakeDiscoveryEngine,
            downloadEngine = FakeDownloadEngine(),
            historyStore = historyStore,
            storageExporter = fakeExporter,
        )

        val viewModel = MainViewModel(
            application = app,
            repository = repository,
            preferenceStore = FakeDownloadPreferenceStore(),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            storageExporter = fakeExporter,
            formatDiscoveryEngine = fakeDiscoveryEngine,
        )

        viewModel.clearAppCache()

        assertTrue(fakeExporter.clearCalled)
        assertTrue(discoveryCacheCleared)
        assertEquals("Cleaned 5.0 MB of temporary cache", viewModel.uiState.value.message)
    }

    @Test
    fun `MainViewModel deleteSelectedVaultTasks awaits all deletions and reports success on complete completion`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val deletedOutputs = mutableListOf<DownloadOutput>()
        val fakeExporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) = Result.failure<ExportDestination>(UnsupportedOperationException())
            override suspend fun exportCompletedFile(request: DownloadRequest, destination: ExportDestination, commandOutput: String) = Result.failure<DownloadOutput>(UnsupportedOperationException())
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = true
            override suspend fun deleteOutput(output: DownloadOutput): Boolean {
                deletedOutputs.add(output)
                return true
            }
        }

        val historyStore = TestHistoryStore()
        val repository = DownloadRepository(
            formatDiscoveryEngine = FakeFormatDiscoveryEngine(),
            downloadEngine = FakeDownloadEngine(),
            historyStore = historyStore,
            storageExporter = fakeExporter,
        )

        // Seed 3 completed tasks
        listOf("task-1", "task-2", "task-3").forEach { id ->
            historyStore.insert(createCompletedRecord(id, testOutput(id)))
        }

        val viewModel = MainViewModel(
            application = app,
            repository = repository,
            preferenceStore = FakeDownloadPreferenceStore(),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            storageExporter = fakeExporter,
            formatDiscoveryEngine = null,
        )

        // Select all 3 tasks
        viewModel.toggleVaultTaskSelection("task-1")
        viewModel.toggleVaultTaskSelection("task-2")
        viewModel.toggleVaultTaskSelection("task-3")
        assertEquals(3, viewModel.uiState.value.selectedVaultTaskIds.size)
        assertTrue(viewModel.uiState.value.isMultiSelectActive)

        viewModel.deleteSelectedVaultTasks(alsoDeleteFiles = true)

        assertEquals(3, deletedOutputs.size)
        assertTrue(viewModel.uiState.value.selectedVaultTaskIds.isEmpty())
        assertFalse(viewModel.uiState.value.isMultiSelectActive)
        assertEquals("Deleted 3 items from vault.", viewModel.uiState.value.message)
    }

    @Test
    fun `MainViewModel deleteSelectedVaultTasks handles partial failures by keeping failed items selected`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeExporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) = Result.failure<ExportDestination>(UnsupportedOperationException())
            override suspend fun exportCompletedFile(request: DownloadRequest, destination: ExportDestination, commandOutput: String) = Result.failure<DownloadOutput>(UnsupportedOperationException())
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = true
            override suspend fun deleteOutput(output: DownloadOutput): Boolean {
                // Fail deletion for task-2
                return !output.contentUri.contains("task-2")
            }
        }

        val historyStore = TestHistoryStore()
        val repository = DownloadRepository(
            formatDiscoveryEngine = FakeFormatDiscoveryEngine(),
            downloadEngine = FakeDownloadEngine(),
            historyStore = historyStore,
            storageExporter = fakeExporter,
        )

        listOf("task-1", "task-2", "task-3").forEach { id ->
            historyStore.insert(createCompletedRecord(id, testOutput(id)))
        }

        val viewModel = MainViewModel(
            application = app,
            repository = repository,
            preferenceStore = FakeDownloadPreferenceStore(),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            storageExporter = fakeExporter,
            formatDiscoveryEngine = null,
        )

        viewModel.toggleVaultTaskSelection("task-1")
        viewModel.toggleVaultTaskSelection("task-2")
        viewModel.toggleVaultTaskSelection("task-3")

        viewModel.deleteSelectedVaultTasks(alsoDeleteFiles = true)

        // Only task-2 failed, so task-2 remains selected
        assertEquals(setOf("task-2"), viewModel.uiState.value.selectedVaultTaskIds)
        assertTrue(viewModel.uiState.value.isMultiSelectActive)
        assertEquals("Failed to delete 1 items", viewModel.uiState.value.message)
        // task-1 and task-3 removed from history
        assertEquals(1, historyStore.records.value.size)
        assertTrue("task-2" in historyStore.records.value)
    }

    @Test
    fun `MainViewModel deleteSelectedVaultTasks with alsoDeleteFiles false removes from history only`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var storageDeleteCalled = false
        val fakeExporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) = Result.failure<ExportDestination>(UnsupportedOperationException())
            override suspend fun exportCompletedFile(request: DownloadRequest, destination: ExportDestination, commandOutput: String) = Result.failure<DownloadOutput>(UnsupportedOperationException())
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = true
            override suspend fun deleteOutput(output: DownloadOutput): Boolean {
                storageDeleteCalled = true
                return true
            }
        }

        val historyStore = TestHistoryStore()
        val repository = DownloadRepository(
            formatDiscoveryEngine = FakeFormatDiscoveryEngine(),
            downloadEngine = FakeDownloadEngine(),
            historyStore = historyStore,
            storageExporter = fakeExporter,
        )

        listOf("task-1", "task-2").forEach { id ->
            historyStore.insert(createCompletedRecord(id, testOutput(id)))
        }

        val viewModel = MainViewModel(
            application = app,
            repository = repository,
            preferenceStore = FakeDownloadPreferenceStore(),
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            storageExporter = fakeExporter,
            formatDiscoveryEngine = null,
        )

        viewModel.toggleVaultTaskSelection("task-1")
        viewModel.toggleVaultTaskSelection("task-2")

        viewModel.deleteSelectedVaultTasks(alsoDeleteFiles = false)

        assertFalse(storageDeleteCalled)
        assertTrue(viewModel.uiState.value.selectedVaultTaskIds.isEmpty())
        assertFalse(viewModel.uiState.value.isMultiSelectActive)
        assertEquals("Deleted 2 items from vault.", viewModel.uiState.value.message)
        assertTrue(historyStore.records.value.isEmpty())
    }

    private fun createCompletedRecord(id: String, output: DownloadOutput) = DownloadRecord(
        taskId = id,
        sourceUrl = "https://example.test/video/$id",
        displayTitle = "Video $id",
        platform = "example.test",
        format = testFormat,
        status = DownloadTaskStatus.COMPLETED,
        stage = DownloadProcessingStage.COMPLETED,
        progressPercent = 100f,
        downloadedBytes = output.fileSizeBytes,
        totalBytes = output.fileSizeBytes,
        speedBytesPerSecond = 0L,
        etaSeconds = null,
        output = output,
        createdAt = 1000L,
        startedAt = 1000L,
        completedAt = 2000L,
        failureCategory = null,
        failureMessage = null,
        technicalFailureDetail = null,
    )

    private class TestHistoryStore : DownloadHistoryStore {
        val records = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())

        override val activeTasks: Flow<List<DownloadRecord>> = records.map { values ->
            values.values.filter { it.status == DownloadTaskStatus.QUEUED || it.status == DownloadTaskStatus.RUNNING }
        }
        override val recentHistory: Flow<List<DownloadRecord>> = records.map { values ->
            values.values.filter { it.status != DownloadTaskStatus.QUEUED && it.status != DownloadTaskStatus.RUNNING }
        }

        override suspend fun insert(record: DownloadRecord) {
            records.value = records.value + (record.taskId to record)
        }

        override suspend fun update(record: DownloadRecord) {
            records.value = records.value + (record.taskId to record)
        }

        override suspend fun get(taskId: String): DownloadRecord? = records.value[taskId]

        override suspend fun recoverRunningTasks(interruptedAt: Long, technicalDetail: String): Int = 0

        override suspend fun requeueInterruptedTasks(): Int = 0

        override suspend fun interruptTask(taskId: String, interruptedAt: Long, technicalDetail: String): Boolean = false

        override suspend fun retry(taskId: String): Boolean = false

        override suspend fun removeHistoryEntry(taskId: String): Boolean {
            if (taskId !in records.value) return false
            records.value = records.value - taskId
            return true
        }

        override suspend fun clearCompletedHistory(): Int {
            val count = records.value.size
            records.value = emptyMap()
            return count
        }
    }

    private class FakeFormatDiscoveryEngine : FormatDiscoveryEngine {
        override fun quickFormatCatalog(url: String): MediaFormatCatalog = MediaFormatCatalog(url, "test", emptyList(), emptyList())
        override fun fastVideoPreset(): AvailableFormat = AvailableFormat(key = "video:18", mode = DownloadMode.VIDEO, formatId = "18", extension = "mp4")
        override suspend fun discoverFormats(url: String): FormatDiscoveryResult = FormatDiscoveryResult.Failure("unused")
    }

    private class FakeDownloadEngine : DownloadEngine {
        override suspend fun download(
            request: DownloadRequest,
            outputDirectory: File,
            onState: (DownloadState) -> Unit,
        ): DownloadExecutionResult = DownloadExecutionResult.Success("ok")

        override suspend fun cancel(processId: String): Boolean = true
    }

    private class FakeDownloadPreferenceStore : DownloadPreferenceStore {
        override val defaultChoice = MutableStateFlow(DefaultDownloadChoice.BEST_VIDEO)
        override val themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
        override val wifiOnly = MutableStateFlow(false)
        override val maxConcurrentDownloads = MutableStateFlow(3)
        override val vaultViewMode = MutableStateFlow("grid")
        override val allowThirdPartyGateways = MutableStateFlow(false)

        override suspend fun setDefaultChoice(choice: DefaultDownloadChoice) { defaultChoice.value = choice }
        override suspend fun setThemeMode(mode: AppThemeMode) { themeMode.value = mode }
        override suspend fun setWifiOnly(enabled: Boolean) { wifiOnly.value = enabled }
        override suspend fun setMaxConcurrentDownloads(limit: Int) { maxConcurrentDownloads.value = limit }
        override suspend fun setVaultViewMode(mode: String) { vaultViewMode.value = mode }
        override suspend fun setAllowThirdPartyGateways(allowed: Boolean) { allowThirdPartyGateways.value = allowed }
    }
}
