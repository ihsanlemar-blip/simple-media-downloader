package com.example.simplemediadownloader

import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt

class MediaDownloader {
    private val activeProcessIds = ConcurrentHashMap.newKeySet<String>()
    private val cancellationRequests = ConcurrentHashMap.newKeySet<String>()

    fun quickFormatCatalog(url: String): MediaFormatCatalog = MediaFormatCatalog(
        sourceUrl = url,
        title = "Fast native downloads",
        videoFormats = QUICK_OUTPUT_HEIGHTS.map { height ->
            AvailableFormat(
                key = "quick-video-$height",
                mode = DownloadMode.VIDEO,
                formatId = "best[height<=$height][vcodec!=none][acodec!=none]",
                extension = "source",
                height = height,
                formatNote = "No conversion",
                isQuickPreset = true,
            )
        },
        audioFormats = listOf(
            AvailableFormat(
                key = "quick-audio-original",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = "bestaudio/best",
                extension = "source",
                formatNote = "No conversion",
                isQuickPreset = true,
            ),
        ) + QUICK_AUDIO_BITRATES.map { bitrate ->
            AvailableFormat(
                key = "quick-audio-$bitrate",
                mode = DownloadMode.AUDIO_MP3,
                formatId = "bestaudio/best",
                extension = "mp3",
                bitrateKbps = bitrate,
                formatNote = "Exact size loading",
                isQuickPreset = true,
            )
        },
        detailsLoading = true,
    )

    fun fastVideoPreset(): AvailableFormat = AvailableFormat(
        key = "fast-video",
        mode = DownloadMode.VIDEO,
        formatId = "best[vcodec!=none][acodec!=none]",
        extension = "source",
        formatNote = "Best native video with audio; no conversion",
        isQuickPreset = true,
    )

