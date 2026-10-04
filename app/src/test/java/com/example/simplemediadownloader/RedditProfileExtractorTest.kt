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
class RedditProfileExtractorTest {
    private val url = "https://www.reddit.com/user/teacher/"
    private fun fixture(name: String) = javaClass.getResource("/profiles/$name")!!.readText()
    private fun extractor(transport: ProfileTransport) = RedditProfileExtractor(ProfileHttpClient(OkHttpClient(), setOf("www.reddit.com"), transport = transport, wait = {}, jitter = { 0 }))
    @Test fun `newest owned hosted videos use canonical post URLs and after pagination`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { endpoint, _ ->
            calls++; assertTrue(endpoint.contains("sort=new")); assertTrue(endpoint.contains("limit=25"))
            ProfileResponse(200, fixture(if (endpoint.contains("after=")) "reddit-next.json" else "reddit.json"))
        })
        assertTrue(source.canHandle(url)); assertFalse(source.canHandle("https://reddit.com/r/learning/comments/a/lesson"))
        assertEquals("@teacher", source.getInfo(url).title)
        val first = source.getItems(url, 20)
        assertEquals(listOf("a", "b"), first.items.map { it.id }); assertTrue(first.hasMore)
        assertEquals("https://www.reddit.com/r/learning/comments/a/lesson/", first.items.first().url)
        assertEquals(30L, first.items.first().durationSeconds)
        val next = source.getItems(url, 20, first.nextContinuation)
        assertEquals(listOf("b", "c"), next.items.map { it.id }); assertFalse(next.hasMore); assertEquals(2, calls)
    }
    @Test fun `private missing invalid cursor and external permalinks cannot crawl unrelated data`() = runBlocking {
        for (status in listOf(401, 404)) {
            var calls = 0
            val source = extractor(ProfileTransport { _, _ -> calls++; ProfileResponse(status, "") })
            try { source.getInfo(url); fail() } catch (_: ProfileDiscoveryException) {}
            assertEquals(1, calls)
        }
        val source = extractor(ProfileTransport { _, _ -> ProfileResponse(200, fixture("reddit.json").replace("/r/learning/comments/a/lesson/", "https://evil.example/r/learning/comments/a/lesson/")) })
        assertEquals(listOf("b"), source.getItems(url, 20).items.map { it.id })
        try { source.load(ProfileAddress.parse(url)!!, "https://evil.example"); fail() } catch (_: ProfileDiscoveryException) {}
    }
    @Test fun `rate limited feed stops at bounded attempts without cookie forwarding`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { _, headers ->
            assertTrue(headers.isEmpty()); calls++; ProfileResponse(429, "")
        })
        try { source.getInfo(url); fail() } catch (error: ProfileDiscoveryException) { assertTrue(error.message!!.contains("Rate limited")) }
        assertEquals(3, calls)
    }
}
