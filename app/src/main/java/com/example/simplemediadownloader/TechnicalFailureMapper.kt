package com.example.simplemediadownloader

enum class FailureOrigin {
    YT_DLP,
    FFMPEG,
    STORAGE,
    ANDROID,
}

internal object TechnicalFailureMapper {
    fun map(
        technicalOutput: String,
        origin: FailureOrigin,
        fallback: String = DownloadFailureCategory.UNKNOWN_FAILURE.userMessage,
    ): MappedDownloadFailure {
        val raw = technicalOutput.ifBlank { "No technical output was provided." }.take(4_000)
        val normalized = raw.lowercase()
        val category = when {
            origin == FailureOrigin.ANDROID || matches(
                normalized,
                "foreground service timed out",
                "app process ended while",
                "android stopped",
            ) -> DownloadFailureCategory.ANDROID_INTERRUPTED_TASK
            matches(normalized, "no space left on device", "enospc", "not enough available storage") ->
                DownloadFailureCategory.INSUFFICIENT_STORAGE
            origin == FailureOrigin.FFMPEG || matches(
                normalized,
                "ffmpeg error",
                "postprocessing error",
                "conversion failed",
                "error while filtering",
                "unable to find a suitable output format",
            ) -> DownloadFailureCategory.CONVERTER_FAILURE
            matches(normalized, "http error 429", "http 429", "too many requests", "rate limit") ->
                DownloadFailureCategory.RATE_LIMITED
            matches(normalized, "drm protected", "this video is drm", "digital rights management") ->
                DownloadFailureCategory.DRM_PROTECTED
            matches(
                normalized,
                "private video",
                "login required",
                "sign in to confirm",
                "use --cookies",
                "authentication required",
                "members-only",
            ) -> DownloadFailureCategory.PRIVATE_OR_LOGIN_REQUIRED
            matches(
                normalized,
                "video has been removed",
                "video unavailable",
                "media is unavailable",
                "this video is no longer available",
                "deleted by the uploader",
                "not found (caused by",
            ) -> DownloadFailureCategory.REMOVED_MEDIA
            matches(
                normalized,
                "please update",
                "update to a newer version",
                "signature extraction failed",
                "nsig extraction failed",
                "unsupported javascript runtime",
                "universal data for rehydration",
            ) -> DownloadFailureCategory.ENGINE_UPDATE_REQUIRED
            matches(
                normalized,
                "unsupported url",
                "no suitable extractor",
                "unsupported site",
                "no service can handle the url",
            ) ->
                DownloadFailureCategory.UNSUPPORTED_SITE
            matches(
                normalized,
                "timed out",
                "connection reset",
                "network is unreachable",
                "temporary failure in name resolution",
                "name or service not known",
                "no address associated with hostname",
                "unable to download webpage",
                "remote end closed connection",
                "connection aborted",
            ) -> DownloadFailureCategory.NETWORK_INTERRUPTED
            else -> DownloadFailureCategory.UNKNOWN_FAILURE
        }
        val message = when (category) {
            DownloadFailureCategory.UNKNOWN_FAILURE -> fallback
            else -> category.userMessage
        }
        return MappedDownloadFailure(category, message, raw)
    }

    private fun matches(value: String, vararg patterns: String): Boolean =
        patterns.any(value::contains)
}
