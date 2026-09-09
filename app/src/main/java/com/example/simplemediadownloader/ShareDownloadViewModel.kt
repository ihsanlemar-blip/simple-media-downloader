package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ShareDownloadUiState(
    val sourceUrl: String? = null,
    val displayUrl: String = "",
    val platform: String = "Web",
    val catalog: MediaFormatCatalog? = null,
    val selectedFormatKey: String? = null,
    val advancedFormatsVisible: Boolean = false,
    val inspectingFormats: Boolean = false,
    val enginePreparing: Boolean = true,
    val engineReady: Boolean = false,
    val awaitingNotificationPermission: Boolean = false,
    val enqueueing: Boolean = false,
    val enqueueSucceeded: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val allFormats: List<AvailableFormat>
        get() = catalog?.let { it.videoFormats + it.audioFormats }.orEmpty()

    val selectedFormat: AvailableFormat?
        get() = allFormats.firstOrNull { it.key == selectedFormatKey }

    val canDownload: Boolean
        get() = sourceUrl != null && selectedFormat != null && engineReady &&
            !awaitingNotificationPermission && !enqueueing && !enqueueSucceeded
}

enum class ShareDownloadAction {
    REQUEST_NOTIFICATION_PERMISSION,
    ENQUEUE_STARTED,
    IGNORED,
}

interface ShareDownloadGateway {
    fun quickFormatCatalog(url: String): MediaFormatCatalog
    fun cachedFormatCatalog(url: String): MediaFormatCatalog?
    fun fastVideoPreset(): AvailableFormat
    suspend fun discoverFormats(url: String): FormatDiscoveryResult
    suspend fun enqueue(request: DownloadRequest): Result<Unit>
    suspend fun request(taskId: String): DownloadRequest?
}

class RepositoryShareDownloadGateway(
    private val repository: DownloadRepository,
) : ShareDownloadGateway {
    override fun quickFormatCatalog(url: String) = repository.quickFormatCatalog(url)
    override fun cachedFormatCatalog(url: String) = repository.cachedFormatCatalog(url)
    override fun fastVideoPreset() = repository.fastVideoPreset()
    override suspend fun discoverFormats(url: String) = repository.discoverFormats(url)
    override suspend fun enqueue(request: DownloadRequest) = repository.enqueue(request)
    override suspend fun request(taskId: String) = repository.request(taskId)
}

fun interface DownloadServiceStarter {
    fun start(context: Context, taskId: String)
}

class AndroidDownloadServiceStarter : DownloadServiceStarter {
    override fun start(context: Context, taskId: String) {
        DownloadService.enqueue(context, taskId)
    }
}

