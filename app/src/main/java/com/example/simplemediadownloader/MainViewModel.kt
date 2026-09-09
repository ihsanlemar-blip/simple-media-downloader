package com.example.simplemediadownloader

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val defaultDownloadChoice: DefaultDownloadChoice = DefaultDownloadChoice.BEST_VIDEO,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val historySearchQuery: String = "",
    val historyPlatformFilter: String? = null,
) {
    val activeTaskCount: Int get() = tasks.count(DownloadTask::isActive)

    val activeTasks: List<DownloadTask>
        get() = tasks.filter(DownloadTask::isActive)

    val filteredHistoryTasks: List<DownloadTask>
        get() = tasks.filter { !it.isActive }
            .filter { task ->
                if (historySearchQuery.isBlank()) true
                else task.title.contains(historySearchQuery, ignoreCase = true) ||
                    task.url.contains(historySearchQuery, ignoreCase = true)
            }
            .filter { task ->
                if (historyPlatformFilter.isNullOrBlank()) true
                else task.platform.equals(historyPlatformFilter, ignoreCase = true)
            }
}

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DownloadRepository =
        (application as SimpleMediaDownloaderApp).downloadRepository,
    private val preferenceStore: DownloadPreferenceStore =
        (application as SimpleMediaDownloaderApp).downloadPreferenceStore,
    private val dispatchers: AppDispatchers =
        (application as SimpleMediaDownloaderApp).dispatchers,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var discoveryGeneration = 0
    private var pendingDiscoveryUrl: String? = null

    init {
        viewModelScope.launch {
            repository.tasks.collect { tasks ->
                _uiState.update { it.copy(tasks = tasks) }
            }
        }
        viewModelScope.launch {
            (application as SimpleMediaDownloaderApp).backendState.collect { backend ->
                _uiState.update { it.copy(backend = backend) }
                if (backend.ready) {
                    pendingDiscoveryUrl?.let { url ->
                        pendingDiscoveryUrl = null
                        startExactDiscovery(url)
                    }
                } else if (!backend.initializing) {
                    _uiState.update { it.copy(isDiscoveringFormats = false) }
                }
            }
        }
        viewModelScope.launch {
            preferenceStore.defaultChoice.collect { choice ->
                _uiState.update { it.copy(defaultDownloadChoice = choice) }
            }
        }
        viewModelScope.launch {
            preferenceStore.themeMode.collect { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            preferenceStore.setThemeMode(mode)
        }
    }

    fun setHistorySearchQuery(query: String) {
        _uiState.update { it.copy(historySearchQuery = query) }
    }

    fun setHistoryPlatformFilter(platform: String?) {
        _uiState.update { it.copy(historyPlatformFilter = platform) }
    }

    fun setUrl(value: String) {
        discoveryGeneration++
        pendingDiscoveryUrl = null
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
        setUrl("")
    }

    fun acceptSharedText(text: String?) {
        val url = UrlExtractor.extractFirstHttpUrl(text)
        if (url == null) {
            showMessage("The shared text does not contain an HTTP or HTTPS URL.")
            return
        }

        setUrl(url)
        showQuickFormatsAndDiscover(url)
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
        val snapshot = _uiState.value
        val url = validCurrentUrl() ?: return
        val choice = snapshot.defaultDownloadChoice
        if (choice == DefaultDownloadChoice.ALWAYS_ASK) {
            showQuickFormatsAndDiscover(url)
            return
        }
        if (!snapshot.backend.ready) {
            showMessage("The download engine is still preparing.")
            return
        }
        val exactCatalog = repository.cachedFormatCatalog(url)
        val quickCatalog = repository.quickFormatCatalog(url)
        val format = exactCatalog?.let {
            DefaultDownloadChoiceMapper.select(choice, it, repository.fastVideoPreset())
        } ?: DefaultDownloadChoiceMapper.select(
            choice,
            quickCatalog,
            repository.fastVideoPreset(),
        )
        if (format == null) {
            showMessage("That default quality is not available. Choose a format instead.")
            showQuickFormatsAndDiscover(url)
            return
        }
        val mediaTitle = exactCatalog?.title
            ?: quickCatalog.title.takeIf { it.isNotBlank() && it != "Fast native downloads" && it != "Available formats" }
            ?: formatTaskTitle(format)
        enqueueDownload(url, format, mediaTitle)
    }

    fun chooseFormat() {
        val snapshot = _uiState.value
        if (snapshot.isDiscoveringFormats) return
        val url = validCurrentUrl() ?: return
        showQuickFormatsAndDiscover(url)
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
        val mediaTitle = snapshot.formatCatalog?.title
            ?.takeIf { it.isNotBlank() && it != "Fast native downloads" && it != "Available formats" }
            ?: formatTaskTitle(format)

        _uiState.update { it.copy(showFormatPicker = false) }
        enqueueDownload(url, format, mediaTitle)
    }

    private fun enqueueDownload(url: String, format: AvailableFormat, title: String) {
        val processId = UUID.randomUUID().toString()
        val request = DownloadRequest(
            id = processId,
            url = url,
            title = title,
            format = format,
        )
        viewModelScope.launch {
            val enqueueResult = repository.enqueue(request)
            if (enqueueResult.isFailure) {
                showMessage(
                    enqueueResult.exceptionOrNull()?.message
                        ?: "Could not add the download to the persistent queue.",
                )
                return@launch
            }
            DownloadService.enqueue(getApplication(), processId)
        }
    }

    fun cancel(processId: String) {
        if (_uiState.value.tasks.none { it.id == processId && it.isActive }) return
        DownloadService.cancel(getApplication(), processId)
    }

    fun cancelAll() {
        if (_uiState.value.tasks.none(DownloadTask::isActive)) return
        DownloadService.cancelAll(getApplication())
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            if (repository.retry(taskId)) {
                DownloadService.enqueue(getApplication(), taskId)
            } else {
                showMessage("This download could not be retried.")
            }
        }
    }

    fun removeHistoryEntry(taskId: String) {
        viewModelScope.launch {
            if (!repository.removeHistoryEntry(taskId)) {
                showMessage("This history entry could not be removed.")
            }
        }
    }

    fun deleteMediaAndHistory(taskId: String) {
        viewModelScope.launch {
            repository.deleteMediaAndHistory(taskId)
                .onFailure { showMessage(it.message ?: "The saved media could not be deleted.") }
        }
    }

    fun clearFinishedTasks() {
        viewModelScope.launch { repository.clearCompletedHistory() }
    }

    fun republishDownloadNotifications() {
        if (_uiState.value.tasks.any(DownloadTask::isActive)) {
            DownloadService.refresh(getApplication())
        }
    }

    private fun showQuickFormatsAndDiscover(url: String) {
        val generation = ++discoveryGeneration
        val cached = repository.cachedFormatCatalog(url)
        val initialCatalog = cached ?: repository.quickFormatCatalog(url)
        _uiState.update {
            it.copy(
                isDiscoveringFormats = cached == null && it.backend.ready,
                formatCatalog = initialCatalog,
                showFormatPicker = true,
            )
        }
        if (cached != null) return
        if (_uiState.value.backend.ready) {
            startExactDiscovery(url, generation)
        } else {
            pendingDiscoveryUrl = url
        }
    }

    private fun startExactDiscovery(url: String, generation: Int = ++discoveryGeneration) {
        _uiState.update { it.copy(isDiscoveringFormats = true) }
        viewModelScope.launch {
            val result = repository.discoverFormats(url)
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

    fun retryYoutubeDlInitialization() {
        (getApplication<Application>() as SimpleMediaDownloaderApp)
            .retryYoutubeDlInitialization()
    }

    fun setDefaultDownloadChoice(choice: DefaultDownloadChoice) {
        viewModelScope.launch {
            runCatching { preferenceStore.setDefaultChoice(choice) }
                .onFailure { showMessage("Could not save the default download choice.") }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun validCurrentUrl(): String? {
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
