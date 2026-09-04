package com.example.simplemediadownloader

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DownloadRepository(
    private val formatDiscoveryEngine: FormatDiscoveryEngine,
    private val downloadEngine: DownloadEngine,
    private val historyStore: DownloadHistoryStore,
    private val storageExporter: StorageExporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val activeTasks: Flow<List<DownloadTask>> = historyStore.activeTasks.map { records ->
        records.map(DownloadRecord::toTask)
    }
    val recentHistory: Flow<List<DownloadTask>> = historyStore.recentHistory.map { records ->
        val tasks = ArrayList<DownloadTask>(records.size)
        for (record in records) {
            val output = record.output
            val validated = if (
                record.status == DownloadTaskStatus.COMPLETED &&
                output != null &&
                !storageExporter.outputExists(output)
            ) {
                record.copy(output = null)
            } else {
                record
            }
            tasks += validated.toTask()
        }
        tasks
    }
    val tasks: Flow<List<DownloadTask>> = combine(activeTasks, recentHistory) { active, history ->
        active + history
    }

    fun quickFormatCatalog(url: String): MediaFormatCatalog =
        formatDiscoveryEngine.quickFormatCatalog(url)

    fun fastVideoPreset(): AvailableFormat = formatDiscoveryEngine.fastVideoPreset()

    fun cachedFormatCatalog(url: String): MediaFormatCatalog? =
        formatDiscoveryEngine.cachedFormatCatalog(url)

    suspend fun discoverFormats(
        url: String,
        onState: (DownloadState) -> Unit = {},
    ): FormatDiscoveryResult {
        onState(DownloadState.Inspecting())
        return formatDiscoveryEngine.discoverFormats(url)
    }

    suspend fun enqueue(request: DownloadRequest): Result<Unit> = runCatching {
        val duplicate = historyStore.activeTasks.first().any { record ->
            NormalizedMediaUrl.from(record.sourceUrl) == NormalizedMediaUrl.from(request.url) &&
                record.format.key == request.format.key
        }
        if (duplicate) throw DuplicateActiveDownloadException()
        if (!historyStore.insertIfNoActiveDuplicate(request.toQueuedRecord(clock()))) {
            throw DuplicateActiveDownloadException()
        }
    }

    suspend fun prepareTask(taskId: String, status: String) {
        persistState(
            taskId,
            DownloadState.Preparing(DownloadProgress(status = status)),
        )
    }

    suspend fun failTask(
        taskId: String,
        message: String,
        category: DownloadFailureCategory,
        technicalDetail: String? = null,
    ) {
        persistState(
            taskId,
            DownloadState.Failed(message, category, technicalDetail),
        )
    }

    suspend fun download(
        request: DownloadRequest,
        onState: (DownloadState) -> Unit = {},
    ): DownloadState {
        ensureRecord(request)
        publishState(request.id, DownloadState.Preparing(), onState)
        val destination = storageExporter.prepareDestination(request).getOrElse { error ->
            val mapped = TechnicalFailureMapper.map(
                error.stackTraceToString(),
                FailureOrigin.STORAGE,
                fallback = "Could not prepare temporary storage for this download.",
            )
            return terminal(
                request.id,
                DownloadState.Failed(
                    message = mapped.message,
                    category = mapped.category,
                    technicalDetail = error.stackTraceToString().take(2_000),
                ),
                onState,
            )
        }

        return try {
            val execution = collectEngineStates(request, destination, onState)
            when (execution) {
            is DownloadExecutionResult.Success -> {
                publishState(
                    request.id,
                    DownloadState.Saving(
                        DownloadProgress(status = "Saving media…"),
                    ),
                    onState,
                )
                val output = storageExporter.exportCompletedFile(
                    request = request,
                    destination = destination,
                    commandOutput = execution.output,
                ).getOrElse { error ->
                    val mapped = TechnicalFailureMapper.map(
                        error.stackTraceToString(),
                        FailureOrigin.STORAGE,
                        fallback = "The media finished processing but could not be saved.",
                    )
                    return terminal(
                        request.id,
                        DownloadState.Failed(
                            message = mapped.message,
                            category = mapped.category,
                            technicalDetail = error.stackTraceToString().take(2_000),
                        ),
                        onState,
                    )
                }
                terminal(request.id, DownloadState.Completed(output), onState)
            }

            DownloadExecutionResult.Cancelled ->
                terminal(request.id, DownloadState.Cancelled, onState)
            is DownloadExecutionResult.Failure -> terminal(
                request.id,
                DownloadState.Failed(
                    message = execution.message,
                    category = execution.category,
                    technicalDetail = execution.technicalDetail,
                ),
                onState,
            )
            }
        } finally {
            storageExporter.cleanup(destination)
        }
    }

    suspend fun queuedRequests(): List<DownloadRequest> = historyStore.activeTasks.first()
        .filter { it.status == DownloadTaskStatus.QUEUED }
        .sortedBy(DownloadRecord::createdAt)
        .map(DownloadRecord::toRequest)

    suspend fun request(taskId: String): DownloadRequest? =
        historyStore.get(taskId)
            ?.takeIf {
                it.status == DownloadTaskStatus.QUEUED ||
                    it.status == DownloadTaskStatus.RUNNING
            }
            ?.toRequest()

    suspend fun cancel(processId: String): Boolean {
        val record = historyStore.get(processId) ?: return false
        if (record.status == DownloadTaskStatus.QUEUED) {
            persistState(processId, DownloadState.Cancelled)
            return true
        }
        val cancelled = downloadEngine.cancel(processId)
        if (cancelled) persistState(processId, DownloadState.Cancelled)
        return cancelled
    }

    suspend fun recoverInterruptedTasks(): Int = historyStore.recoverRunningTasks(
        interruptedAt = clock(),
        technicalDetail = "The app process ended while this task was in ${DownloadTaskStatus.RUNNING.name} state.",
    )

    suspend fun markInterrupted(taskId: String, technicalDetail: String): Boolean =
        historyStore.interruptTask(taskId, clock(), technicalDetail)

    suspend fun restoreRecoverableTasks(): List<DownloadRequest> {
        recoverInterruptedTasks()
        historyStore.requeueInterruptedTasks()
        return queuedRequests()
    }

    suspend fun retry(taskId: String): Boolean {
        if (!historyStore.retry(taskId)) return false
        return historyStore.get(taskId)?.status == DownloadTaskStatus.QUEUED
    }

    suspend fun removeHistoryEntry(taskId: String): Boolean =
        historyStore.removeHistoryEntry(taskId)

    suspend fun deleteMediaAndHistory(taskId: String): Result<Unit> = runCatching {
        val record = historyStore.get(taskId) ?: error("This history entry no longer exists.")
        val output = record.output ?: error("The saved media is already missing.")
        check(storageExporter.deleteOutput(output)) {
            "Android could not delete the saved media. It may already have been removed."
        }
        check(historyStore.removeHistoryEntry(taskId)) {
            "The media was deleted, but its history entry could not be removed."
        }
    }

    suspend fun clearCompletedHistory(): Int = historyStore.clearCompletedHistory()

    suspend fun cleanupAbandonedExports(): Int = storageExporter.cleanupAbandonedExports()

    private suspend fun collectEngineStates(
        request: DownloadRequest,
        destination: ExportDestination,
        onState: (DownloadState) -> Unit,
    ): DownloadExecutionResult = coroutineScope {
        val updates = Channel<DownloadState>(Channel.UNLIMITED)
        val writer = launch {
            var previous: DownloadState? = null
            for (state in updates) {
                if (shouldPersist(previous, state)) {
                    persistState(request.id, state)
                    previous = state
                }
            }
        }
        val result = try {
            downloadEngine.download(request, destination.directory) { state ->
                onState(state)
                updates.trySend(state)
            }
        } finally {
            updates.close()
        }
        writer.join()
        result
    }

    private fun shouldPersist(previous: DownloadState?, current: DownloadState): Boolean {
        if (previous == null || previous::class != current::class) return true
        return current.progress.percentage?.toInt() != previous.progress.percentage?.toInt() ||
            current.progress.downloadedBytes != previous.progress.downloadedBytes ||
            current.progress.speedBytesPerSecond != previous.progress.speedBytesPerSecond ||
            current.progress.status != previous.progress.status
    }

    private suspend fun ensureRecord(request: DownloadRequest) {
        if (historyStore.get(request.id) == null) {
            historyStore.insert(request.toQueuedRecord(clock()))
        }
    }

    private suspend fun publishState(
        taskId: String,
        state: DownloadState,
        onState: (DownloadState) -> Unit,
    ) {
        persistState(taskId, state)
        onState(state)
    }

    private suspend fun terminal(
        taskId: String,
        state: DownloadState,
        onState: (DownloadState) -> Unit,
    ): DownloadState {
        publishState(taskId, state, onState)
        return state
    }

    private suspend fun persistState(taskId: String, state: DownloadState) {
        val current = historyStore.get(taskId) ?: return
        historyStore.update(current.transitionTo(state, clock()))
    }
}

