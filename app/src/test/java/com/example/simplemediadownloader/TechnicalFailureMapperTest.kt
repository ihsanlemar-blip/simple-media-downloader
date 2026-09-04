package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalFailureMapperTest {
    @Test
    fun `representative yt-dlp ffmpeg storage and Android failures map to friendly categories`() {
        val cases = listOf(
            Case("ERROR: Unsupported URL: https://example.test/file", FailureOrigin.YT_DLP, DownloadFailureCategory.UNSUPPORTED_SITE),
            Case("ERROR: Private video. Sign in to confirm your age", FailureOrigin.YT_DLP, DownloadFailureCategory.PRIVATE_OR_LOGIN_REQUIRED),
            Case("ERROR: Video has been removed by the uploader", FailureOrigin.YT_DLP, DownloadFailureCategory.REMOVED_MEDIA),
            Case("ERROR: This video is DRM protected", FailureOrigin.YT_DLP, DownloadFailureCategory.DRM_PROTECTED),
            Case("ERROR: Unable to download webpage: connection reset", FailureOrigin.YT_DLP, DownloadFailureCategory.NETWORK_INTERRUPTED),
            Case("ERROR: HTTP Error 429: Too Many Requests", FailureOrigin.YT_DLP, DownloadFailureCategory.RATE_LIMITED),
            Case("java.io.IOException: No space left on device", FailureOrigin.STORAGE, DownloadFailureCategory.INSUFFICIENT_STORAGE),
            Case("Invalid data found when processing input", FailureOrigin.FFMPEG, DownloadFailureCategory.CONVERTER_FAILURE),
            Case("WARNING: Signature extraction failed; please update", FailureOrigin.YT_DLP, DownloadFailureCategory.ENGINE_UPDATE_REQUIRED),
            Case("Foreground service timed out while downloading", FailureOrigin.ANDROID, DownloadFailureCategory.ANDROID_INTERRUPTED_TASK),
            Case("ERROR: an unexpected extractor response", FailureOrigin.YT_DLP, DownloadFailureCategory.UNKNOWN_FAILURE),
        )

        cases.forEach { case ->
            val mapped = TechnicalFailureMapper.map(case.output, case.origin)
            assertEquals(case.expected, mapped.category)
            assertEquals(case.expected.userMessage, mapped.message)
            assertTrue(mapped.technicalDetail.contains(case.output))
        }
    }

    @Test
    fun `unknown failure keeps technical output out of the friendly message`() {
        val technical = "ERROR: server emitted opaque token X-12345"
        val mapped = TechnicalFailureMapper.map(technical, FailureOrigin.YT_DLP)

        assertEquals(DownloadFailureCategory.UNKNOWN_FAILURE, mapped.category)
        assertEquals(DownloadFailureCategory.UNKNOWN_FAILURE.userMessage, mapped.message)
        assertEquals(technical, mapped.technicalDetail)
    }

    private data class Case(
        val output: String,
        val origin: FailureOrigin,
        val expected: DownloadFailureCategory,
    )
}
