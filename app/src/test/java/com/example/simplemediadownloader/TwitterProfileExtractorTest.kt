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
class TwitterProfileExtractorTest {
    private val url = "https://x.com/teacher"
    private fun fixture(name: String) = javaClass.getResource("/profiles/$name")!!.readText()
    private fun extractor(transport: ProfileTransport) = TwitterProfileExtractor(ProfileHttpClient(OkHttpClient(), setOf("syndication.twitter.com"), transport = transport, wait = {}, jitter = { 0 }))
    @Test fun `owned video tweets retain metadata ordering and public continuation`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { endpoint, _ -> calls++; ProfileResponse(200, fixture(if (endpoint.contains("cursor=")) "twitter-next.html" else "twitter.html")) })
        assertTrue(source.canHandle(url)); assertFalse(source.canHandle("https://x.com/teacher/status/1"))
        assertEquals("Teacher", source.getInfo(url).title)
        val first = source.getItems(url, 20)
        assertEquals(listOf("1", "2"), first.items.map { it.id }); assertTrue(first.hasMore)
        assertEquals("https://x.com/teacher/status/1", first.items.first().url)
        assertEquals(30L, first.items.first().durationSeconds)
        val next = source.getItems(url, 20, first.nextContinuation)
        assertEquals(listOf("2", "3"), next.items.map { it.id }); assertFalse(next.hasMore)
        assertEquals(2, calls)
    }
    @Test fun `private empty changed and unrelated continuation feeds are handled safely`() = runBlocking {
        val private = extractor(ProfileTransport { _, _ -> ProfileResponse(200, "<script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"protected\":true}}}</script>") })
        try { private.getInfo(url); fail() } catch (error: ProfileDiscoveryException) { assertTrue(error.message!!.contains("private")) }
        val empty = extractor(ProfileTransport { _, _ -> ProfileResponse(200, "<script id=\"__NEXT_DATA__\">{\"props\":{\"pageProps\":{\"timeline\":{\"entries\":[]}}}}</script>") })
        val page = empty.getItems(url, 20); assertTrue(page.items.isEmpty()); assertFalse(page.hasMore); assertNotNull(page.notice)
        val unrelated = extractor(ProfileTransport { _, _ -> ProfileResponse(200, fixture("twitter.html").replace("?cursor=page2", "https://evil.example/next")) })
        try { unrelated.getInfo(url); fail() } catch (_: ProfileDiscoveryException) {}
    }
    @Test fun `rate limiting retries public discovery without gateway or cookies`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { endpoint, headers ->
            assertTrue(endpoint.startsWith("https://syndication.twitter.com/")); assertTrue(headers.isEmpty())
            calls++; ProfileResponse(if (calls < 3) 429 else 200, fixture("twitter.html"))
        })
        assertEquals(2, source.getItems(url, 20).items.size); assertEquals(3, calls)
    }
}
