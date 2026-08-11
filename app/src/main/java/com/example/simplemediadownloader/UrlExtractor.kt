package com.example.simplemediadownloader

object UrlExtractor {
    private val httpUrl = Regex("""https?://[^\s<>\"']+""", RegexOption.IGNORE_CASE)
    private val trailingPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun extractFirstHttpUrl(text: String?): String? {
        val match = text?.let(httpUrl::find) ?: return null
        return match.value.trimEnd(*trailingPunctuation).takeIf { it.length > "https://".length }
    }
}

