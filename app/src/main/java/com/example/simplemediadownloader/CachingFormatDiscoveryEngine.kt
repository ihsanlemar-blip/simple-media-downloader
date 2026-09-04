package com.example.simplemediadownloader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.net.URI
import java.util.LinkedHashMap
import java.util.Locale

class CachingFormatDiscoveryEngine(
    private val delegate: FormatDiscoveryEngine,
    private val scope: CoroutineScope,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : FormatDiscoveryEngine {
    private val lock = Any()
    private val cache = LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true)
    private val inFlight = mutableMapOf<String, Deferred<FormatDiscoveryResult>>()

    init {
        require(maxEntries > 0) { "Format cache must hold at least one entry." }
        require(ttlMillis > 0L) { "Format cache expiration must be positive." }
    }

    override fun quickFormatCatalog(url: String): MediaFormatCatalog =
        delegate.quickFormatCatalog(url)

    override fun fastVideoPreset(): AvailableFormat = delegate.fastVideoPreset()

    override fun cachedFormatCatalog(url: String): MediaFormatCatalog? {
        val key = NormalizedMediaUrl.from(url)
        return synchronized(lock) { cachedLocked(key, url) }
    }

    override suspend fun discoverFormats(url: String): FormatDiscoveryResult {
        val key = NormalizedMediaUrl.from(url)
        cachedFormatCatalog(url)?.let { return FormatDiscoveryResult.Success(it) }

        val deferred = synchronized(lock) {
            cachedLocked(key, url)?.let { cached ->
                return@synchronized CompletedDiscovery(
                    FormatDiscoveryResult.Success(cached),
                )
            }
            inFlight[key]?.let(::PendingDiscovery) ?: run {
                val created = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val result = delegate.discoverFormats(url)
                        if (result is FormatDiscoveryResult.Success) {
                            synchronized(lock) {
                                cache[key] = CacheEntry(result.catalog, clock() + ttlMillis)
                                trimLocked()
                            }
                        }
                        result
                    } finally {
                        synchronized(lock) { inFlight.remove(key) }
                    }
                }
                inFlight[key] = created
                PendingDiscovery(created)
            }
        }

        return when (deferred) {
            is CompletedDiscovery -> deferred.result
            is PendingDiscovery -> {
                deferred.deferred.start()
                when (val result = deferred.deferred.await()) {
                    is FormatDiscoveryResult.Success -> result.copy(
                        catalog = result.catalog.copy(sourceUrl = url),
                    )
                    is FormatDiscoveryResult.Failure -> result
                }
            }
        }
    }

    private fun cachedLocked(key: String, requestedUrl: String): MediaFormatCatalog? {
        val entry = cache[key] ?: return null
        if (clock() >= entry.expiresAt) {
            cache.remove(key)
            return null
        }
        return entry.catalog.copy(sourceUrl = requestedUrl)
    }

    private fun trimLocked() {
        while (cache.size > maxEntries) {
            cache.entries.iterator().run {
                next()
                remove()
            }
        }
    }

    private data class CacheEntry(
        val catalog: MediaFormatCatalog,
        val expiresAt: Long,
    )

    private sealed interface DiscoveryLookup
    private data class CompletedDiscovery(val result: FormatDiscoveryResult) : DiscoveryLookup
    private data class PendingDiscovery(
        val deferred: Deferred<FormatDiscoveryResult>,
    ) : DiscoveryLookup

    companion object {
        const val DEFAULT_MAX_ENTRIES = 16
        const val DEFAULT_TTL_MILLIS = 2L * 60L * 1_000L
    }
}

internal object NormalizedMediaUrl {
    fun from(url: String): String = runCatching {
        val parsed = URI(url.trim()).normalize()
        val scheme = parsed.scheme.lowercase(Locale.US)
        val host = parsed.host.lowercase(Locale.US)
        val port = if (
            (scheme == "https" && parsed.port == 443) ||
            (scheme == "http" && parsed.port == 80)
        ) {
            -1
        } else {
            parsed.port
        }
        URI(
            scheme,
            parsed.userInfo,
            host,
            port,
            parsed.path.ifEmpty { "/" },
            parsed.query,
            null,
        ).toASCIIString()
    }.getOrElse {
        url.trim().substringBefore('#')
    }
}
