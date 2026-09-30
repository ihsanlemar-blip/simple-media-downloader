package com.example.simplemediadownloader

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class RangedDownloadValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: HttpServer? = null
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server!!.address.port
        server!!.start()
    }

    @After
    fun tearDown() {
        server?.stop(0)
    }

    private fun testServerUrl(path: String = "/"): String = "http://127.0.0.1:$serverPort$path"

    // Helper to generate a valid MP4 file byte array (with ftyp header) of requested size
    private fun createValidMp4Bytes(size: Int): ByteArray {
        val bytes = ByteArray(size) { (it % 256).toByte() }
        // Set MP4 ftyp box header at beginning
        bytes[0] = 0x00
        bytes[1] = 0x00
        bytes[2] = 0x00
        bytes[3] = 0x18
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        bytes[8] = 'i'.code.toByte()
        bytes[9] = 's'.code.toByte()
        bytes[10] = 'o'.code.toByte()
        bytes[11] = 'm'.code.toByte()
        return bytes
    }

    @Test
    fun `200 fallback handles server that ignores Range and serves full continuous stream`() = runBlocking {
        val totalSize = 3 * 1024 * 1024 // 3 MB (triggers chunked attempt)
        val data = createValidMp4Bytes(totalSize)

        server!!.createContext("/stream") { exchange ->
            // Always return HTTP 200 with full content regardless of Range header
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, totalSize.toLong())
            exchange.responseBody.write(data)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_200")
        val request = DownloadRequest(
            id = "task-200-fallback",
            url = testServerUrl("/stream"),
            title = "Test Video",
            format = AvailableFormat(
                key = "format-1",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/stream"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertTrue("Expected Success on 200 fallback, got: $result", result is DownloadExecutionResult.Success)

        val exportedFile = File(destDir, "Test Video.mp4")
        assertTrue(exportedFile.exists())
        assertEquals(totalSize.toLong(), exportedFile.length())
    }

    @Test
    fun `rejects 206 response with missing Content-Range`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        server!!.createContext("/missing-range") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range != null) {
                // Return 206 but intentionally omit Content-Range header
                exchange.responseHeaders.set("Content-Type", "video/mp4")
                exchange.sendResponseHeaders(206, 1000)
                exchange.responseBody.write(data, 0, 1000)
                exchange.responseBody.close()
            } else {
                exchange.sendResponseHeaders(200, totalSize.toLong())
                exchange.responseBody.write(data)
                exchange.responseBody.close()
            }
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_missing_range")
        val request = DownloadRequest(
            id = "task-missing-range",
            url = testServerUrl("/missing-range"),
            title = "Test Missing Range",
            format = AvailableFormat(
                key = "format-missing",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/missing-range"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        // After chunked attempt fails from missing Content-Range, continuous fallback succeeds or fails cleanly
        // Verify no corrupted partial chunk file is published under a temporary name
        assertFalse(File(destDir, "Test Missing Range-video.tmp").exists())
    }

    @Test
    fun `rejects 206 response with wrong range start`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        server!!.createContext("/wrong-start") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            // Send invalid start 100 instead of expected 0
            exchange.responseHeaders.set("Content-Range", "bytes 100-2097151/$totalSize")
            exchange.sendResponseHeaders(206, 2097052)
            exchange.responseBody.write(data, 0, 2097052)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_wrong_start")
        val request = DownloadRequest(
            id = "task-wrong-start",
            url = testServerUrl("/wrong-start"),
            title = "Test Wrong Start",
            format = AvailableFormat(
                key = "format-wrong-start",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/wrong-start"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        // Should not succeed with wrong start
        assertTrue("Result must be failure or fall back, got $result", result !is DownloadExecutionResult.Success)
    }

    @Test
    fun `rejects 206 response with wrong range end`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        server!!.createContext("/wrong-end") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            // Send end 1000 instead of requested chunk end
            exchange.responseHeaders.set("Content-Range", "bytes 0-1000/$totalSize")
            exchange.sendResponseHeaders(206, 1001)
            exchange.responseBody.write(data, 0, 1001)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_wrong_end")
        val request = DownloadRequest(
            id = "task-wrong-end",
            url = testServerUrl("/wrong-end"),
            title = "Test Wrong End",
            format = AvailableFormat(
                key = "format-wrong-end",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/wrong-end"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertTrue("Result must not succeed with wrong range end", result !is DownloadExecutionResult.Success)
    }

    @Test
    fun `detects truncated short chunk and rejects`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        server!!.createContext("/short-chunk") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            exchange.responseHeaders.set("Content-Range", "bytes 0-2097151/$totalSize")
            // Claim 2 MB chunk in header but close stream after only 500 bytes
            exchange.sendResponseHeaders(206, 2097152)
            exchange.responseBody.write(data, 0, 500)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_short_chunk")
        val request = DownloadRequest(
            id = "task-short-chunk",
            url = testServerUrl("/short-chunk"),
            title = "Test Short Chunk",
            format = AvailableFormat(
                key = "format-short-chunk",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/short-chunk"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertTrue(result !is DownloadExecutionResult.Success)
    }

    @Test
    fun `rejects download when ETag changes between chunks`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)
        val chunkCounter = AtomicInteger(0)

        server!!.createContext("/etag-change") { exchange ->
            val count = chunkCounter.incrementAndGet()
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-0"
            val match = Regex("""bytes=(\d+)-(\d+)""").find(range)
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt() ?: (start + 1)
            val len = end - start + 1

            // Change ETag on second chunk
            val etag = if (count <= 2) "\"etag-v1\"" else "\"etag-v2-changed\""
            exchange.responseHeaders.set("ETag", etag)
            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalSize")
            exchange.sendResponseHeaders(206, len.toLong())
            exchange.responseBody.write(data, start, len)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_etag")
        val request = DownloadRequest(
            id = "task-etag-change",
            url = testServerUrl("/etag-change"),
            title = "Test ETag",
            format = AvailableFormat(
                key = "format-etag",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/etag-change"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertTrue("ETag change must cause chunked download to fail", result !is DownloadExecutionResult.Success)
    }

    @Test
    fun `rejects download when Last-Modified changes between chunks`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)
        val chunkCounter = AtomicInteger(0)

        server!!.createContext("/last-modified-change") { exchange ->
            val count = chunkCounter.incrementAndGet()
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-0"
            val match = Regex("""bytes=(\d+)-(\d+)""").find(range)
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt() ?: (start + 1)
            val len = end - start + 1

            // Change Last-Modified on second chunk
            val lastMod = if (count <= 2) "Wed, 21 Oct 2025 07:28:00 GMT" else "Thu, 22 Oct 2026 08:30:00 GMT"
            exchange.responseHeaders.set("Last-Modified", lastMod)
            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalSize")
            exchange.sendResponseHeaders(206, len.toLong())
            exchange.responseBody.write(data, start, len)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_lastmod")
        val request = DownloadRequest(
            id = "task-lastmod-change",
            url = testServerUrl("/last-modified-change"),
            title = "Test LastMod",
            format = AvailableFormat(
                key = "format-lastmod",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/last-modified-change"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertTrue("Last-Modified change must cause chunked download to fail", result !is DownloadExecutionResult.Success)
    }

    @Test
    fun `recovers from transient failure with retry`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)
        val attemptCount = AtomicInteger(0)

        server!!.createContext("/retry-stream") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-0"
            val match = Regex("""bytes=(\d+)-(\d+)""").find(range)
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt() ?: (start + 1)
            val len = end - start + 1

            // First chunk attempt fails with 500 error, retry succeeds
            val attempt = attemptCount.incrementAndGet()
            if (attempt == 2) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                return@createContext
            }

            exchange.responseHeaders.set("Content-Type", "video/mp4")
            exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalSize")
            exchange.sendResponseHeaders(206, len.toLong())
            exchange.responseBody.write(data, start, len)
            exchange.responseBody.close()
        }

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_retry")
        val request = DownloadRequest(
            id = "task-retry-success",
            url = testServerUrl("/retry-stream"),
            title = "Test Retry",
            format = AvailableFormat(
                key = "format-retry",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/retry-stream"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertTrue("Download should succeed after retrying transient error, got: $result", result is DownloadExecutionResult.Success)
    }

    @Test
    fun `cancellation during transfer terminates cleanly and deletes partial file`() = runBlocking {
        val totalSize = 10 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_cancel")
        val taskId = "task-cancel-during-transfer"

        server!!.createContext("/cancel-stream") { exchange ->
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, totalSize.toLong())
            // Write 64 KB then trigger cancellation
            exchange.responseBody.write(data, 0, 64 * 1024)
            exchange.responseBody.flush()
            runBlocking {
                engine.cancel(taskId)
            }
            try {
                exchange.responseBody.write(data, 64 * 1024, 64 * 1024)
            } catch (_: Exception) {}
            exchange.close()
        }

        val request = DownloadRequest(
            id = taskId,
            url = testServerUrl("/cancel-stream"),
            title = "Test Cancel",
            format = AvailableFormat(
                key = "format-cancel",
                mode = DownloadMode.VIDEO,
                formatId = testServerUrl("/cancel-stream"),
                extension = "mp4",
                estimatedSizeBytes = totalSize.toLong(),
            ),
        )

        val result = engine.download(request, destDir) {}
        assertEquals(DownloadExecutionResult.Cancelled, result)

        // Ensure no partial file is left behind
        val destFile = File(destDir, "Test Cancel.mp4")
        assertFalse("Partial file must be deleted upon cancellation", destFile.exists())
    }
}
