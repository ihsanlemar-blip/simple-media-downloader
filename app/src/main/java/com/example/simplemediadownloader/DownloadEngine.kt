package com.example.simplemediadownloader

import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.Locale
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

internal sealed interface VideoMatchResult {
    data class Match(val format: AvailableFormat) : VideoMatchResult
    data class OnlyHigherResolutionsExist(val requestedHeight: Int, val availableHeights: List<Int>) : VideoMatchResult
    data object NoFormatsAvailable : VideoMatchResult
}

internal fun selectBestVideoFormat(
    catalog: MediaFormatCatalog,
    requestedHeight: Int,
    requestedKey: String? = null,
): VideoMatchResult {
    val formats = catalog.videoFormats
    if (formats.isEmpty()) return VideoMatchResult.NoFormatsAvailable

    if (requestedKey != null) {
        val byKey = formats.firstOrNull { it.key == requestedKey }
        if (byKey != null) return VideoMatchResult.Match(byKey)
    }

    if (requestedHeight <= 0) {
        return VideoMatchResult.Match(formats.first())
    }

    val exact = formats.firstOrNull { it.height == requestedHeight }
    if (exact != null) return VideoMatchResult.Match(exact)

    val lowerOrEqual = formats.filter { it.height in 1..requestedHeight }.maxByOrNull { it.height }
    if (lowerOrEqual != null) return VideoMatchResult.Match(lowerOrEqual)

    val higherOnly = formats.filter { it.height > requestedHeight }
    if (higherOnly.isNotEmpty()) {
        val availableHeights = higherOnly.map { it.height }.distinct().sorted()
        return VideoMatchResult.OnlyHigherResolutionsExist(requestedHeight, availableHeights)
    }

    return VideoMatchResult.Match(formats.first())
}

