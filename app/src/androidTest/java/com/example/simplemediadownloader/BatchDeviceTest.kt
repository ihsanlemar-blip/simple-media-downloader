package com.example.simplemediadownloader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BatchDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val parent = BatchDownloadEntity("preview", "https://youtube.com/playlist?list=test", "YouTube", "YOUTUBE_PLAYLIST", "Course", null, 1, 0, 100, "READY")
    @Test fun selectionPreviewNeverEnqueuesUntilReview() {
        val state = mutableStateOf(BatchUiState(snapshot = BatchSnapshot(parent, BatchProgress(1, 0, 0, 0, 0, 0, 0)), items = listOf(
            BatchItemEntity("preview", "lesson", "https://youtube.com/watch?v=lesson", "Lesson", "Teacher", null, 300, 0))))
        var reviews = 0
        compose.setContent { MaterialTheme {
            BatchScreen(state.value, {}, {}, { _, selected -> state.value = state.value.copy(
                snapshot = BatchSnapshot(parent.copy(selectedCount = if (selected) 1 else 0), BatchProgress(1, if (selected) 1 else 0, 0, 0, 0, 0, 0)),
                items = state.value.items.map { it.copy(selected = selected) }) }, {}, {}, {}, {}, {}, {}, { reviews++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("batch_list").performScrollToNode(hasTestTag("batch_select_all"))
        compose.onNodeWithTag("batch_select_all").performClick()
        assertTrue(state.value.items.first().selected)
        assertEquals(0, reviews)
        compose.onNodeWithTag("batch_list").performScrollToNode(hasTestTag("batch_review"))
        compose.onNodeWithTag("batch_review").performClick()
        assertEquals(1, reviews)
    }
    @Test fun roomBatchSelectionAndCursorSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "batch-device-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, DownloadDatabase::class.java, name).addMigrations(*DownloadDatabaseMigrations.ALL).build()
        var db = open()
        try {
            db.batchDao().insert(parent.copy(continuation = "next-page", selectedCount = 1))
            db.batchDao().insertItems(listOf(BatchItemEntity(parent.batchId, "lesson", "https://youtube.com/watch?v=lesson", "Lesson", null, null, 300, 0, true)))
            db.close(); db = open()
            assertEquals("next-page", db.batchDao().get(parent.batchId)!!.continuation)
            assertEquals(1, db.batchDao().get(parent.batchId)!!.selectedCount)
            assertTrue(db.batchDao().items(parent.batchId).first().selected)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
