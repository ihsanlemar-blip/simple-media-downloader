package com.example.simplemediadownloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DownloadRequest(
    val id: String,
    val url: String,
    val title: String,
    val format: AvailableFormat,
)

sealed interface DownloadExecutionResult {
    data class Success(val output: String) : DownloadExecutionResult
    data object Cancelled : DownloadExecutionResult
    data class Failure(
        val message: String,
        val category: DownloadFailureCategory = DownloadFailureCategory.UNKNOWN_FAILURE,
        val technicalDetail: String? = null,
    ) : DownloadExecutionResult
}

interface DownloadEngine {
    suspend fun download(
        request: DownloadRequest,
        outputDirectory: File,
        onState: (DownloadState) -> Unit,
    ): DownloadExecutionResult

    suspend fun cancel(processId: String): Boolean
}

typealias YtDlpDownloadEngine = OkHttpDownloadEngine

class OkHttpDownloadEngine(
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val discoveryEngine: FormatDiscoveryEngine? = null,
) : DownloadEngine {
    private val activeCalls = ConcurrentHashMap<String, MutableList<Call>>()
    private val cancellationRequests = ConcurrentHashMap.newKeySet<String>()

    override suspend fun download(
        request: DownloadRequest,
        outputDirectory: File,
        onState: (DownloadState) -> Unit,
    ): DownloadExecutionResult = withContext(dispatchers.io) {
        if (cancellationRequests.contains(request.id)) {
            return@withContext DownloadExecutionResult.Cancelled
        }

        try {
            onState(DownloadState.Preparing(DownloadProgress(status = "Resolving stream…")))

            val resolvedFormat = if (request.format.isQuickPreset || !request.format.formatId.startsWith("http")) {
                val discovery = discoveryEngine?.discoverFormats(request.url)
                if (discovery is FormatDiscoveryResult.Success) {
                    val matching = if (request.format.mode == DownloadMode.VIDEO) {
                        discovery.catalog.videoFormats.firstOrNull { it.height == request.format.height }
                            ?: discovery.catalog.videoFormats.firstOrNull()
                    } else {
                        discovery.catalog.audioFormats.firstOrNull { it.mode == request.format.mode }
                            ?: discovery.catalog.audioFormats.firstOrNull()
                    }
                    matching ?: return@withContext DownloadExecutionResult.Failure(
                        message = "Could not find a downloadable stream for this format.",
                        category = DownloadFailureCategory.UNSUPPORTED_SITE,
                    )
                } else {
                    val rawMessage = (discovery as? FormatDiscoveryResult.Failure)?.message
                        ?: "Stream resolution failed."
                    val mapped = TechnicalFailureMapper.map(rawMessage, FailureOrigin.YT_DLP)
                    return@withContext DownloadExecutionResult.Failure(
                        message = mapped.message,
                        category = mapped.category,
                        technicalDetail = rawMessage,
                    )
                }
            } else {
                request.format
            }

            val safeTitle = MediaExportPolicy.sanitizeDisplayName(request.title, resolvedFormat.extension)
                .substringBeforeLast('.')
                .take(120)
            val baseName = safeTitle

            val isMuxing = resolvedFormat.companionAudioFormatId != null
            val finalFile = if (isMuxing) {
                File(outputDirectory, "$baseName.mp4")
            } else {
                File(outputDirectory, "$baseName.${resolvedFormat.extension}")
            }

            if (!isMuxing) {
                val transferKind = if (resolvedFormat.mode == DownloadMode.VIDEO) {
                    DownloadTransferKind.VIDEO
                } else {
                    DownloadTransferKind.AUDIO
                }
                onState(
                    DownloadState.Downloading(
                        DownloadProgress(
                            status = if (transferKind == DownloadTransferKind.VIDEO) {
                                "Downloading video…"
                            } else {
                                "Downloading audio…"
                            },
                        ),
                        transferKind,
                    ),
                )
                downloadStream(
                    taskId = request.id,
                    url = resolvedFormat.formatId,
                    destinationFile = finalFile,
                    transferKind = transferKind,
                    headers = resolvedFormat.httpHeaders,
                    onState = onState,
                )
                onState(DownloadState.Saving(DownloadProgress(status = "Saving media file…")))
            } else {
                val tempVideoFile = File(outputDirectory, "$baseName-video.tmp")
                val tempAudioFile = File(outputDirectory, "$baseName-audio.tmp")
                try {
                    onState(
                        DownloadState.Downloading(
                            DownloadProgress(status = "Downloading video…"),
                            DownloadTransferKind.VIDEO,
                        ),
                    )
                    val videoJob = async(dispatchers.io) {
                        downloadStream(
                            taskId = request.id,
                            url = resolvedFormat.formatId,
                            destinationFile = tempVideoFile,
                            transferKind = DownloadTransferKind.VIDEO,
                            headers = resolvedFormat.httpHeaders,
                            onState = onState,
                        )
                    }

                    onState(
                        DownloadState.Downloading(
                            DownloadProgress(status = "Downloading audio…"),
                            DownloadTransferKind.AUDIO,
                        ),
                    )
                    val audioJob = async(dispatchers.io) {
                        downloadStream(
                            taskId = request.id,
                            url = requireNotNull(resolvedFormat.companionAudioFormatId),
                            destinationFile = tempAudioFile,
                            transferKind = DownloadTransferKind.AUDIO,
                            headers = resolvedFormat.httpHeaders,
                            onState = onState,
                        )
                    }

                    videoJob.await()
                    audioJob.await()

                    onState(DownloadState.Merging(DownloadProgress(status = "Muxing video and audio…")))
                    val muxSuccess = MediaStreamMuxer.mux(tempVideoFile, tempAudioFile, finalFile)
                    if (!muxSuccess || !finalFile.exists() || finalFile.length() == 0L) {
                        return@withContext DownloadExecutionResult.Failure(
                            message = "Could not merge the video and audio tracks.",
                            category = DownloadFailureCategory.CONVERTER_FAILURE,
                        )
                    }

                    onState(DownloadState.Saving(DownloadProgress(status = "Saving media file…")))
                } finally {
                    tempVideoFile.delete()
                    tempAudioFile.delete()
                }
            }

            if (cancellationRequests.contains(request.id)) {
                finalFile.delete()
                DownloadExecutionResult.Cancelled
            } else if (finalFile.length() < 1024L) {
                finalFile.delete()
                DownloadExecutionResult.Failure(
                    message = "Downloaded media file is empty or invalid (${finalFile.length()} bytes).",
                    category = DownloadFailureCategory.UNKNOWN_FAILURE,
                )
            } else {
                DownloadExecutionResult.Success("${OUTPUT_MARKER}${finalFile.absolutePath}")
            }
        } catch (e: CancellationException) {
            DownloadExecutionResult.Cancelled
        } catch (e: Exception) {
            if (cancellationRequests.contains(request.id)) {
                DownloadExecutionResult.Cancelled
            } else {
                val rawMessage = e.localizedMessage ?: "Download encountered an error."
                val mapped = TechnicalFailureMapper.map(rawMessage, FailureOrigin.YT_DLP)
                DownloadExecutionResult.Failure(
                    message = mapped.message,
                    category = mapped.category,
                    technicalDetail = e.stackTraceToString().take(2000),
                )
            }
        } finally {
            activeCalls.remove(request.id)
            cancellationRequests.remove(request.id)
        }
    }

    private fun downloadStream(
        taskId: String,
        url: String,
        destinationFile: File,
        transferKind: DownloadTransferKind,
        headers: Map<String, String>? = null,
        onState: (DownloadState) -> Unit,
    ) {
        val probe = probeStream(url, headers)
        val totalBytes = probe.totalBytes
        if (probe.supportsRange && totalBytes != null && totalBytes > CHUNK_SIZE_BYTES) {
            try {
                downloadStreamChunked(
                    taskId = taskId,
                    url = url,
                    destinationFile = destinationFile,
                    totalBytes = totalBytes,
                    transferKind = transferKind,
                    headers = headers,
                    onState = onState,
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (cancellationRequests.contains(taskId)) {
                    throw CancellationException("Download cancelled")
                }
                // Fall back to continuous download if chunked streaming encounters an unrecoverable protocol error
            }
        }

        downloadStreamContinuous(
            taskId = taskId,
            url = url,
            destinationFile = destinationFile,
            transferKind = transferKind,
            headers = headers,
            onState = onState,
        )
    }

    private fun resolveHeaders(url: String, customHeaders: Map<String, String>?): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["User-Agent"] = USER_AGENT
        headers["Accept"] = "*/*"
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        when {
            host.contains("tiktok") || host.contains("musical.ly") || host.contains("tikwm") -> {
                headers["Referer"] = "https://www.tiktok.com/"
            }
            host.contains("instagram") || host.contains("cdninstagram") -> {
                headers["Referer"] = "https://www.instagram.com/"
            }
            host.contains("facebook") || host.contains("fbcdn") -> {
                headers["Referer"] = "https://www.facebook.com/"
            }
            host.contains("twitter") || host.contains("twimg") || host.contains("x.com") -> {
                headers["Referer"] = "https://twitter.com/"
            }
        }
        customHeaders?.let { headers.putAll(it) }
        return headers
    }

    private data class StreamProbe(
        val totalBytes: Long?,
        val supportsRange: Boolean,
    )

    private fun probeStream(url: String, customHeaders: Map<String, String>? = null): StreamProbe {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-0")
            resolveHeaders(url, customHeaders).forEach { (k, v) ->
                reqBuilder.addHeader(k, v)
            }
            val req = reqBuilder.build()
            client.newCall(req).execute().use { response ->
                if (response.code == 206) {
                    val contentRange = response.header("Content-Range")
                    val totalFromRange = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    StreamProbe(totalBytes = totalFromRange, supportsRange = true)
                } else if (response.isSuccessful) {
                    val cl = response.body?.contentLength()?.takeIf { it > 0 }
                    val acceptRanges = response.header("Accept-Ranges")
                    val isRange = acceptRanges.equals("bytes", ignoreCase = true)
                    StreamProbe(totalBytes = cl, supportsRange = isRange)
                } else {
                    StreamProbe(totalBytes = null, supportsRange = false)
                }
            }
        } catch (_: Exception) {
            StreamProbe(totalBytes = null, supportsRange = false)
        }
    }

    private fun downloadStreamChunked(
        taskId: String,
        url: String,
        destinationFile: File,
        totalBytes: Long,
        transferKind: DownloadTransferKind,
        headers: Map<String, String>? = null,
        onState: (DownloadState) -> Unit,
    ) {
        var downloadedBytes = 0L
        var lastUpdateAt = System.currentTimeMillis()
        var lastBytesAtUpdate = 0L

        java.io.RandomAccessFile(destinationFile, "rw").use { raf ->
            raf.setLength(0L)
            var currentStart = 0L

            while (currentStart < totalBytes) {
                if (cancellationRequests.contains(taskId)) {
                    throw CancellationException("Download cancelled")
                }

                val currentEnd = minOf(currentStart + CHUNK_SIZE_BYTES - 1, totalBytes - 1)
                var chunkSuccess = false
                var attempts = 0
                var lastChunkException: Exception? = null

                while (!chunkSuccess && attempts < 3) {
                    if (cancellationRequests.contains(taskId)) {
                        throw CancellationException("Download cancelled")
                    }
                    attempts++

                    val chunkRequestBuilder = Request.Builder()
                        .url(url)
                        .addHeader("Accept-Encoding", "identity")
                        .addHeader("Range", "bytes=$currentStart-$currentEnd")
                        .addHeader("Connection", "keep-alive")
                    resolveHeaders(url, headers).forEach { (k, v) ->
                        chunkRequestBuilder.addHeader(k, v)
                    }
                    val chunkRequest = chunkRequestBuilder.build()

                    val call = client.newCall(chunkRequest)
                    activeCalls.getOrPut(taskId, ::mutableListOf).add(call)

                    try {
                        call.execute().use { response ->
                            if (response.code != 206 && response.code != 200) {
                                throw java.io.IOException("HTTP error ${response.code} for chunk $currentStart-$currentEnd")
                            }
                            val body = response.body ?: throw java.io.IOException("Empty response body for chunk")
                            body.byteStream().use { streamIn ->
                                raf.seek(currentStart)
                                val buffer = ByteArray(64 * 1024)
                                var chunkBytesRead = 0L
                                while (true) {
                                    if (cancellationRequests.contains(taskId)) {
                                        call.cancel()
                                        throw CancellationException("Download cancelled")
                                    }
                                    val read = streamIn.read(buffer)
                                    if (read < 0) break
                                    raf.write(buffer, 0, read)
                                    chunkBytesRead += read
                                    downloadedBytes = currentStart + chunkBytesRead

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdateAt >= 300) {
                                        val durationSec = (now - lastUpdateAt) / 1000.0
                                        val bytesSince = downloadedBytes - lastBytesAtUpdate
                                        val speed = if (durationSec > 0) (bytesSince / durationSec).toLong() else 0L
                                        val eta = if (speed > 0) {
                                            (totalBytes - downloadedBytes).coerceAtLeast(0) / speed
                                        } else null
                                        val percent = (downloadedBytes.toDouble() * 100.0 / totalBytes.toDouble()).toFloat()

                                        val progress = DownloadProgress(
                                            percentage = percent,
                                            downloadedBytes = downloadedBytes,
                                            totalBytes = totalBytes,
                                            speedBytesPerSecond = speed,
                                            etaSeconds = eta,
                                            status = if (transferKind == DownloadTransferKind.VIDEO) {
                                                "Downloading video…"
                                            } else {
                                                "Downloading audio…"
                                            },
                                        )
                                        onState(DownloadState.Downloading(progress, transferKind))
                                        lastUpdateAt = now
                                        lastBytesAtUpdate = downloadedBytes
                                    }
                                }
                            }
                        }
                        chunkSuccess = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastChunkException = e
                        if (attempts < 3) {
                            try {
                                Thread.sleep(300)
                            } catch (_: InterruptedException) {}
                        }
                    } finally {
                        activeCalls[taskId]?.remove(call)
                    }
                }

                if (!chunkSuccess) {
                    throw lastChunkException ?: java.io.IOException("Failed downloading chunk $currentStart-$currentEnd after 3 attempts")
                }

                currentStart = currentEnd + 1
            }
        }
    }

    private fun downloadStreamContinuous(
        taskId: String,
        url: String,
        destinationFile: File,
        transferKind: DownloadTransferKind,
        headers: Map<String, String>? = null,
        onState: (DownloadState) -> Unit,
    ) {
        val httpRequestBuilder = Request.Builder()
            .url(url)
            .addHeader("Accept-Encoding", "identity")
            .addHeader("Connection", "keep-alive")
        resolveHeaders(url, headers).forEach { (k, v) ->
            httpRequestBuilder.addHeader(k, v)
        }
        val httpRequest = httpRequestBuilder.build()
        val call = client.newCall(httpRequest)
        activeCalls.getOrPut(taskId, ::mutableListOf).add(call)

        val response = call.execute()
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP error ${response.code}: ${response.message}")
        }
        val ct = response.header("Content-Type").orEmpty().lowercase()
        if (ct.contains("text/html")) {
            throw java.io.IOException("Stream URL returned an HTML page ($ct) instead of a media stream.")
        }

        val body = response.body ?: throw java.io.IOException("Empty response body")
        val totalBytes = body.contentLength().takeIf { it > 0 }
        var downloadedBytes = 0L
        var lastUpdateAt = System.currentTimeMillis()
        var lastBytesAtUpdate = 0L

        destinationFile.outputStream().use { fileOut ->
            body.byteStream().use { streamIn ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (cancellationRequests.contains(taskId)) {
                        call.cancel()
                        throw CancellationException("Download cancelled")
                    }
                    val read = streamIn.read(buffer)
                    if (read < 0) break
                    fileOut.write(buffer, 0, read)
                    downloadedBytes += read

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateAt >= 400) {
                        val durationSec = (now - lastUpdateAt) / 1000.0
                        val bytesSince = downloadedBytes - lastBytesAtUpdate
                        val speed = if (durationSec > 0) (bytesSince / durationSec).toLong() else 0L
                        val eta = if (totalBytes != null && speed > 0) {
                            (totalBytes - downloadedBytes).coerceAtLeast(0) / speed
                        } else null
                        val percent = totalBytes?.let {
                            (downloadedBytes.toDouble() * 100.0 / it.toDouble()).toFloat()
                        }

                        val progress = DownloadProgress(
                            percentage = percent,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSecond = speed,
                            etaSeconds = eta,
                            status = if (transferKind == DownloadTransferKind.VIDEO) {
                                "Downloading video…"
                            } else {
                                "Downloading audio…"
                            },
                        )
                        onState(DownloadState.Downloading(progress, transferKind))
                        lastUpdateAt = now
                        lastBytesAtUpdate = downloadedBytes
                    }
                }
            }
        }
    }

    override suspend fun cancel(processId: String): Boolean = withContext(dispatchers.io) {
        cancellationRequests.add(processId)
        activeCalls[processId]?.forEach { it.cancel() }
        true
    }

    companion object {
        const val OUTPUT_MARKER = "__SMD_FILE__"
        private const val CHUNK_SIZE_BYTES = 2 * 1024 * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
