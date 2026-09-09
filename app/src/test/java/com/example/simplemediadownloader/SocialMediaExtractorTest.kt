package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialMediaExtractorTest {

    @Test
    fun `cleanTitle decodes html entities and cleans prefixes and hashtags`() {
        val raw1 = "1.2M views &middot; 50K reactions &middot; Best Cooking Video &amp; Recipes #food #chef"
        val cleaned1 = SocialMediaExtractor.cleanTitle(raw1)
        assertEquals("Best Cooking Video & Recipes", cleaned1)

        val raw2 = "Rock &amp; Roll &#039;Greatest Hits&#039; &quot;Live&quot; #music"
        val cleaned2 = SocialMediaExtractor.cleanTitle(raw2)
        assertEquals("Rock & Roll 'Greatest Hits' \"Live\"", cleaned2)

        val unicode = "مرحبا بالعالم - فيديو جديد"
        val cleanedUnicode = SocialMediaExtractor.cleanTitle(unicode)
        assertEquals("مرحبا بالعالم - فيديو جديد", cleanedUnicode)
    }

    @Test
    fun `cleanTitle handles empty or blank gracefully`() {
        val empty = SocialMediaExtractor.cleanTitle("   ")
        assertEquals("Media Video", empty)
    }

    @Test
    fun `cleanTitle strips platform wrappers for Instagram and Facebook`() {
        val igTitle = "photography_lover on Instagram: \"Breathtaking mountain view from above\""
        assertEquals("Breathtaking mountain view from above", SocialMediaExtractor.cleanTitle(igTitle))

        val fbTitle = "Amazing Skate Tricks 2024 | Facebook"
        assertEquals("Amazing Skate Tricks 2024", SocialMediaExtractor.cleanTitle(fbTitle))

        val fbTitle2 = "Cooking Tutorial - Facebook"
        assertEquals("Cooking Tutorial", SocialMediaExtractor.cleanTitle(fbTitle2))
    }

    @Test
    fun `format model supports platform httpHeaders including Cookie jar`() {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://www.tiktok.com/",
            "Cookie" to "ttwid=12345; msToken=abcdef",
        )
        val format = AvailableFormat(
            key = "tiktok-hd",
            mode = DownloadMode.VIDEO,
            formatId = "https://v16-webapp-prime.tiktok.com/video.mp4",
            extension = "mp4",
            height = 1080,
            formatNote = "HD No Watermark",
            httpHeaders = headers,
        )

        assertEquals("https://www.tiktok.com/", format.httpHeaders?.get("Referer"))
        assertEquals("Mozilla/5.0", format.httpHeaders?.get("User-Agent"))
        assertEquals("ttwid=12345; msToken=abcdef", format.httpHeaders?.get("Cookie"))
    }

    @Test
    fun `catalog model stores rich author and thumbnail metadata`() {
        val catalog = MediaFormatCatalog(
            sourceUrl = "https://www.tiktok.com/@creator/video/123",
            title = "My TikTok Video",
            videoFormats = emptyList(),
            audioFormats = emptyList(),
            author = "Cool Creator",
            thumbnailUrl = "https://p16-sign.tiktokcdn.com/cover.jpg",
        )

        assertEquals("Cool Creator", catalog.author)
        assertEquals("https://p16-sign.tiktokcdn.com/cover.jpg", catalog.thumbnailUrl)
    }
}
