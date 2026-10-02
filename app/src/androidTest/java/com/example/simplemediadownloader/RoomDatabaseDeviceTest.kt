package com.example.simplemediadownloader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDatabaseDeviceTest {
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
    fun databaseInitializesAndPerformsCrudOnDeviceSqlite() = runBlocking {
        val record = DownloadRecord(
            taskId = "device-test-1",
            sourceUrl = "https://www.tiktok.com/@creativecook/video/123456",
            displayTitle = "Delicious Ramen Recipe",
            platform = "TikTok",
            author = "creativecook",
            format = AvailableFormat(
                key = "video:22",
                mode = DownloadMode.VIDEO,
                formatId = "22",
                extension = "mp4",
                height = 720,
            ),
            status = DownloadTaskStatus.COMPLETED,
            stage = DownloadProcessingStage.COMPLETED,
            progressPercent = 100f,
            etaSeconds = 0L,
            output = DownloadOutput(
                contentUri = "content://media/external/video/media/99",
                mimeType = "video/mp4",
                fileSizeBytes = 15_000_000L,
                displayName = "ramen.mp4",
            ),
            createdAt = 1000L,
            startedAt = 1005L,
            completedAt = 1020L,
            failureCategory = null,
            failureMessage = null,
            technicalFailureDetail = null,
        )

        dao.insert(record.toEntity())

        val retrieved = dao.get("device-test-1")
        assertNotNull(retrieved)
        assertEquals("creativecook", retrieved?.author)
        assertEquals("Delicious Ramen Recipe", retrieved?.displayTitle)

        // Verify author search works on real device SQLite
        val searchResults = dao.searchHistory("creativecook").first()
        assertEquals(1, searchResults.size)
        assertEquals("device-test-1", searchResults[0].taskId)

        // Verify remove history works
        dao.removeHistoryEntry("device-test-1")
        assertTrue(dao.observeRecentHistory().first().isEmpty())
    }
}