internal fun selectBestAudioFormat(
    catalog: MediaFormatCatalog,
    requestedMode: DownloadMode,
    requestedBitrateKbps: Int,
    requestedKey: String? = null,
): AvailableFormat? {
    val formats = catalog.audioFormats
    if (formats.isEmpty()) return null

    if (requestedKey != null) {
        val byKey = formats.firstOrNull { it.key == requestedKey }
        if (byKey != null) return byKey
    }

    val modeFormats = formats.filter { it.mode == requestedMode }.ifEmpty { formats }
    if (requestedBitrateKbps > 0) {
        return modeFormats.minByOrNull { kotlin.math.abs(it.bitrateKbps - requestedBitrateKbps) }
            ?: modeFormats.firstOrNull()
    }
    return modeFormats.firstOrNull()
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
    private val activeCalls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val cancellationRequests = ConcurrentHashMap.newKeySet<String>()

    internal fun registerCall(taskId: String, call: Call): Boolean {
        if (cancellationRequests.contains(taskId)) {
            call.cancel()
            return false
        }
        val set = activeCalls.computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }
        return synchronized(set) {
            if (cancellationRequests.contains(taskId)) {
                call.cancel()
                false
            } else {
                set.add(call)
                true
            }
        }
    }

    internal fun unregisterCall(taskId: String, call: Call) {
        activeCalls[taskId]?.let { set ->
            synchronized(set) {
                set.remove(call)
                if (set.isEmpty()) {
                    activeCalls.remove(taskId, set)
                }
            }
        }
    }

    internal fun activeCallCount(taskId: String): Int =
        activeCalls[taskId]?.let { set -> synchronized(set) { set.size } } ?: 0

    private val httpClient: OkHttpClient = client.newBuilder()
        .dns(SafeDns())
        .addInterceptor(SecurityInterceptor(allowCleartextHttp = false))
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val host = req.url.host.lowercase()
            val b = req.newBuilder()
            if (req.header("Referer") == null) {
                when {
                    host.contains("tiktok") || host.contains("musical.ly") || host.contains("tikwm") ->
                        b.header("Referer", "https://www.tiktok.com/")
                    host.contains("instagram") || host.contains("cdninstagram") ->
                        b.header("Referer", "https://www.instagram.com/")
                    host.contains("facebook") || host.contains("fbcdn") ->
                        b.header("Referer", "https://www.facebook.com/")
                    host.contains("twitter") || host.contains("twimg") || host.contains("x.com") ->
                        b.header("Referer", "https://twitter.com/")
                    host.contains("reddit") || host.contains("redd.it") ->
                        b.header("Referer", "https://www.reddit.com/")
                    host.contains("googlevideo") || host.contains("youtube") ->
                        b.header("Referer", "https://www.youtube.com/")
                }
            }
            chain.proceed(b.build())
        }
        .build()

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

            var resolvedTitle = request.title
            var resolvedFormat = if (request.format.isQuickPreset || !request.format.formatId.startsWith("http")) {
                val discovery = discoveryEngine?.discoverFormats(request.url)
                if (discovery is FormatDiscoveryResult.Success) {
                    val discoveredTitle = discovery.catalog.title.takeIf {
                        it.isNotBlank() && it != "Fast native downloads" && it != "Available formats"
                    }
                    if (discoveredTitle != null && (resolvedTitle.isBlank() || resolvedTitle.endsWith("video", ignoreCase = true) || resolvedTitle.endsWith("audio", ignoreCase = true))) {
                        resolvedTitle = discoveredTitle
                    }
                    val matching = if (request.format.mode == DownloadMode.VIDEO) {
                        when (val match = selectBestVideoFormat(discovery.catalog, request.format.height, request.format.key)) {
                            is VideoMatchResult.Match -> match.format
                            is VideoMatchResult.OnlyHigherResolutionsExist -> {
                                return@withContext DownloadExecutionResult.Failure(
                                    message = "Requested resolution (${match.requestedHeight}p) is unavailable and only higher resolutions (${match.availableHeights.joinToString("p, ")}p) exist.",
                                    category = DownloadFailureCategory.UNSUPPORTED_SITE,
                                )
                            }
                            is VideoMatchResult.NoFormatsAvailable -> null
                        }
                    } else {
                        selectBestAudioFormat(discovery.catalog, request.format.mode, request.format.bitrateKbps, request.format.key)
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

            // Fallback header resolution if format has no httpHeaders (e.g. from a legacy queued task or format without cookies)
            if (resolvedFormat.httpHeaders.isNullOrEmpty()) {
                val host = runCatching { java.net.URI(request.url).host.orEmpty().lowercase() }.getOrDefault("")
                if (host.contains("tiktok") || host.contains("musical.ly") || host.contains("instagram")) {
                    val discovery = discoveryEngine?.discoverFormats(request.url)
                    if (discovery is FormatDiscoveryResult.Success) {
                        val matching = if (resolvedFormat.mode == DownloadMode.VIDEO) {
                            when (val match = selectBestVideoFormat(discovery.catalog, resolvedFormat.height, resolvedFormat.key)) {
                                is VideoMatchResult.Match -> match.format
                                else -> null
                            }
                        } else {
                            selectBestAudioFormat(discovery.catalog, resolvedFormat.mode, resolvedFormat.bitrateKbps, resolvedFormat.key)
                        }
                        if (matching?.httpHeaders != null) {
                            resolvedFormat = resolvedFormat.copy(
                                httpHeaders = matching.httpHeaders,
                                formatId = if (matching.formatId.isNotBlank()) matching.formatId else resolvedFormat.formatId,
                            )
                        }
                    }
                }
            }

            val safeDisplayName = MediaExportPolicy.sanitizeDisplayName(resolvedTitle, resolvedFormat.extension)
            val baseName = safeDisplayName.substringBeforeLast('.')

            val isMuxing = resolvedFormat.companionAudioFormatId != null
            var actualFinalFile = if (isMuxing) {
                File(outputDirectory, "$baseName.mp4")
            } else {
                File(outputDirectory, "$baseName.${resolvedFormat.extension}")
            }
            var actualExtension = if (isMuxing) "mp4" else resolvedFormat.extension

            var downloadAttempts = 0
            while (downloadAttempts < 2) {
                try {
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
                        val isAudioOnlyRequest = resolvedFormat.mode != DownloadMode.VIDEO
                        val downloadTargetFile = if (isAudioOnlyRequest) {
                            File(outputDirectory, "$baseName-audio-raw.tmp")
                        } else {
                            actualFinalFile
                        }
                        try {
                            downloadStream(
                                taskId = request.id,
                                url = resolvedFormat.formatId,
                                destinationFile = downloadTargetFile,
                                transferKind = transferKind,
                                headers = resolvedFormat.httpHeaders,
                                onState = onState,
                            )
                            if (isAudioOnlyRequest) {
                                var hasVideo = false
                                val extractor = MediaExtractor()
                                try {
                                    extractor.setDataSource(downloadTargetFile.absolutePath)
                                    for (i in 0 until extractor.trackCount) {
                                        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                                        if (mime.startsWith("video/")) {
                                            hasVideo = true
                                            break
                                        }
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    runCatching { extractor.release() }
                                }

                                if (hasVideo) {
                                    onState(DownloadState.Merging(DownloadProgress(status = "Extracting audio track…")))
                                    // MediaMuxer MPEG-4 always creates an M4A audio container
                                    val audioExtension = "m4a"
                                    val extractedAudioFile = File(outputDirectory, "$baseName.$audioExtension")
                                    val extractSuccess = MediaStreamMuxer.extractAudioTrack(
                                        sourceFile = downloadTargetFile,
                                        outputFile = extractedAudioFile,
                                    ) { cancellationRequests.contains(request.id) }
                                    if (cancellationRequests.contains(request.id)) {
                                        extractedAudioFile.delete()
                                        return@withContext DownloadExecutionResult.Cancelled
                                    }
                                    if (!extractSuccess || !extractedAudioFile.exists() || extractedAudioFile.length() == 0L) {
                                        extractedAudioFile.delete()
                                        return@withContext DownloadExecutionResult.Failure(
                                            message = "Could not extract audio track from video source.",
                                            category = DownloadFailureCategory.CONVERTER_FAILURE,
                                        )
                                    }
                                    actualFinalFile = extractedAudioFile
                                    actualExtension = audioExtension
                                } else {
                                    val detectedExt = detectAudioContainer(downloadTargetFile)
                                    val finalExt = detectedExt ?: resolvedFormat.extension.ifBlank { "m4a" }
                                    val resolvedAudioFile = File(outputDirectory, "$baseName.$finalExt")
                                    if (downloadTargetFile.absolutePath != resolvedAudioFile.absolutePath) {
                                        resolvedAudioFile.delete()
                                        downloadTargetFile.renameTo(resolvedAudioFile)
                                    }
                                    actualFinalFile = resolvedAudioFile
                                    actualExtension = finalExt
                                }
                            }
                            onState(DownloadState.Saving(DownloadProgress(status = "Saving media file…")))
                        } finally {
                            if (isAudioOnlyRequest) {
                                downloadTargetFile.delete()
                            }
                        }
                    } else {
                        val tempVideoFile = File(outputDirectory, "$baseName-video.tmp")
                        val tempAudioFile = File(outputDirectory, "$baseName-audio.tmp")
                        try {
                            val aggregator = TaskProgressAggregator(
                                onState = onState,
                                transferKind = DownloadTransferKind.VIDEO,
                            )
                            onState(
                                DownloadState.Downloading(
                                    DownloadProgress(status = "Downloading video and audio…"),
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
                                    onState = { state ->
                                        if (state is DownloadState.Downloading) {
                                            aggregator.updateVideo(state.progress)
                                        } else {
                                            onState(state)
                                        }
                                    },
                                )
                            }

                            val audioJob = async(dispatchers.io) {
                                downloadStream(
                                    taskId = request.id,
                                    url = requireNotNull(resolvedFormat.companionAudioFormatId),
                                    destinationFile = tempAudioFile,
                                    transferKind = DownloadTransferKind.AUDIO,
                                    headers = resolvedFormat.httpHeaders,
                                    onState = { state ->
                                        if (state is DownloadState.Downloading) {
                                            aggregator.updateAudio(state.progress)
                                        } else {
                                            onState(state)
                                        }
                                    },
                                )
                            }

                            videoJob.await()
                            audioJob.await()

                            onState(DownloadState.Merging(DownloadProgress(status = "Muxing video and audio…")))
                            val muxSuccess = MediaStreamMuxer.mux(tempVideoFile, tempAudioFile, actualFinalFile) {
                                cancellationRequests.contains(request.id)
                            }
                            if (cancellationRequests.contains(request.id)) {
                                actualFinalFile.delete()
                                return@withContext DownloadExecutionResult.Cancelled
                            }
                            if (!muxSuccess || !actualFinalFile.exists() || actualFinalFile.length() == 0L) {
                                actualFinalFile.delete()
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
                    break
                } catch (e: Exception) {
                    if (downloadAttempts == 0 && !cancellationRequests.contains(request.id) &&
                        e.message?.contains("403") == true && discoveryEngine != null
                    ) {
                        downloadAttempts++
                        onState(DownloadState.Preparing(DownloadProgress(status = "Refreshing stream access…")))
                        discoveryEngine.invalidate(request.url)
                        val freshDiscovery = discoveryEngine.discoverFormats(request.url)
                        if (freshDiscovery is FormatDiscoveryResult.Success) {
                            val freshMatching = if (resolvedFormat.mode == DownloadMode.VIDEO) {
                                when (val match = selectBestVideoFormat(freshDiscovery.catalog, resolvedFormat.height, resolvedFormat.key)) {
                                    is VideoMatchResult.Match -> match.format
                                    else -> null
                                }
                            } else {
                                selectBestAudioFormat(freshDiscovery.catalog, resolvedFormat.mode, resolvedFormat.bitrateKbps, resolvedFormat.key)
                            }
                            if (freshMatching != null && freshMatching.formatId.isNotBlank()) {
                                resolvedFormat = resolvedFormat.copy(
                                    formatId = freshMatching.formatId,
                                    companionAudioFormatId = freshMatching.companionAudioFormatId ?: resolvedFormat.companionAudioFormatId,
                                    httpHeaders = freshMatching.httpHeaders ?: resolvedFormat.httpHeaders,
                                )
                                continue
                            }
                        }
                    }
                    throw e
                }
            }

            if (cancellationRequests.contains(request.id)) {
                actualFinalFile.delete()
                DownloadExecutionResult.Cancelled
            } else {
                val validationError = validateMediaFile(actualFinalFile, actualExtension)
                if (validationError != null) {
                    actualFinalFile.delete()
                    validationError
                } else {
                    DownloadExecutionResult.Success("${OUTPUT_MARKER}${actualFinalFile.absolutePath}")
                }
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
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val isStreamingCdn = host.contains("tiktok") || host.contains("musical.ly") ||
            host.contains("cdninstagram") || host.contains("fbcdn")

        val probe = probeStream(taskId, url, headers)
        val totalBytes = probe.totalBytes
        if (!isStreamingCdn && probe.supportsRange && totalBytes != null && totalBytes > CHUNK_SIZE_BYTES) {
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
            knownTotalBytes = totalBytes,
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
            host.contains("reddit") || host.contains("redd.it") -> {
                headers["Referer"] = "https://www.reddit.com/"
            }
            host.contains("googlevideo") || host.contains("youtube") -> {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com/"
            }
        }
        customHeaders?.let { headers.putAll(it) }
        return headers
    }

    private data class StreamProbe(
        val totalBytes: Long?,
        val supportsRange: Boolean,
    )

    private fun probeStream(
        taskId: String,
        url: String,
        customHeaders: Map<String, String>? = null,
    ): StreamProbe {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
        resolveHeaders(url, customHeaders).forEach { (k, v) ->
            reqBuilder.header(k, v)
        }
        val req = reqBuilder.build()
        val call = httpClient.newCall(req)
        if (!registerCall(taskId, call)) {
            throw CancellationException("Download cancelled")
        }
        return try {
            call.execute().use { response ->
                if (cancellationRequests.contains(taskId)) {
                    throw CancellationException("Download cancelled")
                }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancellationRequests.contains(taskId)) {
                throw CancellationException("Download cancelled")
            }
            StreamProbe(totalBytes = null, supportsRange = false)
        } finally {
            unregisterCall(taskId, call)
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
        var currentStart = 0L
        var activeEtag: String? = null
        var activeLastModified: String? = null

        java.io.RandomAccessFile(destinationFile, "rw").use { raf ->
            raf.setLength(0L)
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
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=$currentStart-$currentEnd")
                        .header("Connection", "keep-alive")
                    activeEtag?.let { chunkRequestBuilder.header("If-Match", it) }
                    resolveHeaders(url, headers).forEach { (k, v) ->
                        chunkRequestBuilder.header(k, v)
                    }
                    val chunkRequest = chunkRequestBuilder.build()
                    val call = httpClient.newCall(chunkRequest)
                    if (!registerCall(taskId, call)) {
                        throw CancellationException("Download cancelled")
                    }

                    try {
                        call.execute().use { response ->
                            if (response.code == 200) {
                                // Server ignored Range and returned full file: restart as single continuous stream
                                val body = response.body ?: throw java.io.IOException("Empty response body for full download")
                                raf.setLength(0)
                                raf.seek(0)
                                downloadedBytes = 0L
                                body.byteStream().use { streamIn ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        if (cancellationRequests.contains(taskId)) {
                                            call.cancel()
                                            throw CancellationException("Download cancelled")
                                        }
                                        val read = streamIn.read(buffer)
                                        if (read < 0) break
                                        raf.write(buffer, 0, read)
                                        downloadedBytes += read

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
                                chunkSuccess = true
                                return
                            }

                            if (response.code != 206) {
                                throw java.io.IOException("HTTP error ${response.code} for chunk $currentStart-$currentEnd")
                            }

                            // Validate Content-Range header
                            val contentRange = response.header("Content-Range")
                            if (contentRange != null) {
                                val match = Regex("""bytes\s+(\d+)-(\d+)/(?:(\d+)|\*)""").find(contentRange)
                                if (match != null) {
                                    val rangeStart = match.groupValues[1].toLong()
                                    if (rangeStart != currentStart) {
                                        throw java.io.IOException("Content-Range start $rangeStart does not match expected offset $currentStart")
                                    }
                                }
                            }

                            // Verify entity representation consistency
                            val responseEtag = response.header("ETag")
                            val responseLastModified = response.header("Last-Modified")
                            if (activeEtag == null && responseEtag != null) activeEtag = responseEtag
                            else if (activeEtag != null && responseEtag != null && activeEtag != responseEtag) {
                                throw java.io.IOException("ETag changed from $activeEtag to $responseEtag during ranged download")
                            }
                            if (activeLastModified == null && responseLastModified != null) activeLastModified = responseLastModified
                            else if (activeLastModified != null && responseLastModified != null && activeLastModified != responseLastModified) {
                                throw java.io.IOException("Last-Modified changed during ranged download")
                            }

                            val body = response.body ?: throw java.io.IOException("Empty response body for chunk")
                            body.byteStream().use { streamIn ->
                                raf.seek(currentStart)
                                val buffer = ByteArray(64 * 1024)
                                var chunkBytesRead = 0L
                                val maxBytesExpected = currentEnd - currentStart + 1
                                while (chunkBytesRead < maxBytesExpected) {
                                    if (cancellationRequests.contains(taskId)) {
                                        call.cancel()
                                        throw CancellationException("Download cancelled")
                                    }
                                    val toRead = (maxBytesExpected - chunkBytesRead).coerceAtMost(buffer.size.toLong()).toInt()
                                    val read = streamIn.read(buffer, 0, toRead)
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

                                if (chunkBytesRead < maxBytesExpected && (currentStart + chunkBytesRead) < totalBytes) {
                                    throw java.io.IOException("Premature EOF: chunk received only $chunkBytesRead bytes out of $maxBytesExpected")
                                }
                                currentStart += chunkBytesRead
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
                        unregisterCall(taskId, call)
                    }
                }

                if (!chunkSuccess) {
                    throw lastChunkException ?: java.io.IOException("Failed downloading chunk $currentStart-$currentEnd after 3 attempts")
                }
            }
        }
    }

    private fun downloadStreamContinuous(
        taskId: String,
        url: String,
        destinationFile: File,
        transferKind: DownloadTransferKind,
        knownTotalBytes: Long? = null,
        headers: Map<String, String>? = null,
        onState: (DownloadState) -> Unit,
    ) {
        val httpRequestBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("Connection", "keep-alive")
        resolveHeaders(url, headers).forEach { (k, v) ->
            httpRequestBuilder.header(k, v)
        }
        val httpRequest = httpRequestBuilder.build()
        val call = httpClient.newCall(httpRequest)
        if (!registerCall(taskId, call)) {
            throw CancellationException("Download cancelled")
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP error ${response.code}: ${response.message}")
                }
                val ct = response.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html")) {
                    throw java.io.IOException("Stream URL returned an HTML page ($ct) instead of a media stream.")
                }

                val body = response.body ?: throw java.io.IOException("Empty response body")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: knownTotalBytes
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
        } finally {
            unregisterCall(taskId, call)
        }
    }

    internal fun detectAudioContainer(file: File): String? {
        if (!file.exists() || file.length() < 8) return null
        val headerBytes = ByteArray(64)
        val read = try {
            FileInputStream(file).use { it.read(headerBytes) }
        } catch (_: Exception) {
            return null
        }
        if (read < 4) return null
        val headerString = String(headerBytes, 0, read, Charsets.ISO_8859_1).lowercase(Locale.US)
        return when {
            headerString.contains("ftyp") || headerString.contains("moov") -> "m4a"
            read >= 4 &&
                (headerBytes[0].toInt() and 0xFF) == 0x1A &&
                (headerBytes[1].toInt() and 0xFF) == 0x45 &&
                (headerBytes[2].toInt() and 0xFF) == 0xDF &&
                (headerBytes[3].toInt() and 0xFF) == 0xA3 -> "webm"
            read >= 2 &&
                (headerBytes[0].toInt() and 0xFF) == 0xFF &&
                (headerBytes[1].toInt() and 0xF6) == 0xF0 -> "aac"
            (headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() && headerBytes[2] == '3'.code.toByte()) ||
                (read >= 2 && (headerBytes[0].toInt() and 0xFF) == 0xFF && (headerBytes[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            read >= 4 &&
                headerBytes[0] == 'O'.code.toByte() && headerBytes[1] == 'g'.code.toByte() &&
                headerBytes[2] == 'g'.code.toByte() && headerBytes[3] == 'S'.code.toByte() -> "ogg"
            headerString.startsWith("riff") && headerString.contains("wave") -> "wav"
            read >= 4 &&
                headerBytes[0] == 'f'.code.toByte() && headerBytes[1] == 'L'.code.toByte() &&
                headerBytes[2] == 'a'.code.toByte() && headerBytes[3] == 'C'.code.toByte() -> "flac"
            else -> null
        }
    }

    internal fun validateMediaFile(file: File, expectedExtension: String): DownloadExecutionResult.Failure? {
        val length = file.length()
        if (length < 1024L) {
            return DownloadExecutionResult.Failure(
                message = "Downloaded media file is empty or too small ($length bytes).",
                category = DownloadFailureCategory.UNKNOWN_FAILURE,
            )
        }

        // Read the first 512 bytes for header inspection
        val headerBytes = ByteArray(512.coerceAtMost(length.toInt()))
        try {
            FileInputStream(file).use { stream ->
                val read = stream.read(headerBytes)
                if (read < 16) {
                    return DownloadExecutionResult.Failure(
                        message = "Could not read media file header.",
                        category = DownloadFailureCategory.UNKNOWN_FAILURE,
                    )
                }
            }
        } catch (e: Exception) {
            return DownloadExecutionResult.Failure(
                message = "Failed reading media file header: ${e.message}",
                category = DownloadFailureCategory.UNKNOWN_FAILURE,
            )
        }

        val headerString = String(headerBytes, Charsets.ISO_8859_1).lowercase()
        // Reject HTML error pages and manifests
        if (headerString.contains("<!doctype html") ||
            headerString.contains("<html") ||
            headerString.contains("<head") ||
            headerString.contains("{\"error\"") ||
            headerString.contains("{\"message\"") ||
            headerString.startsWith("#extm3u")
        ) {
            return DownloadExecutionResult.Failure(
                message = "Downloaded file contains web content or an error page instead of media.",
                category = DownloadFailureCategory.UNSUPPORTED_SITE,
            )
        }

        // Check container signature
        val ext = expectedExtension.lowercase(Locale.US)
        val isDirectMatch = when (ext) {
            "mp4", "m4a", "mov", "m4v" -> {
                headerString.contains("ftyp") || headerString.contains("moov")
            }
            "webm", "mkv" -> {
                // EBML ID: 0x1A, 0x45, 0xDF, 0xA3
                headerBytes.size >= 4 &&
                    (headerBytes[0].toInt() and 0xFF) == 0x1A &&
                    (headerBytes[1].toInt() and 0xFF) == 0x45 &&
                    (headerBytes[2].toInt() and 0xFF) == 0xDF &&
                    (headerBytes[3].toInt() and 0xFF) == 0xA3
            }
            "mp3" -> {
                (headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() && headerBytes[2] == '3'.code.toByte()) ||
                    (headerBytes.size >= 2 && (headerBytes[0].toInt() and 0xFF) == 0xFF && (headerBytes[1].toInt() and 0xE0) == 0xE0)
            }
            "ogg", "oga", "opus" -> {
                headerBytes.size >= 4 &&
                    headerBytes[0] == 'O'.code.toByte() && headerBytes[1] == 'g'.code.toByte() &&
                    headerBytes[2] == 'g'.code.toByte() && headerBytes[3] == 'S'.code.toByte()
            }
            "wav" -> {
                headerString.startsWith("riff") && headerString.contains("wave")
            }
            "flac" -> {
                headerBytes.size >= 4 &&
                    headerBytes[0] == 'f'.code.toByte() && headerBytes[1] == 'L'.code.toByte() &&
                    headerBytes[2] == 'a'.code.toByte() && headerBytes[3] == 'C'.code.toByte()
            }
            "aac" -> {
                headerBytes.size >= 2 &&
                    (headerBytes[0].toInt() and 0xFF) == 0xFF && (headerBytes[1].toInt() and 0xF0) == 0xF0
            }
            else -> true
        }

        val isAnyValidAudioContainer = (headerString.contains("ftyp") || headerString.contains("moov")) ||
            (headerBytes.size >= 4 && (headerBytes[0].toInt() and 0xFF) == 0x1A && (headerBytes[1].toInt() and 0xFF) == 0x45 && (headerBytes[2].toInt() and 0xFF) == 0xDF && (headerBytes[3].toInt() and 0xFF) == 0xA3) ||
            ((headerBytes[0] == 'I'.code.toByte() && headerBytes[1] == 'D'.code.toByte() && headerBytes[2] == '3'.code.toByte()) || (headerBytes.size >= 2 && (headerBytes[0].toInt() and 0xFF) == 0xFF && (headerBytes[1].toInt() and 0xE0) == 0xE0)) ||
            (headerBytes.size >= 4 && headerBytes[0] == 'O'.code.toByte() && headerBytes[1] == 'g'.code.toByte() && headerBytes[2] == 'g'.code.toByte() && headerBytes[3] == 'S'.code.toByte()) ||
            (headerString.startsWith("riff") && headerString.contains("wave")) ||
            (headerBytes.size >= 4 && headerBytes[0] == 'f'.code.toByte() && headerBytes[1] == 'L'.code.toByte() && headerBytes[2] == 'a'.code.toByte() && headerBytes[3] == 'C'.code.toByte()) ||
            (headerBytes.size >= 2 && (headerBytes[0].toInt() and 0xFF) == 0xFF && (headerBytes[1].toInt() and 0xF0) == 0xF0)

        val isValidContainer = isDirectMatch || (ext in listOf("mp3", "m4a", "webm", "ogg", "opus", "aac") && isAnyValidAudioContainer)

        if (!isValidContainer) {
            return DownloadExecutionResult.Failure(
                message = "The downloaded file is corrupted or does not match the expected $ext format.",
                category = DownloadFailureCategory.CONVERTER_FAILURE,
            )
        }

        return null
    }

    override suspend fun cancel(processId: String): Boolean = withContext(dispatchers.io) {
        cancellationRequests.add(processId)
        val snapshot = activeCalls[processId]?.let { set ->
            synchronized(set) { set.toList() }
        }.orEmpty()
        snapshot.forEach { it.cancel() }
        true
    }

    companion object {
        const val OUTPUT_MARKER = "__SMD_FILE__"
        private const val CHUNK_SIZE_BYTES = 2 * 1024 * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

internal class TaskProgressAggregator(
    private val onState: (DownloadState) -> Unit,
    private val transferKind: DownloadTransferKind = DownloadTransferKind.VIDEO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    var videoBytesDownloaded: Long = 0L
        private set
    var videoBytesTotal: Long? = null
        private set
    var audioBytesDownloaded: Long = 0L
        private set
    var audioBytesTotal: Long? = null
        private set

    private var maxDownloadedBytes: Long = 0L
    private var maxPercentage: Float = 0f
    private var lastSpeedTimestamp: Long = 0L
    private var lastSpeedBytes: Long = 0L
    private var currentSpeed: Long? = null

    fun updateVideo(progress: DownloadProgress) {
        val stateToEmit = synchronized(lock) {
            progress.downloadedBytes?.let {
                videoBytesDownloaded = it.coerceAtLeast(videoBytesDownloaded)
            }
            if (progress.totalBytes != null && progress.totalBytes > 0) {
                videoBytesTotal = progress.totalBytes
            }
            computeState()
        }
        onState(stateToEmit)
    }

    fun updateAudio(progress: DownloadProgress) {
        val stateToEmit = synchronized(lock) {
            progress.downloadedBytes?.let {
                audioBytesDownloaded = it.coerceAtLeast(audioBytesDownloaded)
            }
            if (progress.totalBytes != null && progress.totalBytes > 0) {
                audioBytesTotal = progress.totalBytes
            }
            computeState()
        }
        onState(stateToEmit)
    }

    private fun computeState(): DownloadState.Downloading {
        val currentSum = videoBytesDownloaded + audioBytesDownloaded
        if (currentSum > maxDownloadedBytes) {
            maxDownloadedBytes = currentSum
        }
        val downloaded = maxDownloadedBytes

        val vTotal = videoBytesTotal
        val aTotal = audioBytesTotal
        val combinedTotal = if (vTotal != null && aTotal != null && vTotal > 0 && aTotal > 0) {
            vTotal + aTotal
        } else {
            null
        }

        val percentage = if (combinedTotal != null && combinedTotal > 0) {
            val calc = (downloaded.toDouble() * 100.0 / combinedTotal.toDouble()).toFloat().coerceIn(0f, 100f)
            if (calc > maxPercentage) {
                maxPercentage = calc
            }
            maxPercentage
        } else {
            null
        }

        val now = clock()
        if (lastSpeedTimestamp == 0L) {
            lastSpeedTimestamp = now
            lastSpeedBytes = downloaded
        } else {
            val deltaSec = (now - lastSpeedTimestamp) / 1000.0
            if (deltaSec >= 0.5) {
                val deltaBytes = downloaded - lastSpeedBytes
                if (deltaBytes >= 0 && deltaSec > 0) {
                    currentSpeed = (deltaBytes / deltaSec).toLong()
                }
                lastSpeedTimestamp = now
                lastSpeedBytes = downloaded
            }
        }

        val eta = if (combinedTotal != null && currentSpeed != null && currentSpeed!! > 0) {
            (combinedTotal - downloaded).coerceAtLeast(0L) / currentSpeed!!
        } else {
            null
        }

        val progress = DownloadProgress(
            percentage = percentage,
            downloadedBytes = downloaded,
            totalBytes = combinedTotal,
            speedBytesPerSecond = currentSpeed,
            etaSeconds = eta,
            status = "Downloading video and audio…",
        )
        return DownloadState.Downloading(progress, transferKind)
    }
}

