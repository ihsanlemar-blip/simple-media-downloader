package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadRepositoryRaceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: DownloadDatabase
    private lateinit var dao: DownloadTaskDao
    private lateinit var historyStore: RoomDownloadHistoryStore

    private val format = AvailableFormat(
        key = "video:race",
        mode = DownloadMode.VIDEO,
        formatId = "best",
        extension = "mp4",
        height = 720,
    )

    private val output = DownloadOutput(
        contentUri = "content://media/race/output.mp4",
        mimeType = "video/mp4",
        fileSizeBytes = 100 * 1024L,
        displayName = "race.mp4",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadTaskDao()
        historyStore = RoomDownloadHistoryStore(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `concurrent rapid progress and cancellation strictly persists CANCELLED state in Room`() = runBlocking(Dispatchers.Default) {
        val startedSignal = CompletableDeferred<Unit>()
        val cancelledSignal = AtomicBoolean(false)

        val raceEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                outputDirectory: File,
                onState: (DownloadState) -> Unit,
            ): DownloadExecutionResult {
                startedSignal.complete(Unit)
                for (i in 1..100) {
                    if (cancelledSignal.get()) {
                        return DownloadExecutionResult.Cancelled
                    }
                    onState(
                        DownloadState.Downloading(
                            DownloadProgress(
                                percentage = i.toFloat(),
                                downloadedBytes = i * 1024L,
                                totalBytes = 100 * 1024L,
                                speedBytesPerSecond = 50_000L,
                                etaSeconds = 2L,
                                status = "Downloading…",
                            ),
                            DownloadTransferKind.VIDEO,
                        ),
                    )
                    delay(2)
                }
                return if (cancelledSignal.get()) {
                    DownloadExecutionResult.Cancelled
                } else {
                    DownloadExecutionResult.Success("output")
                }
            }

            override suspend fun cancel(processId: String): Boolean {
                cancelledSignal.set(true)
                return true
            }
        }

        val destinationDir = temporaryFolder.newFolder("race_downloads")
        val exporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) =
                Result.success(ExportDestination(destinationDir))

            override suspend fun exportCompletedFile(
                request: DownloadRequest,
                destination: ExportDestination,
                commandOutput: String,
            ) = Result.success(output)

            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = true
            override suspend fun deleteOutput(output: DownloadOutput) = true
        }

        val discovery = object : FormatDiscoveryEngine {
            override fun quickFormatCatalog(url: String) = MediaFormatCatalog(
                sourceUrl = url,
                title = "Title",
                videoFormats = emptyList(),
                audioFormats = emptyList(),
            )
            override fun fastVideoPreset() = format
            override fun cachedFormatCatalog(url: String): MediaFormatCatalog? = null
            override suspend fun discoverFormats(url: String) = FormatDiscoveryResult.Failure("not used")
        }

        val repository = DownloadRepository(
            formatDiscoveryEngine = discovery,
            downloadEngine = raceEngine,
            historyStore = historyStore,
            storageExporter = exporter,
        )

        val request = DownloadRequest(
            id = "race-task-1",
            url = "https://example.com/video",
            title = "Race Video",
            format = format,
        )

        repository.enqueue(request).getOrThrow()

        val downloadDeferred = async {
            repository.download(request)
        }

        startedSignal.await()
        delay(15)

        val cancelJob = launch {
            repository.cancel(request.id)
        }
        cancelJob.join()

        val finalState = downloadDeferred.await()
        assertTrue(
            "Expected finalState to be Cancelled but was $finalState",
            finalState is DownloadState.Cancelled,
        )

        val entityInDb = dao.get(request.id)
        assertEquals("Database status must be CANCELLED", DownloadTaskStatus.CANCELLED.name, entityInDb?.status)
        assertEquals("Database stage must be CANCELLED", DownloadProcessingStage.CANCELLED.name, entityInDb?.processingStage)

        val recordInDb = historyStore.get(request.id)
        assertEquals("Record status must be CANCELLED", DownloadTaskStatus.CANCELLED, recordInDb?.status)
        assertEquals("Record stage must be CANCELLED", DownloadProcessingStage.CANCELLED, recordInDb?.stage)
    }

    @Test
    fun `terminal state is strictly monotonic and cannot be overwritten by late progress or success`() = runBlocking {
        val request = DownloadRequest(
            id = "monotonic-task-1",
            url = "https://example.com/mono",
            title = "Monotonic Video",
            format = format,
        )

        val destinationDir = temporaryFolder.newFolder("mono_downloads")
        val exporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) =
                Result.success(ExportDestination(destinationDir))
            override suspend fun exportCompletedFile(
                request: DownloadRequest,
                destination: ExportDestination,
                commandOutput: String,
            ) = Result.success(output)
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = true
            override suspend fun deleteOutput(output: DownloadOutput) = true
        }

        val discovery = object : FormatDiscoveryEngine {
            override fun quickFormatCatalog(url: String) = MediaFormatCatalog(
                sourceUrl = url,
                title = "Title",
                videoFormats = emptyList(),
                audioFormats = emptyList(),
            )
            override fun fastVideoPreset() = format
            override fun cachedFormatCatalog(url: String): MediaFormatCatalog? = null
            override suspend fun discoverFormats(url: String) = FormatDiscoveryResult.Failure("not used")
        }

        val engine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                outputDirectory: File,
                onState: (DownloadState) -> Unit,
            ): DownloadExecutionResult {
                return DownloadExecutionResult.Success("ok")
            }
            override suspend fun cancel(processId: String) = true
        }

        val repository = DownloadRepository(
            formatDiscoveryEngine = discovery,
            downloadEngine = engine,
            historyStore = historyStore,
            storageExporter = exporter,
        )

        repository.enqueue(request).getOrThrow()
        val result = repository.download(request)
        assertTrue(result is DownloadState.Completed)

        val completedEntity = dao.get(request.id)!!
        assertEquals(DownloadTaskStatus.COMPLETED.name, completedEntity.status)

        val updated = historyStore.updateProgress(
            taskId = request.id,
            stage = DownloadProcessingStage.DOWNLOADING_VIDEO,
            progress = DownloadProgress(50f, 1000L, "Late progress"),
        )
        assertFalse(updated)

        val entityAfterLateProgress = dao.get(request.id)!!
        assertEquals(DownloadTaskStatus.COMPLETED.name, entityAfterLateProgress.status)
        assertEquals(DownloadProcessingStage.COMPLETED.name, entityAfterLateProgress.processingStage)
    }
}
