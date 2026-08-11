package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    @Test
    fun `extracts first URL from shared text`() {
        val shared = "Watch this: https://example.com/watch?v=42 and ignore https://second.test/"

        assertEquals("https://example.com/watch?v=42", UrlExtractor.extractFirstHttpUrl(shared))
    }

    @Test
    fun `extracts URL from clipboard text and trims punctuation`() {
        val clipboard = "Copied from an app (http://media.example.test/video/123)."

        assertEquals("http://media.example.test/video/123", UrlExtractor.extractFirstHttpUrl(clipboard))
    }

    @Test
    fun `returns null when text has no web URL`() {
        assertNull(UrlExtractor.extractFirstHttpUrl("example.com is not a complete URL"))
        assertNull(UrlExtractor.extractFirstHttpUrl(null))
    }
}

