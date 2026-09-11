package com.example.simplemediadownloader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Query(
        """SELECT * FROM download_tasks
           WHERE status IN ('QUEUED', 'RUNNING')
           ORDER BY created_at DESC""",
    )
    fun observeActiveTasks(): Flow<List<DownloadTaskEntity>>

    @Query(
        """SELECT * FROM download_tasks
           WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')
           ORDER BY COALESCE(completed_at, created_at) DESC""",
    )
    fun observeRecentHistory(): Flow<List<DownloadTaskEntity>>

    @Query(
        """SELECT * FROM download_tasks
           WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')
           ORDER BY COALESCE(completed_at, created_at) DESC
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun getHistoricalTasks(limit: Int, offset: Int): List<DownloadTaskEntity>

    @Query(
        """SELECT * FROM download_tasks
           WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')
           AND display_title LIKE '%' || :query || '%'
           ORDER BY COALESCE(completed_at, created_at) DESC""",
    )
    fun searchHistory(query: String): Flow<List<DownloadTaskEntity>>

    @Query(
        """SELECT * FROM download_tasks
           WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')
           AND display_title LIKE '%' || :query || '%'
           ORDER BY COALESCE(completed_at, created_at) DESC
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun searchHistoryPaged(query: String, limit: Int, offset: Int): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: DownloadTaskEntity)

    @Query(
        """SELECT COUNT(*) FROM download_tasks
           WHERE canonical_url = :canonicalUrl AND format_key = :formatKey
           AND status IN ('QUEUED', 'RUNNING')""",
    )
    suspend fun countActiveDuplicate(canonicalUrl: String, formatKey: String): Int

    @Transaction
    suspend fun insertIfNoActiveDuplicate(task: DownloadTaskEntity): Boolean {
        val canonical = if (task.canonicalUrl.isNotBlank()) {
            task.canonicalUrl
        } else {
            NormalizedMediaUrl.from(task.sourceUrl)
        }
        if (countActiveDuplicate(canonical, task.formatKey) > 0) return false
        val toInsert = if (task.canonicalUrl.isBlank()) task.copy(canonicalUrl = canonical) else task
        insert(toInsert)
        return true
    }

    @Update
    suspend fun update(task: DownloadTaskEntity): Int

    @Query(
        """UPDATE download_tasks SET
           processing_stage = :stage,
           progress_percent = :progressPercent,
           downloaded_bytes = :downloadedBytes,
           total_bytes = :totalBytes,
           speed_bytes_per_second = :speedBytesPerSecond,
           eta_seconds = :etaSeconds
           WHERE task_id = :taskId AND status = 'RUNNING'""",
    )
    suspend fun updateProgress(
        taskId: String,
        stage: String,
        progressPercent: Float?,
        downloadedBytes: Long?,
        totalBytes: Long?,
        speedBytesPerSecond: Long?,
        etaSeconds: Long?,
    ): Int

    @Query("SELECT * FROM download_tasks WHERE task_id = :taskId")
    suspend fun get(taskId: String): DownloadTaskEntity?

    @Query(
        """UPDATE download_tasks SET
           status = 'INTERRUPTED',
           processing_stage = 'INTERRUPTED',
           completed_at = :interruptedAt,
           failure_category = 'ANDROID_INTERRUPTED_TASK',
           failure_message = :failureMessage,
           technical_failure_detail = :technicalDetail
           WHERE status = 'RUNNING'""",
    )
    suspend fun recoverRunningTasks(
        interruptedAt: Long,
        failureMessage: String,
        technicalDetail: String,
    ): Int

    @Query(
        """UPDATE download_tasks SET
           status = 'INTERRUPTED',
           processing_stage = 'INTERRUPTED',
           completed_at = :interruptedAt,
           failure_category = 'ANDROID_INTERRUPTED_TASK',
           failure_message = :failureMessage,
           technical_failure_detail = :technicalDetail
           WHERE task_id = :taskId
           AND status IN ('RUNNING', 'CANCELLED')""",
    )
    suspend fun interruptTask(
        taskId: String,
        interruptedAt: Long,
        failureMessage: String,
        technicalDetail: String,
    ): Int

    @Query("SELECT * FROM download_tasks WHERE status = 'INTERRUPTED'")
    suspend fun getInterruptedTasks(): List<DownloadTaskEntity>

    @Query(
        """UPDATE download_tasks SET
           status = 'QUEUED',
           processing_stage = 'QUEUED',
           progress_percent = NULL,
           downloaded_bytes = NULL,
           total_bytes = NULL,
           speed_bytes_per_second = NULL,
           eta_seconds = NULL,
           output_content_uri = NULL,
           output_mime_type = NULL,
           output_file_size_bytes = NULL,
           output_display_name = NULL,
           started_at = NULL,
           completed_at = NULL,
           failure_category = NULL,
           failure_message = NULL,
           technical_failure_detail = NULL
           WHERE task_id = :taskId""",
    )
    suspend fun resetToQueued(taskId: String): Int

    @Transaction
    suspend fun requeueInterruptedTasks(): Int {
        val interrupted = getInterruptedTasks()
        var requeued = 0
        for (task in interrupted) {
            val canonical = if (task.canonicalUrl.isNotBlank()) {
                task.canonicalUrl
            } else {
                NormalizedMediaUrl.from(task.sourceUrl)
            }
            if (countActiveDuplicate(canonical, task.formatKey) == 0) {
                requeued += resetToQueued(task.taskId)
            }
        }
        return requeued
    }

    @Transaction
    suspend fun retry(taskId: String): Int {
        val task = get(taskId) ?: return 0
        if (task.status !in listOf("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED")) {
            return 0
        }
        val canonical = if (task.canonicalUrl.isNotBlank()) {
            task.canonicalUrl
        } else {
            NormalizedMediaUrl.from(task.sourceUrl)
        }
        if (countActiveDuplicate(canonical, task.formatKey) > 0) {
            return 0
        }
        return resetToQueued(taskId)
    }

    @Query(
        """DELETE FROM download_tasks
           WHERE task_id = :taskId
           AND status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')""",
    )
    suspend fun removeHistoryEntry(taskId: String): Int

    @Query(
        """DELETE FROM download_tasks
           WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')""",
    )
    suspend fun clearCompletedHistory(): Int
}
