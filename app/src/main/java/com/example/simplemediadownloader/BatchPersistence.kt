package com.example.simplemediadownloader

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "download_batches")
data class BatchDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    val platform: String,
    @ColumnInfo(name = "collection_type") val collectionType: String,
    val title: String?,
    @ColumnInfo(name = "requested_count") val requestedCount: Int?,
    @ColumnInfo(name = "discovered_count") val discoveredCount: Int,
    @ColumnInfo(name = "selected_count") val selectedCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: String,
    val continuation: String? = null,
    @ColumnInfo(name = "has_more") val hasMore: Boolean = true,
    @ColumnInfo(name = "download_mode") val downloadMode: String = DownloadMode.VIDEO.name,
    @ColumnInfo(name = "maximum_height") val maximumHeight: Int = 0,
    @ColumnInfo(name = "mp3_bitrate_kbps") val mp3BitrateKbps: Int = 192,
    @ColumnInfo(name = "skip_existing") val skipExisting: Boolean = true,
    @ColumnInfo(name = "prefix_order") val prefixOrder: Boolean = false,
    val error: String? = null,
    val author: String? = null,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String? = null,
    @ColumnInfo(name = "total_item_count") val totalItemCount: Int? = null,
) { val formatChoice get() = BatchFormatChoice(DownloadMode.valueOf(downloadMode), maximumHeight, mp3BitrateKbps) }

@Entity(tableName = "batch_items", primaryKeys = ["batch_id", "item_id"],
    foreignKeys = [ForeignKey(entity = BatchDownloadEntity::class, parentColumns = ["batch_id"], childColumns = ["batch_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["batch_id", "position"])])
data class BatchItemEntity(
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val url: String,
    val title: String?,
    val author: String?,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long?,
    val position: Int,
    val selected: Boolean = false,
    @ColumnInfo(name = "child_task_id") val childTaskId: String? = null,
    @ColumnInfo(name = "skip_reason") val skipReason: String? = null,
    @ColumnInfo(name = "unavailable_reason") val unavailableReason: String? = null,
)

data class BatchChildCounts(val queued: Int, val running: Int, val completed: Int, val failed: Int, val cancelled: Int)

@Dao
interface BatchDao {
    @Insert suspend fun insert(batch: BatchDownloadEntity)
    @Update suspend fun update(batch: BatchDownloadEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertItems(items: List<BatchItemEntity>)
    @Query("SELECT * FROM download_batches ORDER BY created_at DESC") fun observeBatches(): Flow<List<BatchDownloadEntity>>
    @Query("SELECT * FROM download_batches WHERE batch_id = :id") suspend fun get(id: String): BatchDownloadEntity?
    @Query("SELECT * FROM download_batches WHERE batch_id = :id") fun observe(id: String): Flow<BatchDownloadEntity?>
    @Query("SELECT * FROM batch_items WHERE batch_id = :id ORDER BY position LIMIT :limit OFFSET :offset") suspend fun items(id: String, limit: Int = 50, offset: Int = 0): List<BatchItemEntity>
    @Query("SELECT * FROM batch_items WHERE batch_id = :id ORDER BY position LIMIT :limit OFFSET :offset") fun observeItems(id: String, limit: Int = 50, offset: Int = 0): Flow<List<BatchItemEntity>>
    @Query("SELECT * FROM download_tasks WHERE batch_id = :id ORDER BY batch_index") suspend fun children(id: String): List<DownloadTaskEntity>
    @Query("""SELECT COALESCE(SUM(status = 'QUEUED'), 0) AS queued,
        COALESCE(SUM(status = 'RUNNING'), 0) AS running, COALESCE(SUM(status = 'COMPLETED'), 0) AS completed,
        COALESCE(SUM(status IN ('FAILED', 'INTERRUPTED')), 0) AS failed,
        COALESCE(SUM(status = 'CANCELLED'), 0) AS cancelled FROM download_tasks WHERE batch_id = :id""")
    fun observeCounts(id: String): Flow<BatchChildCounts>
    @Query("UPDATE batch_items SET selected = :selected WHERE batch_id = :id AND item_id = :itemId AND unavailable_reason IS NULL") suspend fun select(id: String, itemId: String, selected: Boolean)
    @Query("UPDATE batch_items SET selected = :selected WHERE batch_id = :id AND unavailable_reason IS NULL") suspend fun selectAll(id: String, selected: Boolean)
    @Query("UPDATE download_batches SET discovered_count = (SELECT COUNT(*) FROM batch_items WHERE batch_id = :id), selected_count = (SELECT COUNT(*) FROM batch_items WHERE batch_id = :id AND selected = 1) WHERE batch_id = :id") suspend fun updateCounts(id: String)
    @Query("UPDATE batch_items SET child_task_id = :childId, skip_reason = :reason WHERE batch_id = :id AND item_id = :itemId") suspend fun assignChild(id: String, itemId: String, childId: String?, reason: String?)
    @Query("UPDATE download_tasks SET batch_id = NULL, batch_index = NULL, source_item_id = NULL WHERE batch_id = :id") suspend fun detachChildren(id: String)
    @Query("DELETE FROM download_batches WHERE batch_id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM download_tasks WHERE canonical_url = :url AND format_key = :key AND status = 'COMPLETED' AND output_content_uri IS NOT NULL ORDER BY completed_at DESC LIMIT 20") suspend fun completedDuplicates(url: String, key: String): List<DownloadTaskEntity>
}
