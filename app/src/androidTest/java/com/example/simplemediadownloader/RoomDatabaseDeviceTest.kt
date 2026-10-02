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
        val format = AvailableFormat(
            key = "video:22",
            mode = DownloadMode.VIDEO,
            formatId = "22",
            extension = "mp4",
            height = 720,
        )
        val entity = DownloadTaskEntity(
            taskId = "device-test-1",
            sourceUrl = "https://www.tiktok.com/@creativecook/video/123456",
            displayTitle = "Delicious Ramen Recipe",
            displayAuthor = "creativecook",
            platform = "TikTok",
            formatKey = format.key,
            formatId = format.formatId,
            downloadMode = format.mode.name,
            fileExtension = format.extension,
            width = null,
            height = format.height,
            fps = null,
            bitrateKbps = null,
            codec = null,
            formatNote = "",
            sizeIsApproximate = false,
            sourceHeight = format.height,
            requiresDownscale = false,
            isQuickPreset = false,
            status = DownloadTaskStatus.COMPLETED.name,
            processingStage = DownloadProcessingStage.COMPLETED.name,
            progressPercent = 100f,
            etaSeconds = 0L,
            completedUri = "content://media/external/video/media/99",
            completedPath = "/storage/emulated/0/Movies/MediaDownloader/ramen.mp4",
            completedFileName = "ramen.mp4",
            completedMimeType = "video/mp4",
            completedSizeBytes = 15_000_000L,
            createdAt = 1000L,
            startedAt = 1005L,
            completedAt = 1020L,
        )

        dao.insert(entity)

        val retrieved = dao.get("device-test-1")
        assertNotNull(retrieved)
        assertEquals("creativecook", retrieved?.displayAuthor)
        assertEquals("Delicious Ramen Recipe", retrieved?.displayTitle)

        // Verify author search works on real device SQLite
        val searchResults = dao.searchHistory("creativecook").first()
        assertEquals(1, searchResults.size)
        assertEquals("device-test-1", searchResults[0].taskId)

        // Verify delete works
        dao.delete("device-test-1")
        assertTrue(dao.observeRecentHistory().first().isEmpty())
    }
}
