package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueSchedulerTest {
    @Test
    fun `default scheduler admits two tasks and schedules the next after completion`() {
        val scheduler = DownloadQueueScheduler()
        scheduler.restore(listOf("one", "two", "three"))

        assertEquals(listOf("one", "two"), scheduler.takeReady())
        assertEquals(listOf("one", "two"), scheduler.activeTaskIds())
        assertEquals(listOf("three"), scheduler.waitingTaskIds())
        assertTrue(scheduler.complete("one"))
        assertEquals(listOf("three"), scheduler.takeReady())
        assertEquals(listOf("two", "three"), scheduler.activeTaskIds())
    }

    @Test
    fun `cancelling one waiting task does not affect an independent active task`() {
        val scheduler = DownloadQueueScheduler(maxConcurrent = 1)
        scheduler.restore(listOf("active", "cancelled", "next"))
        assertEquals(listOf("active"), scheduler.takeReady())

        assertTrue(scheduler.cancelWaiting("cancelled"))
        assertEquals(listOf("active"), scheduler.activeTaskIds())
        assertEquals(listOf("next"), scheduler.waitingTaskIds())
        assertFalse(scheduler.isIdle())

        scheduler.complete("active")
        assertEquals(listOf("next"), scheduler.takeReady())
    }

    @Test
    fun `recovery is stable and does not admit duplicate task ids`() {
        val scheduler = DownloadQueueScheduler(maxConcurrent = 2)
        scheduler.restore(listOf("recovered", "queued", "recovered"))

        assertEquals(listOf("recovered", "queued"), scheduler.takeReady())
        assertFalse(scheduler.enqueue("recovered"))
        assertTrue(scheduler.waitingTaskIds().isEmpty())

        scheduler.complete("recovered")
        scheduler.complete("queued")
        assertTrue(scheduler.isIdle())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid concurrency is rejected`() {
        DownloadQueueScheduler(maxConcurrent = 0)
    }
}
