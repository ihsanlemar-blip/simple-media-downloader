package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformExtractorAdaptersTest {

    private val client = OkHttpClient()

    @Test
    fun `InstagramExtractor extracts shortcodes from diverse URL patterns`() {
        assertEquals("C-xyz123", InstagramExtractor.extractInstagramShortcode("https://www.instagram.com/p/C-xyz123/"))
        assertEquals("D_abc456", InstagramExtractor.extractInstagramShortcode("https://www.instagram.com/reel/D_abc456/?igsh=123"))
        assertEquals("E_789ghi", InstagramExtractor.extractInstagramShortcode("https://instagram.com/tv/E_789ghi/"))
    }

    @Test
    fun `FacebookExtractor correctly extracts IDs and parses HTML fixtures`() {
        val reelUrl = "https://www.facebook.com/reel/998877665544/"
        assertEquals("998877665544", FacebookExtractor.extractFacebookId(reelUrl))

        val htmlFixture = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="Viral Video Title - Facebook">
                <meta property="og:image" content="https://scontent.fbcdn.net/thumb.jpg">
            </head>
            <body>
                <script>
                    {"browser_native_hd_url":"https://video.fbcdn.net/hd.mp4","browser_native_sd_url":"https://video.fbcdn.net/sd.mp4"}
                </script>
            </body>
            </html>
        """.trimIndent()

        val catalog = FacebookExtractor.parseFacebookHtml(client, htmlFixture, reelUrl)
        assertNotNull(catalog)
        assertEquals("Viral Video Title", catalog?.title)
        assertTrue(catalog?.videoFormats?.any { it.key == "fb-hd" } == true)
        assertTrue(catalog?.videoFormats?.any { it.key == "fb-sd" } == true)
        assertTrue(catalog?.audioFormats?.any { it.key == "fb-audio-extract" } == true)
    }

    @Test
    fun `ExtractorSharedUtils unescapes complex JSON and HTML encoded URLs`() {
        val escaped = "https:\\/\\/video-fra3-2.xx.fbcdn.net\\/o1\\/v\\/t2\\/f2?a=1&amp;b=2"
        val unescaped = ExtractorSharedUtils.unescapeJsonUrl(escaped)
        assertEquals("https://video-fra3-2.xx.fbcdn.net/o1/v/t2/f2?a=1&b=2", unescaped)
    }

    @Test
    fun `ExtractorSharedUtils cleans titles accurately across platforms`() {
        val raw = "10M plays &middot; Amazing Performance #viral #live"
        assertEquals("Amazing Performance", ExtractorSharedUtils.cleanTitle(raw))
    }
}
