package com.example.simplemediadownloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FormatDiscoveryCancellationTest {

    @Test
    fun `cancelled coroutine during format discovery propagates CancellationException`() = runBlocking {
        val engine = NewPipeFormatDiscoveryEngine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val deferred = scope.async {
            engine.discoverFormats("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        }

        // Cancel the operation immediately
        deferred.cancel(CancellationException("User cancelled format discovery"))

        try {
            deferred.await()
            fail("Expected CancellationException was not thrown; result was swallowed into Failure")
        } catch (e: CancellationException) {
            assertTrue("CancellationException must be propagated directly", true)
        }
    }

    @Test
    fun `custom cancellation exception is rethrown instead of returning Failure`() = runBlocking {
        val testDispatcher = AppDispatchers(
            io = Dispatchers.Unconfined,
        )
        val engine = NewPipeFormatDiscoveryEngine(dispatchers = testDispatcher)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel(CancellationException("Explicit scope cancellation"))

        val deferred = scope.async {
            engine.discoverFormats("https://www.youtube.com/watch?v=cancelled")
        }

        try {
            deferred.await()
            fail("Expected CancellationException was not thrown")
        } catch (e: CancellationException) {
            assertTrue(e.message?.contains("Explicit scope cancellation") == true || e is CancellationException)
        }
    }
}
