package com.example.simplemediadownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendInitializationStateTest {
    @Test
    fun `youtube initialization can fail and retry without process restart`() {
        val initialization = BackendInitializationState()

        assertFalse(initialization.state.value.initializing)
        assertFalse(initialization.state.value.ready)
        assertTrue(initialization.beginYoutubeDlInitialization())
        assertFalse(initialization.beginYoutubeDlInitialization())
        assertTrue(initialization.state.value.initializing)

        initialization.youtubeDlFailed("Engine unavailable")
        assertFalse(initialization.state.value.initializing)
        assertEquals("Engine unavailable", initialization.state.value.error)

        assertTrue(initialization.beginYoutubeDlInitialization())
        initialization.youtubeDlReady()
        assertTrue(initialization.state.value.ready)
        assertFalse(initialization.beginYoutubeDlInitialization())
    }

    @Test
    fun `ffmpeg remains uninitialized until conversion explicitly needs it`() {
        val initialization = BackendInitializationState()
        initialization.beginYoutubeDlInitialization()
        initialization.youtubeDlReady()

        assertFalse(initialization.state.value.ffmpegInitializing)
        assertFalse(initialization.state.value.ffmpegReady)

        initialization.ffmpegInitializing()
        assertTrue(initialization.state.value.ffmpegInitializing)
        initialization.ffmpegReady()
        assertTrue(initialization.state.value.ffmpegReady)
        assertFalse(initialization.state.value.ffmpegInitializing)
    }
}
