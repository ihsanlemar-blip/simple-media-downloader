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

enum class NavigationTab(val label: String) {
    GATEWAY("Gateway"),
    TRANSFERS("Transfers"),
    VAULT("Vault"),
    SETTINGS("Settings"),
}

enum class VaultViewMode(val label: String) {
    GRID("Grid"),
    LIST("List"),
}

enum class VaultMediaType(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
}

data class MainUiState(
    val currentTab: NavigationTab = NavigationTab.GATEWAY,
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
    val vaultViewMode: VaultViewMode = VaultViewMode.GRID,
    val vaultMediaType: VaultMediaType = VaultMediaType.ALL,
    val selectedVaultTaskIds: Set<String> = emptySet(),
    val isMultiSelectActive: Boolean = false,
    val previewMedia: DownloadOutput? = null,
    val wifiOnly: Boolean = false,
    val maxConcurrentDownloads: Int = 3,
    val allowThirdPartyGateways: Boolean = false,
) {
    val activeTaskCount: Int get() = tasks.count(DownloadTask::isActive)

    val activeTasks: List<DownloadTask>
        get() = tasks.filter(DownloadTask::isActive)

    val filteredHistoryTasks: List<DownloadTask>
        get() = filteredVaultTasks

    val filteredVaultTasks: List<DownloadTask>
        get() = tasks.filter { !it.isActive }
            .filter { task ->
                when (vaultMediaType) {
                    VaultMediaType.ALL -> true
                    VaultMediaType.VIDEOS -> {
                        val isVideoMode = task.format.mode == DownloadMode.VIDEO
                        val isVideoState = (task.state as? DownloadState.Completed)?.output?.mimeType?.startsWith("video") == true
                        isVideoMode || isVideoState
                    }
                    VaultMediaType.AUDIO -> {
                        val isAudioMode = task.format.mode != DownloadMode.VIDEO
                        val isAudioState = (task.state as? DownloadState.Completed)?.output?.mimeType?.startsWith("audio") == true
                        isAudioMode || isAudioState
                    }
                }
            }
            .filter { task ->
                if (historySearchQuery.isBlank()) true
                else task.title.contains(historySearchQuery, ignoreCase = true) ||
                    task.url.contains(historySearchQuery, ignoreCase = true)
            }
            .filter { task ->
                if (historyPlatformFilter.isNullOrBlank()) true
                else task.platform.equals(historyPlatformFilter, ignoreCase = true)
            }

    val totalActiveSpeedBytesPerSec: Long
        get() = activeTasks.mapNotNull { it.progress.speedBytesPerSecond }.sum()

    val totalDownloadedBytesInSession: Long
        get() = tasks.sumOf { it.progress.downloadedBytes ?: 0L }
}

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DownloadRepository =
        (application as SimpleMediaDownloaderApp).downloadRepository,
    private val preferenceStore: DownloadPreferenceStore =
        (application as SimpleMediaDownloaderApp).downloadPreferenceStore,
    private val dispatchers: AppDispatchers =
        (application as SimpleMediaDownloaderApp).dispatchers,
    private val storageExporter: StorageExporter =
        (application as? SimpleMediaDownloaderApp)?.storageExporter
            ?: object : StorageExporter {
                override suspend fun prepareDestination(request: DownloadRequest) =
                    Result.failure<ExportDestination>(UnsupportedOperationException())
                override suspend fun exportCompletedFile(
                    request: DownloadRequest,
                    destination: ExportDestination,
                    commandOutput: String,
                ) = Result.failure<DownloadOutput>(UnsupportedOperationException())
                override suspend fun cleanup(destination: ExportDestination) {}
                override suspend fun outputExists(output: DownloadOutput) = false
                override suspend fun clearDisposableCache(): Long = repository.clearDisposableCache()
            },
    private val formatDiscoveryEngine: FormatDiscoveryEngine? =
        (application as? SimpleMediaDownloaderApp)?.formatDiscoveryEngine,
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
        viewModelScope.launch {
            preferenceStore.wifiOnly.collect { enabled ->
                _uiState.update { it.copy(wifiOnly = enabled) }
            }
        }
        viewModelScope.launch {
            preferenceStore.maxConcurrentDownloads.collect { limit ->
                _uiState.update { it.copy(maxConcurrentDownloads = limit) }
            }
        }
        viewModelScope.launch {
            preferenceStore.vaultViewMode.collect { modeStr ->
                val mode = if (modeStr == "list") VaultViewMode.LIST else VaultViewMode.GRID
                _uiState.update { it.copy(vaultViewMode = mode) }
            }
        }
        viewModelScope.launch {
            preferenceStore.allowThirdPartyGateways.collect { allowed ->
                _uiState.update { it.copy(allowThirdPartyGateways = allowed) }
            }
        }
    }

    fun setTab(tab: NavigationTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun setVaultViewMode(mode: VaultViewMode) {
        _uiState.update { it.copy(vaultViewMode = mode) }
        viewModelScope.launch {
            preferenceStore.setVaultViewMode(if (mode == VaultViewMode.GRID) "grid" else "list")
        }
    }

    fun setVaultMediaType(type: VaultMediaType) {
        _uiState.update { it.copy(vaultMediaType = type) }
    }

    fun toggleVaultTaskSelection(id: String) {
        _uiState.update {
            val current = it.selectedVaultTaskIds
            val updated = if (id in current) current - id else current + id
            it.copy(
                selectedVaultTaskIds = updated,
                isMultiSelectActive = updated.isNotEmpty(),
            )
        }
    }

    fun selectAllVaultTasks() {
        _uiState.update {
            val allIds = it.filteredVaultTasks.map { t -> t.id }.toSet()
            it.copy(selectedVaultTaskIds = allIds, isMultiSelectActive = allIds.isNotEmpty())
        }
    }

    fun clearVaultSelection() {
        _uiState.update { it.copy(selectedVaultTaskIds = emptySet(), isMultiSelectActive = false) }
    }

    fun deleteSelectedVaultTasks(alsoDeleteFiles: Boolean = true) {
        val ids = _uiState.value.selectedVaultTaskIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failedIds = mutableSetOf<String>()
            for (taskId in ids) {
                val result = if (alsoDeleteFiles) {
                    repository.deleteMediaAndHistory(taskId)
                } else {
                    runCatching {
                        check(repository.removeHistoryEntry(taskId)) {
                            "Could not remove history entry."
                        }
                    }
                }
                if (result.isFailure) {
                    failedIds.add(taskId)
                }
            }
            val successCount = ids.size - failedIds.size
            _uiState.update { current ->
                current.copy(
                    selectedVaultTaskIds = failedIds,
                    isMultiSelectActive = failedIds.isNotEmpty(),
                )
            }
            if (failedIds.isNotEmpty()) {
                showMessage("Failed to delete ${failedIds.size} items")
            } else {
                showMessage("Deleted $successCount items from vault.")
            }
        }
    }

    private val previewPlaybackPositions = mutableMapOf<String, Long>()

    fun getSavedPreviewPosition(uri: String): Long = previewPlaybackPositions[uri] ?: 0L

    fun savePreviewPosition(uri: String, positionMs: Long) {
        if (positionMs > 0L) {
            previewPlaybackPositions[uri] = positionMs
        } else {
            previewPlaybackPositions.remove(uri)
        }
    }

    fun setPreviewMedia(output: DownloadOutput?) {
        _uiState.update { it.copy(previewMedia = output) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { preferenceStore.setWifiOnly(enabled) }
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        viewModelScope.launch { preferenceStore.setMaxConcurrentDownloads(limit) }
    }

    fun setAllowThirdPartyGateways(enabled: Boolean) {
        viewModelScope.launch { preferenceStore.setAllowThirdPartyGateways(enabled) }
    }

    fun clearAppCache() {
        viewModelScope.launch(dispatchers.io) {
            val bytesFreed = storageExporter.clearDisposableCache()
            formatDiscoveryEngine?.clearCache() ?: repository.clearFormatCache()
            val formatted = formatByteCount(bytesFreed)
            showMessage("Cleaned $formatted of temporary cache")
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
            val serviceResult = runCatching {
                DownloadService.enqueue(
                    getApplication(),
                    processId,
                    concurrency = preferenceStore.maxConcurrentDownloads.value,
                )
            }
            if (serviceResult.isFailure) {
                val error = serviceResult.exceptionOrNull()
                repository.failTask(
                    processId,
                    message = "Could not start background download service. Tap Retry to try again.",
                    category = DownloadFailureCategory.UNKNOWN_FAILURE,
                    technicalDetail = error?.stackTraceToString()?.take(2_000),
                )
                showMessage("Could not start background download service. Tap Retry on the task to try again.")
            }
        }
    }

    fun cancel(processId: String) {
        if (_uiState.value.tasks.none { it.id == processId && it.isActive }) return
        runCatching {
            DownloadService.cancel(getApplication(), processId)
        }.onFailure {
            showMessage("Could not send cancel request to download service.")
        }
    }

    fun cancelAll() {
        if (_uiState.value.tasks.none(DownloadTask::isActive)) return
        runCatching {
            DownloadService.cancelAll(getApplication())
        }.onFailure {
            showMessage("Could not send cancel request to download service.")
        }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            if (repository.retry(taskId)) {
                val serviceResult = runCatching {
                    DownloadService.enqueue(
                        getApplication(),
                        taskId,
                        concurrency = preferenceStore.maxConcurrentDownloads.value,
                    )
                }
                if (serviceResult.isFailure) {
                    val error = serviceResult.exceptionOrNull()
                    repository.failTask(
                        taskId,
                        message = "Could not start background download service. Tap Retry to try again.",
                        category = DownloadFailureCategory.UNKNOWN_FAILURE,
                        technicalDetail = error?.stackTraceToString()?.take(2_000),
                    )
                    showMessage("Could not start background download service. Tap Retry on the task to try again.")
                }
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
            runCatching {
                DownloadService.refresh(getApplication())
            }.onFailure {
                showMessage("Could not update download service notifications.")
            }
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
