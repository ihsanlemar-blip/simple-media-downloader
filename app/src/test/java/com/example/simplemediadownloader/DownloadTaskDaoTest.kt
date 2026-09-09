package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadTaskDaoTest {
    private lateinit var database: DownloadDatabase
    private lateinit var dao: DownloadTaskDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        dao = database.downloadTaskDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insert transition recovery and retry preserve stable task`() = runBlocking {
        val queued = record("stable-id").toEntity()
        dao.insert(queued)

        assertEquals(listOf("stable-id"), dao.observeActiveTasks().first().map { it.taskId })
        assertTrue(dao.observeRecentHistory().first().isEmpty())

        dao.update(
            queued.copy(
                status = DownloadTaskStatus.RUNNING.name,
                processingStage = DownloadProcessingStage.DOWNLOADING_VIDEO.name,
                progressPercent = 37f,
                startedAt = 200L,
            ),
        )
        assertEquals(37f, dao.get("stable-id")!!.progressPercent)

        assertEquals(
            1,
            dao.recoverRunningTasks(
                interruptedAt = 300L,
                failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
                technicalDetail = "process ended",
            ),
        )
        val interrupted = dao.get("stable-id")!!
        assertEquals(DownloadTaskStatus.INTERRUPTED.name, interrupted.status)
        assertEquals(DownloadProcessingStage.INTERRUPTED.name, interrupted.processingStage)
        assertEquals("process ended", interrupted.technicalFailureDetail)
        assertTrue(dao.observeActiveTasks().first().isEmpty())
        assertEquals("stable-id", dao.observeRecentHistory().first().single().taskId)

        assertEquals(1, dao.requeueInterruptedTasks())
        val recoveredQueueEntry = dao.get("stable-id")!!
        assertEquals(DownloadTaskStatus.QUEUED.name, recoveredQueueEntry.status)
        assertNull(recoveredQueueEntry.progressPercent)
        assertNull(recoveredQueueEntry.startedAt)
        assertNull(recoveredQueueEntry.completedAt)

        dao.update(
            recoveredQueueEntry.copy(
                status = DownloadTaskStatus.INTERRUPTED.name,
                processingStage = DownloadProcessingStage.INTERRUPTED.name,
                completedAt = 350L,
            ),
        )

        assertEquals(1, dao.retry("stable-id"))
        val retried = dao.get("stable-id")!!
        assertEquals("stable-id", retried.taskId)
        assertEquals(DownloadTaskStatus.QUEUED.name, retried.status)
        assertEquals(DownloadProcessingStage.QUEUED.name, retried.processingStage)
        assertNull(retried.progressPercent)
        assertNull(retried.startedAt)
        assertNull(retried.completedAt)
        assertNull(retried.failureCategory)
        assertNull(retried.technicalFailureDetail)
    }

    @Test
    fun `history deletion never removes active tasks`() = runBlocking {
        val queued = record("queued").toEntity()
        val completed = record("completed").copy(
            status = DownloadTaskStatus.COMPLETED,
            stage = DownloadProcessingStage.COMPLETED,
            progressPercent = 100f,
            output = DownloadOutput(
                "content://files/completed.mp4",
                "video/mp4",
                4_096L,
                "completed.mp4",
            ),
            completedAt = 400L,
        ).toEntity()
        val failed = record("failed").copy(
            status = DownloadTaskStatus.FAILED,
            stage = DownloadProcessingStage.FAILED,
            completedAt = 500L,
            failureCategory = DownloadFailureCategory.NETWORK_INTERRUPTED,
            failureMessage = DownloadFailureCategory.NETWORK_INTERRUPTED.userMessage,
            technicalFailureDetail = "DNS lookup failed",
        ).toEntity()
        dao.insert(queued)
        dao.insert(completed)
        dao.insert(failed)

        assertEquals(0, dao.removeHistoryEntry("queued"))
        assertEquals(1, dao.removeHistoryEntry("completed"))
        assertNull(dao.get("completed"))
        assertEquals(1, dao.clearCompletedHistory())
        assertNull(dao.get("failed"))
        assertEquals("queued", dao.get("queued")!!.taskId)
    }

    @Test
    fun `forced service shutdown marks only the owned active task interrupted`() = runBlocking {
        val active = record("active").toEntity().copy(
            status = DownloadTaskStatus.RUNNING.name,
            processingStage = DownloadProcessingStage.DOWNLOADING_VIDEO.name,
            startedAt = 200L,
        )
        val queued = record("queued").toEntity()
        dao.insert(active)
        dao.insert(queued)

        assertEquals(
            1,
            dao.interruptTask(
                taskId = "active",
                interruptedAt = 300L,
                failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
                technicalDetail = "service stopped",
            ),
        )

        assertEquals(DownloadTaskStatus.INTERRUPTED.name, dao.get("active")!!.status)
        assertEquals("service stopped", dao.get("active")!!.technicalFailureDetail)
        assertEquals(DownloadTaskStatus.QUEUED.name, dao.get("queued")!!.status)
        assertEquals(0, dao.interruptTask("queued", 400L, "interrupted", "ignored"))
    }

    @Test
    fun `file backed queue survives database reopen and recovers running task`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "download-restart-test.db"
        context.deleteDatabase(databaseName)
        var persistentDatabase = Room.databaseBuilder(
            context,
            DownloadDatabase::class.java,
            databaseName,
        ).build()
        persistentDatabase.downloadTaskDao().insert(
            record("restart-id").toEntity().copy(
                status = DownloadTaskStatus.RUNNING.name,
                processingStage = DownloadProcessingStage.DOWNLOADING_VIDEO.name,
                progressPercent = 61f,
                startedAt = 200L,
            ),
        )
        persistentDatabase.close()

        persistentDatabase = Room.databaseBuilder(
            context,
            DownloadDatabase::class.java,
            databaseName,
        ).build()
        val reopenedDao = persistentDatabase.downloadTaskDao()
        assertEquals("restart-id", reopenedDao.get("restart-id")!!.taskId)
        assertEquals(
            1,
            reopenedDao.recoverRunningTasks(
                interruptedAt = 300L,
                failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
                technicalDetail = "cold process restart",
            ),
        )
        val recovered = reopenedDao.get("restart-id")!!
        assertEquals(DownloadTaskStatus.INTERRUPTED.name, recovered.status)
        assertEquals(61f, recovered.progressPercent)
        persistentDatabase.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test
    fun `http headers are persisted and restored across database conversions`() = runBlocking {
        val testHeaders = mapOf(
            "Cookie" to "ttwid=12345; sessionid=abcdef",
            "User-Agent" to "CustomMobileUA",
            "Referer" to "https://www.tiktok.com/",
        )
        val originalRecord = record("headers-id").copy(
            format = record("headers-id").format.copy(httpHeaders = testHeaders),
        )
        val entity = originalRecord.toEntity()
        dao.insert(entity)

        val retrievedEntity = dao.get("headers-id")!!
        val retrievedRecord = retrievedEntity.toRecord()

        assertEquals(testHeaders, retrievedRecord.format.httpHeaders)
    }

    private fun record(id: String) = DownloadRecord(
        taskId = id,
        sourceUrl = "https://example.test/video",
        displayTitle = "360p video",
        platform = "example.test",
        format = AvailableFormat(
            key = "video:18",
            mode = DownloadMode.VIDEO,
            formatId = "18",
            extension = "mp4",
            height = 360,
        ),
        status = DownloadTaskStatus.QUEUED,
        stage = DownloadProcessingStage.QUEUED,
        progressPercent = null,
        etaSeconds = null,
        output = null,
        createdAt = 100L,
        startedAt = null,
        completedAt = null,
        failureCategory = null,
        failureMessage = null,
        technicalFailureDetail = null,
    )
}
