package com.example.simplemediadownloader

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Organizes discovery and normal child requests. This class never transfers or converts media. */
class BatchRepository(
    private val database: DownloadDatabase,
    private val downloads: DownloadRepository,
    private val extractors: CollectionExtractorRegistry,
    private val availableBytes: () -> Long? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.batchDao()
    private val discoveryLock = Mutex() // One discovery/format-planning operation at a time.
    private val locks = ConcurrentHashMap<String, Mutex>()
    val batches = dao.observeBatches()
    fun observe(id: String): Flow<BatchSnapshot?> = combine(dao.observe(id), dao.observeCounts(id)) { parent, counts ->
        parent?.let { BatchSnapshot(it, BatchProgress(it.discoveredCount, it.selectedCount, counts.queued, counts.running, counts.completed, counts.failed, counts.cancelled)) }
    }
    fun items(id: String, limit: Int = 50, offset: Int = 0) = dao.observeItems(id, limit, offset)
    suspend fun create(url: String, requestedCount: Int? = null): String {
        require(NetworkSecurityPolicy.isAllowedShareUrl(url)) { "This collection URL is not permitted" }
        require(requestedCount == null || requestedCount > 0)
        val type = when (SourceUrlClassifier.classify(url)) {
            SourceUrlType.YOUTUBE_PLAYLIST -> CollectionType.YOUTUBE_PLAYLIST
            SourceUrlType.SOCIAL_PROFILE -> CollectionType.SOCIAL_PROFILE
            else -> CollectionType.OTHER_COLLECTION
        }
        val id = UUID.randomUUID().toString()
        dao.insert(BatchDownloadEntity(id, url, PlatformResolver.fromUrl(url), type.name, null, requestedCount,
            0, 0, clock(), BatchStatus.DISCOVERING.name, prefixOrder = type == CollectionType.YOUTUBE_PLAYLIST))
        return id
    }
    suspend fun discoverNext(id: String) = locked(id) {
        discoveryLock.withLock {
            val original = parent(id)
            check(original.status in editableStatuses) { "This batch is already queued" }
            if (!original.hasMore) return@withLock
            try {
                val extractor = extractors.extractor(original.sourceUrl)
                val info = if (original.discoveredCount == 0) extractor.getInfo(original.sourceUrl) else null
                val remaining = original.requestedCount?.minus(original.discoveredCount)
                val page = extractor.getItems(original.sourceUrl, remaining?.coerceAtMost(50) ?: 50, original.continuation)
                require(!page.hasMore || (page.nextContinuation != null && page.nextContinuation != original.continuation)) { "Collection returned a repeated continuation" }
                require(page.items.size <= (remaining?.coerceAtMost(50) ?: 50)) { "Collection page exceeds requested limit" }
                require(page.items.all { it.position >= 0 && it.id.isNotBlank() }) { "Collection returned an invalid media URL" }
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    dao.insertItems(page.items.map { BatchItemEntity(id, it.id, it.url, it.title, it.author, it.thumbnailUrl, it.durationSeconds, it.position,
                        unavailableReason = it.unavailableReason ?: if (!NetworkSecurityPolicy.isAllowedShareUrl(it.url)) "Unsupported media URL" else null) })
                    dao.update(original.copy(title = info?.title ?: original.title, platform = info?.platform ?: original.platform,
                        author = info?.author ?: original.author, thumbnailUrl = info?.thumbnailUrl ?: original.thumbnailUrl,
                        totalItemCount = info?.itemCount ?: original.totalItemCount,
                        continuation = page.nextContinuation, hasMore = page.hasMore && (remaining == null || page.items.size < remaining),
                        status = BatchStatus.READY.name, error = null))
                    dao.updateCounts(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled // The durable cursor still points to the uncommitted page.
            } catch (error: Exception) {
                dao.update(original.copy(status = BatchStatus.FAILED.name, error = CredentialRedactor.redactDiagnostics(error.message)))
                throw error
            }
        }
    }
    suspend fun discoverRemaining(id: String) {
        // Persist one page before requesting the next; cancellation leaves a reusable cursor.
        while (parent(id).hasMore) {
            currentCoroutineContext().ensureActive()
            discoverNext(id)
        }
    }
    suspend fun select(id: String, itemId: String?, selected: Boolean) = locked(id) {
        check(parent(id).status in editableStatuses)
        check(dao.children(id).isEmpty()) { "This batch has partially prepared children. Finish preparing or cancel it before changing selection." }
        database.withTransaction {
            if (itemId == null) dao.selectAll(id, selected) else dao.select(id, itemId, selected)
            dao.updateCounts(id)
        }
    }
    suspend fun configure(id: String, choice: BatchFormatChoice, skipExisting: Boolean, prefixOrder: Boolean) = locked(id) {
        val p = parent(id); check(p.status in editableStatuses)
        check(dao.children(id).isEmpty()) { "Finish preparing this batch before changing its format." }
        dao.update(p.copy(downloadMode = choice.mode.name, maximumHeight = choice.maximumHeight,
            mp3BitrateKbps = choice.mp3BitrateKbps, skipExisting = skipExisting, prefixOrder = prefixOrder))
    }
    suspend fun estimate(id: String): BatchEstimate = locked(id) {
        discoveryLock.withLock {
            val p = parent(id)
            var bytes = 0L; var unknown = 0; var fresh = 0; var completed = 0; var queued = 0
            forEachSelected(id) { item ->
                val format = resolve(p, item).first
                val duplicate = duplicate(item, format)
                if (duplicate == "QUEUED") queued++ else if (duplicate == "COMPLETED") completed++
                if (duplicate == null || (!p.skipExisting && duplicate == "COMPLETED")) {
                    fresh++
                    val size = if (p.formatChoice.mode == DownloadMode.AUDIO_MP3) Mp3SizeEstimator.bytes(item.durationSeconds ?: format.durationSeconds, p.mp3BitrateKbps) else format.estimatedSizeBytes
                    if (size == null) unknown++ else bytes = if (Long.MAX_VALUE - bytes < size) Long.MAX_VALUE else bytes + size
                }
            }
            BatchEstimate(bytes, unknown, fresh, completed, queued, availableBytes())
        }
    }
    suspend fun enqueue(id: String) = locked(id) {
        discoveryLock.withLock {
            val p = parent(id); check(p.status in editableStatuses); check(p.selectedCount > 0) { "Select at least one item" }
            // Each child is durable as it is planned, but READY parents cannot be admitted by the service.
            // Repeating after process death is safe: deterministic task IDs and the stored child link prevent duplicates.
            forEachSelected(id) { item ->
                if (item.childTaskId != null && database.downloadTaskDao().get(item.childTaskId) != null) return@forEachSelected
                val (format, failure) = resolve(p, item)
                val duplicate = duplicate(item, format)
                if (duplicate == "QUEUED" || (duplicate == "COMPLETED" && p.skipExisting)) {
                    dao.assignChild(id, item.itemId, null, duplicate)
                } else {
                    val childId = UUID.nameUUIDFromBytes((id + ":" + item.itemId).toByteArray(Charsets.UTF_8)).toString()
                    val title = item.title?.takeIf(String::isNotBlank) ?: "Downloaded media"
                    val request = DownloadRequest(childId, item.url, title, format, item.author, id, item.position, item.itemId,
                        filenamePrefix = if (p.prefixOrder) playlistFilenamePrefix(item.position, p.discoveredCount, p.totalItemCount) else null)
                    database.withTransaction {
                        val result = downloads.enqueue(request)
                        if (result.exceptionOrNull() is DuplicateActiveDownloadException) {
                            dao.assignChild(id, item.itemId, null, "QUEUED")
                        } else {
                            result.getOrThrow()
                            dao.assignChild(id, item.itemId, childId, null)
                            if (failure != null) downloads.failTask(childId, failure, DownloadFailureCategory.UNSUPPORTED_SITE)
                        }
                    }
                }
            }
            dao.update(parent(id).copy(status = BatchStatus.QUEUED.name, error = null))
        }
    }
    suspend fun pause(id: String) = locked(id) {
        val p = parent(id); check(p.status !in editableStatuses && p.status != BatchStatus.CANCELLED.name)
        dao.update(p.copy(status = BatchStatus.PAUSED.name))
    }
    suspend fun resume(id: String) = locked(id) {
        val p = parent(id); check(p.status == BatchStatus.PAUSED.name)
        dao.update(p.copy(status = BatchStatus.QUEUED.name))
    }
    suspend fun cancel(id: String) = locked(id) {
        val p = parent(id)
        dao.update(p.copy(status = BatchStatus.CANCELLED.name, continuation = null, hasMore = false))
        // Make child cancellation durable immediately through the normal repository. The service
        // also cancels and joins its jobs, including setup/export where no engine call is registered.
        dao.children(id).filter { it.status in setOf("QUEUED", "RUNNING") }.forEach { downloads.cancel(it.taskId) }
    }
    suspend fun retryFailed(id: String): Int = locked(id) {
        val p = parent(id)
        check(p.status !in editableStatuses) { "Finish preparing this batch before retrying children" }
        var count = 0
        dao.children(id).filter { it.status in setOf("FAILED", "INTERRUPTED") }.forEach { if (downloads.retry(it.taskId)) count++ }
        if (count > 0 && p.status != BatchStatus.PAUSED.name) dao.update(p.copy(status = BatchStatus.QUEUED.name))
        count
    }
    suspend fun deleteHistory(id: String) = locked(id) {
        val p = parent(id)
        val children = dao.children(id)
        check(children.none { it.status in setOf("QUEUED", "RUNNING") }) { "Cancel or finish this batch before removing its history" }
        database.withTransaction {
            // Keep child history and saved media: per-item actions remain available in the existing Vault.
            dao.detachChildren(p.batchId); dao.delete(p.batchId)
        }
    }
    private suspend fun resolve(p: BatchDownloadEntity, item: BatchItemEntity): Pair<AvailableFormat, String?> {
        currentCoroutineContext().ensureActive()
        val result = downloads.discoverFormats(item.url)
        if (result is FormatDiscoveryResult.Success) p.formatChoice.select(result.catalog)?.let { return it to null }
        val fallback = downloads.quickFormatCatalog(item.url)
        val format = p.formatChoice.select(fallback) ?: downloads.fastVideoPreset().copy(mode = p.formatChoice.mode,
            targetAudioBitrateKbps = if (p.formatChoice.mode == DownloadMode.AUDIO_MP3) p.mp3BitrateKbps else 0,
            extension = if (p.formatChoice.mode == DownloadMode.AUDIO_MP3) "mp3" else "mp4", height = p.maximumHeight)
        return format to ((result as? FormatDiscoveryResult.Failure)?.message ?: "No usable format is available for this item.")
    }
    private suspend fun duplicate(item: BatchItemEntity, format: AvailableFormat): String? {
        val canonical = NormalizedMediaUrl.from(item.url)
        if (database.downloadTaskDao().countActiveDuplicate(canonical, format.key) > 0) return "QUEUED"
        if (dao.completedDuplicates(canonical, format.key).any { it.toRecord().output?.let { output -> downloads.savedOutputExists(output) } == true }) return "COMPLETED"
        return null
    }
    private suspend fun forEachSelected(id: String, action: suspend (BatchItemEntity) -> Unit) {
        var offset = 0
        while (true) {
            val page = dao.items(id, 50, offset)
            if (page.isEmpty()) return
            page.filter { it.selected && it.unavailableReason == null }.forEach { action(it) }
            offset += page.size
        }
    }
    private suspend fun parent(id: String) = requireNotNull(dao.get(id)) { "This batch no longer exists" }
    private suspend fun <T> locked(id: String, action: suspend () -> T): T = locks.computeIfAbsent(id) { Mutex() }.withLock { action() }
    companion object {
        private val editableStatuses = setOf("DISCOVERING", "READY", "FAILED")
        fun aggregate(parent: BatchDownloadEntity, children: List<DownloadTaskEntity>): BatchProgress = BatchProgress(
            parent.discoveredCount, parent.selectedCount,
            children.count { it.status == "QUEUED" }, children.count { it.status == "RUNNING" },
            children.count { it.status == "COMPLETED" }, children.count { it.status in setOf("FAILED", "INTERRUPTED") }, children.count { it.status == "CANCELLED" },
        )
    }
}
