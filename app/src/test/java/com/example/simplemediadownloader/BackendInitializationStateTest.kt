package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendInitializationStateTest {
    @Test
    fun `backend initialization can fail and retry without process restart`() {
        val initialization = BackendInitializationState()

        assertFalse(initialization.state.value.initializing)
        assertFalse(initialization.state.value.ready)
        assertTrue(initialization.beginBackendInitialization())
        assertFalse(initialization.beginBackendInitialization())
        assertTrue(initialization.state.value.initializing)

        initialization.backendFailed("Engine unavailable")
        assertFalse(initialization.state.value.initializing)
        assertEquals("Engine unavailable", initialization.state.value.error)

        assertTrue(initialization.beginBackendInitialization())
        initialization.backendReady()
        assertTrue(initialization.state.value.ready)
        assertTrue(initialization.state.value.engineReady)
        assertFalse(initialization.beginBackendInitialization())
    }

    @Test
    fun `media processor remains uninitialized until conversion explicitly needs it`() {
        val initialization = BackendInitializationState()
        initialization.beginBackendInitialization()
        initialization.backendReady()

        assertFalse(initialization.state.value.mediaProcessorInitializing)
        assertFalse(initialization.state.value.mediaProcessorReady)

        initialization.mediaProcessorInitializing()
        assertTrue(initialization.state.value.mediaProcessorInitializing)
        initialization.mediaProcessorReady()
        assertTrue(initialization.state.value.mediaProcessorReady)
        assertFalse(initialization.state.value.mediaProcessorInitializing)
    }
}
