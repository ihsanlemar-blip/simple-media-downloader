package com.example.simplemediadownloader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomDownloadHistoryStore(
    private val dao: DownloadTaskDao,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : DownloadHistoryStore {
    override val activeTasks: Flow<List<DownloadRecord>> =
        dao.observeActiveTasks().map { tasks -> tasks.map(DownloadTaskEntity::toRecord) }

    override val recentHistory: Flow<List<DownloadRecord>> =
        dao.observeRecentHistory().map { tasks -> tasks.map(DownloadTaskEntity::toRecord) }

    override suspend fun insert(record: DownloadRecord) = withContext(dispatchers.io) {
        dao.insert(record.toEntity())
    }

    override suspend fun insertIfNoActiveDuplicate(record: DownloadRecord): Boolean =
        withContext(dispatchers.io) { dao.insertIfNoActiveDuplicate(record.toEntity()) }

    override suspend fun update(record: DownloadRecord) = withContext(dispatchers.io) {
        check(dao.update(record.toEntity()) == 1) { "Download task ${record.taskId} no longer exists." }
    }

    override suspend fun get(taskId: String): DownloadRecord? = withContext(dispatchers.io) {
        dao.get(taskId)?.toRecord()
    }

    override suspend fun recoverRunningTasks(
        interruptedAt: Long,
        technicalDetail: String,
    ): Int = withContext(dispatchers.io) {
        dao.recoverRunningTasks(
            interruptedAt = interruptedAt,
            failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
            technicalDetail = technicalDetail,
        )
    }

    override suspend fun requeueInterruptedTasks(): Int = withContext(dispatchers.io) {
        dao.requeueInterruptedTasks()
    }

    override suspend fun interruptTask(
        taskId: String,
        interruptedAt: Long,
        technicalDetail: String,
    ): Boolean = withContext(dispatchers.io) {
        dao.interruptTask(
            taskId = taskId,
            interruptedAt = interruptedAt,
            failureMessage = DownloadFailureCategory.ANDROID_INTERRUPTED_TASK.userMessage,
            technicalDetail = technicalDetail,
        ) == 1
    }

    override suspend fun retry(taskId: String): Boolean = withContext(dispatchers.io) {
        dao.retry(taskId) == 1
    }

    override suspend fun removeHistoryEntry(taskId: String): Boolean = withContext(dispatchers.io) {
        dao.removeHistoryEntry(taskId) == 1
    }

    override suspend fun clearCompletedHistory(): Int = withContext(dispatchers.io) {
        dao.clearCompletedHistory()
    }
}
