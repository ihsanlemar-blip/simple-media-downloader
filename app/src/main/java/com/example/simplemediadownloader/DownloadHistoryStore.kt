package com.example.simplemediadownloader

import kotlinx.coroutines.flow.Flow

enum class DownloadTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED,
    INTERRUPTED,
}

enum class DownloadProcessingStage {
    QUEUED,
    PREPARING,
    INSPECTING,
    DOWNLOADING_VIDEO,
    DOWNLOADING_AUDIO,
    MERGING,
    CONVERTING,
    SAVING,
    COMPLETED,
    CANCELLED,
    FAILED,
    INTERRUPTED,
}

data class DownloadRecord(
    val taskId: String,
    val sourceUrl: String,
    val displayTitle: String,
    val platform: String,
    val format: AvailableFormat,
    val status: DownloadTaskStatus,
    val stage: DownloadProcessingStage,
    val progressPercent: Float?,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long? = null,
    val etaSeconds: Long?,
    val output: DownloadOutput?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val failureCategory: DownloadFailureCategory?,
    val failureMessage: String?,
    val technicalFailureDetail: String?,
) {
    fun toTask(): DownloadTask = DownloadTask(
        id = taskId,
        url = sourceUrl,
        title = displayTitle,
        format = format,
        state = toDownloadState(),
        platform = platform,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
    )

    private fun toDownloadState(): DownloadState {
        val progress = DownloadProgress(
            percentage = progressPercent,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSecond = speedBytesPerSecond,
            etaSeconds = etaSeconds,
            status = stage.statusText(format),
        )
        return when (status) {
            DownloadTaskStatus.QUEUED -> DownloadState.Queued
            DownloadTaskStatus.RUNNING -> when (stage) {
                DownloadProcessingStage.INSPECTING -> DownloadState.Inspecting(progress)
                DownloadProcessingStage.DOWNLOADING_VIDEO -> DownloadState.Downloading(
                    progress,
                    DownloadTransferKind.VIDEO,
                )
                DownloadProcessingStage.DOWNLOADING_AUDIO -> DownloadState.Downloading(
                    progress,
                    DownloadTransferKind.AUDIO,
                )
                DownloadProcessingStage.MERGING -> DownloadState.Merging(progress)
                DownloadProcessingStage.CONVERTING -> DownloadState.Converting(progress)
                DownloadProcessingStage.SAVING -> DownloadState.Saving(progress)
                else -> DownloadState.Preparing(progress)
            }
            DownloadTaskStatus.COMPLETED -> output?.let(DownloadState::Completed)
                ?: DownloadState.Failed(
                    message = "The saved download is no longer available.",
                    category = DownloadFailureCategory.UNKNOWN_FAILURE,
                    technicalDetail = "Completed database record has no output content URI.",
                )
            DownloadTaskStatus.CANCELLED -> DownloadState.Cancelled
            DownloadTaskStatus.FAILED,
            DownloadTaskStatus.INTERRUPTED -> {
                val category = failureCategory ?: if (status == DownloadTaskStatus.INTERRUPTED) {
                    DownloadFailureCategory.ANDROID_INTERRUPTED_TASK
                } else {
                    DownloadFailureCategory.UNKNOWN_FAILURE
                }
                DownloadState.Failed(
                    message = failureMessage ?: category.userMessage,
                    category = category,
                    technicalDetail = technicalFailureDetail,
                )
            }
        }
    }
}

interface DownloadHistoryStore {
    val activeTasks: Flow<List<DownloadRecord>>
    val recentHistory: Flow<List<DownloadRecord>>

    suspend fun insert(record: DownloadRecord)
    suspend fun insertIfNoActiveDuplicate(record: DownloadRecord): Boolean {
        insert(record)
        return true
    }
    suspend fun update(record: DownloadRecord)
    suspend fun get(taskId: String): DownloadRecord?
    suspend fun recoverRunningTasks(interruptedAt: Long, technicalDetail: String): Int
    suspend fun interruptTask(taskId: String, interruptedAt: Long, technicalDetail: String): Boolean
    suspend fun requeueInterruptedTasks(): Int
    suspend fun retry(taskId: String): Boolean
    suspend fun removeHistoryEntry(taskId: String): Boolean
    suspend fun clearCompletedHistory(): Int
}

private fun DownloadProcessingStage.statusText(format: AvailableFormat): String = when (this) {
    DownloadProcessingStage.QUEUED -> "Queued..."
    DownloadProcessingStage.PREPARING -> "Preparing download..."
    DownloadProcessingStage.INSPECTING -> "Inspecting formats..."
    DownloadProcessingStage.DOWNLOADING_VIDEO -> "Downloading video…"
    DownloadProcessingStage.DOWNLOADING_AUDIO -> "Downloading audio…"
    DownloadProcessingStage.MERGING -> "Merging video and audio…"
    DownloadProcessingStage.CONVERTING -> if (format.mode == DownloadMode.AUDIO_MP3) {
        "Converting to MP3…"
    } else {
        "Creating ${format.height}p video…"
    }
    DownloadProcessingStage.SAVING -> "Finishing file…"
    DownloadProcessingStage.COMPLETED -> "Completed"
    DownloadProcessingStage.CANCELLED -> "Cancelled"
    DownloadProcessingStage.FAILED -> "Failed"
    DownloadProcessingStage.INTERRUPTED -> "Interrupted"
}
