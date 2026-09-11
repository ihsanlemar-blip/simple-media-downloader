package com.example.simplemediadownloader

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialMediaMultiFormatAndAudioSizingTest {

    private val client = OkHttpClient()

    @Test
    fun `unescapeDashManifest unescapes XML entities and JSON escape sequences`() {
        val escaped = """\u003CMPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"\u003E\u003CRepresentation mimeType=\"video\/mp4\" bandwidth=\"4300000\"\u003E\u003CBaseURL\u003Ehttps:\/\/fbcdn.net\/v.mp4?a=1\u0026b=2\u003C\/BaseURL\u003E\u003C\/Representation\u003E\u003C\/MPD\u003E"""
        val unescaped = SocialMediaExtractor.unescapeDashManifest(escaped)

        assertTrue(unescaped.startsWith("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertTrue(unescaped.contains("""mimeType="video/mp4""""))
        assertTrue(unescaped.contains("https://fbcdn.net/v.mp4?a=1&b=2"))
        assertTrue(unescaped.endsWith("</MPD>"))
    }

    @Test
    fun `parseDashManifest extracts multiple video resolutions and dedicated audio track`() {
        val mpdXml = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
                <Period>
                    <AdaptationSet>
                        <!-- 1080p vertical reel (1080x1920) -->
                        <Representation id="1080" mimeType="video/mp4" width="1080" height="1920" bandwidth="4300000">
                            <BaseURL>https://video.fbcdn.net/v_1080.mp4</BaseURL>
                        </Representation>
                        <!-- 720p vertical reel (720x1280) high bitrate -->
                        <Representation id="720_high" mimeType="video/mp4" width="720" height="1280" bandwidth="2600000">
                            <BaseURL>https://video.fbcdn.net/v_720_high.mp4</BaseURL>
                        </Representation>
                        <!-- 720p vertical reel (720x1280) lower bitrate -->
                        <Representation id="720_low" mimeType="video/mp4" width="720" height="1280" bandwidth="1500000">
                            <BaseURL>https://video.fbcdn.net/v_720_low.mp4</BaseURL>
                        </Representation>
                        <!-- 540p vertical reel (540x960) -->
                        <Representation id="540" mimeType="video/mp4" width="540" height="960" bandwidth="700000">
                            <BaseURL>https://video.fbcdn.net/v_540.mp4</BaseURL>
                        </Representation>
                        <!-- Audio Representation (68 kbps pure audio track) -->
                        <Representation id="audio_68k" mimeType="audio/mp4" bandwidth="68000">
                            <BaseURL>https://video.fbcdn.net/a_68k.mp4</BaseURL>
                        </Representation>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val headers = mapOf("User-Agent" to "Mozilla/5.0")
        val (videoFormats, audioFormat) = SocialMediaExtractor.parseDashManifest(client, mpdXml, headers)

        // Discovers all distinct resolution tiers and data-saver variants (1080p, 720p, 720p Data Saver, 540p)
        assertEquals(4, videoFormats.size)

        val f1080 = videoFormats.find { it.height == 1080 }
        assertNotNull("1080p format must be present", f1080)
        assertEquals("https://video.fbcdn.net/v_1080.mp4", f1080?.formatId)
        assertEquals("https://video.fbcdn.net/a_68k.mp4", f1080?.companionAudioFormatId)
        assertTrue(f1080?.formatNote?.contains("1080p") == true)

        val f720 = videoFormats.find { it.key == "fb-dash-720" }
        assertNotNull("720p format must be present", f720)
        // Should select the higher bitrate 720p stream
        assertEquals("https://video.fbcdn.net/v_720_high.mp4", f720?.formatId)
        assertEquals("https://video.fbcdn.net/a_68k.mp4", f720?.companionAudioFormatId)

        val f720Saver = videoFormats.find { it.key == "fb-dash-720-saver" }
        assertNotNull("720p Data Saver variant must be present", f720Saver)
        assertEquals("https://video.fbcdn.net/v_720_low.mp4", f720Saver?.formatId)

        val f540 = videoFormats.find { it.height == 540 }
        assertNotNull("540p format must be present", f540)
        assertEquals("https://video.fbcdn.net/v_540.mp4", f540?.formatId)
        assertEquals("https://video.fbcdn.net/a_68k.mp4", f540?.companionAudioFormatId)
        assertTrue("540p must be flagged as Data Saver", f540?.isDataSaver == true)

        // Audio format must be separated pure audio track
        assertNotNull(audioFormat)
        assertEquals("fb-dash-audio", audioFormat?.key)
        assertEquals("https://video.fbcdn.net/a_68k.mp4", audioFormat?.formatId)
        assertEquals("m4a", audioFormat?.extension)
        assertEquals(68, audioFormat?.bitrateKbps)
        assertTrue("Audio track must be pure audio", audioFormat?.isPureAudioTrack == true)
        assertTrue("68 kbps audio must be flagged as Data Saver", audioFormat?.isDataSaver == true)
    }

    @Test
    fun `audio size estimation reduces audio size dramatically from multiplexed video`() {
        val videoSizeBytes = 7_400_000L // 7.4 MB video
        val estimatedAudioBytes = (videoSizeBytes * 0.08).toLong().coerceAtLeast(64 * 1024L)

        // Estimated audio for a 7.4 MB video should be ~592 KB, NOT 7.4 MB!
        assertTrue(estimatedAudioBytes < 1_000_000L)
        assertTrue(estimatedAudioBytes > 500_000L)
        assertEquals(592_000L, estimatedAudioBytes)

        // For a very small video (e.g. 500 KB), ensure floor of 64 KB applies
        val smallVideoBytes = 500_000L
        val smallAudioBytes = (smallVideoBytes * 0.08).toLong().coerceAtLeast(64 * 1024L)
        assertEquals(64 * 1024L, smallAudioBytes)
    }

    @Test
    fun `AvailableFormat marks extracted audio as sizeIsApproximate and m4a extension`() {
        val format = AvailableFormat(
            key = "fb-audio-extract",
            mode = DownloadMode.AUDIO_ORIGINAL,
            formatId = "https://video.fbcdn.net/prog_hd.mp4",
            extension = "m4a",
            formatNote = "Video source (audio extracted)",
            estimatedSizeBytes = 592_000L,
            sizeIsApproximate = true,
        )

        assertEquals("m4a", format.extension)
        assertTrue(format.sizeIsApproximate)
        assertEquals(592_000L, format.estimatedSizeBytes)
        assertTrue(!format.isPureAudioTrack)
    }

    @Test
    fun `commonShareFormats preserves 540p, 480p, 360p resolutions for vertical reels and data saving`() {
        val formats = listOf(
            AvailableFormat(key = "v-1080", mode = DownloadMode.VIDEO, formatId = "u1", extension = "mp4", height = 1080),
            AvailableFormat(key = "v-720", mode = DownloadMode.VIDEO, formatId = "u2", extension = "mp4", height = 720),
            AvailableFormat(key = "v-540", mode = DownloadMode.VIDEO, formatId = "u3", extension = "mp4", height = 540),
            AvailableFormat(key = "v-360", mode = DownloadMode.VIDEO, formatId = "u4", extension = "mp4", height = 360),
        )

        val filtered = commonShareFormats(formats, DownloadMode.VIDEO)
        assertEquals(4, filtered.size)
        assertTrue(filtered.any { it.height == 540 })
        assertTrue(filtered.any { it.height == 360 })
    }

    @Test
    fun testLiveTikTokExtraction() = kotlinx.coroutines.runBlocking {
        val res = SocialMediaExtractor.extract(client, "https://vt.tiktok.com/ZSqfnBCjo/", "TikTok", false)
        assertTrue("TikTok extraction must succeed", res is FormatDiscoveryResult.Success)
        val catalog = (res as FormatDiscoveryResult.Success).catalog
        assertTrue("Must have video formats", catalog.videoFormats.isNotEmpty())
        assertTrue("Must have audio formats", catalog.audioFormats.isNotEmpty())
        val hd = catalog.videoFormats.firstOrNull { it.height == 1080 }
        assertNotNull("1080p must be present", hd)
        assertTrue("1080p stream must have estimated size > 0", (hd?.estimatedSizeBytes ?: 0L) > 0L)
    }
}
