package com.example.simplemediadownloader

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareDownloadViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val backend = MutableStateFlow(
        BackendState(initializing = false, youtubeDlReady = true),
    )

    @Test
    fun `initialization is one-shot and shows quick formats before exact discovery`() {
        val gateway = FakeShareGateway().apply {
            discoveryGate = CompletableDeferred()
        }
        val viewModel = viewModel(gateway = gateway)
        val shared = SharedUrlResult.Valid(URL, additionalUrlDetected = false)

        viewModel.initialize(shared)
        assertTrue(viewModel.uiState.value.catalog!!.detailsLoading)
        viewModel.initialize(shared)
        gateway.discoveryGate!!.complete(Unit)
        idleMainLooper()

        assertEquals(1, gateway.discoveryCalls)
        assertFalse(viewModel.uiState.value.inspectingFormats)
        assertFalse(viewModel.uiState.value.catalog!!.detailsLoading)
    }

    @Test
    fun `selection and advanced state restore from saved state`() {
        val restoredState = mapOf<String, Any?>(
            ShareDownloadViewModel.KEY_SOURCE_URL to URL,
            ShareDownloadViewModel.KEY_ADVANCED_VISIBLE to true,
            ShareDownloadViewModel.KEY_SELECTION_MODE to DownloadMode.AUDIO_MP3.name,
            ShareDownloadViewModel.KEY_SELECTION_HEIGHT to 0,
            ShareDownloadViewModel.KEY_SELECTION_BITRATE to 192,
        )
        val gateway = FakeShareGateway().apply { cachedCatalog = exactCatalog() }
        val viewModel = viewModel(
            gateway = gateway,
            savedStateHandle = SavedStateHandle(restoredState),
        )

        viewModel.initialize(SharedUrlResult.Valid(URL, false))
        idleMainLooper()

        assertTrue(viewModel.uiState.value.advancedFormatsVisible)
        assertEquals(DownloadMode.AUDIO_MP3, viewModel.uiState.value.selectedFormat!!.mode)
        assertEquals(192, viewModel.uiState.value.selectedFormat!!.bitrateKbps)
        assertEquals(0, gateway.discoveryCalls)
    }

    @Test
    fun `repeated download taps persist and hand off exactly once`() {
        val gateway = FakeShareGateway()
        val starter = FakeServiceStarter()
        val viewModel = viewModel(gateway = gateway, serviceStarter = starter)
        viewModel.initialize(SharedUrlResult.Valid(URL, false))

        assertEquals(ShareDownloadAction.ENQUEUE_STARTED, viewModel.requestDownload(false))
        assertEquals(ShareDownloadAction.IGNORED, viewModel.requestDownload(false))
        idleMainLooper()

        assertEquals(1, gateway.enqueueCalls)
        assertEquals(listOf("stable-share-task"), starter.taskIds)
        assertTrue(viewModel.uiState.value.enqueueSucceeded)
    }

    @Test
    fun `notification permission gates but does not duplicate successful handoff`() {
        val gateway = FakeShareGateway()
        val starter = FakeServiceStarter()
        val viewModel = viewModel(gateway = gateway, serviceStarter = starter)
        viewModel.initialize(SharedUrlResult.Valid(URL, false))

        assertEquals(
            ShareDownloadAction.REQUEST_NOTIFICATION_PERMISSION,
            viewModel.requestDownload(true),
        )
        assertTrue(viewModel.uiState.value.awaitingNotificationPermission)
        assertEquals(ShareDownloadAction.IGNORED, viewModel.requestDownload(true))
        assertEquals(
            ShareDownloadAction.ENQUEUE_STARTED,
            viewModel.continueAfterNotificationPermission(),
        )
        idleMainLooper()

        assertEquals(1, gateway.enqueueCalls)
        assertEquals(1, starter.taskIds.size)
    }

    @Test
    fun `cancel before enqueue never persists or starts service`() {
        val gateway = FakeShareGateway()
        val starter = FakeServiceStarter()
        val viewModel = viewModel(gateway = gateway, serviceStarter = starter)
        viewModel.initialize(SharedUrlResult.Valid(URL, false))

        assertTrue(viewModel.cancelBeforeEnqueue())
        assertEquals(ShareDownloadAction.IGNORED, viewModel.requestDownload(false))
        idleMainLooper()

        assertEquals(0, gateway.enqueueCalls)
        assertTrue(starter.taskIds.isEmpty())
    }

    @Test
    fun `invalid input remains readable and cannot enqueue`() {
        val gateway = FakeShareGateway()
        val starter = FakeServiceStarter()
        val viewModel = viewModel(gateway = gateway, serviceStarter = starter)

        viewModel.initialize(SharedUrlResult.Invalid("No valid shared link."))

        assertEquals("No valid shared link.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.canDownload)
        assertEquals(ShareDownloadAction.IGNORED, viewModel.requestDownload(false))
        assertEquals(0, gateway.enqueueCalls)
    }

    @Test
    fun `enqueue failure stays open and service failure can retry without duplicate insert`() {
        val gateway = FakeShareGateway().apply {
            enqueueResult = Result.failure(IllegalStateException("database full"))
        }
        val starter = FakeServiceStarter()
        val failedEnqueue = viewModel(gateway = gateway, serviceStarter = starter)
        failedEnqueue.initialize(SharedUrlResult.Valid(URL, false))
        failedEnqueue.requestDownload(false)
        idleMainLooper()

        assertFalse(failedEnqueue.uiState.value.enqueueSucceeded)
        assertTrue(failedEnqueue.uiState.value.error!!.contains("database full"))
        assertTrue(starter.taskIds.isEmpty())

        gateway.enqueueResult = Result.success(Unit)
        starter.failuresRemaining = 1
        val handoffRetry = viewModel(gateway = gateway, serviceStarter = starter)
        handoffRetry.initialize(SharedUrlResult.Valid(URL, false))
        handoffRetry.requestDownload(false)
        idleMainLooper()
        assertNotNull(handoffRetry.uiState.value.error)
        handoffRetry.requestDownload(false)
        idleMainLooper()

        assertTrue(handoffRetry.uiState.value.enqueueSucceeded)
        assertEquals(2, gateway.enqueueCalls)
        assertEquals(1, starter.taskIds.size)
    }

    @Test
    fun `restored persisted task resumes only the service handoff`() {
        val gateway = FakeShareGateway()
        val starter = FakeServiceStarter()
        val handle = SavedStateHandle(
            mapOf<String, Any?>(
                ShareDownloadViewModel.KEY_TASK_ID to "restored-task",
                ShareDownloadViewModel.KEY_PERSISTED to true,
                ShareDownloadViewModel.KEY_HANDED_OFF to false,
            ),
        )

        val viewModel = viewModel(gateway, starter, handle)
        idleMainLooper()

        assertEquals(0, gateway.enqueueCalls)
        assertEquals(listOf("restored-task"), starter.taskIds)
        assertTrue(viewModel.uiState.value.enqueueSucceeded)
    }

    private fun viewModel(
        gateway: FakeShareGateway,
        serviceStarter: FakeServiceStarter = FakeServiceStarter(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = ShareDownloadViewModel(
        application = application,
        savedStateHandle = savedStateHandle,
        gateway = gateway,
        preferenceStore = FakePreferenceStore(),
        backendState = backend,
        serviceStarter = serviceStarter,
        taskIdFactory = { "stable-share-task" },
    )

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class FakePreferenceStore : DownloadPreferenceStore {
        override val defaultChoice: StateFlow<DefaultDownloadChoice> =
            MutableStateFlow(DefaultDownloadChoice.VIDEO_720)
        override val themeMode: StateFlow<AppThemeMode> =
            MutableStateFlow(AppThemeMode.DYNAMIC)
        override val wifiOnly: StateFlow<Boolean> =
            MutableStateFlow(false)
        override val maxConcurrentDownloads: StateFlow<Int> =
            MutableStateFlow(3)
        override val vaultViewMode: StateFlow<String> =
            MutableStateFlow("grid")

        override suspend fun setDefaultChoice(choice: DefaultDownloadChoice) = Unit
        override suspend fun setThemeMode(mode: AppThemeMode) = Unit
        override suspend fun setWifiOnly(enabled: Boolean) = Unit
        override suspend fun setMaxConcurrentDownloads(limit: Int) = Unit
        override suspend fun setVaultViewMode(mode: String) = Unit
    }

    private class FakeShareGateway : ShareDownloadGateway {
        var discoveryCalls = 0
        var enqueueCalls = 0
        var enqueueResult: Result<Unit> = Result.success(Unit)
        var cachedCatalog: MediaFormatCatalog? = null
        var discoveryGate: CompletableDeferred<Unit>? = null
        private var existingRequest: DownloadRequest? = null

        override fun quickFormatCatalog(url: String) = quickCatalog(url)
        override fun cachedFormatCatalog(url: String) = cachedCatalog
        override fun fastVideoPreset() = quickCatalog(URL).videoFormats.first()

        override suspend fun discoverFormats(url: String): FormatDiscoveryResult {
            discoveryCalls++
            discoveryGate?.await()
            return FormatDiscoveryResult.Success(exactCatalog(url))
        }

        override suspend fun enqueue(request: DownloadRequest): Result<Unit> {
            enqueueCalls++
            if (enqueueResult.isSuccess) existingRequest = request
            return enqueueResult
        }

        override suspend fun request(taskId: String): DownloadRequest? =
            existingRequest?.takeIf { it.id == taskId }
    }

    private class FakeServiceStarter : DownloadServiceStarter {
        var failuresRemaining = 0
        val taskIds = mutableListOf<String>()

        override fun start(context: Context, taskId: String) {
            if (failuresRemaining > 0) {
                failuresRemaining--
                error("service unavailable")
            }
            taskIds += taskId
        }
    }

    companion object {
        private const val URL = "https://www.youtube.com/watch?v=shared"

        private fun quickCatalog(url: String) = MediaFormatCatalog(
            sourceUrl = url,
            title = "Common presets",
            videoFormats = listOf(video("quick-1080", 1080), video("quick-720", 720)),
            audioFormats = listOf(
                audio("quick-original", DownloadMode.AUDIO_ORIGINAL, 0),
                audio("quick-mp3", DownloadMode.AUDIO_MP3, 192),
            ),
            detailsLoading = true,
        )

        private fun exactCatalog(url: String = URL) = quickCatalog(url).copy(
            title = "Shared video",
            videoFormats = listOf(
                video("exact-1080", 1080, 40_000_000L),
                video("exact-720", 720, 20_000_000L),
                video("exact-480", 480, 10_000_000L),
            ),
            audioFormats = listOf(
                audio("exact-original", DownloadMode.AUDIO_ORIGINAL, 160),
                audio("exact-mp3", DownloadMode.AUDIO_MP3, 192),
            ),
            detailsLoading = false,
        )

        private fun video(key: String, height: Int, size: Long? = null) = AvailableFormat(
            key = key,
            mode = DownloadMode.VIDEO,
            formatId = key,
            extension = "mp4",
            height = height,
            estimatedSizeBytes = size,
        )

        private fun audio(key: String, mode: DownloadMode, bitrate: Int) = AvailableFormat(
            key = key,
            mode = mode,
            formatId = key,
            extension = if (mode == DownloadMode.AUDIO_MP3) "mp3" else "m4a",
            bitrateKbps = bitrate,
        )
    }
}