class DuplicateActiveDownloadException : IllegalStateException(
    "This URL and format are already queued or downloading.",
)

private fun DownloadRequest.toQueuedRecord(createdAt: Long): DownloadRecord = DownloadRecord(
    taskId = id,
    sourceUrl = url,
    displayTitle = title,
    platform = PlatformResolver.fromUrl(url),
    format = format,
    status = DownloadTaskStatus.QUEUED,
    stage = DownloadProcessingStage.QUEUED,
    progressPercent = null,
    downloadedBytes = null,
    totalBytes = format.estimatedSizeBytes,
    speedBytesPerSecond = null,
    etaSeconds = null,
    output = null,
    createdAt = createdAt,
    startedAt = null,
    completedAt = null,
    failureCategory = null,
    failureMessage = null,
    technicalFailureDetail = null,
)

private fun DownloadRecord.toRequest(): DownloadRequest = DownloadRequest(
    id = taskId,
    url = sourceUrl,
    title = displayTitle,
    format = format,
)

private fun DownloadRecord.transitionTo(state: DownloadState, now: Long): DownloadRecord {
    val progress = state.progress.percentage?.takeIf { it > 0f }
    return when (state) {
        DownloadState.Queued -> copy(
            status = DownloadTaskStatus.QUEUED,
            stage = DownloadProcessingStage.QUEUED,
            progressPercent = null,
            downloadedBytes = null,
            totalBytes = format.estimatedSizeBytes,
            speedBytesPerSecond = null,
            etaSeconds = null,
        )
        is DownloadState.Preparing -> running(
            DownloadProcessingStage.PREPARING,
            progress,
            state.progress,
            now,
        )
        is DownloadState.Inspecting -> running(
            DownloadProcessingStage.INSPECTING,
            progress,
            state.progress,
            now,
        )
        is DownloadState.Downloading -> running(
            if (state.transferKind == DownloadTransferKind.AUDIO) {
                DownloadProcessingStage.DOWNLOADING_AUDIO
            } else {
                DownloadProcessingStage.DOWNLOADING_VIDEO
            },
            progress,
            state.progress,
            now,
        )
        is DownloadState.Merging -> running(
            DownloadProcessingStage.MERGING,
            progress,
            state.progress,
            now,
        )
        is DownloadState.Converting -> running(
            DownloadProcessingStage.CONVERTING,
            progress,
            state.progress,
            now,
        )
        is DownloadState.Saving -> running(
            DownloadProcessingStage.SAVING,
            progress,
            state.progress,
            now,
        )
        is DownloadState.Completed -> copy(
            status = DownloadTaskStatus.COMPLETED,
            stage = DownloadProcessingStage.COMPLETED,
            progressPercent = 100f,
            downloadedBytes = state.output.fileSizeBytes,
            totalBytes = state.output.fileSizeBytes,
            speedBytesPerSecond = null,
            etaSeconds = null,
            output = state.output,
            completedAt = now,
            failureCategory = null,
            failureMessage = null,
            technicalFailureDetail = null,
        )
        DownloadState.Cancelled -> copy(
            status = DownloadTaskStatus.CANCELLED,
            stage = DownloadProcessingStage.CANCELLED,
            speedBytesPerSecond = null,
            etaSeconds = null,
            completedAt = now,
            failureCategory = null,
            failureMessage = null,
            technicalFailureDetail = null,
        )
        is DownloadState.Failed -> copy(
            status = DownloadTaskStatus.FAILED,
            stage = DownloadProcessingStage.FAILED,
            speedBytesPerSecond = null,
            etaSeconds = null,
            completedAt = now,
            failureCategory = state.category,
            failureMessage = state.message,
            technicalFailureDetail = state.technicalDetail,
        )
    }
}

private fun DownloadRecord.running(
    nextStage: DownloadProcessingStage,
    progress: Float?,
    details: DownloadProgress,
    now: Long,
): DownloadRecord = copy(
    status = DownloadTaskStatus.RUNNING,
    stage = nextStage,
    progressPercent = progress,
    downloadedBytes = details.downloadedBytes ?: downloadedBytes,
    totalBytes = details.totalBytes ?: totalBytes,
    speedBytesPerSecond = details.speedBytesPerSecond,
    etaSeconds = details.etaSeconds,
    startedAt = startedAt ?: now,
    completedAt = null,
    output = null,
    failureCategory = null,
    failureMessage = null,
    technicalFailureDetail = null,
)
