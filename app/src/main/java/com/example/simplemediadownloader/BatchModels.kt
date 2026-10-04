package com.example.simplemediadownloader

import java.net.URI

enum class SourceUrlType { SINGLE_MEDIA, YOUTUBE_PLAYLIST, SOCIAL_PROFILE, UNKNOWN }

/** Classification is shared by typed, pasted, and ACTION_SEND links. No network requests. */
object SourceUrlClassifier {
    fun classify(url: String): SourceUrlType {
        if (!NetworkSecurityPolicy.isAllowedShareUrl(url)) return SourceUrlType.UNKNOWN
        val uri = runCatching { URI(url) }.getOrNull() ?: return SourceUrlType.UNKNOWN
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return SourceUrlType.UNKNOWN
        val path = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (host in setOf("youtube.com", "music.youtube.com", "youtu.be")) {
            if (uri.rawQuery.orEmpty().split('&').any { it.startsWith("list=") && it.length > 5 }) return SourceUrlType.YOUTUBE_PLAYLIST
            if (path.firstOrNull()?.let { it.startsWith('@') || it in setOf("channel", "c", "user") } == true) return SourceUrlType.SOCIAL_PROFILE
        }
        val profile = when (host) {
            "tiktok.com" -> path.size == 1 && path[0].startsWith('@')
            "instagram.com" -> path.size == 1 && path[0] !in setOf("p", "reel", "reels", "stories", "explore", "accounts")
            "twitter.com", "x.com" -> path.size == 1 && path[0] !in setOf("home", "search", "i", "intent", "explore")
            "facebook.com" -> (path.size == 1 && path[0] !in setOf("watch", "reel", "reels", "share")) || uri.path == "/profile.php"
            "reddit.com" -> path.size == 2 && path[0] in setOf("user", "u")
            else -> false
        }
        return if (profile) SourceUrlType.SOCIAL_PROFILE else SourceUrlType.SINGLE_MEDIA
    }
}

enum class CollectionType { YOUTUBE_PLAYLIST, SOCIAL_PROFILE, OTHER_COLLECTION }
enum class BatchStatus { DISCOVERING, READY, QUEUED, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, FAILED }
data class CollectionInfo(val sourceUrl: String, val platform: String, val type: CollectionType, val title: String?, val itemCount: Int? = null)
data class CollectionItem(val id: String, val url: String, val title: String?, val author: String?, val thumbnailUrl: String?, val durationSeconds: Long?, val position: Int)
data class CollectionPage(val items: List<CollectionItem>, val nextContinuation: String?, val hasMore: Boolean)
interface CollectionExtractor {
    suspend fun canHandle(url: String): Boolean
    suspend fun getInfo(url: String): CollectionInfo
    suspend fun getItems(url: String, limit: Int? = null, continuation: String? = null): CollectionPage
}

data class BatchFormatChoice(val mode: DownloadMode, val maximumHeight: Int = 0, val mp3BitrateKbps: Int = 192) {
    init {
        require(maximumHeight in setOf(0, 480, 720, 1080))
        require(mp3BitrateKbps in PlatformAudioPolicy.MP3_BITRATES)
    }
    fun select(catalog: MediaFormatCatalog): AvailableFormat? = when (mode) {
        DownloadMode.VIDEO -> if (maximumHeight == 0) catalog.videoFormats.maxByOrNull { it.height }
            else catalog.videoFormats.filter { it.height in 1..maximumHeight }.maxByOrNull { it.height }
                // Existing downscale presets keep the cap when only larger sources exist.
                ?: catalog.videoFormats.filter { it.height > maximumHeight }.minByOrNull { it.height }?.let { source ->
                    source.copy(key = "batch-video-$maximumHeight:${source.key}", height = maximumHeight,
                        width = if (source.height > 0) ((source.width.toLong() * maximumHeight / source.height).toInt() / 2) * 2 else 0,
                        sourceHeight = source.height, requiresDownscale = true)
                }
        else -> selectBestAudioFormat(catalog, mode, mp3BitrateKbps)
    }
    companion object {
        fun audioDefault(platform: String, youtubeBitrate: Int = 192) = BatchFormatChoice(PlatformAudioPolicy.defaultAudioMode(platform), mp3BitrateKbps = youtubeBitrate)
    }
}

data class BatchProgress(val total: Int, val selected: Int, val queued: Int, val running: Int, val completed: Int, val failed: Int, val cancelled: Int) {
    fun status(parent: BatchDownloadEntity): BatchStatus {
        val control = BatchStatus.valueOf(parent.status)
        if (control in setOf(BatchStatus.DISCOVERING, BatchStatus.READY, BatchStatus.PAUSED, BatchStatus.CANCELLED, BatchStatus.FAILED)) return control
        return when {
            running > 0 -> BatchStatus.RUNNING
            queued > 0 -> BatchStatus.QUEUED
            failed > 0 -> BatchStatus.COMPLETED_WITH_ERRORS
            cancelled > 0 && completed == 0 -> BatchStatus.CANCELLED
            else -> BatchStatus.COMPLETED
        }
    }
}
data class BatchSnapshot(val parent: BatchDownloadEntity, val progress: BatchProgress) { val status get() = progress.status(parent) }
data class BatchEstimate(val knownBytes: Long, val unknownItems: Int, val newItems: Int, val alreadyDownloaded: Int, val alreadyQueued: Int, val freeBytes: Long?) {
    val requiredBytes: Long get() = if (knownBytes > (Long.MAX_VALUE - 64 * 1024 * 1024) / 2) Long.MAX_VALUE else knownBytes * 2 + 64 * 1024 * 1024
    val likelyInsufficient: Boolean get() = freeBytes != null && requiredBytes > freeBytes
    val label: String get() = "Estimated total: ~${formatByteCount(knownBytes)}" + if (unknownItems > 0) " + $unknownItems unknown items" else ""
}
