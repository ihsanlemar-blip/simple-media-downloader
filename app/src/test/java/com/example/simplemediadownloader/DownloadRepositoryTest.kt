package com.example.simplemediadownloader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val format = AvailableFormat(
        key = "video:18",
        mode = DownloadMode.VIDEO,
        formatId = "18",
        extension = "mp4",
        height = 360,
    )
    private val output = DownloadOutput(
        contentUri = "content://media/external_primary/video/media/42",
        mimeType = "video/mp4",
        fileSizeBytes = 4_096L,
        displayName = "saved.mp4",
    )

    @Test
    fun `repository inserts stable task and persists state transitions through completion`() = runBlocking {
        val outputDirectory = temporaryFolder.newFolder("downloads")
        val engine = FakeDownloadEngine().apply {
            result = DownloadExecutionResult.Success("engine output")
            emittedState = DownloadState.Downloading(
                DownloadProgress(50f, 4L, "Downloading…"),
            )
        }
        val exporter = FakeStorageExporter(outputDirectory, output)
        val history = InMemoryHistoryStore()
        var now = 1_000L
        val repository = repository(engine, history, exporter) { now++ }
        val request = request("stable-task-id")
        val states = mutableListOf<DownloadState>()

        assertTrue(repository.enqueue(request).isSuccess)
        assertEquals("stable-task-id", repository.activeTasks.first().single().id)
        val result = repository.download(request, states::add)

        assertTrue(result is DownloadState.Completed)
        assertSame(outputDirectory, engine.outputDirectory)
        assertEquals("engine output", exporter.commandOutput)
        assertEquals(listOf(outputDirectory), exporter.cleanedDestinations)
        val stored = history.get("stable-task-id")!!
        assertEquals("stable-task-id", stored.taskId)
        assertEquals("example.test", stored.platform)
        assertEquals(DownloadTaskStatus.COMPLETED, stored.status)
        assertEquals(DownloadProcessingStage.COMPLETED, stored.stage)
        assertEquals(100f, stored.progressPercent)
        assertEquals(output, stored.output)
        assertEquals("stable-task-id", repository.recentHistory.first().single().id)
        assertTrue(stored.startedAt != null)
        assertTrue(stored.completedAt != null)
        assertTrue(states[0] is DownloadState.Preparing)
        assertTrue(states[1] is DownloadState.Downloading)
        assertTrue(states[2] is DownloadState.Saving)
        assertTrue(states[3] is DownloadState.Completed)
    }

    @Test
    fun `repository recovery retry and deletion keep the same task id`() = runBlocking {
        val history = InMemoryHistoryStore()
        val repository = repository(
            FakeDownloadEngine(),
            history,
            FakeStorageExporter(temporaryFolder.root, output),
        ) { 5_000L }
        val request = request("recoverable-id")
        repository.enqueue(request).getOrThrow()
        repository.prepareTask(request.id, "Preparing")

        assertEquals(1, repository.recoverInterruptedTasks())
        val interrupted = history.get(request.id)!!
        assertEquals(DownloadTaskStatus.INTERRUPTED, interrupted.status)
        assertEquals(DownloadFailureCategory.ANDROID_INTERRUPTED_TASK, interrupted.failureCategory)

        assertTrue(repository.retry(request.id))
        val retried = history.get(request.id)!!
        assertEquals("recoverable-id", retried.taskId)
        assertEquals(DownloadTaskStatus.QUEUED, retried.status)
        assertNull(retried.startedAt)
        assertNull(retried.completedAt)
        assertNull(retried.failureCategory)

        repository.failTask(
            request.id,
            "failed",
            DownloadFailureCategory.UNKNOWN_FAILURE,
            "technical",
        )
        assertTrue(repository.removeHistoryEntry(request.id))
        assertNull(history.get(request.id))

        val second = request("clearable-id")
        repository.enqueue(second).getOrThrow()
        repository.failTask(
            second.id,
            "failed",
            DownloadFailureCategory.UNKNOWN_FAILURE,
        )
        assertEquals(1, repository.clearCompletedHistory())
        assertNull(history.get(second.id))
    }

    @Test
    fun `service recovery requeues interrupted and existing queued tasks in creation order`() =
        runBlocking {
            val history = InMemoryHistoryStore()
            var now = 5_000L
            val repository = repository(
                FakeDownloadEngine(),
                history,
                FakeStorageExporter(temporaryFolder.root, output),
            ) { now++ }
            val interrupted = request("interrupted-id")
            val queued = request("queued-id")
            repository.enqueue(interrupted).getOrThrow()
            repository.prepareTask(interrupted.id, "Downloading")
            repository.enqueue(queued).getOrThrow()

            val recovered = repository.restoreRecoverableTasks()

            assertEquals(listOf("interrupted-id", "queued-id"), recovered.map { it.id })
            assertEquals(DownloadTaskStatus.QUEUED, history.get(interrupted.id)!!.status)
            assertNull(history.get(interrupted.id)!!.startedAt)
            assertNull(history.get(interrupted.id)!!.failureCategory)
        }

    @Test
    fun `active cancellation uses stable process id and persists only that task as cancelled`() =
        runBlocking {
            val engine = FakeDownloadEngine()
            val history = InMemoryHistoryStore()
            val repository = repository(
                engine,
                history,
                FakeStorageExporter(temporaryFolder.root, output),
            )
            val first = request("cancel-this-id")
            val second = request("leave-this-id")
            repository.enqueue(first).getOrThrow()
            repository.enqueue(second).getOrThrow()
            repository.prepareTask(first.id, "Downloading")
            repository.prepareTask(second.id, "Downloading")

            assertTrue(repository.cancel(first.id))

            assertEquals("cancel-this-id", engine.cancelledProcessId)
            assertEquals(DownloadTaskStatus.CANCELLED, history.get(first.id)!!.status)
            assertEquals(DownloadTaskStatus.RUNNING, history.get(second.id)!!.status)
        }

    @Test
    fun `repository records categorized storage failure before engine starts`() = runBlocking {
        val engine = FakeDownloadEngine()
        val history = InMemoryHistoryStore()
        val exporter = FakeStorageExporter(temporaryFolder.root, output).apply {
            preparationFailure = IllegalStateException("storage unavailable")
        }
        val repository = repository(engine, history, exporter)
        val request = request("failed-id")
        repository.enqueue(request).getOrThrow()

        val result = repository.download(request)

        assertTrue(result is DownloadState.Failed)
        assertEquals(DownloadFailureCategory.UNKNOWN_FAILURE, (result as DownloadState.Failed).category)
        assertFalse(engine.downloadCalled)
        val stored = history.get(request.id)!!
        assertEquals(DownloadTaskStatus.FAILED, stored.status)
        assertEquals("Could not prepare temporary storage for this download.", stored.failureMessage)
        assertTrue(stored.technicalFailureDetail!!.contains("IllegalStateException"))
    }

    @Test
    fun `insufficient storage is categorized before engine starts`() = runBlocking {
        val engine = FakeDownloadEngine()
        val history = InMemoryHistoryStore()
        val exporter = FakeStorageExporter(temporaryFolder.root, output).apply {
            preparationFailure = IllegalStateException("No space left on device")
        }
        val repository = repository(engine, history, exporter)
        val request = request("no-space-id")
        repository.enqueue(request).getOrThrow()

        val result = repository.download(request)

        assertEquals(
            DownloadFailureCategory.INSUFFICIENT_STORAGE,
            (result as DownloadState.Failed).category,
        )
        assertEquals(DownloadFailureCategory.INSUFFICIENT_STORAGE.userMessage, result.message)
        assertFalse(engine.downloadCalled)
    }

    @Test
    fun `cancelled engine result cleans temporary workspace without exporting`() = runBlocking {
        val directory = temporaryFolder.newFolder("cancelled-work")
        val engine = FakeDownloadEngine().apply { result = DownloadExecutionResult.Cancelled }
        val exporter = FakeStorageExporter(directory, output)
        val repository = repository(engine, InMemoryHistoryStore(), exporter)
        val request = request("cancelled-work-id")
        repository.enqueue(request).getOrThrow()

        val result = repository.download(request)

        assertEquals(DownloadState.Cancelled, result)
        assertEquals(listOf(directory), exporter.cleanedDestinations)
        assertFalse(exporter.exportCalled)
    }

    @Test
    fun `media store export failure cleans workspace and records storage failure`() = runBlocking {
        val directory = temporaryFolder.newFolder("failed-export")
        val exporter = FakeStorageExporter(directory, output).apply {
            exportFailure = IllegalStateException("public row write failed")
        }
        val repository = repository(
            FakeDownloadEngine().apply { result = DownloadExecutionResult.Success("done") },
            InMemoryHistoryStore(),
            exporter,
        )
        val request = request("failed-export-id")
        repository.enqueue(request).getOrThrow()

        val result = repository.download(request)

        assertTrue(result is DownloadState.Failed)
        assertEquals(DownloadFailureCategory.UNKNOWN_FAILURE, (result as DownloadState.Failed).category)
        assertEquals(listOf(directory), exporter.cleanedDestinations)
    }

    @Test
    fun `completed history detects a media store row deleted outside the app`() = runBlocking {
        val directory = temporaryFolder.newFolder("missing-output")
        val history = InMemoryHistoryStore()
        val exporter = FakeStorageExporter(directory, output).apply { outputAvailable = false }
        val repository = repository(
            FakeDownloadEngine().apply { result = DownloadExecutionResult.Success("done") },
            history,
            exporter,
        )
        val request = request("externally-deleted-id")
        repository.enqueue(request).getOrThrow()
        repository.download(request)

        val task = repository.recentHistory.first().single()

        assertTrue(task.state is DownloadState.Failed)
        assertEquals(
            DownloadFailureCategory.UNKNOWN_FAILURE,
            (task.state as DownloadState.Failed).category,
        )
    }

    @Test
    fun `duplicate active URL and format is rejected without creating another task`() = runBlocking {
        val history = InMemoryHistoryStore()
        val repository = repository(
            FakeDownloadEngine(),
            history,
            FakeStorageExporter(temporaryFolder.root, output),
        )

        val sharedUrl = "https://example.test/video/shared"
        repository.enqueue(request("original-id", sharedUrl)).getOrThrow()
        val duplicate = repository.enqueue(request("duplicate-id", sharedUrl))

        assertTrue(duplicate.isFailure)
        assertTrue(duplicate.exceptionOrNull() is DuplicateActiveDownloadException)
        assertEquals(listOf("original-id"), repository.activeTasks.first().map(DownloadTask::id))
    }

    @Test
    fun `delete media removes MediaStore output and its completed history entry`() = runBlocking {
        val directory = temporaryFolder.newFolder("delete-output")
        val history = InMemoryHistoryStore()
        val exporter = FakeStorageExporter(directory, output)
        val repository = repository(
            FakeDownloadEngine().apply { result = DownloadExecutionResult.Success("done") },
            history,
            exporter,
        )
        val request = request("delete-output-id")
        repository.enqueue(request).getOrThrow()
        repository.download(request)

        assertTrue(repository.deleteMediaAndHistory(request.id).isSuccess)
        assertEquals(output, exporter.deletedOutput)
        assertNull(history.get(request.id))
    }

    private fun request(
        id: String,
        url: String = "https://example.test/video/$id",
    ) = DownloadRequest(
        id = id,
        url = url,
        title = "360p video",
        format = format,
    )

    private fun repository(
        engine: FakeDownloadEngine,
        history: InMemoryHistoryStore,
        exporter: FakeStorageExporter,
        clock: () -> Long = { 1_000L },
    ) = DownloadRepository(FakeDiscoveryEngine(), engine, history, exporter, clock)

    private class FakeDiscoveryEngine : FormatDiscoveryEngine {
        override fun quickFormatCatalog(url: String) = MediaFormatCatalog(
            sourceUrl = url,
            title = "Quick",
            videoFormats = emptyList(),
            audioFormats = emptyList(),
        )

        override fun fastVideoPreset() = AvailableFormat(
            key = "fast",
            mode = DownloadMode.VIDEO,
            formatId = "best",
            extension = "source",
        )

        override suspend fun discoverFormats(url: String) =
            FormatDiscoveryResult.Failure("not needed")
    }

    private class FakeDownloadEngine : DownloadEngine {
        var result: DownloadExecutionResult = DownloadExecutionResult.Cancelled
        var emittedState: DownloadState? = null
        var outputDirectory: File? = null
        var downloadCalled = false
        var cancelledProcessId: String? = null

        override suspend fun download(
            request: DownloadRequest,
            outputDirectory: File,
            onState: (DownloadState) -> Unit,
        ): DownloadExecutionResult {
            downloadCalled = true
            this.outputDirectory = outputDirectory
            emittedState?.let(onState)
            return result
        }

        override suspend fun cancel(processId: String): Boolean {
            cancelledProcessId = processId
            return true
        }
    }

    private class FakeStorageExporter(
        private val directory: File,
        private val output: DownloadOutput,
    ) : StorageExporter {
        var preparationFailure: Throwable? = null
        var commandOutput: String? = null
        var exportCalled = false
        var exportFailure: Throwable? = null
        var outputAvailable = true
        var deleteSucceeds = true
        var deletedOutput: DownloadOutput? = null
        val cleanedDestinations = mutableListOf<File>()

        override suspend fun prepareDestination(request: DownloadRequest): Result<ExportDestination> {
            val failure = preparationFailure
            return if (failure != null) {
                Result.failure(failure)
            } else {
                Result.success(ExportDestination(directory))
            }
        }

        override suspend fun exportCompletedFile(
            request: DownloadRequest,
            destination: ExportDestination,
            commandOutput: String,
        ): Result<DownloadOutput> {
            exportCalled = true
            this.commandOutput = commandOutput
            val failure = exportFailure
            return if (failure != null) Result.failure(failure) else Result.success(output)
        }

        override suspend fun cleanup(destination: ExportDestination) {
            cleanedDestinations += destination.directory
        }

        override suspend fun outputExists(output: DownloadOutput): Boolean = outputAvailable

        override suspend fun deleteOutput(output: DownloadOutput): Boolean {
            deletedOutput = output
            return deleteSucceeds
        }
    }

    private class InMemoryHistoryStore : DownloadHistoryStore {
        private val records = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())

        override val activeTasks: Flow<List<DownloadRecord>> = records.map { values ->
            values.values.filter {
                it.status == DownloadTaskStatus.QUEUED || it.status == DownloadTaskStatus.RUNNING
            }
        }
        override val recentHistory: Flow<List<DownloadRecord>> = records.map { values ->
            values.values.filter {
                it.status != DownloadTaskStatus.QUEUED && it.status != DownloadTaskStatus.RUNNING
            }
        }

        override suspend fun insert(record: DownloadRecord) {
            check(record.taskId !in records.value)
            records.value = records.value + (record.taskId to record)
        }

        override suspend fun update(record: DownloadRecord) {
            check(record.taskId in records.value)
            records.value = records.value + (record.taskId to record)
        }

        override suspend fun get(taskId: String): DownloadRecord? = records.value[taskId]

        override suspend fun recoverRunningTasks(
            interruptedAt: Long,
            technicalDetail: String,
        ): Int {
            val running = records.value.values.filter { it.status == DownloadTaskStatus.RUNNING }
            running.forEach { record ->
                update(
                    record.copy(
                        status = DownloadTaskStatus.INTERRUPTED,
                        stage = DownloadProcessingStage.INTERRUPTED,
                        completedAt = interruptedAt,
                        failureCategory = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK,
                        failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
                        technicalFailureDetail = technicalDetail,
                    ),
                )
            }
            return running.size
        }

        override suspend fun requeueInterruptedTasks(): Int {
            val interrupted = records.value.values.filter {
                it.status == DownloadTaskStatus.INTERRUPTED
            }
            interrupted.forEach { record ->
                update(
                    record.copy(
                        status = DownloadTaskStatus.QUEUED,
                        stage = DownloadProcessingStage.QUEUED,
                        progressPercent = null,
                        etaSeconds = null,
                        output = null,
                        startedAt = null,
                        completedAt = null,
                        failureCategory = null,
                        failureMessage = null,
                        technicalFailureDetail = null,
                    ),
                )
            }
            return interrupted.size
        }

        override suspend fun interruptTask(
            taskId: String,
            interruptedAt: Long,
            technicalDetail: String,
        ): Boolean {
            val record = records.value[taskId] ?: return false
            if (record.status != DownloadTaskStatus.RUNNING &&
                record.status != DownloadTaskStatus.CANCELLED
            ) return false
            update(
                record.copy(
                    status = DownloadTaskStatus.INTERRUPTED,
                    stage = DownloadProcessingStage.INTERRUPTED,
                    completedAt = interruptedAt,
                    failureCategory = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK,
                    failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
                    technicalFailureDetail = technicalDetail,
                ),
            )
            return true
        }

        override suspend fun retry(taskId: String): Boolean {
            val record = records.value[taskId] ?: return false
            if (record.status == DownloadTaskStatus.QUEUED ||
                record.status == DownloadTaskStatus.RUNNING
            ) return false
            update(
                record.copy(
                    status = DownloadTaskStatus.QUEUED,
                    stage = DownloadProcessingStage.QUEUED,
                    progressPercent = null,
                    etaSeconds = null,
                    output = null,
                    startedAt = null,
                    completedAt = null,
                    failureCategory = null,
                    failureMessage = null,
                    technicalFailureDetail = null,
                ),
            )
            return true
        }

        override suspend fun removeHistoryEntry(taskId: String): Boolean {
            val record = records.value[taskId] ?: return false
            if (record.status == DownloadTaskStatus.QUEUED ||
                record.status == DownloadTaskStatus.RUNNING
            ) return false
            records.value = records.value - taskId
            return true
        }

        override suspend fun clearCompletedHistory(): Int {
            val terminalIds = recentHistoryRecords().map(DownloadRecord::taskId)
            records.value = records.value - terminalIds.toSet()
            return terminalIds.size
        }

        private fun recentHistoryRecords(): List<DownloadRecord> = records.value.values.filter {
            it.status != DownloadTaskStatus.QUEUED && it.status != DownloadTaskStatus.RUNNING
        }
    }
}
