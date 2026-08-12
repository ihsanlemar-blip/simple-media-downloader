package com.example.simplemediadownloader

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class MainUiState(
    val url: String = "",
    val backend: BackendState = BackendState(),
    val isDiscoveringFormats: Boolean = false,
    val formatCatalog: MediaFormatCatalog? = null,
    val showFormatPicker: Boolean = false,
    val tasks: List<DownloadTask> = emptyList(),
    val message: String? = null,
    val isUpdatingBackend: Boolean = false,
) {
    val activeTaskCount: Int get() = tasks.count(DownloadTask::isActive)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val downloader = MediaDownloader()
    private val notifier = DownloadNotifier(application)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var discoveryGeneration = 0
    private var pendingSharedUrl: String? = null

    init {
        viewModelScope.launch {
            SimpleMediaDownloaderApp.backendState.collect { backend ->
                _uiState.update { it.copy(backend = backend) }
                if (backend.ready) {
                    pendingSharedUrl?.let { url ->
                        pendingSharedUrl = null
                        discoverFormats(url)
                    }
                }
            }
        }
    }

    fun setUrl(value: String) {
        discoveryGeneration++
        _uiState.update {
            it.copy(
                url = value,
                formatCatalog = null,
                showFormatPicker = false,
                isDiscoveringFormats = false,
            )
        }
    }

    fun clearUrl() {
        pendingSharedUrl = null
        setUrl("")
    }

    fun acceptSharedText(text: String?) {
        val url = UrlExtractor.extractFirstHttpUrl(text)
        if (url == null) {
            showMessage("The shared text does not contain an HTTP or HTTPS URL.")
            return
        }

        setUrl(url)
        if (_uiState.value.backend.ready) {
            discoverFormats(url)
        } else {
            pendingSharedUrl = url
            showMessage("Shared URL added. Formats will open when the engine is ready.")
        }
    }

    fun pasteFromClipboard() {
        val clipboard = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(getApplication())
            ?.toString()
        val url = UrlExtractor.extractFirstHttpUrl(text)

        if (url == null) {
            showMessage("Clipboard does not contain an HTTP or HTTPS URL.")
        } else {
            setUrl(url)
            showMessage("URL pasted. Use Fast download or choose a quality.")
        }
    }

    fun fastDownload() {
        val url = validCurrentUrl() ?: return
        enqueueDownload(url, downloader.fastVideoPreset(), "Fast video")
    }

    fun chooseFormat() {
        val snapshot = _uiState.value
        if (snapshot.isDiscoveringFormats) return
        if (!snapshot.backend.ready) {
            showMessage("The download engine is not ready.")
            return
        }
        val url = validCurrentUrl() ?: return

        if (snapshot.formatCatalog?.sourceUrl == url) {
            _uiState.update { it.copy(showFormatPicker = true) }
        } else {
            discoverFormats(url)
        }
    }

    fun dismissFormatPicker() {
        _uiState.update { it.copy(showFormatPicker = false) }
    }

    fun download(format: AvailableFormat) {
        val snapshot = _uiState.value
        if (!snapshot.backend.ready) {
            showMessage("The download engine is not ready.")
            return
        }
        val url = UrlExtractor.extractFirstHttpUrl(snapshot.url)
        val isCurrentFormat = snapshot.formatCatalog
            ?.takeIf { it.sourceUrl == url }
            ?.let { format in it.videoFormats || format in it.audioFormats }
            ?: false
        if (url == null || !isCurrentFormat) {
            showMessage("Formats are out of date. Choose quality again to refresh them.")
            return
        }

        _uiState.update { it.copy(showFormatPicker = false) }
        enqueueDownload(url, format, formatTaskTitle(format))
    }

    private fun enqueueDownload(url: String, format: AvailableFormat, title: String) {
        val processId = UUID.randomUUID().toString()
        val initialProgress = DownloadProgress(status = "Queued...")
        val task = DownloadTask(
            id = processId,
            url = url,
            title = title,
            format = format,
            progress = initialProgress,
        )
        _uiState.update { it.copy(tasks = listOf(task) + it.tasks) }
        notifier.showProgress(task, force = true)

        viewModelScope.launch {
            if (format.requiresFfmpeg) {
                updateTaskProgress(processId, DownloadProgress(status = "Preparing media converter..."))
                val ffmpegResult = getApplication<SimpleMediaDownloaderApp>().ensureFfmpeg()
                if (ffmpegResult.isFailure) {
                    finishTask(
                        processId,
                        DownloadResult.Failure(
                            "The selected format needs the media converter, but it could not start: " +
                                (ffmpegResult.exceptionOrNull()?.message ?: "unknown error"),
                        ),
                    )
                    return@launch
                }
            }

            val result = downloader.download(
                url = url,
                format = format,
                processId = processId,
            ) { progress -> updateTaskProgress(processId, progress) }
            finishTask(processId, result)
        }
    }

    private fun updateTaskProgress(processId: String, progress: DownloadProgress) {
        var updatedTask: DownloadTask? = null
        _uiState.update { current ->
            current.copy(
                tasks = current.tasks.map { task ->
                    if (task.id == processId && task.isActive) {
                        task.copy(progress = progress).also { updatedTask = it }
                    } else {
                        task
                    }
                },
            )
        }
        updatedTask?.let(notifier::showProgress)
    }

    private fun finishTask(processId: String, result: DownloadResult) {
        val task = _uiState.value.tasks.firstOrNull { it.id == processId } ?: return
        when (result) {
            is DownloadResult.Success -> notifier.showCompleted(processId, result.file)
            DownloadResult.Cancelled -> notifier.showCancelled(processId)
            is DownloadResult.Failure -> notifier.showFailed(processId, result.message)
        }
        _uiState.update { current ->
            current.copy(
                tasks = current.tasks.map { item ->
                    if (item.id == processId) {
                        item.copy(
                            isActive = false,
                            progress = if (result is DownloadResult.Success) {
                                DownloadProgress(100f, null, "Completed")
                            } else {
                                item.progress
                            },
                            result = result,
                        )
                    } else {
                        item
                    }
                },
            )
        }
    }

    fun cancel(processId: String) {
        val task = _uiState.value.tasks.firstOrNull { it.id == processId && it.isActive } ?: return
        updateTaskProgress(processId, task.progress.copy(status = "Cancelling..."))
        viewModelScope.launch { downloader.cancel(processId) }
    }

    fun clearFinishedTasks() {
        _uiState.update { it.copy(tasks = it.tasks.filter(DownloadTask::isActive)) }
    }

    fun republishDownloadNotifications() {
        _uiState.value.tasks.filter(DownloadTask::isActive).forEach {
            notifier.showProgress(it, force = true)
        }
    }

    private fun discoverFormats(url: String) {
        val generation = ++discoveryGeneration
        val quickCatalog = downloader.quickFormatCatalog(url)
        _uiState.update {
            it.copy(
                isDiscoveringFormats = true,
                formatCatalog = quickCatalog,
                showFormatPicker = true,
            )
        }
        viewModelScope.launch {
            val result = downloader.discoverFormats(url)
            if (generation != discoveryGeneration) return@launch
            when (result) {
                is FormatDiscoveryResult.Success -> _uiState.update { current ->
                    current.copy(
                        isDiscoveringFormats = false,
                        formatCatalog = result.catalog,
                        showFormatPicker = current.showFormatPicker,
                    )
                }
                is FormatDiscoveryResult.Failure -> _uiState.update { current ->
                    current.copy(
                        isDiscoveringFormats = false,
                        message = result.message,
                    )
                }
            }
        }
    }

    fun updateYoutubeDl() {
        val snapshot = _uiState.value
        if (
            !snapshot.backend.youtubeDlReady ||
            snapshot.isUpdatingBackend ||
            snapshot.activeTaskCount > 0 ||
            snapshot.isDiscoveringFormats
        ) return

        _uiState.update {
            it.copy(isUpdatingBackend = true, message = "Checking for a download engine update...")
        }
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    when (
                        YoutubeDL.getInstance().updateYoutubeDL(
                            getApplication(),
                            YoutubeDL.UpdateChannel.STABLE,
                        )
                    ) {
                        YoutubeDL.UpdateStatus.DONE -> "Download engine updated successfully."
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Download engine is already up to date."
                        null -> "Download engine update finished."
                    }
                } catch (error: Exception) {
                    "Update failed; the bundled download engine is still available: " +
                        (error.message ?: error.javaClass.simpleName)
                }
            }
            _uiState.update { it.copy(isUpdatingBackend = false, message = message) }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun validCurrentUrl(): String? {
        if (!_uiState.value.backend.ready) {
            showMessage("The download engine is not ready.")
            return null
        }
        val raw = _uiState.value.url.trim()
        val url = UrlExtractor.extractFirstHttpUrl(raw)
        if (url == null || url != raw) {
            showMessage("Enter one valid HTTP or HTTPS URL.")
            return null
        }
        return url
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    private fun formatTaskTitle(format: AvailableFormat): String = when (format.mode) {
        DownloadMode.VIDEO -> if (format.height > 0) "${format.height}p video" else "Video"
        DownloadMode.AUDIO_ORIGINAL -> "Original ${format.extension.uppercase()} audio"
        DownloadMode.AUDIO_MP3 -> "${format.bitrateKbps} kbps MP3"
    }
}
