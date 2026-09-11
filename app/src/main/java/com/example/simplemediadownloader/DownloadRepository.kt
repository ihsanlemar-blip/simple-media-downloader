package com.example.simplemediadownloader

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadRepository(
    private val formatDiscoveryEngine: FormatDiscoveryEngine,
    private val downloadEngine: DownloadEngine,
    private val historyStore: DownloadHistoryStore,
    private val storageExporter: StorageExporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val taskAttemptGenerations = ConcurrentHashMap<String, Long>()
    private val taskMutexes = ConcurrentHashMap<String, Mutex>()

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

    fun invalidateFormatCatalog(url: String) {
        formatDiscoveryEngine.invalidate(url)
    }

    suspend fun refreshFormats(
        url: String,
        onState: (DownloadState) -> Unit = {},
    ): FormatDiscoveryResult {
        onState(DownloadState.Inspecting())
        return formatDiscoveryEngine.refreshFormats(url)
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
        val attemptId = taskAttemptGenerations.compute(request.id) { _, current -> (current ?: 0L) + 1L }!!
        ensureRecord(request)
        publishState(request.id, DownloadState.Preparing(), attemptId, onState)
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
                attemptId,
                onState,
            )
        }

        return try {
            val execution = collectEngineStates(request, destination, attemptId, onState)
            when (execution) {
            is DownloadExecutionResult.Success -> {
                if (taskAttemptGenerations[request.id] != attemptId) {
                    val finalRecord = historyStore.get(request.id)
                    if (finalRecord?.status == DownloadTaskStatus.CANCELLED) {
                        return DownloadState.Cancelled
                    }
                }
                publishState(
                    request.id,
                    DownloadState.Saving(
                        DownloadProgress(status = "Saving media…"),
                    ),
                    attemptId,
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
                        attemptId,
                        onState,
                    )
                }
                if (taskAttemptGenerations[request.id] != attemptId) {
                    val finalRecord = historyStore.get(request.id)
                    if (finalRecord?.status == DownloadTaskStatus.CANCELLED) {
                        return DownloadState.Cancelled
                    }
                }
                terminal(request.id, DownloadState.Completed(output), attemptId, onState)
            }

            DownloadExecutionResult.Cancelled ->
                terminal(request.id, DownloadState.Cancelled, attemptId, onState)
            is DownloadExecutionResult.Failure -> {
                if (taskAttemptGenerations[request.id] != attemptId) {
                    val finalRecord = historyStore.get(request.id)
                    if (finalRecord?.status == DownloadTaskStatus.CANCELLED) {
                        return DownloadState.Cancelled
                    }
                }
                terminal(
                    request.id,
                    DownloadState.Failed(
                        message = execution.message,
                        category = execution.category,
                        technicalDetail = execution.technicalDetail,
                    ),
                    attemptId,
                    onState,
                )
            }
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
        taskAttemptGenerations.compute(processId) { _, current -> (current ?: 0L) + 1L }
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

    suspend fun pauseForWifi(taskId: String): Boolean {
        taskAttemptGenerations.compute(taskId) { _, current -> (current ?: 0L) + 1L }
        downloadEngine.cancel(taskId)
        val mutex = taskMutexes.computeIfAbsent(taskId) { Mutex() }
        return mutex.withLock {
            val record = historyStore.get(taskId) ?: return false
            if (record.status == DownloadTaskStatus.RUNNING || record.status == DownloadTaskStatus.QUEUED) {
                historyStore.update(
                    record.copy(
                        status = DownloadTaskStatus.QUEUED,
                        stage = DownloadProcessingStage.WAITING_FOR_WIFI,
                        speedBytesPerSecond = null,
                        etaSeconds = null,
                    ),
                )
                true
            } else {
                false
            }
        }
    }

    suspend fun markWaitingForWifi(taskId: String): Boolean {
        val mutex = taskMutexes.computeIfAbsent(taskId) { Mutex() }
        return mutex.withLock {
            val record = historyStore.get(taskId) ?: return false
            if (record.status == DownloadTaskStatus.QUEUED && record.stage != DownloadProcessingStage.WAITING_FOR_WIFI) {
                historyStore.update(
                    record.copy(stage = DownloadProcessingStage.WAITING_FOR_WIFI),
                )
                true
            } else {
                false
            }
        }
    }

    suspend fun restoreRecoverableTasks(): List<DownloadRequest> {
        recoverInterruptedTasks()
        historyStore.requeueInterruptedTasks()
        return queuedRequests()
    }

    suspend fun retry(taskId: String): Boolean {
        taskAttemptGenerations.compute(taskId) { _, current -> (current ?: 0L) + 1L }
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

    suspend fun clearDisposableCache(): Long = storageExporter.clearDisposableCache()

    fun clearFormatCache() = formatDiscoveryEngine.clearCache()

    fun searchHistory(query: String): Flow<List<DownloadTask>> =
        historyStore.searchHistory(query).map { list -> list.map(DownloadRecord::toTask) }

    suspend fun getHistoricalTasks(limit: Int, offset: Int): List<DownloadTask> =
        historyStore.getHistoricalTasks(limit, offset).map(DownloadRecord::toTask)

    private suspend fun collectEngineStates(
        request: DownloadRequest,
        destination: ExportDestination,
        attemptId: Long,
        onState: (DownloadState) -> Unit,
    ): DownloadExecutionResult = coroutineScope {
        val updates = Channel<DownloadState>(Channel.CONFLATED)
        val writer = launch {
            var lastPersistTime = 0L
            var lastPersistedPercentage: Float? = null
            var lastStage: DownloadProcessingStage? = null

            for (state in updates) {
                if (taskAttemptGenerations[request.id] != attemptId) {
                    break
                }

                if (state.isTerminal) {
                    persistState(request.id, state, attemptId)
                    break
                }

                val stage = state.toProcessingStage()
                val now = clock()
                val percentage = state.progress.percentage
                val stageChanged = stage != lastStage
                val timeElapsed = (now - lastPersistTime) >= 1_000L
                val percentageChangedSignificant = lastPersistedPercentage == null ||
                    (percentage != null && abs(percentage - lastPersistedPercentage) >= 5.0f)

                if (stageChanged || timeElapsed || percentageChangedSignificant) {
                    persistProgress(request.id, stage, state.progress, attemptId)
                    lastPersistTime = now
                    lastPersistedPercentage = percentage
                    lastStage = stage
                }
            }
        }
        val result = try {
            downloadEngine.download(request, destination.directory) { state ->
                onState(state)
                if (taskAttemptGenerations[request.id] == attemptId) {
                    updates.trySend(state)
                }
            }
        } finally {
            updates.close()
        }
        writer.join()
        result
    }

    private suspend fun ensureRecord(request: DownloadRequest) {
        if (historyStore.get(request.id) == null) {
            historyStore.insert(request.toQueuedRecord(clock()))
        }
    }

    private suspend fun publishState(
        taskId: String,
        state: DownloadState,
        attemptId: Long? = null,
        onState: (DownloadState) -> Unit,
    ) {
        persistState(taskId, state, attemptId)
        onState(state)
    }

    private suspend fun terminal(
        taskId: String,
        state: DownloadState,
        attemptId: Long? = null,
        onState: (DownloadState) -> Unit,
    ): DownloadState {
        publishState(taskId, state, attemptId, onState)
        return state
    }

    private suspend fun persistProgress(
        taskId: String,
        stage: DownloadProcessingStage,
        progress: DownloadProgress,
        attemptId: Long? = null,
    ) {
        val mutex = taskMutexes.computeIfAbsent(taskId) { Mutex() }
        mutex.withLock {
            if (attemptId != null && taskAttemptGenerations[taskId] != attemptId) {
                return
            }
            historyStore.updateProgress(taskId, stage, progress)
        }
    }

    private suspend fun persistState(
        taskId: String,
        state: DownloadState,
        attemptId: Long? = null,
    ) {
        val mutex = taskMutexes.computeIfAbsent(taskId) { Mutex() }
        mutex.withLock {
            if (attemptId != null && taskAttemptGenerations[taskId] != attemptId && state !is DownloadState.Cancelled) {
                return
            }
            val current = historyStore.get(taskId) ?: return
            val updated = current.transitionTo(state, clock())
            if (updated != current) {
                historyStore.update(updated)
            }
        }
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
    if (status == DownloadTaskStatus.CANCELLED && state !is DownloadState.Cancelled) {
        return this
    }
    if (status == DownloadTaskStatus.COMPLETED && state !is DownloadState.Completed) {
        return this
    }
    if (status == DownloadTaskStatus.FAILED && state !is DownloadState.Failed) {
        return this
    }
    if (status == DownloadTaskStatus.INTERRUPTED && state !is DownloadState.Queued) {
        return this
    }
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
        is DownloadState.WaitingForWifi -> copy(
            status = DownloadTaskStatus.QUEUED,
            stage = DownloadProcessingStage.WAITING_FOR_WIFI,
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

private fun DownloadState.toProcessingStage(): DownloadProcessingStage = when (this) {
    DownloadState.Queued -> DownloadProcessingStage.QUEUED
    is DownloadState.WaitingForWifi -> DownloadProcessingStage.WAITING_FOR_WIFI
    is DownloadState.Preparing -> DownloadProcessingStage.PREPARING
    is DownloadState.Inspecting -> DownloadProcessingStage.INSPECTING
    is DownloadState.Downloading -> if (transferKind == DownloadTransferKind.AUDIO) {
        DownloadProcessingStage.DOWNLOADING_AUDIO
    } else {
        DownloadProcessingStage.DOWNLOADING_VIDEO
    }
    is DownloadState.Merging -> DownloadProcessingStage.MERGING
    is DownloadState.Converting -> DownloadProcessingStage.CONVERTING
    is DownloadState.Saving -> DownloadProcessingStage.SAVING
    is DownloadState.Completed -> DownloadProcessingStage.COMPLETED
    DownloadState.Cancelled -> DownloadProcessingStage.CANCELLED
    is DownloadState.Failed -> DownloadProcessingStage.FAILED
}