    suspend fun discoverFormats(url: String): FormatDiscoveryResult = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(url)
                .addOption("--no-playlist")
                .apply {
                    if (needsEjs(url)) addOption("--remote-components", "ejs:github")
                }
            val info = YoutubeDL.getInstance().getInfo(request)
            val catalog = createCatalog(url, info)
            if (catalog.videoFormats.isEmpty() && catalog.audioFormats.isEmpty()) {
                FormatDiscoveryResult.Failure("No downloadable video or audio formats were reported.")
            } else {
                FormatDiscoveryResult.Success(catalog)
            }
        } catch (error: Exception) {
            FormatDiscoveryResult.Failure(humanReadableError(error, "Could not inspect this URL."))
        }
    }

    suspend fun download(
        url: String,
        format: AvailableFormat,
        processId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val outputDirectory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            OUTPUT_FOLDER,
        )
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            return@withContext DownloadResult.Failure(
                "Could not create Downloads/$OUTPUT_FOLDER. Check available storage.",
            )
        }

        val before = outputDirectory.listFiles().orEmpty().associate { it.name to it.lastModified() }
        val request = buildRequest(url, format, outputDirectory, processId.take(8))
        activeProcessIds += processId

        try {
            if (cancellationRequests.contains(processId)) return@withContext DownloadResult.Cancelled

            onProgress(DownloadProgress(status = "Connecting…"))
            val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                val parsedProgress = progressPercentage(line, progress)
                onProgress(
                    DownloadProgress(
                        percentage = parsedProgress,
                        etaSeconds = progressEtaSeconds(line) ?: eta.takeIf { it >= 0 },
                        status = statusFor(line, parsedProgress, format),
                    ),
                )
            }

            if (cancellationRequests.contains(processId)) return@withContext DownloadResult.Cancelled

            val file = outputFileFrom(response.out, outputDirectory)
                ?: newestChangedFile(outputDirectory, before)
                ?: return@withContext DownloadResult.Failure(
                    "The download finished but the saved file could not be located.",
                )

            DownloadResult.Success(file)
        } catch (_: YoutubeDL.CanceledException) {
            DownloadResult.Cancelled
        } catch (_: InterruptedException) {
            DownloadResult.Cancelled
        } catch (error: Exception) {
            if (cancellationRequests.contains(processId)) {
                DownloadResult.Cancelled
            } else {
                DownloadResult.Failure(humanReadableError(error))
            }
        } finally {
            activeProcessIds -= processId
            cancellationRequests -= processId
        }
    }

    suspend fun cancel(processId: String): Boolean = withContext(Dispatchers.IO) {
        cancellationRequests += processId
        var destroyed = false

        for (attempt in 0 until 20) {
            destroyed = YoutubeDL.getInstance().destroyProcessById(processId) || destroyed
            if (destroyed || !activeProcessIds.contains(processId)) break
            delay(50)
        }
        destroyed || activeProcessIds.contains(processId) || cancellationRequests.contains(processId)
    }

    internal fun buildRequest(
        url: String,
        format: AvailableFormat,
        outputDirectory: File,
        taskSuffix: String = "",
    ): YoutubeDLRequest {
        val variantSuffix = DownloadOptions.variantSuffix(format)
        val uniqueSuffix = taskSuffix.takeIf(String::isNotBlank)?.let { " task-$it" }.orEmpty()
        val outputTemplate = File(
            outputDirectory,
            "%(title).150B [%(id)s] $variantSuffix$uniqueSuffix.%(ext)s",
        ).absolutePath

        return YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--restrict-filenames")
            .addOption("--windows-filenames")
            .addOption("--newline")
            .addOption("--concurrent-fragments", CONCURRENT_FRAGMENTS.toString())
            .apply {
                if (needsEjs(url)) addOption("--remote-components", "ejs:github")
            }
            .addOption("--output", outputTemplate)
            .addOption("--print", "after_move:$OUTPUT_MARKER%(filepath)s")
            // --print implies --quiet in yt-dlp. Re-enable output so the Android
            // library receives the intermediate progress lines.
            .addOption("--progress")
            .addOption("--no-quiet")
            .addOption("--no-color")
            .apply {
                addOption("--format", DownloadOptions.formatSelector(format))
                when (format.mode) {
                    DownloadMode.VIDEO -> {
                        addOption("--merge-output-format", "mp4/mkv")
                        if (format.requiresDownscale) {
                            addOption("--recode-video", format.extension)
                            val scaleFilter = if (format.isQuickPreset) {
                                "'scale=-2:min(${format.height}\\,ih)'"
                            } else {
                                "scale=-2:${format.height}"
                            }
                            addOption(
                                "--postprocessor-args",
                                "VideoConvertor+ffmpeg_o:-vf $scaleFilter " +
                                    "-c:v libx264 -preset veryfast -crf 23 -c:a aac -b:a 128k",
                            )
                        }
                    }

                    DownloadMode.AUDIO_ORIGINAL -> Unit

                    DownloadMode.AUDIO_MP3 -> {
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", DownloadOptions.audioBitrate(format))
                    }
                }
            }
    }

    internal fun progressPercentage(line: String, libraryProgress: Float): Float {
        val parsed = PROGRESS_PERCENT.find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
        return (parsed ?: libraryProgress.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 100f)
    }

    internal fun progressEtaSeconds(line: String): Long? {
        val value = PROGRESS_ETA.find(line)?.groupValues?.getOrNull(1) ?: return null
        val parts = value.split(':').mapNotNull(String::toLongOrNull)
        if (parts.size !in 2..3) return null
        return if (parts.size == 3) {
            parts[0] * 3600L + parts[1] * 60L + parts[2]
        } else {
            parts[0] * 60L + parts[1]
        }
    }

    private fun createCatalog(url: String, info: VideoInfo): MediaFormatCatalog {
        val rawFormats = info.formats.orEmpty()
        val audioCapable = rawFormats.filter { format ->
            format.formatId.orEmpty().isNotBlank() &&
                !format.acodec.equals("none", ignoreCase = true)
        }
        val audioOnly = audioCapable.filter { format ->
            format.vcodec.equals("none", ignoreCase = true)
        }
        val preferredAudio = audioOnly.maxWithOrNull(
            compareBy<VideoFormat>(
                { if (it.ext.equals("m4a", ignoreCase = true)) 1 else 0 },
                { audioBitrate(it) },
                { it.tbr },
            ),
        )

        val nativeVideoFormats = rawFormats
            .asSequence()
            .filter { it.formatId.orEmpty().isNotBlank() && it.height > 0 }
            .filter { !it.vcodec.equals("none", ignoreCase = true) }
            .map { raw -> videoOption(raw, preferredAudio, info.duration) }
            .distinctBy(AvailableFormat::key)
            .sortedWith(
                compareByDescending<AvailableFormat> { it.height }
                    .thenByDescending { it.width }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.bitrateKbps }
                    .thenBy { it.extension }
                    .thenBy { it.formatId },
            )
            .toList()
        val videoFormats = buildResolutionLadder(nativeVideoFormats)

        val audioSources = audioOnly.ifEmpty { audioCapable }
        val audioFormats = audioSources
            .flatMap { raw ->
                listOf(
                    audioOption(raw, info.duration, DownloadMode.AUDIO_ORIGINAL),
                    audioOption(raw, info.duration, DownloadMode.AUDIO_MP3),
                )
            }
            .distinctBy {
                listOf(it.mode, it.bitrateKbps, it.codec, it.extension, it.estimatedSizeBytes)
            }
            .sortedWith(
                compareBy<AvailableFormat> { it.mode.ordinal }
                    .thenByDescending { it.bitrateKbps }
                    .thenByDescending { it.estimatedSizeBytes ?: 0L }
                    .thenBy { it.extension }
                    .thenBy { it.formatId },
            )

        return MediaFormatCatalog(
            sourceUrl = url,
            title = info.title.orEmpty().ifBlank {
                info.fulltitle.orEmpty().ifBlank { "Available formats" }
            },
            videoFormats = videoFormats,
            audioFormats = audioFormats,
        )
    }

    internal fun buildResolutionLadder(nativeFormats: List<AvailableFormat>): List<AvailableFormat> {
        if (nativeFormats.isEmpty()) return emptyList()
        return nativeFormats
            .groupBy(AvailableFormat::height)
            .toSortedMap(reverseOrder())
            .mapNotNull { (height, formats) ->
                bestNativeFormat(formats)?.let { best ->
                    best.copy(key = "${best.key}:native-$height", sourceHeight = height)
                }
            }
    }

    private fun bestNativeFormat(formats: List<AvailableFormat>): AvailableFormat? =
        formats.maxWithOrNull(
            compareBy<AvailableFormat>(
                { it.bitrateKbps },
                { it.fps },
                { if (it.extension.equals("mp4", ignoreCase = true)) 1 else 0 },
            ),
        )

    private fun downscaledOption(source: AvailableFormat, targetHeight: Int): AvailableFormat {
        val ratio = targetHeight.toDouble() / source.height
        val outputWidth = if (source.width > 0) {
            ((source.width * ratio).roundToInt() / 2 * 2).coerceAtLeast(2)
        } else {
            0
        }
        val estimatedSize = source.estimatedSizeBytes?.let { sourceBytes ->
            (sourceBytes * ratio.pow(1.35)).toLong().coerceAtLeast(512L * 1024L)
        }
        val outputContainer = if (source.extension.equals("mp4", ignoreCase = true)) "mkv" else "mp4"
        return source.copy(
            key = "${source.key}:downscale-$targetHeight",
            extension = outputContainer,
            width = outputWidth,
            height = targetHeight,
            bitrateKbps = (source.bitrateKbps * ratio.pow(1.2)).roundToInt().coerceAtLeast(1),
            formatNote = "Created from ${source.height}p",
            estimatedSizeBytes = estimatedSize,
            sizeIsApproximate = true,
            sourceHeight = source.height,
            requiresDownscale = true,
        )
    }

    private fun videoOption(
        raw: VideoFormat,
        preferredAudio: VideoFormat?,
        durationSeconds: Int,
    ): AvailableFormat {
        val needsAudio = raw.acodec.equals("none", ignoreCase = true)
        val companion = preferredAudio.takeIf { needsAudio }
        val companionId = if (needsAudio) companion?.formatId ?: "bestaudio" else null
        val videoSize = formatSize(raw, durationSeconds)
        val audioSize = companion?.let { formatSize(it, durationSeconds) }
        val combinedSize = when {
            !needsAudio -> videoSize.bytes
            companion == null -> null
            videoSize.bytes != null && audioSize?.bytes != null -> videoSize.bytes + audioSize.bytes
            else -> null
        }
        return AvailableFormat(
            key = "video:${raw.formatId.orEmpty()}:${companionId.orEmpty()}",
            mode = DownloadMode.VIDEO,
            formatId = raw.formatId.orEmpty(),
            companionAudioFormatId = companionId,
            extension = raw.ext.orEmpty().ifBlank { "media" },
            width = raw.width,
            height = raw.height,
            fps = raw.fps,
            bitrateKbps = raw.tbr,
            codec = raw.vcodec.orEmpty(),
            formatNote = raw.formatNote.orEmpty(),
            estimatedSizeBytes = combinedSize,
            sizeIsApproximate = needsAudio || videoSize.approximate || audioSize?.approximate == true,
        )
    }

    private fun audioOption(
        raw: VideoFormat,
        durationSeconds: Int,
        mode: DownloadMode,
    ): AvailableFormat {
        val bitrate = audioBitrate(raw).coerceIn(32, 320)
        val sourceSize = formatSize(raw, durationSeconds)
        val outputSize = if (mode == DownloadMode.AUDIO_MP3 && durationSeconds > 0) {
            durationSeconds.toLong() * bitrate * 1_000L / 8L
        } else {
            sourceSize.bytes
        }
        return AvailableFormat(
            key = "audio:${mode.name.lowercase()}:${raw.formatId.orEmpty()}",
            mode = mode,
            formatId = raw.formatId.orEmpty(),
            extension = if (mode == DownloadMode.AUDIO_MP3) {
                "mp3"
            } else {
                raw.ext.orEmpty().ifBlank { "audio" }
            },
            bitrateKbps = bitrate,
            codec = raw.acodec.orEmpty(),
            formatNote = raw.formatNote.orEmpty(),
            estimatedSizeBytes = outputSize,
            sizeIsApproximate = mode == DownloadMode.AUDIO_MP3 || sourceSize.approximate,
        )
    }

    private fun audioBitrate(format: VideoFormat): Int =
        format.abr.takeIf { it > 0 } ?: format.tbr.takeIf { it > 0 } ?: 192

    private fun formatSize(format: VideoFormat, durationSeconds: Int): FormatSize = when {
        format.fileSize > 0L -> FormatSize(format.fileSize, false)
        format.fileSizeApproximate > 0L -> FormatSize(format.fileSizeApproximate, true)
        durationSeconds > 0 && format.tbr > 0 ->
            FormatSize(durationSeconds.toLong() * format.tbr * 1_000L / 8L, true)
        else -> FormatSize(null, true)
    }

    private fun outputFileFrom(output: String, outputDirectory: File): File? {
        val path = output.lineSequence()
            .lastOrNull { it.startsWith(OUTPUT_MARKER) }
            ?.removePrefix(OUTPUT_MARKER)
            ?.trim()
            ?: return null
        val file = File(path)
        return file.takeIf {
            it.exists() &&
                it.isFile &&
                it.canonicalPath.startsWith(outputDirectory.canonicalPath + File.separator)
        }
    }

    private fun newestChangedFile(outputDirectory: File, before: Map<String, Long>): File? =
        outputDirectory.listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    !it.name.endsWith(".part") &&
                    !it.name.endsWith(".ytdl") &&
                    before[it.name] != it.lastModified()
            }
            .maxByOrNull(File::lastModified)

    private fun statusFor(line: String, progress: Float, format: AvailableFormat): String = when {
        line.contains("ExtractAudio", ignoreCase = true) -> "Converting to MP3…"
        format.requiresDownscale &&
            (line.contains("VideoConvertor", ignoreCase = true) ||
                line.contains("Converting video", ignoreCase = true)) ->
            "Creating ${format.height}p video…"
        line.contains("Merger", ignoreCase = true) || line.contains("Merging", ignoreCase = true) ->
            "Merging video and audio…"
        line.contains("Fixup", ignoreCase = true) || line.contains("Post-process", ignoreCase = true) ->
            "Finishing file…"
        progress > 0f -> "Downloading…"
        else -> "Preparing download…"
    }

    private fun humanReadableError(
        error: Exception,
        fallback: String = "Download failed. Check the URL and your internet connection.",
    ): String {
        val raw = error.message.orEmpty()
        val usefulLine = raw.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?: raw.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty)

        val detail = usefulLine
            ?.replace(Regex("^ERROR:\\s*", RegexOption.IGNORE_CASE), "")
            ?.take(350)
            .orEmpty()

        return when {
            detail.contains("Unsupported URL", ignoreCase = true) ->
                "This URL is not supported by the current download engine."
            detail.contains("Private video", ignoreCase = true) ||
                detail.contains("login", ignoreCase = true) ->
                "This media is not publicly accessible. Login and private-account downloads are not supported."
            detail.contains("DRM", ignoreCase = true) ->
                "This media is DRM-protected and cannot be downloaded."
            detail.contains("HTTP Error 403", ignoreCase = true) ||
                detail.contains("HTTP 403", ignoreCase = true) ->
                "The media server refused the download (HTTP 403). Update the download engine " +
                    "from the menu " +
                    "and retry. If it persists, this source may currently require a PO token that " +
                    "this app does not collect."
            detail.contains("No address associated with hostname", ignoreCase = true) ||
                detail.contains("Name or service not known", ignoreCase = true) ->
                "The media site could not be reached. Check your internet connection, DNS, or VPN."
            detail.contains("universal data for rehydration", ignoreCase = true) ->
                "This TikTok page could not be read. Update the download engine, then check whether " +
                    "TikTok is reachable on your network or VPN and retry."
            detail.isNotBlank() -> detail
                .replace(Regex("yt-dlp", RegexOption.IGNORE_CASE), "download engine")
                .replace(Regex("https://github\\.com/[^\\s]+"), "")
                .trim()
            else -> fallback
        }
    }

    private data class FormatSize(val bytes: Long?, val approximate: Boolean)

    private fun needsEjs(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase()
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }.getOrDefault(false)

    companion object {
        const val OUTPUT_FOLDER = "MediaDownloader"
        private val PROGRESS_PERCENT = Regex("""\[download]\s+(\d+(?:\.\d+)?)%""")
        private val PROGRESS_ETA = Regex("""\bETA\s+(\d{1,3}:\d{2}(?::\d{2})?)""")
        private const val OUTPUT_MARKER = "__SMD_FILE__"
        private const val MINIMUM_OUTPUT_HEIGHT = 144
        private val STANDARD_OUTPUT_HEIGHTS = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
        private val QUICK_OUTPUT_HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        private val QUICK_AUDIO_BITRATES = listOf(320, 256, 192, 128, 96)
        private const val CONCURRENT_FRAGMENTS = 4
    }
}
