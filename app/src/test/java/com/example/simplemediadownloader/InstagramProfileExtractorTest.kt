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
class InstagramProfileExtractorTest {
    private val url = "https://www.instagram.com/teacher/"
    private fun fixture(name: String) = javaClass.getResource("/profiles/$name")!!.readText()
    private fun extractor(transport: ProfileTransport) = InstagramProfileExtractor(ProfileHttpClient(OkHttpClient(), setOf("www.instagram.com"), transport = transport, wait = {}, jitter = { 0 }))

    @Test fun `public timeline preserves metadata recent order and canonical video posts with pagination`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = extractor(ProfileTransport { endpoint, headers ->
            requests += endpoint
            assertEquals("936619743392459", headers["X-IG-App-ID"])
            assertFalse(headers.keys.any { it.equals("Cookie", true) || it.equals("Authorization", true) })
            ProfileResponse(200, fixture(if (endpoint.contains("graphql")) "instagram-next.json" else "instagram.json"))
        })
        assertTrue(source.canHandle(url)); assertFalse(source.canHandle("https://www.instagram.com/reel/first/"))
        assertEquals("Teacher", source.getInfo(url).title)
        val first = source.getItems(url, 10)
        assertEquals(listOf("1", "2"), first.items.map { it.id })
        assertEquals("https://www.instagram.com/p/first/", first.items.first().url)
        assertEquals("Lesson 1", first.items.first().title); assertEquals(30L, first.items.first().durationSeconds)
        assertTrue(first.hasMore)
        val next = source.getItems(url, 10, first.nextContinuation)
        assertEquals(listOf("2", "3"), next.items.map { it.id }); assertFalse(next.hasMore)
        assertEquals(2, requests.size) // Initial metadata is reused, not fetched twice.
        assertTrue(requests.last().contains("after-first"))
    }
    @Test fun `private and changed profiles fail safely without fetching another page`() = runBlocking {
        for (body in listOf("{\"data\":{\"user\":{\"is_private\":true}}}", "<html>Sign in</html>", "{\"data\":{\"user\":null}}")) {
            var calls = 0
            val source = extractor(ProfileTransport { _, _ -> calls++; ProfileResponse(200, body) })
            try { source.getInfo(url); fail() } catch (error: ProfileDiscoveryException) { assertFalse(error.userMessage.contains("JSONObject")) }
            assertEquals(1, calls)
        }
    }
    @Test fun `rate limit retries are bounded and use same public endpoint`() = runBlocking {
        var calls = 0
        val source = extractor(ProfileTransport { _, _ -> calls++; ProfileResponse(if (calls < 3) 429 else 200, fixture("instagram.json")) })
        assertEquals(2, source.getItems(url, 20).items.size); assertEquals(3, calls)
    }
}
