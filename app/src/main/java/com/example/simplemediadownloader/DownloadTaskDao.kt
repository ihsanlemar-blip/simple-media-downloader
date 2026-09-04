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
           ORDER BY COALESCE(completed_at, created_at) DESC
           LIMIT :limit""",
    )
    fun observeRecentHistory(limit: Int = 100): Flow<List<DownloadTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: DownloadTaskEntity)

    @Query(
        """SELECT COUNT(*) FROM download_tasks
           WHERE source_url = :sourceUrl AND format_key = :formatKey
           AND status IN ('QUEUED', 'RUNNING')""",
    )
    suspend fun countActiveDuplicate(sourceUrl: String, formatKey: String): Int

    @Transaction
    suspend fun insertIfNoActiveDuplicate(task: DownloadTaskEntity): Boolean {
        if (countActiveDuplicate(task.sourceUrl, task.formatKey) > 0) return false
        insert(task)
        return true
    }

    @Update
    suspend fun update(task: DownloadTaskEntity): Int

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
           WHERE status = 'INTERRUPTED'""",
    )
    suspend fun requeueInterruptedTasks(): Int

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
           WHERE task_id = :taskId
           AND status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'INTERRUPTED')""",
    )
    suspend fun retry(taskId: String): Int

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
