package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadEngineValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val engine = OkHttpDownloadEngine()

    @Test
    fun `rejects files smaller than 1024 bytes`() {
        val smallFile = tempFolder.newFile("small.mp4")
        smallFile.writeBytes(ByteArray(500))

        val error = engine.validateMediaFile(smallFile, "mp4")
        assertNotNull(error)
        assertTrue(error!!.message.contains("empty or too small"))
    }

    @Test
    fun `rejects HTML error pages`() {
        val htmlFile = tempFolder.newFile("error.mp4")
        val content = "<!DOCTYPE html><html><head><title>403 Forbidden</title></head><body><h1>Access Denied</h1></body></html>"
        val padded = content + " ".repeat(1500)
        htmlFile.writeText(padded)

        val error = engine.validateMediaFile(htmlFile, "mp4")
        assertNotNull(error)
        assertTrue(error!!.message.contains("web content or an error page"))
    }

    @Test
    fun `rejects JSON error responses`() {
        val jsonFile = tempFolder.newFile("api_error.mp4")
        val content = "{\"error\": {\"message\": \"Rate limit exceeded\", \"status\": 429}}"
        val padded = content + " ".repeat(1500)
        jsonFile.writeText(padded)

        val error = engine.validateMediaFile(jsonFile, "mp4")
        assertNotNull(error)
        assertTrue(error!!.message.contains("web content or an error page"))
    }

    @Test
    fun `rejects M3U8 manifests`() {
        val m3uFile = tempFolder.newFile("stream.mp4")
        val content = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nchunk.ts"
        val padded = content + "\n".repeat(1500)
        m3uFile.writeText(padded)

        val error = engine.validateMediaFile(m3uFile, "mp4")
        assertNotNull(error)
        assertTrue(error!!.message.contains("web content or an error page"))
    }

    @Test
    fun `rejects corrupted or mismatched container`() {
        val corruptedFile = tempFolder.newFile("corrupted.mp4")
        // Fill 2048 bytes with random non-media garbage
        corruptedFile.writeBytes(ByteArray(2048) { (it % 250).toByte() })

        val error = engine.validateMediaFile(corruptedFile, "mp4")
        assertNotNull(error)
        assertTrue(error!!.message.contains("corrupted or does not match"))
    }

    @Test
    fun `accepts valid MP4 container`() {
        val mp4File = tempFolder.newFile("valid.mp4")
        val bytes = ByteArray(2048)
        // Set standard MP4 ftyp box: 0x00 0x00 0x00 0x18 'f' 't' 'y' 'p' 'm' 'p' '4' '2'
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        mp4File.writeBytes(bytes)

        val error = engine.validateMediaFile(mp4File, "mp4")
        assertNull(error)
    }

    @Test
    fun `accepts valid WebM container`() {
        val webmFile = tempFolder.newFile("valid.webm")
        val bytes = ByteArray(2048)
        // EBML header: 0x1A, 0x45, 0xDF, 0xA3
        bytes[0] = 0x1A.toByte()
        bytes[1] = 0x45.toByte()
        bytes[2] = 0xDF.toByte()
        bytes[3] = 0xA3.toByte()
        webmFile.writeBytes(bytes)

        val error = engine.validateMediaFile(webmFile, "webm")
        assertNull(error)
    }

    @Test
    fun `accepts valid MP3 with ID3 tag`() {
        val mp3File = tempFolder.newFile("valid.mp3")
        val bytes = ByteArray(2048)
        // ID3 header
        bytes[0] = 'I'.code.toByte()
        bytes[1] = 'D'.code.toByte()
        bytes[2] = '3'.code.toByte()
        mp3File.writeBytes(bytes)

        val error = engine.validateMediaFile(mp3File, "mp3")
        assertNull(error)
    }

    @Test
    fun `accepts valid FLAC file`() {
        val flacFile = tempFolder.newFile("valid.flac")
        val bytes = ByteArray(2048)
        // fLaC header
        bytes[0] = 'f'.code.toByte()
        bytes[1] = 'L'.code.toByte()
        bytes[2] = 'a'.code.toByte()
        bytes[3] = 'C'.code.toByte()
        flacFile.writeBytes(bytes)

        val error = engine.validateMediaFile(flacFile, "flac")
        assertNull(error)
    }

    @Test
    fun `accepts valid OGG file`() {
        val oggFile = tempFolder.newFile("valid.ogg")
        val bytes = ByteArray(2048)
        // OggS header
        bytes[0] = 'O'.code.toByte()
        bytes[1] = 'g'.code.toByte()
        bytes[2] = 'g'.code.toByte()
        bytes[3] = 'S'.code.toByte()
        oggFile.writeBytes(bytes)

        val error = engine.validateMediaFile(oggFile, "ogg")
        assertNull(error)
    }

    @Test
    fun `accepts valid WAV file`() {
        val wavFile = tempFolder.newFile("valid.wav")
        val bytes = ByteArray(2048)
        // RIFF....WAVE
        bytes[0] = 'r'.code.toByte()
        bytes[1] = 'i'.code.toByte()
        bytes[2] = 'f'.code.toByte()
        bytes[3] = 'f'.code.toByte()
        bytes[8] = 'w'.code.toByte()
        bytes[9] = 'a'.code.toByte()
        bytes[10] = 'v'.code.toByte()
        bytes[11] = 'e'.code.toByte()
        wavFile.writeBytes(bytes)

        val error = engine.validateMediaFile(wavFile, "wav")
        assertNull(error)
    }

    @Test
    fun `detectAudioContainer correctly detects M4A, WebM, MP3, OGG, and AAC containers`() {
        val m4aFile = tempFolder.newFile("sample.m4a")
        val m4aBytes = ByteArray(1024)
        m4aBytes[4] = 'f'.code.toByte()
        m4aBytes[5] = 't'.code.toByte()
        m4aBytes[6] = 'y'.code.toByte()
        m4aBytes[7] = 'p'.code.toByte()
        m4aFile.writeBytes(m4aBytes)
        assertEquals("m4a", engine.detectAudioContainer(m4aFile))

        val mp3File = tempFolder.newFile("sample.mp3")
        val mp3Bytes = ByteArray(1024)
        mp3Bytes[0] = 'I'.code.toByte()
        mp3Bytes[1] = 'D'.code.toByte()
        mp3Bytes[2] = '3'.code.toByte()
        mp3File.writeBytes(mp3Bytes)
        assertEquals("mp3", engine.detectAudioContainer(mp3File))

        val webmFile = tempFolder.newFile("sample.webm")
        val webmBytes = ByteArray(1024)
        webmBytes[0] = 0x1A.toByte()
        webmBytes[1] = 0x45.toByte()
        webmBytes[2] = 0xDF.toByte()
        webmBytes[3] = 0xA3.toByte()
        webmFile.writeBytes(webmBytes)
        assertEquals("webm", engine.detectAudioContainer(webmFile))

        val oggFile = tempFolder.newFile("sample.ogg")
        val oggBytes = ByteArray(1024)
        oggBytes[0] = 'O'.code.toByte()
        oggBytes[1] = 'g'.code.toByte()
        oggBytes[2] = 'g'.code.toByte()
        oggBytes[3] = 'S'.code.toByte()
        oggFile.writeBytes(oggBytes)
        assertEquals("ogg", engine.detectAudioContainer(oggFile))

        val aacFile = tempFolder.newFile("sample.aac")
        val aacBytes = ByteArray(1024)
        aacBytes[0] = 0xFF.toByte()
        aacBytes[1] = 0xF1.toByte()
        aacFile.writeBytes(aacBytes)
        assertEquals("aac", engine.detectAudioContainer(aacFile))
    }

    @Test
    fun `accepts authentic M4A stream even if expectedExtension was mp3`() {
        val m4aAudioFile = tempFolder.newFile("audio_as_mp3.mp3")
        val bytes = ByteArray(2048)
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        m4aAudioFile.writeBytes(bytes)

        // Validates that upstream M4A/AAC streams delivered under an MP3 preset do not fail
        val error = engine.validateMediaFile(m4aAudioFile, "mp3")
        assertNull(error)
    }
}
