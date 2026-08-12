package com.example.simplemediadownloader

import java.io.File

enum class DownloadMode(val label: String) {
    VIDEO("Video"),
    AUDIO_ORIGINAL("Original audio"),
    AUDIO_MP3("MP3"),
}

data class AvailableFormat(
    val key: String,
    val mode: DownloadMode,
    val formatId: String,
    val companionAudioFormatId: String? = null,
    val extension: String,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val codec: String = "",
    val formatNote: String = "",
    val estimatedSizeBytes: Long? = null,
    val sizeIsApproximate: Boolean = true,
    val sourceHeight: Int = height,
    val requiresDownscale: Boolean = false,
    val isQuickPreset: Boolean = false,
) {
    val requiresFfmpeg: Boolean
        get() = requiresDownscale || companionAudioFormatId != null || mode == DownloadMode.AUDIO_MP3
}

data class MediaFormatCatalog(
    val sourceUrl: String,
    val title: String,
    val videoFormats: List<AvailableFormat>,
    val audioFormats: List<AvailableFormat>,
    val detailsLoading: Boolean = false,
)

sealed interface FormatDiscoveryResult {
    data class Success(val catalog: MediaFormatCatalog) : FormatDiscoveryResult
    data class Failure(val message: String) : FormatDiscoveryResult
}

data class DownloadProgress(
    val percentage: Float = 0f,
    val etaSeconds: Long? = null,
    val status: String = "Preparing download...",
)

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data object Cancelled : DownloadResult
    data class Failure(val message: String) : DownloadResult
}

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val format: AvailableFormat,
    val progress: DownloadProgress = DownloadProgress(),
    val result: DownloadResult? = null,
    val isActive: Boolean = true,
)

data class BackendState(
    val initializing: Boolean = true,
    val youtubeDlReady: Boolean = false,
    val ffmpegInitializing: Boolean = false,
    val ffmpegReady: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = !initializing && youtubeDlReady
}