class ShareDownloadViewModel @JvmOverloads constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val gateway: ShareDownloadGateway = RepositoryShareDownloadGateway(
        (application as SimpleMediaDownloaderApp).downloadRepository,
    ),
    private val preferenceStore: DownloadPreferenceStore =
        (application as SimpleMediaDownloaderApp).downloadPreferenceStore,
    private val backendState: StateFlow<BackendState> =
        (application as SimpleMediaDownloaderApp).backendState,
    private val serviceStarter: DownloadServiceStarter = AndroidDownloadServiceStarter(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(
        ShareDownloadUiState(
            sourceUrl = savedStateHandle[KEY_SOURCE_URL],
            displayUrl = savedStateHandle.get<String>(KEY_SOURCE_URL)
                ?.let(::shortenedSourceUrl)
                .orEmpty(),
            platform = savedStateHandle.get<String>(KEY_SOURCE_URL)
                ?.let(PlatformResolver::fromUrl)
                ?: "Web",
            advancedFormatsVisible = savedStateHandle[KEY_ADVANCED_VISIBLE] ?: false,
            awaitingNotificationPermission = savedStateHandle[KEY_AWAITING_PERMISSION] ?: false,
            enqueueSucceeded = savedStateHandle[KEY_HANDED_OFF] ?: false,
        ),
    )
    val uiState: StateFlow<ShareDownloadUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var discoveryJob: Job? = null
    private var enqueueStarted = false
    private var cancelled = false

    init {
        viewModelScope.launch {
            backendState.collect { backend ->
                _uiState.update { current ->
                    if (current.sourceUrl == null) return@update current
                    current.copy(
                        enginePreparing = backend.initializing,
                        engineReady = backend.ready,
                        error = when {
                            backend.ready -> current.error?.takeUnless(::isEngineError)
                            backend.initializing -> current.error?.takeUnless(::isEngineError)
                            !backend.initializing && backend.error != null -> backend.error
                            else -> current.error
                        },
                    )
                }
                if (backend.ready) startExactDiscoveryIfNeeded()
            }
        }
        if (
            savedStateHandle.get<Boolean>(KEY_PERSISTED) == true &&
            savedStateHandle.get<Boolean>(KEY_HANDED_OFF) != true
        ) {
            resumeServiceHandoff()
        }
    }

    fun initialize(result: SharedUrlResult) {
        if (initialized) return
        initialized = true
        when (result) {
            is SharedUrlResult.Invalid -> _uiState.update {
                it.copy(
                    enginePreparing = false,
                    inspectingFormats = false,
                    error = result.message,
                )
            }
            is SharedUrlResult.Valid -> initializeUrl(result)
        }
    }

    private fun initializeUrl(result: SharedUrlResult.Valid) {
        val restoredUrl = savedStateHandle.get<String>(KEY_SOURCE_URL)
        val url = restoredUrl ?: result.url.also { savedStateHandle[KEY_SOURCE_URL] = it }
        val cached = gateway.cachedFormatCatalog(url)
        val catalog = cached ?: gateway.quickFormatCatalog(url)
        val selected = restoredSelection(catalog) ?: preferredSelection(catalog)
        val notice = if (result.additionalUrlDetected) {
            "Multiple links were shared. Only the first link will be used."
        } else {
            null
        }
        _uiState.update {
            it.copy(
                sourceUrl = url,
                displayUrl = shortenedSourceUrl(url),
                platform = PlatformResolver.fromUrl(url),
                catalog = catalog,
                selectedFormatKey = selected?.key,
                inspectingFormats = cached == null && backendState.value.ready,
                enginePreparing = backendState.value.initializing,
                engineReady = backendState.value.ready,
                notice = notice,
                error = backendState.value.error,
            )
        }
        selected?.let(::saveSelection)
        if (cached == null) startExactDiscoveryIfNeeded()
    }

    fun selectFormat(formatKey: String) {
        val format = _uiState.value.allFormats.firstOrNull { it.key == formatKey } ?: return
        saveSelection(format)
        _uiState.update { it.copy(selectedFormatKey = format.key, error = null) }
    }

    fun setAdvancedFormatsVisible(visible: Boolean) {
        savedStateHandle[KEY_ADVANCED_VISIBLE] = visible
        _uiState.update { it.copy(advancedFormatsVisible = visible) }
    }

    fun retryEngineInitialization() {
        (getApplication<Application>() as? SimpleMediaDownloaderApp)
            ?.retryYoutubeDlInitialization()
    }

    @Synchronized
    fun requestDownload(notificationPermissionRequired: Boolean): ShareDownloadAction {
        val state = _uiState.value
        if (cancelled || enqueueStarted || state.enqueueSucceeded || state.awaitingNotificationPermission) {
            return ShareDownloadAction.IGNORED
        }
        if (state.sourceUrl == null || state.selectedFormat == null || !state.engineReady) {
            _uiState.update { it.copy(error = "Choose an available format before downloading.") }
            return ShareDownloadAction.IGNORED
        }
        if (notificationPermissionRequired) {
            savedStateHandle[KEY_AWAITING_PERMISSION] = true
            _uiState.update { it.copy(awaitingNotificationPermission = true, error = null) }
            return ShareDownloadAction.REQUEST_NOTIFICATION_PERMISSION
        }
        beginEnqueue()
        return ShareDownloadAction.ENQUEUE_STARTED
    }

    @Synchronized
    fun continueAfterNotificationPermission(): ShareDownloadAction {
        if (cancelled || enqueueStarted || _uiState.value.enqueueSucceeded) {
            return ShareDownloadAction.IGNORED
        }
        savedStateHandle[KEY_AWAITING_PERMISSION] = false
        _uiState.update { it.copy(awaitingNotificationPermission = false) }
        beginEnqueue()
        return ShareDownloadAction.ENQUEUE_STARTED
    }

    @Synchronized
    fun cancelBeforeEnqueue(): Boolean {
        if (enqueueStarted || _uiState.value.enqueueSucceeded) return false
        cancelled = true
        savedStateHandle[KEY_AWAITING_PERMISSION] = false
        _uiState.update { it.copy(awaitingNotificationPermission = false) }
        return true
    }

    private fun beginEnqueue() {
        val state = _uiState.value
        val url = state.sourceUrl ?: return
        val format = state.selectedFormat ?: return
        enqueueStarted = true
        _uiState.update { it.copy(enqueueing = true, error = null) }
        val taskId = stableTaskId()
        val mediaTitle = state.catalog?.title
            ?.takeIf { it.isNotBlank() && it != "Fast native downloads" && it != "Available formats" }
            ?: shareTaskTitle(format)
        val request = DownloadRequest(
            id = taskId,
            url = url,
            title = mediaTitle,
            format = format,
        )
        viewModelScope.launch {
            val alreadyPersisted = savedStateHandle.get<Boolean>(KEY_PERSISTED) == true
            val enqueueResult = if (alreadyPersisted) Result.success(Unit) else gateway.enqueue(request)
            val persisted = enqueueResult.isSuccess || gateway.request(taskId)?.let { existing ->
                existing.url == request.url && existing.format.key == request.format.key
            } == true
            if (!persisted) {
                enqueueStarted = false
                _uiState.update {
                    it.copy(
                        enqueueing = false,
                        error = "Could not add the download to the queue: " +
                            (enqueueResult.exceptionOrNull()?.message ?: "unknown storage error"),
                    )
                }
                return@launch
            }
            savedStateHandle[KEY_PERSISTED] = true
            handoffToService(taskId)
        }
    }

    private fun resumeServiceHandoff() {
        enqueueStarted = true
        _uiState.update { it.copy(enqueueing = true) }
        viewModelScope.launch { handoffToService(stableTaskId()) }
    }

    private fun handoffToService(taskId: String) {
        runCatching { serviceStarter.start(getApplication(), taskId) }
            .onSuccess {
                savedStateHandle[KEY_HANDED_OFF] = true
                enqueueStarted = false
                _uiState.update {
                    it.copy(
                        awaitingNotificationPermission = false,
                        enqueueing = false,
                        enqueueSucceeded = true,
                        error = null,
                    )
                }
            }
            .onFailure { error ->
                enqueueStarted = false
                _uiState.update {
                    it.copy(
                        enqueueing = false,
                        error = "The download was saved but the download service could not start: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            }
    }

    private fun startExactDiscoveryIfNeeded() {
        val url = _uiState.value.sourceUrl ?: return
        if (!backendState.value.ready || discoveryJob != null) return
        gateway.cachedFormatCatalog(url)?.let { cached ->
            replaceCatalog(cached)
            return
        }
        _uiState.update { it.copy(inspectingFormats = true, error = null) }
        discoveryJob = viewModelScope.launch {
            when (val result = gateway.discoverFormats(url)) {
                is FormatDiscoveryResult.Success -> replaceCatalog(result.catalog)
                is FormatDiscoveryResult.Failure -> _uiState.update {
                    it.copy(
                        inspectingFormats = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    private fun replaceCatalog(catalog: MediaFormatCatalog) {
        val previous = _uiState.value.selectedFormat
        val selected = previous?.let { matchSelection(it, catalog) }
            ?: restoredSelection(catalog)
            ?: preferredSelection(catalog)
        selected?.let(::saveSelection)
        _uiState.update {
            it.copy(
                catalog = catalog,
                selectedFormatKey = selected?.key,
                inspectingFormats = false,
                error = null,
            )
        }
    }

    private fun preferredSelection(catalog: MediaFormatCatalog): AvailableFormat? =
        DefaultDownloadChoiceMapper.select(
            preferenceStore.defaultChoice.value,
            catalog,
            gateway.fastVideoPreset(),
        ) ?: catalog.videoFormats.firstOrNull() ?: catalog.audioFormats.firstOrNull()

    private fun restoredSelection(catalog: MediaFormatCatalog): AvailableFormat? {
        val mode = savedStateHandle.get<String>(KEY_SELECTION_MODE)
            ?.let { runCatching { DownloadMode.valueOf(it) }.getOrNull() }
            ?: return null
        val height = savedStateHandle[KEY_SELECTION_HEIGHT] ?: 0
        val bitrate = savedStateHandle[KEY_SELECTION_BITRATE] ?: 0
        return catalog.allFormats().firstOrNull {
            it.mode == mode && when (mode) {
                DownloadMode.VIDEO -> it.height == height
                DownloadMode.AUDIO_ORIGINAL -> true
                DownloadMode.AUDIO_MP3 -> it.bitrateKbps == bitrate
            }
        }
    }

    private fun matchSelection(
        previous: AvailableFormat,
        catalog: MediaFormatCatalog,
    ): AvailableFormat? = catalog.allFormats().firstOrNull { it.key == previous.key }
        ?: catalog.allFormats().firstOrNull {
            it.mode == previous.mode && when (previous.mode) {
                DownloadMode.VIDEO -> it.height == previous.height
                DownloadMode.AUDIO_ORIGINAL -> true
                DownloadMode.AUDIO_MP3 -> it.bitrateKbps == previous.bitrateKbps
            }
        }

    private fun saveSelection(format: AvailableFormat) {
        savedStateHandle[KEY_SELECTION_MODE] = format.mode.name
        savedStateHandle[KEY_SELECTION_HEIGHT] = format.height
        savedStateHandle[KEY_SELECTION_BITRATE] = format.bitrateKbps
    }

    private fun stableTaskId(): String = savedStateHandle.get<String>(KEY_TASK_ID)
        ?: taskIdFactory().also { savedStateHandle[KEY_TASK_ID] = it }

    private fun MediaFormatCatalog.allFormats() = videoFormats + audioFormats

    private fun isEngineError(message: String): Boolean =
        message.startsWith("Download engine:")

    companion object {
        internal const val KEY_SOURCE_URL = "share_source_url"
        internal const val KEY_ADVANCED_VISIBLE = "share_advanced_visible"
        internal const val KEY_SELECTION_MODE = "share_selection_mode"
        internal const val KEY_SELECTION_HEIGHT = "share_selection_height"
        internal const val KEY_SELECTION_BITRATE = "share_selection_bitrate"
        internal const val KEY_TASK_ID = "share_task_id"
        internal const val KEY_AWAITING_PERMISSION = "share_awaiting_permission"
        internal const val KEY_PERSISTED = "share_task_persisted"
        internal const val KEY_HANDED_OFF = "share_service_started"
    }
}

internal fun shareTaskTitle(format: AvailableFormat): String = when (format.mode) {
    DownloadMode.VIDEO -> if (format.height > 0) "${format.height}p video" else "Video"
    DownloadMode.AUDIO_ORIGINAL -> "Original ${format.extension.uppercase()} audio"
    DownloadMode.AUDIO_MP3 -> "${format.bitrateKbps} kbps MP3"
}
