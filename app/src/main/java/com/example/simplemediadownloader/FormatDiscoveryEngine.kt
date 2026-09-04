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
}

typealias YtDlpFormatDiscoveryEngine = NewPipeFormatDiscoveryEngine

class NewPipeFormatDiscoveryEngine(
    private val dispatchers: AppDispatchers = AppDispatchers(),
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

    private val okHttpClient = OkHttpClient.Builder().build()

    override suspend fun discoverFormats(url: String): FormatDiscoveryResult =
        withContext(dispatchers.io) {
            val platform = PlatformResolver.fromUrl(url)
            if (platform in listOf("Facebook", "TikTok", "Instagram", "X", "Reddit")) {
                return@withContext SocialMediaExtractor.extract(okHttpClient, url, platform)
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
        val audioStreams = extractor.audioStreams.orEmpty()
        val preferredAudio = audioStreams.maxWithOrNull(
            compareBy<AudioStream>(
                { if (it.format?.suffix?.equals("m4a", ignoreCase = true) == true) 1 else 0 },
                { it.averageBitrate },
            ),
        )

        val progressiveVideos = extractor.videoStreams.orEmpty()
            .map { raw -> videoOption(raw, companionAudio = null) }

        val adaptiveVideos = extractor.videoOnlyStreams.orEmpty()
            .map { raw -> videoOption(raw, companionAudio = preferredAudio) }

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

        val audioFormats = audioStreams
            .flatMap { raw ->
                listOf(
                    audioOption(raw, DownloadMode.AUDIO_ORIGINAL),
                    audioOption(raw, DownloadMode.AUDIO_MP3),
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
    ): AvailableFormat {
        val companionId = companionAudio?.content
        val height = raw.height.takeIf { it > 0 } ?: parseHeight(raw.resolution)
        val width = raw.width.takeIf { it > 0 } ?: (height * 16 / 9)
        val extension = raw.format?.suffix ?: "mp4"

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
            formatNote = raw.quality ?: raw.resolution.orEmpty(),
            estimatedSizeBytes = null,
            sizeIsApproximate = true,
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
    ): AvailableFormat {
        val bitrate = (raw.averageBitrate.takeIf { it > 0 } ?: 128).coerceIn(32, 320)
        val extension = if (mode == DownloadMode.AUDIO_MP3) "mp3" else (raw.format?.suffix ?: "m4a")

        return AvailableFormat(
            key = "audio:${mode.name.lowercase()}:${raw.format?.name.orEmpty()}:$bitrate",
            mode = mode,
            formatId = raw.content.orEmpty(),
            extension = extension,
            bitrateKbps = bitrate,
            codec = raw.codec.orEmpty(),
            formatNote = raw.quality.orEmpty(),
            estimatedSizeBytes = null,
            sizeIsApproximate = true,
        )
    }

    companion object {
        private val QUICK_OUTPUT_HEIGHTS = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        private val QUICK_AUDIO_BITRATES = listOf(320, 256, 192, 128, 96)
    }
}
