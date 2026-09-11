package com.example.simplemediadownloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DownloadEngineCancellationTest {

    private class FakeCall : Call {
        private val cancelled = AtomicBoolean(false)
        private val executed = AtomicBoolean(false)

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isCanceled(): Boolean = cancelled.get()

        override fun isExecuted(): Boolean = executed.get()

        override fun execute(): Response {
            executed.set(true)
            if (isCanceled()) {
                throw IOException("Canceled")
            }
            throw UnsupportedOperationException("Not implemented")
        }

        override fun enqueue(responseCallback: Callback) = Unit

        override fun request(): Request =
            Request.Builder().url("https://example.com/stream").build()

        override fun clone(): Call = FakeCall()

        override fun timeout(): Timeout = Timeout.NONE
    }

    @Test
    fun `registerCall registers call and unregisterCall removes it cleanly`() {
        val engine = OkHttpDownloadEngine()
        val call = FakeCall()

        val registered = engine.registerCall("task-1", call)
        assertTrue(registered)
        assertEquals(1, engine.activeCallCount("task-1"))

        engine.unregisterCall("task-1", call)
        assertEquals(0, engine.activeCallCount("task-1"))
    }

    @Test
    fun `registerCall immediately cancels call if task is already cancelled`() = runBlocking {
        val engine = OkHttpDownloadEngine()
        engine.cancel("task-cancelled")

        val call = FakeCall()
        val registered = engine.registerCall("task-cancelled", call)

        assertFalse(registered)
        assertTrue("Call must be immediately cancelled", call.isCanceled())
        assertEquals(0, engine.activeCallCount("task-cancelled"))
    }

    @Test
    fun `cancel cancels all currently active registered calls via safe snapshot`() = runBlocking {
        val engine = OkHttpDownloadEngine()
        val call1 = FakeCall()
        val call2 = FakeCall()
        val call3 = FakeCall()

        engine.registerCall("task-multi", call1)
        engine.registerCall("task-multi", call2)
        engine.registerCall("task-multi", call3)

        assertEquals(3, engine.activeCallCount("task-multi"))

        engine.cancel("task-multi")

        assertTrue(call1.isCanceled())
        assertTrue(call2.isCanceled())
        assertTrue(call3.isCanceled())
    }

    @Test
    fun `concurrent registration unregistration and cancellation does not throw ConcurrentModificationException`() = runBlocking {
        val engine = OkHttpDownloadEngine()
        val taskId = "concurrent-task"
        val callCount = 100
        val calls = List(callCount) { FakeCall() }
        val errors = AtomicInteger(0)
        val startLatch = CountDownLatch(1)

        val threads = mutableListOf<Thread>()

        // 5 threads registering and unregistering calls
        for (i in 0 until 5) {
            val thread = Thread {
                startLatch.await()
                try {
                    for (c in calls) {
                        if (engine.registerCall(taskId, c)) {
                            Thread.sleep(1)
                            engine.unregisterCall(taskId, c)
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
            threads.add(thread)
            thread.start()
        }

        // 2 threads calling cancel concurrently
        for (i in 0 until 2) {
            val cancelThread = Thread {
                startLatch.await()
                try {
                    Thread.sleep(10)
                    runBlocking { engine.cancel(taskId) }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
            threads.add(cancelThread)
            cancelThread.start()
        }

        startLatch.countDown()
        threads.forEach { it.join(5000) }

        assertEquals("No concurrency exceptions should occur during registry operations", 0, errors.get())
    }

    @Test
    fun `cancellation during stream probing cancels probe call and throws CancellationException`() = runBlocking {
        val probeExecuted = AtomicBoolean(false)
        val probeBlockedLatch = CountDownLatch(1)
        val cancellationLatch = CountDownLatch(1)

        val blockingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                probeExecuted.set(true)
                probeBlockedLatch.countDown()
                // Wait until cancelled
                cancellationLatch.await(3, TimeUnit.SECONDS)
                chain.proceed(chain.request())
            }
            .build()

        val engine = OkHttpDownloadEngine(
            dispatchers = AppDispatchers(io = Dispatchers.IO),
            client = blockingClient,
        )

        val taskId = "probe-cancel-task"
        val probeJob = async(Dispatchers.IO) {
            try {
                // Trigger download which initiates stream probing
                engine.download(
                    request = DownloadRequest(
                        id = taskId,
                        url = "https://example.com/video.mp4",
                        format = AvailableFormat(
                            key = "best",
                            mode = DownloadMode.VIDEO,
                            formatId = "https://example.com/video.mp4",
                            extension = "mp4",
                        ),
                        title = "Test",
                    ),
                    outputDirectory = java.io.File(System.getProperty("java.io.tmpdir")!!),
                    onState = {},
                )
            } catch (e: CancellationException) {
                DownloadExecutionResult.Cancelled
            }
        }

        // Wait for probe to start
        assertTrue("Probe should start", probeBlockedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Active call should be registered during probe", engine.activeCallCount(taskId) > 0)

        // Cancel the task while probe is active
        engine.cancel(taskId)
        cancellationLatch.countDown()

        val result = probeJob.await()
        assertTrue("Result must be Cancelled", result is DownloadExecutionResult.Cancelled)
        assertEquals("Active calls must be 0 after completion", 0, engine.activeCallCount(taskId))
    }
}
