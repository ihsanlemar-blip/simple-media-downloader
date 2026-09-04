package com.example.simplemediadownloader

import java.net.URI

internal object YtDlpSupport {
    fun needsEjs(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase()
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }.getOrDefault(false)

    fun humanReadableError(
        error: Exception,
        fallback: String = "Download failed. Check the URL and your internet connection.",
    ): String = mapFailure(error, fallback).message

    fun mapFailure(
        error: Exception,
        fallback: String = DownloadFailureCategory.UNKNOWN_FAILURE.userMessage,
    ): MappedDownloadFailure = TechnicalFailureMapper.map(
        technicalOutput = error.message.orEmpty().ifBlank { error.stackTraceToString() },
        origin = FailureOrigin.YT_DLP,
        fallback = fallback,
    )
}

internal data class MappedDownloadFailure(
    val category: DownloadFailureCategory,
    val message: String,
    val technicalDetail: String,
)
