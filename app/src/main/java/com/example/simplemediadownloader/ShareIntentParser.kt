package com.example.simplemediadownloader

import android.content.Intent
import java.net.URI

sealed interface SharedUrlResult {
    data class Valid(
        val url: String,
        val additionalUrlDetected: Boolean,
    ) : SharedUrlResult

    data class Invalid(val message: String) : SharedUrlResult
}

object ShareIntentParser {
    internal const val MAX_SHARED_TEXT_LENGTH = 32_768
    internal const val MAX_URL_LENGTH = 4_096

    fun parse(intent: Intent?): SharedUrlResult {
        if (intent?.action != Intent.ACTION_SEND) {
            return SharedUrlResult.Invalid("This share request is not supported.")
        }
        if (!intent.type.equals("text/plain", ignoreCase = true)) {
            return SharedUrlResult.Invalid("Only shared text links are supported.")
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: return SharedUrlResult.Invalid("No link was included in the shared item.")
        return parseText(text)
    }

    internal fun parseText(text: String): SharedUrlResult {
        if (text.isBlank()) {
            return SharedUrlResult.Invalid("No link was included in the shared item.")
        }
        if (text.length > MAX_SHARED_TEXT_LENGTH) {
            return SharedUrlResult.Invalid("The shared text is too long to inspect safely.")
        }
        val url = UrlExtractor.extractFirstHttpUrl(text)
            ?: return SharedUrlResult.Invalid("The shared text does not contain a valid web link.")
        if (url.length > MAX_URL_LENGTH || !isValidHttpUrl(url)) {
            return SharedUrlResult.Invalid("The shared link is malformed or too long.")
        }
        val remainingText = text.substringAfter(url, missingDelimiterValue = "")
        return SharedUrlResult.Valid(
            url = url,
            additionalUrlDetected = UrlExtractor.extractFirstHttpUrl(remainingText) != null,
        )
    }

    private fun isValidHttpUrl(url: String): Boolean = runCatching {
        val parsed = URI(url)
        (parsed.scheme.equals("http", ignoreCase = true) ||
            parsed.scheme.equals("https", ignoreCase = true)) &&
            !parsed.host.isNullOrBlank() &&
            NetworkSecurityPolicy.isAllowedShareUrl(url)
    }.getOrDefault(false)
}

internal fun shortenedSourceUrl(url: String, maximumLength: Int = 72): String {
    if (url.length <= maximumLength) return url
    val edgeLength = ((maximumLength - 1) / 2).coerceAtLeast(1)
    return url.take(edgeLength) + "…" + url.takeLast(edgeLength)
}
