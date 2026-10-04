package com.example.simplemediadownloader

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YouTubePlaylistExtractorAdapterTest {
    private val url = "https://www.youtube.com/playlist?list=PLfixture"
    private fun item(index: Int, title: String = "Lesson $index", mediaUrl: String = "https://www.youtube.com/watch?v=lesson$index") =
        StreamInfoItem(0, mediaUrl, title, StreamType.VIDEO_STREAM).apply {
            uploaderName = "Teacher"; duration = 300
            thumbnails = listOf(Image("https://i.ytimg.com/vi/lesson$index/default.jpg", 90, 120, Image.ResolutionLevel.LOW))
        }
    private fun adapter(initial: PlaylistSourcePage, more: PlaylistSourcePage = PlaylistSourcePage(emptyList(), null)) =
        YouTubePlaylistExtractorAdapter(AppDispatchers(), object : PlaylistSource {
            override fun initial(url: String) = initial
            override fun more(url: String, page: Page) = more
        })

    @Test fun `metadata is optional and preserves available title author thumbnail count`() = runBlocking {
        val metadata = CollectionInfo(url, "YouTube", CollectionType.YOUTUBE_PLAYLIST, "Course", 120, "Teacher", "https://i.ytimg.com/playlist.jpg")
        assertEquals(metadata, adapter(PlaylistSourcePage(emptyList(), null, metadata)).getInfo(url))
        val missing = adapter(PlaylistSourcePage(listOf(item(0)), null)).getInfo(url)
        assertNull(missing.title); assertNull(missing.author); assertNull(missing.itemCount)
    }

    @Test fun `pagination preserves every position across partial upstream pages and normalizes watch context`() = runBlocking {
        val urls = mutableListOf<String>()
        var moreCalls = 0
        val extractor = YouTubePlaylistExtractorAdapter(AppDispatchers(), object : PlaylistSource {
            override fun initial(url: String): PlaylistSourcePage {
                urls += url
                return PlaylistSourcePage((0..2).map { item(it) }, Page("https://www.youtube.com/next", "cursor"))
            }
            override fun more(url: String, page: Page): PlaylistSourcePage {
                urls += url; moreCalls++
                assertEquals("cursor", page.id)
                return PlaylistSourcePage((3..5).map { item(it) }, null)
            }
        })
        val found = mutableListOf<CollectionItem>()
        var token: String? = null
        do {
            val page = extractor.getItems("https://www.youtube.com/watch?v=first&list=PLfixture", 2, token)
            found += page.items; token = page.nextContinuation
            assertEquals(token != null, page.hasMore)
        } while (token != null)
        assertEquals((0..5).toList(), found.map { it.position })
        assertEquals(6, found.map { it.id }.distinct().size)
        assertEquals((0..5).map { "Lesson $it" }, found.map { it.title })
        assertTrue(urls.all { it == url }); assertEquals(2, moreCalls) // two slices of the same upstream page
        assertEquals(300L, found.first().durationSeconds)
        assertEquals("Teacher", found.first().author)
        assertNotNull(found.first().thumbnailUrl)
    }

    @Test fun `unavailable entries keep their position and a reason without poisoning the page`() = runBlocking {
        val entries = listOf(item(0), item(1, "[Private video]"), item(2, "[Deleted video]"),
            item(3, mediaUrl = "http://127.0.0.1/secret"), item(4).apply { contentAvailability = ContentAvailability.MEMBERSHIP },
            item(5).apply { duration = -1 }, item(6).apply { contentAvailability = ContentAvailability.UNKNOWN })
        val page = adapter(PlaylistSourcePage(entries, null)).getItems(url)
        assertEquals((0..6).toList(), page.items.map { it.position })
        assertEquals(listOf(null, "Private video", "Removed video", "Unsupported media URL", "Members-only video", null, null), page.items.map { it.unavailableReason })
        assertNull(page.items[5].durationSeconds)
    }

    @Test fun `unsafe and authenticated continuation pages are rejected`() = runBlocking {
        val unsafe = adapter(PlaylistSourcePage(listOf(item(0)), Page("https://127.0.0.1/secret")))
        val token = unsafe.getItems(url, 1).nextContinuation
        assertNotNull(token)
        try { unsafe.getItems(url, 1, token); fail("Unsafe continuation accepted") } catch (_: IllegalArgumentException) {}
        val authenticated = adapter(PlaylistSourcePage(listOf(item(0)), Page("https://www.youtube.com/next", "id", emptyList(), mapOf("SID" to "secret"), null)))
        try { authenticated.getItems(url, 1); fail("Cookie continuation accepted") } catch (_: IllegalStateException) {}
    }

    @Test fun `cancellation interrupts blocking NewPipe work`() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val extractor = YouTubePlaylistExtractorAdapter(AppDispatchers(), object : PlaylistSource {
            override fun initial(url: String): PlaylistSourcePage {
                entered.countDown()
                try { Thread.sleep(30_000) } finally { released.countDown() }
                return PlaylistSourcePage(emptyList(), null)
            }
            override fun more(url: String, page: Page) = error("Unused")
        })
        val job = launch(Dispatchers.Default) { extractor.getInfo(url) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(released.await(1, TimeUnit.SECONDS)); assertTrue(job.isCancelled)
    }

    @Test fun `typed pasted and shared playlist contexts use the same classification`() {
        listOf(url, "https://www.youtube.com/watch?v=first&list=PLfixture", "https://youtu.be/first?list=PLfixture", "https://m.youtube.com/watch?v=first&%6cist=PLfixture").forEach { source ->
            assertEquals(SourceUrlType.YOUTUBE_PLAYLIST, SourceUrlClassifier.classify(source))
            val pasted = ShareIntentParser.parseText("Course: $source") as SharedUrlResult.Valid
            val shared = ShareIntentParser.parse(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, source)) as SharedUrlResult.Valid
            assertEquals(SourceUrlType.YOUTUBE_PLAYLIST, SourceUrlClassifier.classify(pasted.url))
            assertEquals(SourceUrlType.YOUTUBE_PLAYLIST, SourceUrlClassifier.classify(shared.url))
            assertEquals(url, SourceUrlClassifier.playlistUrl(source))
        }
        listOf("https://www.youtube.com/watch?v=first", "https://www.youtube.com/watch?v=first&list=", "https://www.youtube.com/watch?v=first&list=a&list=b", "https://www.youtube.com/watch?v=first&list=%20", "https://www.youtube.com/shorts/first?list=PLfixture").forEach {
            assertEquals(SourceUrlType.SINGLE_MEDIA, SourceUrlClassifier.classify(it))
        }
    }

    @Test fun `numbering uses playlist width and durations do not invent unknown values`() {
        assertEquals("001 - ", playlistFilenamePrefix(0, 50, 120))
        assertEquals("1000 - ", playlistFilenamePrefix(999, 1000, null))
        assertEquals("03 - ", playlistFilenamePrefix(2, 3, null))
        assertEquals("5:00", playlistDurationLabel(300)); assertEquals("1:01:01", playlistDurationLabel(3661))
    }
}
