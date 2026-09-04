package com.example.simplemediadownloader

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
    val percentage: Float? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val status: String = "Preparing download...",
) {
    constructor(percentage: Float, etaSeconds: Long?, status: String) : this(
        percentage = percentage,
        downloadedBytes = null,
        totalBytes = null,
        speedBytesPerSecond = null,
        etaSeconds = etaSeconds,
        status = status,
    )

    val isDeterminate: Boolean
        get() = percentage != null && percentage.isFinite() && totalBytes?.let { it > 0L } == true
}

sealed interface DownloadResult {
    data class Success(val output: DownloadOutput) : DownloadResult
    data object Cancelled : DownloadResult
    data class Failure(val message: String) : DownloadResult
}

data class DownloadOutput(
    val contentUri: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val displayName: String,
)

enum class DownloadFailureCategory(val label: String, val userMessage: String) {
    UNSUPPORTED_SITE(
        "Unsupported site",
        "This site or link is not supported by the current download engine.",
    ),
    PRIVATE_OR_LOGIN_REQUIRED(
        "Private or login required",
        "This media is private or requires a login that the app cannot use.",
    ),
    REMOVED_MEDIA("Removed media", "This media has been removed or is no longer available."),
    DRM_PROTECTED(
        "DRM protected",
        "This media is DRM-protected and cannot be downloaded.",
    ),
    NETWORK_INTERRUPTED(
        "Network interrupted",
        "The connection was interrupted. Check your network and retry.",
    ),
    RATE_LIMITED(
        "Rate limited",
        "The media site is temporarily limiting requests. Wait a while, then retry.",
    ),
    INSUFFICIENT_STORAGE(
        "Insufficient storage",
        "There is not enough available storage to complete this download.",
    ),
    CONVERTER_FAILURE(
        "Converter failure",
        "The media converter could not process the selected format.",
    ),
    ENGINE_UPDATE_REQUIRED(
        "Engine update required",
        "The download engine needs an update before it can handle this media.",
    ),
    ANDROID_INTERRUPTED_TASK(
        "Interrupted by Android",
        "Android interrupted this task. Retry it to start again.",
    ),
    UNKNOWN_FAILURE(
        "Unknown failure",
        "The download failed for an unexpected reason.",
    ),
}

sealed interface DownloadState {
    val progress: DownloadProgress
    val result: DownloadResult? get() = null
    val isTerminal: Boolean get() = result != null

    data object Queued : DownloadState {
        override val progress = DownloadProgress(status = "Queued...")
    }

    data class Preparing(
        override val progress: DownloadProgress = DownloadProgress(),
    ) : DownloadState

    data class Inspecting(
        override val progress: DownloadProgress = DownloadProgress(status = "Inspecting formats..."),
    ) : DownloadState

    data class Downloading(
        override val progress: DownloadProgress,
        val transferKind: DownloadTransferKind = DownloadTransferKind.VIDEO,
    ) : DownloadState

    data class Merging(
        override val progress: DownloadProgress,
    ) : DownloadState

    data class Converting(
        override val progress: DownloadProgress,
    ) : DownloadState

    data class Saving(
        override val progress: DownloadProgress,
    ) : DownloadState

    data class Completed(val output: DownloadOutput) : DownloadState {
        override val progress = DownloadProgress(
            percentage = 100f,
            downloadedBytes = output.fileSizeBytes,
            totalBytes = output.fileSizeBytes,
            status = "Completed",
        )
        override val result = DownloadResult.Success(output)
    }

    data object Cancelled : DownloadState {
        override val progress = DownloadProgress(status = "Cancelled")
        override val result = DownloadResult.Cancelled
    }

    data class Failed(
        val message: String,
        val category: DownloadFailureCategory = DownloadFailureCategory.UNKNOWN_FAILURE,
        val technicalDetail: String? = null,
    ) : DownloadState {
        override val progress = DownloadProgress(status = "Failed")
        override val result = DownloadResult.Failure(message)
    }
}

enum class DownloadTransferKind {
    VIDEO,
    AUDIO,
}

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val format: AvailableFormat,
    val state: DownloadState = DownloadState.Queued,
    val platform: String = PlatformResolver.fromUrl(url),
    val createdAt: Long = 0L,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
) {
    val progress: DownloadProgress get() = state.progress
    val result: DownloadResult? get() = state.result
    val isActive: Boolean get() = !state.isTerminal
}

data class BackendState(
    val initializing: Boolean = false,
    val youtubeDlReady: Boolean = true,
    val ffmpegInitializing: Boolean = false,
    val ffmpegReady: Boolean = true,
    val error: String? = null,
) {
    val ready: Boolean get() = !initializing && youtubeDlReady
}
