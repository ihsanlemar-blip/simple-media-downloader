package com.example.simplemediadownloader

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileDiscoveryTest {
    private fun fixture(name: String) = javaClass.getResource("/profiles/$name")!!.readText()
    private val hosts = setOf("www.tiktok.com", "tiktok.com")
    @Test fun `classification accepts real accounts and preserves single post and ambiguous routes`() {
        listOf("https://tiktok.com/@teacher", "https://instagram.com/teacher.name/", "https://facebook.com/people/Teacher/123", "https://facebook.com/profile.php?id=123", "https://x.com/teacher", "https://reddit.com/u/teacher/submitted").forEach { assertEquals(it, SourceUrlType.SOCIAL_PROFILE, SourceUrlClassifier.classify(it)) }
        listOf("https://tiktok.com/@teacher/video/123", "https://instagram.com/reel/abc", "https://facebook.com/teacher/posts/123", "https://facebook.com/story.php?story_fbid=123&id=456", "https://x.com/teacher/status/123", "https://reddit.com/user/teacher/comments/abc/title", "https://instagram.com/accounts/login", "https://x.com/settings", "https://facebook.com/groups", "https://facebook.com/watch?v=123").forEach { assertEquals(it, SourceUrlType.SINGLE_MEDIA, SourceUrlClassifier.classify(it)) }
        assertNull(ProfileAddress.parse("https://127.0.0.1/@teacher"))
        assertNull(ProfileAddress.parse("https://tiktok.com.evil.test/@teacher"))
    }
    @Test fun `count default presets and hard bounds are explicit`() {
        assertEquals(20, ProfileDiscoveryPolicy.DEFAULT_COUNT)
        assertEquals(listOf(10, 20, 50, 100), ProfileDiscoveryPolicy.PRESETS)
        listOf(0, -1, 101, Int.MAX_VALUE).forEach { try { ProfileDiscoveryPolicy.validate(it); fail("Accepted $it") } catch (_: IllegalArgumentException) {} }
    }
    @Test fun `public HTTP backoff is exponential bounded and honors capped retry after`() = runBlocking {
        val waits = mutableListOf<Long>(); var calls = 0
        val http = ProfileHttpClient(OkHttpClient(), hosts, transport = ProfileTransport { _, _ ->
            calls++; when (calls) { 1 -> ProfileResponse(429, "", 1000); 2 -> ProfileResponse(503, ""); else -> ProfileResponse(200, "ok") }
        }, wait = { waits += it }, jitter = { 0 })
        assertEquals("ok", http.get("https://www.tiktok.com/@teacher"))
        assertEquals(listOf(30_000L, 2_000L), waits); assertEquals(3, calls)
    }
    @Test fun `private missing and rate limited profiles return safe errors with bounded requests`() = runBlocking {
        for ((status, body, count) in listOf(Triple(403, "private login", 1), Triple(404, "", 1), Triple(429, "", 3), Triple(500, "", 3), Triple(403, "temporary challenge", 3))) {
            var calls = 0
            val http = ProfileHttpClient(OkHttpClient(), hosts, transport = ProfileTransport { _, _ -> calls++; ProfileResponse(status, body) }, wait = {}, jitter = { 0 })
            try { http.get("https://www.tiktok.com/@teacher"); fail("Expected error") } catch (error: ProfileDiscoveryException) { assertFalse(error.userMessage.contains("JSON")) }
            assertEquals(count, calls)
        }
    }
    @Test fun `timeouts retry but cancellation and off origin requests do not`() = runBlocking {
        var calls = 0
        val http = ProfileHttpClient(OkHttpClient(), hosts, transport = ProfileTransport { _, _ -> calls++; throw IOException("socket details") }, wait = {}, jitter = { 0 })
        try { http.get("https://www.tiktok.com/@teacher"); fail() } catch (error: ProfileDiscoveryException) { assertFalse(error.message!!.contains("socket")) }
        assertEquals(3, calls)
        try { http.get("https://gateway.example.com/profile"); fail() } catch (_: ProfileDiscoveryException) {}
        assertEquals(3, calls)
        val cancelled = ProfileHttpClient(OkHttpClient(), hosts, transport = ProfileTransport { _, _ -> throw CancellationException() }, wait = { fail("Cancellation retried") })
        try { cancelled.get("https://www.tiktok.com/@teacher"); fail() } catch (_: CancellationException) {}
    }
    @Test fun `TikTok returns canonical posts dates duration continuation and no streams`() = runBlocking {
        val extractor = TikTokProfileExtractor(ProfileHttpClient(OkHttpClient(), hosts, transport = ProfileTransport { url, _ -> ProfileResponse(200, fixture(if (url.contains("/api/")) "tiktok-next.json" else "tiktok.html")) }, wait = {}))
        val info = extractor.getInfo("https://www.tiktok.com/@teacher")
        assertEquals("Teacher", info.title); assertEquals("@teacher", info.author)
        val first = extractor.getItems(info.sourceUrl, 2)
        assertEquals(listOf("1", "2"), first.items.map { it.id }); assertTrue(first.hasMore)
        val second = extractor.getItems(info.sourceUrl, 1, first.nextContinuation)
        assertEquals("2", second.items.single().id); assertTrue(second.hasMore)
        val third = extractor.getItems(info.sourceUrl, 1, second.nextContinuation)
        assertEquals("https://www.tiktok.com/@teacher/video/3", third.items.single().url)
        assertEquals(100L, third.items.single().publishedAtSeconds); assertEquals(20L, third.items.single().durationSeconds)
        assertFalse(third.hasMore)
    }
    @Test fun `TikTok gateway requires opt in and verified public account and revocation stops paging`() = runBlocking {
        var gatewayCalls = 0
        var enabled = false
        fun direct(private: Boolean = false) = ProfileHttpClient(OkHttpClient(), hosts, transport = ProfileTransport { url, _ ->
            ProfileResponse(200, if (url.contains("/api/")) "" else "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\">{\"__DEFAULT_SCOPE__\":{\"webapp.user-detail\":{\"userInfo\":{\"user\":{\"secUid\":\"public\",\"privateAccount\":$private}}}}}</script>")
        }, wait = {})
        val gateway = ProfileHttpClient(OkHttpClient(), setOf("www.tikwm.com"), transport = ProfileTransport { url, headers ->
            gatewayCalls++; assertTrue(url.contains("unique_id=teacher")); assertFalse(headers.keys.any { it.equals("Cookie", true) })
            ProfileResponse(200, """{"code":0,"data":{"videos":[{"video_id":"1","title":"Lesson","duration":30,"create_time":300,"author":{"unique_id":"teacher"}}],"hasMore":true,"cursor":"2"}}""")
        }, wait = {})
        val extractor = TikTokProfileExtractor(direct(), gateway) { enabled }
        try { extractor.getInfo("https://www.tiktok.com/@teacher"); fail() } catch (_: ProfileDiscoveryException) {}
        assertEquals(0, gatewayCalls)
        enabled = true
        val page = extractor.getItems("https://www.tiktok.com/@teacher", 10)
        assertEquals("https://www.tiktok.com/@teacher/video/1", page.items.single().url)
        assertEquals(1, gatewayCalls)
        enabled = false
        try { extractor.getItems("https://www.tiktok.com/@teacher", 10, page.nextContinuation); fail() } catch (_: ProfileDiscoveryException) {}
        assertEquals(1, gatewayCalls)
        val private = TikTokProfileExtractor(direct(true), gateway) { true }
        try { private.getInfo("https://www.tiktok.com/@teacher"); fail() } catch (error: ProfileDiscoveryException) { assertTrue(error.message!!.contains("private")) }
        assertEquals(1, gatewayCalls)
    }

}
