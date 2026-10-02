package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalFailureMapperTest {
    @Test
    fun `representative download engine media processor storage and Android failures map to friendly categories`() {
        val cases = listOf(
            Case("ERROR: Unsupported URL: https://example.test/file", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.UNSUPPORTED_SITE),
            Case("ERROR: Private video. Sign in to confirm your age", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.PRIVATE_OR_LOGIN_REQUIRED),
            Case("ERROR: Video has been removed by the uploader", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.REMOVED_MEDIA),
            Case("ERROR: This video is DRM protected", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.DRM_PROTECTED),
            Case("ERROR: Unable to download webpage: connection reset", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.NETWORK_INTERRUPTED),
            Case("ERROR: HTTP Error 429: Too Many Requests", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.RATE_LIMITED),
            Case("java.io.IOException: No space left on device", FailureOrigin.STORAGE, DownloadFailureCategory.INSUFFICIENT_STORAGE),
            Case("Invalid data found when processing input", FailureOrigin.MEDIA_PROCESSOR, DownloadFailureCategory.CONVERTER_FAILURE),
            Case("WARNING: Signature extraction failed; please update", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.ENGINE_UPDATE_REQUIRED),
            Case("Foreground service timed out while downloading", FailureOrigin.ANDROID, DownloadFailureCategory.ANDROID_INTERRUPTED_TASK),
            Case("ERROR: an unexpected extractor response", FailureOrigin.DOWNLOAD_ENGINE, DownloadFailureCategory.UNKNOWN_FAILURE),
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
        val mapped = TechnicalFailureMapper.map(technical, FailureOrigin.DOWNLOAD_ENGINE)

        assertEquals(DownloadFailureCategory.UNKNOWN_FAILURE, mapped.category)
        assertEquals(DownloadFailureCategory.UNKNOWN_FAILURE.userMessage, mapped.message)
        assertEquals(technical, mapped.technicalDetail)
    }

    @Test
    fun `failure origin entries and media muxer errors are mapped properly`() {
        val allOrigins = FailureOrigin.values().toSet()
        assertTrue(allOrigins.contains(FailureOrigin.DOWNLOAD_ENGINE))
        assertTrue(allOrigins.contains(FailureOrigin.MEDIA_PROCESSOR))
        val mapped = TechnicalFailureMapper.map("fatal: media muxer error encountered", FailureOrigin.MEDIA_PROCESSOR)
        assertEquals(DownloadFailureCategory.CONVERTER_FAILURE, mapped.category)
    }

    private data class Case(
        val output: String,
        val origin: FailureOrigin,
        val expected: DownloadFailureCategory,
    )
}
