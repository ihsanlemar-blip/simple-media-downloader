package com.example.simplemediadownloader

import java.util.Locale

/** Product defaults apply only when creating a new audio request. */
object PlatformAudioPolicy {
    const val DEFAULT_MP3_BITRATE_KBPS = 192
    val MP3_BITRATES = listOf(128, 192, 256, 320)

    fun defaultAudioMode(platform: String): DownloadMode =
        if (platform.equals("YouTube", ignoreCase = true)) DownloadMode.AUDIO_MP3
        else DownloadMode.AUDIO_ORIGINAL

    fun defaultMp3Bitrate(platform: String): Int = DEFAULT_MP3_BITRATE_KBPS
    fun mp3ExpandedByDefault(platform: String): Boolean = defaultAudioMode(platform) == DownloadMode.AUDIO_MP3

    fun selectAudio(catalog: MediaFormatCatalog, bitrateKbps: Int = DEFAULT_MP3_BITRATE_KBPS): AvailableFormat? {
        val mode = defaultAudioMode(PlatformResolver.fromUrl(catalog.sourceUrl))
        return if (mode == DownloadMode.AUDIO_MP3) {
            catalog.audioFormats.firstOrNull { it.mode == mode && it.targetAudioBitrateKbps == bitrateKbps }
        } else AudioFormatOptions.bestSource(catalog.audioFormats.filter { it.mode == mode })
    }
}

object Mp3SizeEstimator {
    fun bytes(durationSeconds: Long?, bitrateKbps: Int): Long? {
        require(bitrateKbps in PlatformAudioPolicy.MP3_BITRATES)
        val seconds = durationSeconds?.takeIf { it > 0 } ?: return null
        val bytesPerSecond = bitrateKbps * 125L
        return if (seconds > Long.MAX_VALUE / bytesPerSecond) null else seconds * bytesPerSecond
    }

    fun display(bytes: Long?): String = bytes?.let {
        String.format(Locale.US, "~%.1f MB", it / 1_000_000.0)
    } ?: "Size unavailable"
}

/** Source discovery is independent from requested output; conversion is never an extractor job. */
object AudioFormatOptions {
    fun bestSource(formats: List<AvailableFormat>): AvailableFormat? {
        val native = formats.filter { it.mode == DownloadMode.AUDIO_ORIGINAL }
        val audioOnly = native.filter { it.isPureAudioTrack }
        val candidates = audioOnly.ifEmpty { native }
        // Stay in the highest quality tier; prefer AAC/M4A compatibility, then smallest source.
        val maxBitrate = candidates.maxOfOrNull { it.bitrateKbps } ?: return null
        val qualityTier = candidates.filter { it.bitrateKbps >= maxBitrate * 0.8 }
        return qualityTier.minWithOrNull(compareBy<AvailableFormat>(
            { when (it.sourceExtension.lowercase(Locale.US)) { "m4a", "aac" -> 0; "webm", "opus" -> 1; else -> 2 } },
            { it.sourceSizeBytes ?: it.estimatedSizeBytes ?: Long.MAX_VALUE },
            { -it.bitrateKbps },
        ))
    }

    fun mp3(source: AvailableFormat, bitrateKbps: Int, durationSeconds: Long?): AvailableFormat {
        require(bitrateKbps in PlatformAudioPolicy.MP3_BITRATES)
        return source.copy(
            key = "${source.key}:mp3-$bitrateKbps",
            mode = DownloadMode.AUDIO_MP3,
            extension = "mp3",
            sourceExtension = source.sourceExtension,
            sourceSizeBytes = source.sourceSizeBytes ?: source.estimatedSizeBytes,
            companionAudioFormatId = null,
            bitrateKbps = bitrateKbps,
            targetAudioBitrateKbps = bitrateKbps,
            durationSeconds = durationSeconds,
            estimatedSizeBytes = Mp3SizeEstimator.bytes(durationSeconds, bitrateKbps),
            sizeIsApproximate = true,
            codec = "MPEG Layer III",
            formatNote = when (bitrateKbps) {
                128 -> "Smaller file"
                192 -> "Recommended"
                256 -> "Larger file"
                else -> "Maximum MP3 bitrate"
            } + if (!source.isPureAudioTrack) " • Video source (audio decoded only)" else "",
        )
    }

    fun augment(catalog: MediaFormatCatalog): MediaFormatCatalog {
        val native = catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_ORIGINAL }.map { audio ->
            val video = catalog.videoFormats.firstOrNull { it.formatId == audio.formatId }
            audio.copy(
                sourceExtension = video?.extension ?: audio.sourceExtension,
                sourceSizeBytes = video?.estimatedSizeBytes ?: audio.sourceSizeBytes ?: audio.estimatedSizeBytes,
            )
        }
        var source = bestSource(native) ?: return catalog
        if (!source.isPureAudioTrack) {
            val progressive = catalog.videoFormats.filter {
                it.companionAudioFormatId == null && !it.formatNote.contains("Muted", true)
            }.minWithOrNull(compareBy<AvailableFormat>({ it.estimatedSizeBytes ?: Long.MAX_VALUE }, { it.height }))
            if (progressive != null) source = source.copy(
                formatId = progressive.formatId,
                sourceExtension = progressive.extension,
                sourceSizeBytes = progressive.estimatedSizeBytes,
                httpHeaders = progressive.httpHeaders ?: source.httpHeaders,
            )
        }
        val duration = catalog.durationSeconds ?: source.durationSeconds
        val mp3 = PlatformAudioPolicy.MP3_BITRATES.map { mp3(source, it, duration) }
        return catalog.copy(audioFormats = native + mp3)
    }
}
