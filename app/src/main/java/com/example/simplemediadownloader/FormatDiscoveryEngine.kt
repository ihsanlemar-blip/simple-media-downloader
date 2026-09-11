package com.example.simplemediadownloader

import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream

interface FormatDiscoveryEngine {
    fun quickFormatCatalog(url: String): MediaFormatCatalog
    fun fastVideoPreset(): AvailableFormat
    fun cachedFormatCatalog(url: String): MediaFormatCatalog? = null
    suspend fun discoverFormats(url: String): FormatDiscoveryResult
    fun clearCache() {}
    fun invalidate(url: String) {}
    suspend fun refreshFormats(url: String): FormatDiscoveryResult {
        invalidate(url)
        return discoverFormats(url)
    }
}

typealias YtDlpFormatDiscoveryEngine = NewPipeFormatDiscoveryEngine

class NewPipeFormatDiscoveryEngine(
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val preferenceStore: DownloadPreferenceStore? = null,
) : FormatDiscoveryEngine {
    override fun quickFormatCatalog(url: String): MediaFormatCatalog = MediaFormatCatalog(
        sourceUrl = url,
        title = "Fast native downloads",
        videoFormats = QUICK_OUTPUT_HEIGHTS.map { height ->
            AvailableFormat(
                key = "quick-video-$height",
                mode = DownloadMode.VIDEO,
                formatId = "quick-$height",
                extension = "mp4",
                height = height,
                formatNote = "Best video and audio",
                isQuickPreset = true,
            )
        },
        audioFormats = listOf(
            AvailableFormat(
                key = "quick-audio-original",
                mode = DownloadMode.AUDIO_ORIGINAL,
                formatId = "quick-audio-original",
                extension = "m4a",
                formatNote = "No conversion",
                isQuickPreset = true,
            ),
        ) + QUICK_AUDIO_BITRATES.map { bitrate ->
            AvailableFormat(
                key = "quick-audio-$bitrate",
                mode = DownloadMode.AUDIO_MP3,
                formatId = "quick-audio-$bitrate",
                extension = "mp3",
                bitrateKbps = bitrate,
                formatNote = "Exact size loading",
                isQuickPreset = true,
            )
        },
        detailsLoading = true,
    )

    override fun fastVideoPreset(): AvailableFormat = AvailableFormat(
        key = "fast-video",
        mode = DownloadMode.VIDEO,
        formatId = "fast-video",
        extension = "mp4",
        formatNote = "Best video and audio",
        isQuickPreset = true,
    )

    private val cookieJar = ScopedCookieJar()
    private val okHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(SafeDns())
        .addInterceptor(SecurityInterceptor(allowCleartextHttp = true))
        .build()

    override suspend fun discoverFormats(url: String): FormatDiscoveryResult =
        withContext(dispatchers.io) {
            val platform = PlatformResolver.fromUrl(url)
            val allowGateways = preferenceStore?.allowThirdPartyGateways?.value ?: false
            if (platform in listOf("Facebook", "TikTok", "Instagram", "X", "Reddit")) {
                return@withContext SocialMediaExtractor.extract(
                    client = okHttpClient,
                    url = url,
                    platform = platform,
                    allowThirdPartyGateways = allowGateways,
                )
            }

            try {
                val service = NewPipe.getServiceByUrl(url)
                val extractor: StreamExtractor = service.getStreamExtractor(url)
                extractor.fetchPage()

                val catalog = createCatalog(url, extractor)
                if (catalog.videoFormats.isEmpty() && catalog.audioFormats.isEmpty()) {
                    FormatDiscoveryResult.Failure(
                        "No downloadable video or audio formats were reported.",
                    )
                } else {
                    FormatDiscoveryResult.Success(catalog)
                }
            } catch (error: Exception) {
                FormatDiscoveryResult.Failure(
                    error.localizedMessage ?: "Could not inspect this URL.",
                )
            }
        }

    private fun createCatalog(url: String, extractor: StreamExtractor): MediaFormatCatalog {
        val durationSeconds = runCatching { extractor.length }.getOrDefault(0L).coerceAtLeast(0L)
        val audioStreams = extractor.audioStreams.orEmpty()
        val preferredAudio = audioStreams.maxWithOrNull(
            compareBy<AudioStream>(
                { if (it.format?.suffix?.equals("m4a", ignoreCase = true) == true) 1 else 0 },
                { it.averageBitrate },
            ),
        )

        val progressiveVideos = extractor.videoStreams.orEmpty()
            .map { raw -> videoOption(raw, companionAudio = null, durationSeconds = durationSeconds) }

        val adaptiveVideos = extractor.videoOnlyStreams.orEmpty()
            .map { raw -> videoOption(raw, companionAudio = preferredAudio, durationSeconds = durationSeconds) }

        val allVideos = (progressiveVideos + adaptiveVideos)
            .distinctBy(AvailableFormat::key)
            .sortedWith(
                compareByDescending<AvailableFormat> { it.height }
                    .thenByDescending { it.width }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.bitrateKbps }
                    .thenBy { it.extension }
                    .thenBy { it.formatId },
            )
        val videoFormats = buildResolutionLadder(allVideos)

        val originalAudioFormats = audioStreams
            .map { raw ->
                audioOption(raw, DownloadMode.AUDIO_ORIGINAL, durationSeconds)
            }

        val mp3AudioFormats = listOf(64, 128, 192, 320).mapNotNull { targetBitrate ->
            val closestStream = audioStreams.minByOrNull { kotlin.math.abs(it.averageBitrate - targetBitrate) }
                ?: audioStreams.firstOrNull()
            closestStream?.let { raw ->
                val nativeExt = raw.format?.suffix?.lowercase() ?: "m4a"
                audioOption(raw, DownloadMode.AUDIO_MP3, durationSeconds).copy(
                    key = "audio:mp3:$targetBitrate",
                    extension = nativeExt,
                    bitrateKbps = targetBitrate,
                    formatNote = if (targetBitrate <= 64) "Data Saver Audio" else "${targetBitrate} kbps Audio",
                )
            }
        }

        val audioFormats = (originalAudioFormats + mp3AudioFormats)
            .distinctBy {
                listOf(it.mode, it.bitrateKbps, it.codec, it.extension)
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
            title = extractor.name.orEmpty().ifBlank { "Available formats" },
            videoFormats = videoFormats,
            audioFormats = audioFormats,
        )
    }

    internal fun buildResolutionLadder(
        nativeFormats: List<AvailableFormat>,
    ): List<AvailableFormat> {
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

    private fun videoOption(
        raw: VideoStream,
        companionAudio: AudioStream?,
        durationSeconds: Long = 0L,
    ): AvailableFormat {
        val companionId = companionAudio?.content
        val height = raw.height.takeIf { it > 0 } ?: parseHeight(raw.resolution)
        val width = raw.width.takeIf { it > 0 } ?: (height * 16 / 9)
        val extension = raw.format?.suffix ?: "mp4"

        var videoClen = raw.content?.let { url ->
            Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        }
        if (videoClen == null && durationSeconds > 0 && raw.bitrate > 0) {
            videoClen = (raw.bitrate.toLong() * durationSeconds / 8L)
        }

        var audioClen = companionAudio?.content?.let { url ->
            Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        }
        if (audioClen == null && durationSeconds > 0 && (companionAudio?.averageBitrate ?: 0) > 0) {
            audioClen = (companionAudio!!.averageBitrate.toLong() * 1000L * durationSeconds / 8L)
        }

        val totalBytes = if (videoClen != null && audioClen != null) {
            videoClen + audioClen
        } else {
            videoClen ?: audioClen
        }

        val note = when {
            height >= 1080 -> "${height}p Full HD"
            height >= 720 -> "${height}p HD"
            height in 480..540 -> "${height}p SD"
            height in 1..360 -> "${height}p (Data Saver)"
            else -> raw.quality ?: raw.resolution.orEmpty()
        }

        return AvailableFormat(
            key = "video:${raw.resolution.orEmpty()}:${raw.format?.name.orEmpty()}:${companionAudio?.format?.name.orEmpty()}",
            mode = DownloadMode.VIDEO,
            formatId = raw.content.orEmpty(),
            companionAudioFormatId = companionId,
            extension = extension,
            width = width,
            height = height,
            fps = raw.fps.takeIf { it > 0 } ?: 30,
            bitrateKbps = raw.bitrate.takeIf { it > 0 } ?: 0,
            codec = raw.codec.orEmpty(),
            formatNote = note,
            estimatedSizeBytes = totalBytes,
            sizeIsApproximate = raw.content?.contains("clen=") != true,
        )
    }

    private fun parseHeight(resolution: String?): Int {
        if (resolution == null) return 720
        val digits = resolution.filter { it.isDigit() }
        return digits.toIntOrNull() ?: 720
    }

    private fun audioOption(
        raw: AudioStream,
        mode: DownloadMode,
        durationSeconds: Long = 0L,
    ): AvailableFormat {
        val bitrate = (raw.averageBitrate.takeIf { it > 0 } ?: 128).coerceIn(32, 320)
        val extension = raw.format?.suffix ?: "m4a"

        val audioClen = raw.content?.let { url ->
            Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        }
        val estimatedSize = audioClen ?: if (durationSeconds > 0 && bitrate > 0) {
            (bitrate.toLong() * 1000L * durationSeconds / 8L)
        } else null

        val note = when {
            bitrate <= 64 -> "Data Saver (${bitrate} kbps)"
            bitrate <= 128 -> "Standard (${bitrate} kbps)"
            else -> "High Quality (${bitrate} kbps)"
        }

        return AvailableFormat(
            key = "audio:${mode.name.lowercase()}:${raw.format?.name.orEmpty()}:$bitrate",
            mode = mode,
            formatId = raw.content.orEmpty(),
            extension = extension,
            bitrateKbps = bitrate,
            codec = raw.codec.orEmpty(),
            formatNote = note,
            estimatedSizeBytes = estimatedSize,
            sizeIsApproximate = audioClen == null,
        )
    }

    companion object {
        private val QUICK_OUTPUT_HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        private val QUICK_AUDIO_BITRATES = listOf(320, 256, 192, 128, 96)
    }
}
