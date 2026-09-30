package com.example.simplemediadownloader

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BoundedResponseAndSecurityTest {

    @Test
    fun `readBoundedString reads normal response body under 8 MiB`() {
        val sampleJson = """{"status":"ok","title":"Test Video"}"""
        val responseBody = sampleJson.toResponseBody("application/json".toMediaType())

        val result = responseBody.readBoundedString()
        assertEquals(sampleJson, result)
    }

    @Test
    fun `readBoundedString throws IOException early when Content-Length exceeds limit`() {
        val oversizedLength = MAX_METADATA_BODY_BYTES + 1024L
        // Custom ResponseBody reporting oversized Content-Length
        val customBody = object : ResponseBody() {
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = oversizedLength
            override fun source() = Buffer().writeUtf8("tiny preview")
        }

        try {
            customBody.readBoundedString()
            fail("Expected IOException on oversized Content-Length")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exceeds maximum allowed metadata size") == true)
        }
    }

    @Test
    fun `readBoundedString throws IOException when unchunked body exceeds limit during streaming`() {
        val limit = 1024L // 1 KB limit for testing
        val buffer = Buffer()
        // Write 2 KB into source with unknown Content-Length (-1)
        val chunk = ByteArray(2048) { 'a'.code.toByte() }
        buffer.write(chunk)

        val customBody = object : ResponseBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength() = -1L // Unknown length
            override fun source() = buffer
        }

        try {
            customBody.readBoundedString(maxBytes = limit)
            fail("Expected IOException when streamed body exceeds maxBytes")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("exceeds maximum allowed metadata size") == true)
        }
    }

    @Test
    fun `isObviousNonMediaPayload detects HTML, XML, and JSON error responses`() {
        val engine = OkHttpDownloadEngine(enforceSecurityPolicy = false)

        val htmlPayload = "<!DOCTYPE html><html><body>Error</body></html>".toByteArray()
        assertTrue(engine.isObviousNonMediaPayload(htmlPayload, htmlPayload.size))

        val htmlTagPayload = "<html lang=\"en\"><head></head></html>".toByteArray()
        assertTrue(engine.isObviousNonMediaPayload(htmlTagPayload, htmlTagPayload.size))

        val xmlErrorPayload = "<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>".toByteArray()
        assertTrue(engine.isObviousNonMediaPayload(xmlErrorPayload, xmlErrorPayload.size))

        val jsonErrorPayload = """{"error": "Video unavailable", "code": 404}""".toByteArray()
        assertTrue(engine.isObviousNonMediaPayload(jsonErrorPayload, jsonErrorPayload.size))

        // Legitimate binary media headers must NOT be flagged as non-media
        val mp4Ftyp = byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        assertFalse(engine.isObviousNonMediaPayload(mp4Ftyp, mp4Ftyp.size))

        val id3Header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00, 0x00)
        assertFalse(engine.isObviousNonMediaPayload(id3Header, id3Header.size))
    }

    @Test
    fun `media URL security policy rejects private IPs, loopback, metadata, and cleartext`() {
        // Cleartext HTTP is blocked when allowCleartextHttp = false
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("http://cdn.example.com/video.mp4", allowCleartextHttp = false))

        // Loopback and private IP addresses are blocked
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("https://127.0.0.1/video.mp4", allowCleartextHttp = false))
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("https://localhost/video.mp4", allowCleartextHttp = false))
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("https://10.0.0.1/video.mp4", allowCleartextHttp = false))
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("https://192.168.1.1/video.mp4", allowCleartextHttp = false))
        assertFalse(NetworkSecurityPolicy.isAllowedMediaUrl("https://169.254.169.254/latest/meta-data/", allowCleartextHttp = false))

        // Public HTTPS media URLs are permitted
        assertTrue(NetworkSecurityPolicy.isAllowedMediaUrl("https://v16.tiktokcdn.com/video.mp4", allowCleartextHttp = false))
        assertTrue(NetworkSecurityPolicy.isAllowedMediaUrl("https://scontent.cdninstagram.com/stream.mp4", allowCleartextHttp = false))
        assertTrue(NetworkSecurityPolicy.isAllowedMediaUrl("https://video.twimg.com/ext_tw_video/123.mp4", allowCleartextHttp = false))
        assertTrue(NetworkSecurityPolicy.isAllowedMediaUrl("https://rr1---sn-4g5edn6e.googlevideo.com/videoplayback", allowCleartextHttp = false))
    }
}
