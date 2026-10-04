package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatchRepositoryTest {
    private lateinit var db: DownloadDatabase
    private lateinit var downloads: DownloadRepository
    private lateinit var batches: BatchRepository
    private val url = "https://www.youtube.com/playlist?list=PLtest"
    private var transferred = 0
    private var exported = 0
    private var firstPageCalls = 0
    private var savedOutputAvailable = true
    private val native = AvailableFormat("audio", DownloadMode.AUDIO_ORIGINAL, "https://example.com/audio.m4a", extension = "m4a", bitrateKbps = 128, estimatedSizeBytes = 5_000_000, durationSeconds = 300)
    private val video = AvailableFormat("video360", DownloadMode.VIDEO, "https://example.com/video.mp4", extension = "mp4", height = 360, estimatedSizeBytes = 20_000_000)
    private val discovery = object : FormatDiscoveryEngine {
        override fun quickFormatCatalog(url: String) = AudioFormatOptions.augment(MediaFormatCatalog(url, "Lesson", listOf(video), listOf(native), durationSeconds = 300))
        override fun fastVideoPreset() = video.copy(isQuickPreset = true, formatId = "quick-best")
        override suspend fun discoverFormats(url: String): FormatDiscoveryResult = FormatDiscoveryResult.Success(quickFormatCatalog(url))
    }
    private val extractor = object : CollectionExtractor {
        override suspend fun canHandle(url: String) = true
        override suspend fun getInfo(url: String) = CollectionInfo(url, "YouTube", CollectionType.YOUTUBE_PLAYLIST, "Course", 3)
        override suspend fun getItems(url: String, limit: Int?, continuation: String?): CollectionPage {
            if (continuation == null) firstPageCalls++
            val start = if (continuation == null) 0 else 2
            val items = (start until (if (start == 0) 2 else 3)).map { index -> CollectionItem("lesson-$index", "https://www.youtube.com/watch?v=lesson$index", "Lesson $index", "Teacher", null, 300, index) }
            return CollectionPage(items.take(limit ?: 50), if (start == 0) "page2" else null, start == 0)
        }
    }
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        downloads = DownloadRepository(discovery, object : DownloadEngine {
            override suspend fun download(request: DownloadRequest, outputDirectory: File, onState: (DownloadState) -> Unit): DownloadExecutionResult {
                transferred++; onState(DownloadState.Downloading(DownloadProgress(100f, null, "Downloading audio…")))
                if (request.format.mode == DownloadMode.AUDIO_MP3) onState(DownloadState.Converting(DownloadProgress(100f, null, "Converting to MP3…")))
                return DownloadExecutionResult.Success("file")
            }
            override suspend fun cancel(processId: String) = true
        }, RoomDownloadHistoryStore(db.downloadTaskDao()), object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) = Result.success(ExportDestination(context.cacheDir, request.id))
            override suspend fun exportCompletedFile(request: DownloadRequest, destination: ExportDestination, commandOutput: String): Result<DownloadOutput> {
                exported++
                return Result.success(DownloadOutput("content://media/$exported", if (request.format.mode == DownloadMode.AUDIO_MP3) "audio/mpeg" else "audio/mp4", 123, "Lesson.${request.format.outputExtension}"))
            }
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = savedOutputAvailable
        })
        batches = newManager()
    }
    private fun newManager() = BatchRepository(db, downloads, CollectionExtractorRegistry(listOf(extractor)), { 1_000_000_000L })
    @After fun teardown() { db.close() }
    private suspend fun ready(): String = batches.create(url).also { batches.discoverNext(it); batches.discoverNext(it); batches.select(it, null, true) }

    @Test fun `discovery selection pagination restoration does not enqueue`() = runBlocking {
        val id = batches.create(url)
        batches.discoverNext(id)
        assertEquals(2, batches.observe(id).first()!!.parent.discoveredCount)
        assertEquals(0, batches.observe(id).first()!!.parent.selectedCount)
        batches.select(id, "lesson-1", true)
        val restored = newManager()
        assertTrue(restored.items(id).first()[1].selected)
        restored.discoverNext(id)
        assertEquals(listOf(0, 1, 2), restored.items(id).first().map { it.position })
        assertEquals(1, firstPageCalls)
        assertEquals(0, transferred)
        assertTrue(downloads.queuedRequests().isEmpty())
    }
    @Test fun `MP3 child uses normal repository with persisted source output and order`() = runBlocking {
        val id = ready()
        batches.configure(id, BatchFormatChoice(DownloadMode.AUDIO_MP3), true, true)
        assertEquals(21_600_000L, batches.estimate(id).knownBytes)
        batches.enqueue(id)
        val requests = downloads.queuedRequests()
        assertEquals(3, requests.size)
        assertEquals(listOf(0, 1, 2), requests.map { it.batchIndex })
        val child = requests[0]
        assertEquals(id, child.batchId); assertEquals("lesson-0", child.sourceItemId)
        assertEquals("01 - ", child.filenamePrefix)
        assertEquals(192, child.format.targetAudioBitrateKbps)
        assertEquals("m4a", child.format.sourceExtension); assertEquals("mp3", child.format.outputExtension)
        downloads.download(child)
        assertEquals(1, transferred); assertEquals(1, exported)
        val progress = batches.observe(id).first()!!.progress
        assertEquals(1, progress.completed); assertEquals(2, progress.queued)
        assertEquals(id, db.downloadTaskDao().get(child.id)!!.batchId)
    }
    @Test fun `native child and standalone task use same pipeline`() = runBlocking {
        val id = ready()
        batches.configure(id, BatchFormatChoice(DownloadMode.AUDIO_ORIGINAL), true, false)
        batches.enqueue(id)
        val child = downloads.queuedRequests().first()
        assertFalse(child.format.requiresAudioTranscode)
        downloads.download(child)
        val single = DownloadRequest("single", "https://example.com/standalone", "Single", native)
        downloads.enqueue(single).getOrThrow()
        assertNull(downloads.request(single.id)!!.batchId)
        downloads.download(single)
        assertEquals(2, transferred)
    }
    @Test fun `pause admission resume cancel retain completed media and retry failed`() = runBlocking {
        val id = ready(); batches.enqueue(id)
        val children = downloads.queuedRequests()
        downloads.download(children[0])
        downloads.failTask(children[1].id, "Network", DownloadFailureCategory.NETWORK_INTERRUPTED)
        batches.pause(id)
        assertTrue(downloads.queuedRequests().isEmpty())
        assertEquals(BatchStatus.PAUSED, batches.observe(id).first()!!.status)
        assertEquals(1, batches.retryFailed(id))
        assertTrue(downloads.queuedRequests().isEmpty())
        assertEquals(BatchStatus.PAUSED, batches.observe(id).first()!!.status)
        batches.resume(id)
        assertEquals(2, downloads.queuedRequests().size)
        assertEquals(0, batches.retryFailed(id))
        batches.cancel(id)
        val snapshot = batches.observe(id).first()!!
        assertEquals(BatchStatus.CANCELLED, snapshot.status)
        assertEquals(1, snapshot.progress.completed); assertEquals(2, snapshot.progress.cancelled)
        assertNotNull(db.downloadTaskDao().get(children[0].id)!!.outputContentUri)
        batches.deleteHistory(id)
        assertNull(db.batchDao().get(id))
        assertNull(db.downloadTaskDao().get(children[0].id)!!.batchId)
        assertNotNull(db.downloadTaskDao().get(children[0].id)!!.outputContentUri)
    }
    @Test fun `duplicate summary skip and explicit completed redownload`() = runBlocking {
        val id = ready()
        downloads.enqueue(DownloadRequest("existing-complete", "https://www.youtube.com/watch?v=lesson0", "Lesson", video)).getOrThrow()
        downloads.download(downloads.request("existing-complete")!!)
        downloads.enqueue(DownloadRequest("existing-queue", "https://www.youtube.com/watch?v=lesson1", "Lesson", video)).getOrThrow()
        val summary = batches.estimate(id)
        assertEquals(1, summary.alreadyDownloaded); assertEquals(1, summary.alreadyQueued); assertEquals(1, summary.newItems)
        batches.enqueue(id)
        assertEquals(1, db.batchDao().children(id).size)
        assertEquals(listOf("COMPLETED", "QUEUED", null), db.batchDao().items(id).map { it.skipReason })
        val redownload = ready()
        batches.configure(redownload, BatchFormatChoice(DownloadMode.VIDEO), false, false)
        batches.enqueue(redownload)
        assertEquals(1, db.batchDao().children(redownload).size) // only completed lesson0; active 1+2 still skipped
    }
    @Test fun `missing completed output is counted as a new item`() = runBlocking {
        val id = ready()
        downloads.enqueue(DownloadRequest("missing", "https://www.youtube.com/watch?v=lesson0", "Lesson", video)).getOrThrow()
        downloads.download(downloads.request("missing")!!)
        savedOutputAvailable = false
        val summary = batches.estimate(id)
        assertEquals(0, summary.alreadyDownloaded)
        assertEquals(3, summary.newItems)
    }

    @Test fun `queued paused active and cancelled batches survive recovery`() = runBlocking {
        val id = ready(); batches.enqueue(id)
        val child = downloads.queuedRequests().first()
        downloads.prepareTask(child.id, "Starting")
        batches.pause(id)
        assertTrue(downloads.restoreRecoverableTasks().isEmpty())
        assertEquals("QUEUED", db.downloadTaskDao().get(child.id)!!.status)
        assertEquals(BatchStatus.PAUSED, newManager().observe(id).first()!!.status)
        batches.resume(id)
        assertEquals(3, downloads.restoreRecoverableTasks().size)
        downloads.prepareTask(child.id, "Starting again")
        // Simulate death after cancellation control is durable but before service cancels the active child.
        db.batchDao().update(db.batchDao().get(id)!!.copy(status = "CANCELLED"))
        assertTrue(downloads.restoreRecoverableTasks().isEmpty())
        assertTrue(db.batchDao().children(id).all { it.status == "CANCELLED" })
    }
    @Test fun `pause during active execution prevents stale terminal writes and export`() = runBlocking {
        val id = ready(); batches.enqueue(id)
        val child = downloads.queuedRequests().first()
        val result = downloads.download(child) { state ->
            if (state is DownloadState.Downloading) runBlocking { downloads.pauseForBatch(child.id) }
        }
        assertEquals(DownloadState.Cancelled, result)
        assertEquals("QUEUED", db.downloadTaskDao().get(child.id)!!.status)
        assertEquals(0, exported)
    }

    @Test fun `cancel is durable for running children before service acknowledgement`() = runBlocking {
        val id = ready(); batches.enqueue(id)
        val active = downloads.queuedRequests().first()
        downloads.prepareTask(active.id, "Starting")
        batches.cancel(id)
        assertEquals("CANCELLED", db.downloadTaskDao().get(active.id)!!.status)
        assertEquals(3, batches.observe(id).first()!!.progress.cancelled)
        assertNull(downloads.request(active.id))
        assertTrue(downloads.retry(active.id)) // Existing per-item Retry remains usable after batch Cancel.
        assertEquals(listOf(active.id), downloads.queuedRequests().map { it.id })
        assertEquals(2, batches.observe(id).first()!!.progress.cancelled)
    }

    @Test fun `terminal child aggregation reports errors`() = runBlocking {
        val id = ready(); batches.enqueue(id)
        downloads.queuedRequests().forEach { downloads.failTask(it.id, "Unavailable", DownloadFailureCategory.REMOVED_MEDIA) }
        assertEquals(BatchStatus.COMPLETED_WITH_ERRORS, batches.observe(id).first()!!.status)
        assertEquals(3, batches.observe(id).first()!!.progress.failed)
    }
}
