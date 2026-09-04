package com.example.simplemediadownloader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CachingFormatDiscoveryEngineTest {
    @Test
    fun `normalized URLs reuse successful discovery until expiration`() = runBlocking {
        var now = 1_000L
        val delegate = FakeDiscoveryEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CachingFormatDiscoveryEngine(
            delegate = delegate,
            scope = scope,
            ttlMillis = 100L,
            clock = { now },
        )
        try {
            engine.discoverFormats("HTTPS://Example.Test:443/watch?v=one#details")

            assertNotNull(engine.cachedFormatCatalog("https://example.test/watch?v=one"))
            engine.discoverFormats("https://example.test/watch?v=one")
            assertEquals(1, delegate.calls.get())

            now += 100L
            assertNull(engine.cachedFormatCatalog("https://example.test/watch?v=one"))
            engine.discoverFormats("https://example.test/watch?v=one")
            assertEquals(2, delegate.calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `simultaneous discovery for one URL shares one operation`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val delegate = FakeDiscoveryEngine(gate, started)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CachingFormatDiscoveryEngine(delegate, scope)
        try {
            val first = async { engine.discoverFormats("https://example.test/video") }
            started.await()
            val second = async { engine.discoverFormats("https://example.test/video#same") }

            gate.complete(Unit)

            val firstResult = first.await() as FormatDiscoveryResult.Success
            val secondResult = second.await() as FormatDiscoveryResult.Success
            assertEquals(firstResult.catalog.title, secondResult.catalog.title)
            assertEquals(firstResult.catalog.videoFormats, secondResult.catalog.videoFormats)
            assertEquals(1, delegate.calls.get())
        } finally {
            scope.cancel()
        }
    }

    private class FakeDiscoveryEngine(
        private val gate: CompletableDeferred<Unit>? = null,
        private val started: CompletableDeferred<Unit>? = null,
    ) : FormatDiscoveryEngine {
        val calls = AtomicInteger()

        override fun quickFormatCatalog(url: String) = catalog(url)

        override fun fastVideoPreset() = catalog("fast").videoFormats.single()

        override suspend fun discoverFormats(url: String): FormatDiscoveryResult {
            calls.incrementAndGet()
            started?.complete(Unit)
            gate?.await()
            return FormatDiscoveryResult.Success(catalog(url))
        }

        private fun catalog(url: String) = MediaFormatCatalog(
            sourceUrl = url,
            title = "Example",
            videoFormats = listOf(
                AvailableFormat(
                    key = "video",
                    mode = DownloadMode.VIDEO,
                    formatId = "18",
                    extension = "mp4",
                    height = 360,
                ),
            ),
            audioFormats = emptyList(),
        )
    }
}
