package com.example.simplemediadownloader

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `filename sanitization preserves meaningful unicode and safe extension`() {
        val sanitized = MediaExportPolicy.sanitizeDisplayName(
            "  My:* Great\u0000 Video...MP4",
            "mkv",
        )
        val unicode = MediaExportPolicy.sanitizeDisplayName("مرحبا بالعالم.webm", "mp4")

        assertTrue(sanitized.startsWith("My__ Great_ Video"))
        assertTrue(sanitized.endsWith(".mp4"))
        assertFalse(sanitized.any { it in "\\/:*?\"<>|" || it.isISOControl() })
        assertEquals("مرحبا بالعالم.webm", unicode)
        assertEquals("CON_.mp3", MediaExportPolicy.sanitizeDisplayName("CON.mp3", "mp3"))
    }

    @Test
    fun `mime resolution distinguishes audio and video containers`() {
        assertEquals("video/mp4", MediaExportPolicy.mimeType("movie.mp4", DownloadMode.VIDEO))
        assertEquals("audio/mpeg", MediaExportPolicy.mimeType("song.mp3", DownloadMode.AUDIO_MP3))
        assertEquals("video/webm", MediaExportPolicy.mimeType("movie.webm", DownloadMode.VIDEO))
        assertEquals(
            "audio/webm",
            MediaExportPolicy.mimeType("audio.webm", DownloadMode.AUDIO_ORIGINAL),
        )
        assertEquals(
            "application/octet-stream",
            MediaExportPolicy.mimeType("media.unknown", DownloadMode.VIDEO),
        )
    }

    @Test
    fun `collision names use predictable case insensitive numeric suffixes`() {
        val existing = setOf("Title.mp4", "title (1).MP4", "other.mp4")

        assertEquals(
            "Title (2).mp4",
            MediaExportPolicy.collisionSafeName("Title.mp4", existing),
        )
        assertEquals(
            "Fresh.mp4",
            MediaExportPolicy.collisionSafeName("Fresh.mp4", existing),
        )
    }

    @Test
    fun `pending row values and collection match public media type`() {
        val values = MediaExportPolicy.pendingValues(
            displayName = "Title.mp4",
            mimeType = "video/mp4",
            relativePath = "Movies/MediaDownloader",
            taskId = "stable-id",
        )
        val target = MediaExportPolicy.targetFor("video/mp4")
        val output = MediaExportPolicy.output(
            contentUri = Uri.parse("content://media/external_primary/video/media/42"),
            displayName = "Title.mp4",
            mimeType = "video/mp4",
            fileSizeBytes = 4_096L,
        )

        assertEquals("Title.mp4", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("video/mp4", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(
            "Movies/MediaDownloader",
            values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH),
        )
        assertEquals(1, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("Movies/MediaDownloader", target.relativePath)
        assertEquals("content://media/external_primary/video/media/42", output.contentUri)
        assertEquals(4_096L, output.fileSizeBytes)
    }

    @Test
    fun `temporary file exports to content uri and cleanup removes workspace`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspaceRoot = temporaryFolder.newFolder("workspaces")
        val writer = FakeMediaStoreWriter()
        val exporter = DownloadsStorageExporter(
            context = context,
            dispatchers = AppDispatchers(Dispatchers.Unconfined),
            workspaceRoot = workspaceRoot,
            mediaStoreWriter = writer,
            availableBytes = { Long.MAX_VALUE },
        )
        val request = request("stable-task")
        val destination = exporter.prepareDestination(request).getOrThrow()
        val source = File(destination.directory, "Meaningful Title.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val output = exporter.exportCompletedFile(
            request,
            destination,
            YtDlpDownloadEngine.OUTPUT_MARKER + source.absolutePath,
        ).getOrThrow()
        exporter.cleanup(destination)

        assertEquals("content://media/external_primary/video/media/42", output.contentUri)
        assertEquals("Meaningful Title.mp4", output.displayName)
        assertEquals("video/mp4", output.mimeType)
        assertEquals(4L, output.fileSizeBytes)
        assertEquals("stable-task", writer.taskId)
        assertFalse(destination.directory.exists())
    }

    @Test
    fun `reasonable size estimate fails before large work when storage is insufficient`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val exporter = DownloadsStorageExporter(
                context = context,
                dispatchers = AppDispatchers(Dispatchers.Unconfined),
                workspaceRoot = temporaryFolder.newFolder("low-space"),
                mediaStoreWriter = FakeMediaStoreWriter(),
                availableBytes = { 16L * 1024 * 1024 },
            )
            val request = request("large").copy(
                format = request("large").format.copy(
                    estimatedSizeBytes = 100L * 1024 * 1024,
                    requiresDownscale = true,
                ),
            )

            val result = exporter.prepareDestination(request)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Not enough"))
        }

    @Test
    fun `service recovery delegates abandoned pending row cleanup off the public contract`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val writer = FakeMediaStoreWriter().apply { abandonedRows = 2 }
            val exporter = DownloadsStorageExporter(
                context = context,
                dispatchers = AppDispatchers(Dispatchers.Unconfined),
                workspaceRoot = temporaryFolder.newFolder("abandoned-rows"),
                mediaStoreWriter = writer,
                availableBytes = { Long.MAX_VALUE },
            )

            assertEquals(2, exporter.cleanupAbandonedExports())
            assertEquals(1, writer.cleanupCalls)
        }

    @Test
    fun `failed stream deletes incomplete pending media store row`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = FailingMediaProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
        val source = temporaryFolder.newFile("pending.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val result = runCatching {
            ContentResolverMediaStoreWriter(context.contentResolver).write(
                source = source,
                requestedDisplayName = "pending.mp4",
                mimeType = "video/mp4",
                taskId = "pending-task",
            )
        }

        assertTrue(result.isFailure)
        assertEquals(1, provider.insertedValues!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(provider.insertedUri, provider.deletedUri)
    }

    @Test
    fun `recovery deletes only marked abandoned pending rows from output collections`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val provider = PendingRowsProvider()
            ShadowContentResolver.registerProviderInternal("media", provider)

            val removed = ContentResolverMediaStoreWriter(context.contentResolver)
                .cleanupAbandonedPendingRows()

            assertEquals(3, removed)
            assertEquals(3, provider.deletedUris.size)
            assertTrue(provider.selections.all { it.contains(MediaStore.MediaColumns.IS_PENDING) })
            assertTrue(
                provider.selectionArgs.all {
                    it.contentEquals(arrayOf("1", "${MediaExportPolicy.PENDING_TITLE_PREFIX}%"))
                },
            )
        }

    private fun request(id: String) = DownloadRequest(
        id = id,
        url = "https://example.test/video",
        title = "Video",
        format = AvailableFormat(
            key = "video:18",
            mode = DownloadMode.VIDEO,
            formatId = "18",
            extension = "mp4",
            height = 360,
        ),
    )

    private class FakeMediaStoreWriter : MediaStoreWriter {
        var taskId: String? = null
        var abandonedRows = 0
        var cleanupCalls = 0

        override suspend fun write(
            source: File,
            requestedDisplayName: String,
            mimeType: String,
            taskId: String,
        ): DownloadOutput {
            this.taskId = taskId
            return DownloadOutput(
                contentUri = "content://media/external_primary/video/media/42",
                mimeType = mimeType,
                fileSizeBytes = source.length(),
                displayName = requestedDisplayName,
            )
        }

        override suspend fun exists(contentUri: String): Boolean = true

        override suspend fun cleanupAbandonedPendingRows(): Int {
            cleanupCalls++
            return abandonedRows
        }
    }

    private class FailingMediaProvider : ContentProvider() {
        var insertedValues: ContentValues? = null
        var insertedUri: Uri? = null
        var deletedUri: Uri? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(MediaStore.MediaColumns.DISPLAY_NAME))

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            insertedValues = ContentValues(values)
            return Uri.withAppendedPath(uri, "42").also { insertedUri = it }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletedUri = uri
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            throw FileNotFoundException("simulated output failure")
        }
    }

    private class PendingRowsProvider : ContentProvider() {
        val selections = mutableListOf<String>()
        val selectionArgs = mutableListOf<Array<out String>>()
        val deletedUris = mutableListOf<Uri>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(MediaStore.MediaColumns._ID)).apply {
            selections += selection.orEmpty()
            this@PendingRowsProvider.selectionArgs += selectionArgs.orEmpty()
            addRow(arrayOf(7L))
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletedUris += uri
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
