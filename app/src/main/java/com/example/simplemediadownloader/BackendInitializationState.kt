package com.example.simplemediadownloader

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackendInitializationState {
    private val _state = MutableStateFlow(
        BackendState(
            initializing = false,
            youtubeDlReady = false,
            ffmpegReady = false,
        ),
    )
    val state: StateFlow<BackendState> = _state.asStateFlow()

    @Synchronized
    fun beginYoutubeDlInitialization(): Boolean {
        val current = _state.value
        if (current.initializing || current.youtubeDlReady) return false
        _state.value = current.copy(
            initializing = true,
            youtubeDlReady = false,
            error = null,
        )
        return true
    }

    @Synchronized
    fun youtubeDlReady() {
        _state.value = _state.value.copy(
            initializing = false,
            youtubeDlReady = true,
            error = null,
        )
    }

    @Synchronized
    fun youtubeDlFailed(message: String) {
        _state.value = _state.value.copy(
            initializing = false,
            youtubeDlReady = false,
            error = message,
        )
    }

    @Synchronized
    fun ffmpegInitializing() {
        _state.value = _state.value.copy(ffmpegInitializing = true)
    }

    @Synchronized
    fun ffmpegReady() {
        _state.value = _state.value.copy(
            ffmpegInitializing = false,
            ffmpegReady = true,
            error = null,
        )
    }

    @Synchronized
    fun ffmpegFailed(message: String) {
        _state.value = _state.value.copy(
            ffmpegInitializing = false,
            error = message,
        )
    }
}
