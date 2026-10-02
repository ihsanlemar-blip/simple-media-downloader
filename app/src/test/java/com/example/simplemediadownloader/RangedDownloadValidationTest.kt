package com.example.simplemediadownloader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RangedDownloadValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class SimpleLocalServer(
        private val handler: (headers: Map<String, String>, path: String, output: OutputStream, socket: Socket) -> Unit,
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        private val acceptThread = thread(start = true, isDaemon = true) {
            try {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    thread(start = true, isDaemon = true) {
                        try {
                            val input = socket.getInputStream().bufferedReader()
                            val requestLine = input.readLine() ?: return@thread
                            val parts = requestLine.split(" ")
                            val path = parts.getOrNull(1) ?: "/"
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                val colon = line.indexOf(':')
                                if (colon > 0) {
                                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                                }
                            }
                            val out = socket.getOutputStream()
                            handler(headers, path, out, socket)
                        } catch (ignored: Exception) {
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        fun url(path: String = "/"): String = "http://127.0.0.1:$port$path"

        override fun close() {
            runCatching { serverSocket.close() }
        }
    }

    private fun createValidMp4Bytes(size: Int): ByteArray {
        val bytes = ByteArray(size) { (it % 256).toByte() }
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
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        SimpleLocalServer { _, _, out, _ ->
            val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $totalSize\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data)
            out.flush()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_200")
            val request = DownloadRequest(
                id = "task-200-fallback",
                url = server.url("/stream"),
                title = "Test Video",
                format = AvailableFormat(
                    key = "format-1",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/stream"),
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
    }

    @Test
    fun `rejects 206 response with missing Content-Range`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        SimpleLocalServer { headers, _, out, _ ->
            if (headers.containsKey("range")) {
                val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(data, 0, 1000)
                out.flush()
            } else {
                val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $totalSize\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(data)
                out.flush()
            }
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_missing_range")
            val request = DownloadRequest(
                id = "task-missing-range",
                url = server.url("/missing-range"),
                title = "Test Missing Range",
                format = AvailableFormat(
                    key = "format-missing",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/missing-range"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertFalse(File(destDir, "Test Missing Range-video.tmp").exists())
        }
    }

    @Test
    fun `rejects 206 response with malformed Content-Range`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        SimpleLocalServer { _, _, out, _ ->
            val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: invalid-format-content-range\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, 0, 1000)
            out.flush()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_malformed_range")
            val request = DownloadRequest(
                id = "task-malformed-range",
                url = server.url("/malformed-range"),
                title = "Test Malformed Range",
                format = AvailableFormat(
                    key = "format-malformed",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/malformed-range"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue("Malformed Content-Range must be rejected, got: $result", result !is DownloadExecutionResult.Success)
            assertFalse(File(destDir, "Test Malformed Range-video.tmp").exists())
        }
    }

    @Test
    fun `rejects 206 response with wrong range start`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        SimpleLocalServer { _, _, out, _ ->
            val bodyLen = 2097052
            val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes 100-2097151/$totalSize\r\nContent-Length: $bodyLen\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, 0, bodyLen)
            out.flush()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_wrong_start")
            val request = DownloadRequest(
                id = "task-wrong-start",
                url = server.url("/wrong-start"),
                title = "Test Wrong Start",
                format = AvailableFormat(
                    key = "format-wrong-start",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/wrong-start"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue("Result must not succeed with wrong range start, got $result", result !is DownloadExecutionResult.Success)
        }
    }

    @Test
    fun `rejects 206 response with wrong range end`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        SimpleLocalServer { _, _, out, _ ->
            val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes 0-1000/$totalSize\r\nContent-Length: 1001\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, 0, 1001)
            out.flush()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_wrong_end")
            val request = DownloadRequest(
                id = "task-wrong-end",
                url = server.url("/wrong-end"),
                title = "Test Wrong End",
                format = AvailableFormat(
                    key = "format-wrong-end",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/wrong-end"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue("Result must not succeed with wrong range end", result !is DownloadExecutionResult.Success)
        }
    }

    @Test
    fun `detects truncated short chunk and rejects`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        SimpleLocalServer { _, _, out, socket ->
            val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes 0-2097151/$totalSize\r\nContent-Length: 2097152\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, 0, 500)
            out.flush()
            socket.close()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_short_chunk")
            val request = DownloadRequest(
                id = "task-short-chunk",
                url = server.url("/short-chunk"),
                title = "Test Short Chunk",
                format = AvailableFormat(
                    key = "format-short-chunk",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/short-chunk"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue(result !is DownloadExecutionResult.Success)
        }
    }

    @Test
    fun `rejects download when ETag changes between chunks`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)
        val chunkCounter = AtomicInteger(0)

        SimpleLocalServer { headers, _, out, _ ->
            val count = chunkCounter.incrementAndGet()
            val range = headers["range"] ?: "bytes=0-0"
            val match = Regex("""bytes=(\d+)-(\d+)""").find(range)
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt() ?: (start + 1)
            val len = end - start + 1

            val etag = if (count <= 2) "\"etag-v1\"" else "\"etag-v2-changed\""
            val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nETag: $etag\r\nContent-Range: bytes $start-$end/$totalSize\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, start, len)
            out.flush()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_etag")
            val request = DownloadRequest(
                id = "task-etag-change",
                url = server.url("/etag-change"),
                title = "Test ETag",
                format = AvailableFormat(
                    key = "format-etag",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/etag-change"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue("ETag change must cause chunked download to fail", result !is DownloadExecutionResult.Success)
        }
    }

    @Test
    fun `rejects download when Last-Modified changes between chunks`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)
        val chunkCounter = AtomicInteger(0)

        SimpleLocalServer { headers, _, out, _ ->
            val count = chunkCounter.incrementAndGet()
            val range = headers["range"] ?: "bytes=0-0"
            val match = Regex("""bytes=(\d+)-(\d+)""").find(range)
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt() ?: (start + 1)
            val len = end - start + 1

            val lastMod = if (count <= 2) "Wed, 21 Oct 2025 07:28:00 GMT" else "Thu, 22 Oct 2026 08:30:00 GMT"
            val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nLast-Modified: $lastMod\r\nContent-Range: bytes $start-$end/$totalSize\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, start, len)
            out.flush()
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_lastmod")
            val request = DownloadRequest(
                id = "task-lastmod-change",
                url = server.url("/last-modified-change"),
                title = "Test LastMod",
                format = AvailableFormat(
                    key = "format-lastmod",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/last-modified-change"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue("Last-Modified change must cause chunked download to fail", result !is DownloadExecutionResult.Success)
        }
    }

    @Test
    fun `recovers from transient failure with retry`() = runBlocking {
        val totalSize = 3 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)
        val attemptCount = AtomicInteger(0)

        SimpleLocalServer { headers, _, out, socket ->
            val attempt = attemptCount.incrementAndGet()
            val range = headers["range"] ?: "bytes=0-0"
            val match = Regex("""bytes=(\d+)-(\d+)""").find(range)
            val start = match?.groupValues?.get(1)?.toInt() ?: 0
            val end = match?.groupValues?.get(2)?.toInt() ?: (start + 1)
            val len = end - start + 1

            if (attempt == 2) {
                val header = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.flush()
                socket.close()
            } else {
                val header = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$end/$totalSize\r\nContent-Length: $len\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.write(data, start, len)
                out.flush()
            }
        }.use { server ->
            val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
            val destDir = tempFolder.newFolder("output_retry")
            val request = DownloadRequest(
                id = "task-retry-success",
                url = server.url("/retry-stream"),
                title = "Test Retry",
                format = AvailableFormat(
                    key = "format-retry",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/retry-stream"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertTrue("Download should succeed after retrying transient error, got: $result", result is DownloadExecutionResult.Success)
        }
    }

    @Test
    fun `cancellation during transfer terminates cleanly and deletes partial file`() = runBlocking {
        val totalSize = 10 * 1024 * 1024
        val data = createValidMp4Bytes(totalSize)

        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)
        val destDir = tempFolder.newFolder("output_cancel")
        val taskId = "task-cancel-during-transfer"

        SimpleLocalServer { _, _, out, socket ->
            val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: $totalSize\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.write(data, 0, 64 * 1024)
            out.flush()
            runBlocking {
                engine.cancel(taskId)
            }
            try {
                out.write(data, 64 * 1024, 64 * 1024)
                out.flush()
            } catch (ignored: Exception) {
            }
            socket.close()
        }.use { server ->
            val request = DownloadRequest(
                id = taskId,
                url = server.url("/cancel-stream"),
                title = "Test Cancel",
                format = AvailableFormat(
                    key = "format-cancel",
                    mode = DownloadMode.VIDEO,
                    formatId = server.url("/cancel-stream"),
                    extension = "mp4",
                    estimatedSizeBytes = totalSize.toLong(),
                ),
            )

            val result = engine.download(request, destDir) {}
            assertEquals(DownloadExecutionResult.Cancelled, result)

            val destFile = File(destDir, "Test Cancel.mp4")
            assertFalse("Partial file must be deleted upon cancellation", destFile.exists())
        }
    }
}
