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

class SimpleMediaDownloaderApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.createChannel(this)
        applicationScope.launch { initializeBackends() }
    }

    private fun initializeBackends() {
        var youtubeDlReady = false
        var ffmpegReady = false
        val errors = mutableListOf<String>()

        try {
            YoutubeDL.getInstance().init(this)
            youtubeDlReady = true
        } catch (error: Exception) {
            Log.e(TAG, "yt-dlp initialization failed", error)
            errors += "Download engine: ${error.message ?: error.javaClass.simpleName}"
        }

        try {
            FFmpeg.getInstance().init(this)
            ffmpegReady = true
        } catch (error: Exception) {
            Log.e(TAG, "FFmpeg initialization failed", error)
            errors += "FFmpeg: ${error.message ?: error.javaClass.simpleName}"
        }

        _backendState.value = BackendState(
            initializing = false,
            youtubeDlReady = youtubeDlReady,
            ffmpegReady = ffmpegReady,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    }

    companion object {
        private const val TAG = "SimpleMediaDownloader"
        private val _backendState = MutableStateFlow(BackendState())
        val backendState = _backendState.asStateFlow()
    }
}
