package com.example.simplemediadownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class BatchUiState(
    val pendingProfileUrl: String? = null,
    val parents: List<BatchDownloadEntity> = emptyList(),
    val snapshot: BatchSnapshot? = null,
    val items: List<BatchItemEntity> = emptyList(),
    val page: Int = 0,
    val busy: Boolean = false,
    val discovering: Boolean = false,
    val error: String? = null,
    val estimate: BatchEstimate? = null,
)

class BatchViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as SimpleMediaDownloaderApp
    private val batches = app.batchRepository
    private val _state = MutableStateFlow(BatchUiState())
    val state = _state.asStateFlow()
    private var observation: Job? = null
    private var itemObservation: Job? = null
    private var operation: Job? = null
    init {
        viewModelScope.launch { batches.batches.collect { list -> _state.update { it.copy(parents = list) } } }
        _state.update { it.copy(pendingProfileUrl = savedState.get<String>("profileUrl")) }
        savedState.get<String>("batchId")?.let(::open)
    }
    fun initialize(url: String?) {
        if (url == null || savedState.get<String>("batchId") != null) return
        if (SourceUrlClassifier.classify(url) == SourceUrlType.SOCIAL_PROFILE) {
            savedState["profileUrl"] = url
            _state.update { it.copy(pendingProfileUrl = url) }
            return
        }
        perform(discovery = true) {
            val id = batches.create(url)
            open(id)
            batches.discoverNext(id)
        }
    }
    fun dismissProfileChoice() {
        savedState.remove<String>("profileUrl")
        _state.update { it.copy(pendingProfileUrl = null) }
    }
    fun startProfile(count: Int) {
        val url = _state.value.pendingProfileUrl ?: return
        perform(discovery = true) {
            ProfileDiscoveryPolicy.validate(count)
            val id = batches.create(url, count)
            dismissProfileChoice()
            open(id)
            batches.discoverRemaining(id)
        }
    }
    fun open(id: String) {
        if (savedState.get<String>("batchId") != id) savedState["page"] = 0
        savedState["batchId"] = id
        _state.update { it.copy(snapshot = null, items = emptyList(), estimate = null, error = null) }
        observation?.cancel()
        observation = viewModelScope.launch { batches.observe(id).collect { snapshot -> _state.update { it.copy(snapshot = snapshot) } } }
        page(savedState.get<Int>("page") ?: 0)
    }
    fun closePreview() {
        observation?.cancel(); itemObservation?.cancel()
        savedState.remove<String>("batchId"); savedState.remove<Int>("page")
        _state.update { it.copy(snapshot = null, items = emptyList(), estimate = null, page = 0) }
    }
    fun page(page: Int) {
        val id = savedState.get<String>("batchId") ?: return
        savedState["page"] = page.coerceAtLeast(0)
        _state.update { it.copy(page = page.coerceAtLeast(0)) }
        itemObservation?.cancel()
        itemObservation = viewModelScope.launch { batches.items(id, 50, page.coerceAtLeast(0) * 50).collect { list -> _state.update { it.copy(items = list) } } }
    }
    fun discoverMore() = discover(false)
    fun analyzeRemaining() = discover(true)
    private fun discover(all: Boolean) {
        val id = savedState.get<String>("batchId") ?: return
        perform(discovery = true) { if (all) batches.discoverRemaining(id) else batches.discoverNext(id) }
    }
    fun stopDiscovery() { if (_state.value.discovering) operation?.cancel() }
    fun select(item: String?, selected: Boolean) = current { batches.select(it, item, selected) }
    fun configure(choice: BatchFormatChoice? = null, skipExisting: Boolean? = null, prefix: Boolean? = null) = current { id ->
        val p = requireNotNull(_state.value.snapshot).parent
        batches.configure(id, choice ?: p.formatChoice, skipExisting ?: p.skipExisting, prefix ?: p.prefixOrder)
    }
    fun audioDefault() {
        val p = _state.value.snapshot?.parent ?: return
        configure(BatchFormatChoice.audioDefault(p.platform, app.downloadPreferenceStore.youtubeMp3BitrateKbps.value))
    }
    fun estimate() = current { id -> val estimate = batches.estimate(id); _state.update { it.copy(estimate = estimate) } }
    fun dismissEstimate() { _state.update { it.copy(estimate = null) } }
    fun enqueue() = current { id ->
        batches.enqueue(id)
        dismissEstimate()
        wakeService()
    }
    fun pause() = current { batches.pause(it); wakeService() }
    fun resume() = current { batches.resume(it); wakeService() }
    fun cancel() = current { batches.cancel(it); wakeService() }
    fun retry() = current { batches.retryFailed(it); wakeService() }
    fun delete() = current { batches.deleteHistory(it); closePreview() }
    private fun wakeService() = DownloadService.refresh(getApplication())
    private fun current(block: suspend (String) -> Unit) {
        val id = savedState.get<String>("batchId") ?: return
        perform { block(id) }
    }
    private fun perform(discovery: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, discovering = discovery, error = null, estimate = null) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(error = if (_state.value.snapshot?.parent?.collectionType == CollectionType.SOCIAL_PROFILE.name) profileError(error) else CredentialRedactor.redactDiagnostics(error.message) ?: "Could not update this batch") } }
            finally { _state.update { it.copy(busy = false, discovering = false) } }
        }
    }
}
