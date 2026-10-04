package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfilePersistenceTest {
    @Test fun `profile previews and normal children survive database close reopen and interruption recovery`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "profile-recovery-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, DownloadDatabase::class.java, name).build()
        var db = open()
        val parent = BatchDownloadEntity("profile", "https://www.tiktok.com/@teacher", "TikTok", CollectionType.SOCIAL_PROFILE.name,
            "Teacher", 20, 3, 3, 1L, BatchStatus.QUEUED.name, hasMore = false, downloadMode = DownloadMode.AUDIO_ORIGINAL.name,
            discoveryPage = 2, discoveryNotice = "Only 3 public media posts were available.")
        val preview = parent.copy(batchId = "preview", status = BatchStatus.READY.name, continuation = "page2", hasMore = true, selectedCount = 1)
        try {
            db.batchDao().insert(parent); db.batchDao().insert(preview)
            val format = AvailableFormat("native", DownloadMode.AUDIO_ORIGINAL, "https://media.example/audio.m4a", extension = "m4a")
            val items = (0..2).map { index ->
                val post = "https://www.tiktok.com/@teacher/video/${index + 1}"
                val status = listOf(DownloadTaskStatus.COMPLETED, DownloadTaskStatus.RUNNING, DownloadTaskStatus.QUEUED)[index]
                val record = DownloadRecord("child$index", post, "Lesson $index", "TikTok", format, status = status,
                    stage = if (index == 0) DownloadProcessingStage.COMPLETED else if (index == 1) DownloadProcessingStage.DOWNLOADING_AUDIO else DownloadProcessingStage.QUEUED,
                    progressPercent = null, etaSeconds = null,
                    output = if (index == 0) DownloadOutput("content://media/1", "audio/mp4", 123L, "Lesson.m4a") else null,
                    createdAt = 1L, startedAt = null, completedAt = if (index == 0) 2L else null,
                    failureCategory = null, failureMessage = null, technicalFailureDetail = null,
                    batchId = parent.batchId, batchIndex = index, sourceItemId = "${index + 1}")
                db.downloadTaskDao().insert(record.toEntity())
                BatchItemEntity(parent.batchId, "${index + 1}", post, "Lesson $index", "@teacher", null, 300L, index,
                    selected = true, childTaskId = record.taskId, publishedAtSeconds = 1000L - index)
            }
            db.batchDao().insertItems(items)
            db.batchDao().insertItems(items.map { it.copy(batchId = preview.batchId, selected = it.position == 0, childTaskId = null) })
            db.close(); db = open()
            assertEquals(parent, db.batchDao().get(parent.batchId)); assertEquals(preview, db.batchDao().get(preview.batchId))
            assertEquals(items, db.batchDao().items(parent.batchId))
            assertEquals(listOf(true, false, false), db.batchDao().items(preview.batchId).map { it.selected })
            val history = RoomDownloadHistoryStore(db.downloadTaskDao())
            assertEquals(1, history.recoverRunningTasks(3L, "Restart")); assertEquals(1, history.requeueInterruptedTasks())
            val children = db.batchDao().children(parent.batchId)
            assertEquals(listOf("COMPLETED", "QUEUED", "QUEUED"), children.map { it.status })
            assertEquals(listOf(0, 1, 2), children.map { it.batchIndex })
            assertEquals("content://media/1", children.first().outputContentUri)
            assertEquals(2, db.batchDao().observeCounts(parent.batchId).first().queued)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
