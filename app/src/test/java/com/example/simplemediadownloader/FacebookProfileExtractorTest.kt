package com.example.simplemediadownloader

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FacebookProfileExtractorTest {
    private val url = "https://www.facebook.com/teacher"
    private fun fixture(name: String) = javaClass.getResource("/profiles/$name")!!.readText()
    private fun extractor(transport: ProfileTransport) = FacebookProfileExtractor(ProfileHttpClient(OkHttpClient(), setOf("www.facebook.com"), transport = transport, wait = {}, jitter = { 0 }))
    @Test fun `timeline videos retain order metadata and canonical child URLs across exposed pages`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { endpoint, _ -> calls++; ProfileResponse(200, fixture(if (endpoint.contains("cursor=")) "facebook-next.html" else "facebook.html")) })
        assertTrue(source.canHandle(url)); assertFalse(source.canHandle("https://www.facebook.com/teacher/posts/1"))
        assertEquals("Teacher", source.getInfo(url).title)
        val first = source.getItems(url, 10)
        assertEquals(listOf("1", "2"), first.items.map { it.id }); assertTrue(first.hasMore)
        assertEquals("https://www.facebook.com/watch/?v=1", first.items.first().url)
        assertEquals(30L, first.items.first().durationSeconds)
        val second = source.getItems(url, 10, first.nextContinuation)
        assertEquals(listOf("2", "3"), second.items.map { it.id }); assertFalse(second.hasMore)
        assertEquals(2, calls)
    }
    @Test fun `private changed and off-origin continuation responses fail safely`() = runBlocking {
        for (body in listOf("{\"profile\":{\"is_private\":true}}", "<html>Sign in</html>")) {
            val source = extractor(ProfileTransport { _, _ -> ProfileResponse(200, body) })
            try { source.getInfo(url); fail() } catch (error: ProfileDiscoveryException) { assertFalse(error.message!!.contains("JSON")) }
        }
        var calls = 0
        val source = extractor(ProfileTransport { _, _ -> calls++; ProfileResponse(200, fixture("facebook.html").replace("/teacher?cursor=page2", "https://evil.example/teacher?cursor=page2")) })
        val first = source.getItems(url, 20)
        try { source.getItems(url, 20, first.nextContinuation); fail() } catch (_: ProfileDiscoveryException) {}
        assertEquals(1, calls)
    }
    @Test fun `temporary blocks retry within budget without user cookies`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { _, headers ->
            assertFalse(headers.keys.any { it.equals("Cookie", true) }); calls++
            ProfileResponse(if (calls < 3) 503 else 200, fixture("facebook.html"))
        })
        assertEquals(2, source.getItems(url, 20).items.size); assertEquals(3, calls)
    }
}
