package com.example.simplemediadownloader

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SimpleMediaDownloaderApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ffmpegMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.createChannel(this)
        applicationScope.launch { initializeYoutubeDl() }
    }

    private fun initializeYoutubeDl() {
        try {
            YoutubeDL.getInstance().init(this)
            _backendState.value = BackendState(
                initializing = false,
                youtubeDlReady = true,
            )
        } catch (error: Exception) {
            Log.e(TAG, "yt-dlp initialization failed", error)
            _backendState.value = BackendState(
                initializing = false,
                error = "Download engine: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    suspend fun ensureFfmpeg(): Result<Unit> = ffmpegMutex.withLock {
        if (_backendState.value.ffmpegReady) return@withLock Result.success(Unit)
        _backendState.value = _backendState.value.copy(ffmpegInitializing = true)

        runCatching { FFmpeg.getInstance().init(this) }
            .onSuccess {
                _backendState.value = _backendState.value.copy(
                    ffmpegInitializing = false,
                    ffmpegReady = true,
                    error = null,
                )
            }
            .onFailure { error ->
                Log.e(TAG, "FFmpeg initialization failed", error)
                _backendState.value = _backendState.value.copy(
                    ffmpegInitializing = false,
                    error = "Media converter: ${error.message ?: error.javaClass.simpleName}",
                )
            }
    }

    companion object {
        private const val TAG = "SimpleMediaDownloader"
        private val _backendState = MutableStateFlow(BackendState())
        val backendState = _backendState.asStateFlow()
    }
}
