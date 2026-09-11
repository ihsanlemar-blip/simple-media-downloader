package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DownloadDuplicateAndSecurityTest {

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
    fun `CredentialRedactor strips credentials from headers on terminal state`() {
        val headers = mapOf(
            "Cookie" to "sessionid=xyz; tracker=123",
            "Authorization" to "Bearer secret-token-abc",
            "X-Auth-Token" to "jwt-token-val",
            "User-Agent" to "SimpleMediaDownloader/1.0",
            "Referer" to "https://example.com/",
        )

        // 1. Non-terminal keeps all headers
        val nonTerminal = CredentialRedactor.sanitizeHeaders(headers, isTerminal = false)
        assertNotNull(nonTerminal)
        assertEquals(5, nonTerminal!!.size)
        assertTrue(nonTerminal.containsKey("Cookie"))

        // 2. Terminal strips Cookie, Authorization, and tokens
        val terminal = CredentialRedactor.sanitizeHeaders(headers, isTerminal = true)
        assertNotNull(terminal)
        assertEquals(2, terminal!!.size)
        assertFalse(terminal.containsKey("Cookie"))
        assertFalse(terminal.containsKey("Authorization"))
        assertFalse(terminal.containsKey("X-Auth-Token"))
        assertEquals("SimpleMediaDownloader/1.0", terminal["User-Agent"])
        assertEquals("https://example.com/", terminal["Referer"])

        // 3. If only credentials exist, returns null
        val onlyCredentials = mapOf("Cookie" to "abc", "Authorization" to "Bearer 123")
        val stripped = CredentialRedactor.sanitizeHeaders(onlyCredentials, isTerminal = true)
        assertNull(stripped)
    }

    @Test
    fun `CredentialRedactor redacts auth signatures and query parameters in diagnostics`() {
        val rawDetail = "Request failed at https://cdn.example.com/video.mp4?id=12345&sig=abcdef123456&expire=1719999999&token=supersecret&format=mp4 with Authorization: Bearer eyJhbGciOi..."
        val redacted = CredentialRedactor.redactDiagnostics(rawDetail)

        assertNotNull(redacted)
        // Sensitive query parameters redacted
        assertTrue(redacted!!.contains("sig=[REDACTED]"))
        assertTrue(redacted.contains("expire=[REDACTED]"))
        assertTrue(redacted.contains("token=[REDACTED]"))
        // Non-sensitive query parameters preserved
        assertTrue(redacted.contains("id=12345"))
        assertTrue(redacted.contains("format=mp4"))
        // Headers redacted
        assertTrue(redacted.contains("Authorization: [REDACTED]"))
        assertFalse(redacted.contains("abcdef123456"))
        assertFalse(redacted.contains("supersecret"))
    }

    @Test
    fun `DownloadRecord toEntity sanitizes headers and diagnostics upon completion or failure`() {
        val completedRecord = testRecord("completed-task").copy(
            status = DownloadTaskStatus.COMPLETED,
            format = testRecord("completed-task").format.copy(
                httpHeaders = mapOf(
                    "Cookie" to "session=secret",
                    "User-Agent" to "App/1.0",
                ),
            ),
        )
        val entity = completedRecord.toEntity()
        assertNotNull(entity.httpHeaders)
        assertFalse(entity.httpHeaders!!.contains("session=secret"))
        assertTrue(entity.httpHeaders!!.contains("User-Agent"))

        val failedRecord = testRecord("failed-task").copy(
            status = DownloadTaskStatus.FAILED,
            failureMessage = "Stream error with token=abc12345",
            technicalFailureDetail = "Stack trace: error at https://cdn.test/m?expire=123456&sig=sec888",
        )
        val failedEntity = failedRecord.toEntity()
        assertTrue(failedEntity.failureMessage!!.contains("token=[REDACTED]"))
        assertTrue(failedEntity.technicalFailureDetail!!.contains("expire=[REDACTED]"))
        assertTrue(failedEntity.technicalFailureDetail!!.contains("sig=[REDACTED]"))
    }

    @Test
    fun `insertIfNoActiveDuplicate prevents active duplicates using canonical URL`() = runBlocking {
        val task1 = testRecord("task-1").copy(
            sourceUrl = "https://EXAMPLE.com/watch?v=abc#section1",
        ).toEntity()

        val inserted1 = dao.insertIfNoActiveDuplicate(task1)
        assertTrue(inserted1)

        // Same video with different casing and fragment has the same canonical URL
        val task2 = testRecord("task-2").copy(
            sourceUrl = "https://example.com/watch?v=abc#section2",
        ).toEntity()

        val inserted2 = dao.insertIfNoActiveDuplicate(task2)
        assertFalse("Duplicate canonical URL with same format must be rejected", inserted2)

        // Same canonical URL with DIFFERENT format is allowed
        val task3 = testRecord("task-3").copy(
            sourceUrl = "https://example.com/watch?v=abc",
            format = testRecord("task-3").format.copy(key = "audio:140"),
        ).toEntity()

        val inserted3 = dao.insertIfNoActiveDuplicate(task3)
        assertTrue("Different format key must be allowed", inserted3)
    }

    @Test
    fun `retry refuses to requeue when an active task with same canonical URL exists`() = runBlocking {
        // 1. Task A completed earlier
        val taskA = testRecord("task-A").copy(
            sourceUrl = "https://example.com/video/1",
            status = DownloadTaskStatus.COMPLETED,
        ).toEntity()
        dao.insert(taskA)

        // 2. Task B with the same canonical URL and format is currently QUEUED
        val taskB = testRecord("task-B").copy(
            sourceUrl = "https://EXAMPLE.COM/video/1#hd",
            status = DownloadTaskStatus.QUEUED,
        ).toEntity()
        dao.insert(taskB)

        // 3. Retrying Task A should fail (return 0) because Task B is active
        val retryResult = dao.retry("task-A")
        assertEquals(0, retryResult)
        val taskAAfter = dao.get("task-A")!!
        assertEquals(DownloadTaskStatus.COMPLETED.name, taskAAfter.status)

        // 4. Mark Task B as FAILED
        dao.update(taskB.copy(status = DownloadTaskStatus.FAILED.name))

        // 5. Now retrying Task A succeeds
        val retrySuccess = dao.retry("task-A")
        assertEquals(1, retrySuccess)
        val taskARetried = dao.get("task-A")!!
        assertEquals(DownloadTaskStatus.QUEUED.name, taskARetried.status)
    }

    @Test
    fun `requeueInterruptedTasks skips tasks that have active duplicates`() = runBlocking {
        // Interrupted task 1
        val interrupted1 = testRecord("int-1").copy(
            sourceUrl = "https://example.com/item/1",
            status = DownloadTaskStatus.INTERRUPTED,
        ).toEntity()
        dao.insert(interrupted1)

        // Interrupted task 2
        val interrupted2 = testRecord("int-2").copy(
            sourceUrl = "https://example.com/item/2",
            status = DownloadTaskStatus.INTERRUPTED,
        ).toEntity()
        dao.insert(interrupted2)

        // Active task for item/2 already running
        val activeFor2 = testRecord("active-2").copy(
            sourceUrl = "https://example.com/item/2",
            status = DownloadTaskStatus.RUNNING,
        ).toEntity()
        dao.insert(activeFor2)

        // Requeue: int-1 should be requeued (no active duplicate), int-2 should NOT be requeued
        val requeuedCount = dao.requeueInterruptedTasks()
        assertEquals(1, requeuedCount)

        assertEquals(DownloadTaskStatus.QUEUED.name, dao.get("int-1")!!.status)
        assertEquals(DownloadTaskStatus.INTERRUPTED.name, dao.get("int-2")!!.status)
    }

    @Test
    fun `full database-backed search and pagination across all historical tasks`() = runBlocking {
        // Insert 120 completed tasks
        for (i in 1..120) {
            val title = if (i % 2 == 0) "Tutorial Video $i" else "Music Track $i"
            val record = testRecord("task-$i").copy(
                displayTitle = title,
                status = DownloadTaskStatus.COMPLETED,
                completedAt = 1000L + i,
            ).toEntity()
            dao.insert(record)
        }

        // 1. observeRecentHistory has no 100 limit, returns all 120
        val allHistory = dao.observeRecentHistory().first()
        assertEquals(120, allHistory.size)

        // 2. Pagination: get 20 tasks at offset 10
        val page = dao.getHistoricalTasks(limit = 20, offset = 10)
        assertEquals(20, page.size)
        // Most recent first: completedAt 1120 is at index 0, so offset 10 starts at 1110
        assertEquals("task-110", page.first().taskId)

        // 3. Search: search for "Tutorial" returns 60 items
        val searchResults = dao.searchHistory("Tutorial").first()
        assertEquals(60, searchResults.size)
        assertTrue(searchResults.all { it.displayTitle.contains("Tutorial") })

        // 4. Paged search: first 10 items of "Music"
        val pagedSearch = dao.searchHistoryPaged("Music", limit = 10, offset = 0)
        assertEquals(10, pagedSearch.size)
        assertTrue(pagedSearch.all { it.displayTitle.contains("Music") })
    }

    private fun testRecord(id: String) = DownloadRecord(
        taskId = id,
        sourceUrl = "https://example.com/media/$id",
        displayTitle = "Title $id",
        platform = "generic",
        format = AvailableFormat(
            key = "video:1080",
            mode = DownloadMode.VIDEO,
            formatId = "137",
            extension = "mp4",
            height = 1080,
        ),
        status = DownloadTaskStatus.QUEUED,
        stage = DownloadProcessingStage.QUEUED,
        progressPercent = null,
        etaSeconds = null,
        output = null,
        createdAt = System.currentTimeMillis(),
        startedAt = null,
        completedAt = null,
        failureCategory = null,
        failureMessage = null,
        technicalFailureDetail = null,
    )
}
