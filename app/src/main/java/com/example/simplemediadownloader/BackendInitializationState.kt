package com.example.simplemediadownloader

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackendInitializationState {
    private val _state = MutableStateFlow(
        BackendState(
            initializing = false,
            engineReady = false,
            mediaProcessorReady = false,
        ),
    )
    val state: StateFlow<BackendState> = _state.asStateFlow()

    @Synchronized
    fun beginBackendInitialization(): Boolean {
        val current = _state.value
        if (current.initializing || current.engineReady) return false
        _state.value = current.copy(
            initializing = true,
            engineReady = false,
            error = null,
        )
        return true
    }

    @Synchronized
    fun backendReady() {
        _state.value = _state.value.copy(
            initializing = false,
            engineReady = true,
            error = null,
        )
    }

    @Synchronized
    fun backendFailed(message: String) {
        _state.value = _state.value.copy(
            initializing = false,
            engineReady = false,
            error = message,
        )
    }

    @Synchronized
    fun mediaProcessorInitializing() {
        _state.value = _state.value.copy(mediaProcessorInitializing = true)
    }

    @Synchronized
    fun mediaProcessorReady() {
        _state.value = _state.value.copy(
            mediaProcessorInitializing = false,
            mediaProcessorReady = true,
            error = null,
        )
    }

    @Synchronized
    fun mediaProcessorFailed(message: String) {
        _state.value = _state.value.copy(
            mediaProcessorInitializing = false,
            error = message,
        )
    }
}
