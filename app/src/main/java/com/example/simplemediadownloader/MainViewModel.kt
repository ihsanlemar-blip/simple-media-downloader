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
    val isDownloading: Boolean = false,
    val activeProcessId: String? = null,
    val progress: DownloadProgress = DownloadProgress(),
    val result: DownloadResult? = null,
    val message: String? = null,
    val isUpdatingBackend: Boolean = false,
)

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
                result = null,
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
            _uiState.update { it.copy(message = "The shared text does not contain an HTTP or HTTPS URL.") }
        } else {
            setUrl(url)
            if (_uiState.value.isDownloading) {
                pendingSharedUrl = url
                _uiState.update {
                    it.copy(message = "Shared URL added. Finish or cancel the current download first.")
                }
            } else if (_uiState.value.backend.ready) {
                discoverFormats(url)
            } else {
                pendingSharedUrl = url
                _uiState.update {
                    it.copy(message = "Shared URL added. Formats will open when the backend is ready.")
                }
            }
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
            _uiState.update { it.copy(message = "Clipboard does not contain an HTTP or HTTPS URL.") }
        } else {
            setUrl(url)
            _uiState.update { it.copy(message = "URL pasted. Tap Download to choose a format.") }
        }
    }

    fun chooseFormat() {
        val snapshot = _uiState.value
        if (snapshot.isDownloading || snapshot.isDiscoveringFormats) return
        if (!snapshot.backend.ready) {
            _uiState.update { it.copy(message = "The download backend is not ready.") }
            return
        }
        val url = UrlExtractor.extractFirstHttpUrl(snapshot.url)
        if (url == null || url != snapshot.url.trim()) {
            _uiState.update { it.copy(message = "Enter one valid HTTP or HTTPS URL.") }
            return
        }

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
        if (snapshot.isDownloading) return
        if (!snapshot.backend.ready) {
            _uiState.update { it.copy(message = "The download backend is not ready.") }
            return
        }
        val url = UrlExtractor.extractFirstHttpUrl(snapshot.url)
        val isCurrentFormat = snapshot.formatCatalog
            ?.takeIf { it.sourceUrl == url }
            ?.let { format in it.videoFormats || format in it.audioFormats }
            ?: false
        if (url == null || !isCurrentFormat) {
            _uiState.update { it.copy(message = "Formats are out of date. Tap Download to refresh them.") }
            return
        }

        discoveryGeneration++
        val processId = UUID.randomUUID().toString()
        val initialProgress = DownloadProgress(status = "Connecting…")
        _uiState.update {
            it.copy(
                isDownloading = true,
                activeProcessId = processId,
                progress = initialProgress,
                result = null,
                showFormatPicker = false,
                isDiscoveringFormats = false,
            )
        }
        notifier.showProgress(initialProgress, force = true)

        viewModelScope.launch {
            val result = downloader.download(
                url = url,
                format = format,
                processId = processId,
            ) { progress ->
                if (_uiState.value.activeProcessId == processId) {
                    _uiState.update { current ->
                        if (current.activeProcessId == processId) {
                            current.copy(progress = progress)
                        } else {
                            current
                        }
                    }
                    notifier.showProgress(progress)
                }
            }

            if (_uiState.value.activeProcessId == processId) {
                when (result) {
                    is DownloadResult.Success -> notifier.showCompleted(result.file)
                    DownloadResult.Cancelled -> notifier.showCancelled()
                    is DownloadResult.Failure -> notifier.showFailed(result.message)
                }
            }
            _uiState.update { current ->
                if (current.activeProcessId == processId) {
                    current.copy(
                        isDownloading = false,
                        activeProcessId = null,
                        progress = if (result is DownloadResult.Success) {
                            DownloadProgress(100f, null, "Completed")
                        } else {
                            current.progress
                        },
                        result = result,
                    )
                } else {
                    current
                }
            }
            pendingSharedUrl?.let { sharedUrl ->
                pendingSharedUrl = null
                discoverFormats(sharedUrl)
            }
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
                result = null,
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

    fun cancel() {
        val processId = _uiState.value.activeProcessId ?: return
        val progress = _uiState.value.progress.copy(status = "Cancelling…")
        _uiState.update { it.copy(progress = progress) }
        notifier.showProgress(progress, force = true)
        viewModelScope.launch {
            downloader.cancel(processId)
        }
    }

    fun republishDownloadNotification() {
        val state = _uiState.value
        if (state.isDownloading) notifier.showProgress(state.progress, force = true)
    }

    fun updateYoutubeDl() {
        val snapshot = _uiState.value
        if (
            !snapshot.backend.youtubeDlReady ||
            snapshot.isUpdatingBackend ||
            snapshot.isDownloading ||
            snapshot.isDiscoveringFormats
        ) return

        _uiState.update {
            it.copy(isUpdatingBackend = true, message = "Checking for a download engine update…")
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
}
