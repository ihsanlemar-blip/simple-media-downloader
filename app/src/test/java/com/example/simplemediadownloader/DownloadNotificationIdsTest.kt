package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationIdsTest {
    @Test
    fun `task notification IDs are stable positive and outside foreground namespace`() {
        val taskId = "f39126d5-fade-4a9f-92ab-0cc82f597c19"
        val first = DownloadNotificationIds.forTask(taskId)

        assertEquals(first, DownloadNotificationIds.forTask(taskId))
        assertTrue(first >= 1_000)
        assertNotEquals(DownloadNotificationIds.FOREGROUND, first)
        assertNotEquals(first, DownloadNotificationIds.forTask("another-task"))
    }
}
