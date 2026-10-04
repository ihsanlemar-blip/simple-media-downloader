package com.example.simplemediadownloader

import org.junit.Assert.*
import org.junit.Test

class PlatformAudioPolicyTest {
    @Test fun `YouTube defaults to MP3 192 and other platforms preserve original`() {
        assertEquals(DownloadMode.AUDIO_MP3, PlatformAudioPolicy.defaultAudioMode("YouTube"))
        assertEquals(192, PlatformAudioPolicy.defaultMp3Bitrate("YouTube"))
        listOf("Facebook", "Instagram", "TikTok", "X", "Twitter", "Reddit", "Unknown", "Future").forEach {
            assertEquals(it, DownloadMode.AUDIO_ORIGINAL, PlatformAudioPolicy.defaultAudioMode(it))
            assertFalse(PlatformAudioPolicy.mp3ExpandedByDefault(it))
        }
    }
    @Test fun `estimates bytes and decimal approximate MB from target bitrate only`() {
        listOf(128 to 4_800_000L, 192 to 7_200_000L, 256 to 9_600_000L, 320 to 12_000_000L).forEach { (bitrate, bytes) ->
            assertEquals(bytes, Mp3SizeEstimator.bytes(300, bitrate))
            assertEquals("~${bytes / 1_000_000}.${bytes / 100_000 % 10} MB", Mp3SizeEstimator.display(bytes))
        }
        assertNull(Mp3SizeEstimator.bytes(null, 192))
        assertNull(Mp3SizeEstimator.bytes(0, 192))
        assertNull(Mp3SizeEstimator.bytes(-1, 192))
        assertNull(Mp3SizeEstimator.bytes(Long.MAX_VALUE, 320))
        assertEquals("Size unavailable", Mp3SizeEstimator.display(null))
    }
    @Test fun `MP3 variants reuse audio source and keep original available`() {
        val source = AvailableFormat("native", DownloadMode.AUDIO_ORIGINAL, "https://cdn.example/audio", extension = "webm", bitrateKbps = 160, estimatedSizeBytes = 100,
            httpHeaders = mapOf("Referer" to "https://example.com"))
        val catalog = AudioFormatOptions.augment(MediaFormatCatalog("https://youtube.com/watch?v=1", "Title", emptyList(), listOf(source), durationSeconds = 300))
        assertEquals(listOf(128, 192, 256, 320), catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_MP3 }.map { it.targetAudioBitrateKbps })
        assertEquals(1, catalog.audioFormats.count { it.mode == DownloadMode.AUDIO_ORIGINAL })
        catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_MP3 }.forEach {
            assertEquals(source.formatId, it.formatId)
            assertEquals(source.httpHeaders, it.httpHeaders)
            assertEquals("webm", it.sourceExtension)
            assertEquals("mp3", it.outputExtension)
            assertEquals(Mp3SizeEstimator.bytes(300, it.bitrateKbps), it.estimatedSizeBytes)
            assertTrue(it.requiresAudioTranscode)
            assertFalse(it.requiresMuxing)
        }
        assertEquals(192, PlatformAudioPolicy.selectAudio(catalog)!!.targetAudioBitrateKbps)
        assertEquals(320, PlatformAudioPolicy.selectAudio(catalog, 320)!!.targetAudioBitrateKbps)
        assertEquals(DownloadMode.AUDIO_ORIGINAL, PlatformAudioPolicy.selectAudio(catalog.copy(sourceUrl = "https://www.facebook.com/watch?v=1"))!!.mode)
        assertEquals(catalog, AudioFormatOptions.augment(catalog))
    }
    @Test fun `audio-only sources win over video and near-quality sources avoid excess data`() {
        fun source(key: String, extension: String, bitrate: Int, size: Long, note: String = "") =
            AvailableFormat(key, DownloadMode.AUDIO_ORIGINAL, "https://example.com/$key", extension = extension, bitrateKbps = bitrate, estimatedSizeBytes = size, formatNote = note)
        val aac = source("aac", "m4a", 192, 5_000_000)
        val opus = source("opus", "webm", 192, 6_000_000)
        val video = source("video", "mp4", 320, 50_000_000, "Video source (audio extracted)")
        assertEquals(aac, AudioFormatOptions.bestSource(listOf(video, opus, aac)))
        assertEquals(aac, AudioFormatOptions.bestSource(listOf(aac, aac.copy(key = "larger", estimatedSizeBytes = 8_000_000))))
    }
    @Test fun `all platform catalogs offer same conversion options with native defaults`() {
        val source = AvailableFormat("source", DownloadMode.AUDIO_ORIGINAL, "https://example.test/audio", extension = "m4a", bitrateKbps = 128)
        listOf("https://youtube.com/watch?v=1", "https://facebook.com/watch?v=1", "https://instagram.com/reel/1/",
            "https://tiktok.com/@fixture/video/1", "https://x.com/fixture/status/1", "https://reddit.com/r/fixture/comments/1/", "https://unknown.example.test/watch").forEach { url ->
            val catalog = AudioFormatOptions.augment(MediaFormatCatalog(url, "Fixture", emptyList(), listOf(source)))
            assertEquals(4, catalog.audioFormats.count { it.mode == DownloadMode.AUDIO_MP3 })
            assertTrue(catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_MP3 }.all { it.estimatedSizeBytes == null })
            assertEquals(PlatformAudioPolicy.defaultAudioMode(PlatformResolver.fromUrl(url)), PlatformAudioPolicy.selectAudio(catalog)!!.mode)
        }
    }
    @Test fun `quick MP3 presets remain placeholders until stream discovery`() {
        val catalog = NewPipeFormatDiscoveryEngine().quickFormatCatalog("https://youtube.com/watch?v=1")
        assertEquals(4, catalog.audioFormats.count { it.mode == DownloadMode.AUDIO_MP3 })
        catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_MP3 }.forEach {
            assertTrue(it.isQuickPreset)
            assertFalse(it.formatId.startsWith("http"))
            assertNull(it.estimatedSizeBytes)
        }
    }
}
