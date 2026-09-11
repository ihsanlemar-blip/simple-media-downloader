package com.example.simplemediadownloader

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SimpleMediaDownloaderApp::class)
class NetworkPolicyEnforcementTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var app: SimpleMediaDownloaderApp
    private lateinit var fakeNetwork: FakeNetworkConnectivityManager
    private lateinit var fakePrefs: FakeDownloadPreferenceStore
    private lateinit var database: DownloadDatabase
    private lateinit var dao: DownloadTaskDao
    private lateinit var repository: DownloadRepository
    private lateinit var serviceController: ServiceController<DownloadService>

    private val format = AvailableFormat(
        key = "video:wifi_test",
        mode = DownloadMode.VIDEO,
        formatId = "720",
        extension = "mp4",
        height = 720,
    )

    private val output = DownloadOutput(
        contentUri = "content://media/wifi_test/output.mp4",
        mimeType = "video/mp4",
        fileSizeBytes = 50 * 1024L,
        displayName = "wifi_test.mp4",
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<SimpleMediaDownloaderApp>()
        database = Room.inMemoryDatabaseBuilder(app, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadTaskDao()

        fakeNetwork = FakeNetworkConnectivityManager(
            NetworkStatus(isConnected = true, isWifi = true, isMetered = false),
        )
        fakePrefs = FakeDownloadPreferenceStore(initialWifiOnly = true, initialMaxConcurrent = 2)

        app.networkConnectivityManager = fakeNetwork
        app.downloadPreferenceStore = fakePrefs
    }

    @After
    fun tearDown() {
        if (::serviceController.isInitialized) {
            runCatching { serviceController.destroy() }
        }
        database.close()
    }

    @Test
    fun `network status accurately determines allowed transports based on wifi only policy`() {
        // Wi-Fi connected, unmetered
        val wifi = NetworkStatus(isConnected = true, isWifi = true, isMetered = false)
        assertTrue("Wi-Fi should be allowed when wifiOnly is true", wifi.isAllowed(wifiOnly = true))
        assertTrue("Wi-Fi should be allowed when wifiOnly is false", wifi.isAllowed(wifiOnly = false))

        // Metered Wi-Fi (e.g. hotspot) is still Wi-Fi
        val meteredWifi = NetworkStatus(isConnected = true, isWifi = true, isMetered = true)
        assertTrue("Metered Wi-Fi is still Wi-Fi", meteredWifi.isAllowed(wifiOnly = true))

        // Cellular metered connection
        val cellular = NetworkStatus(isConnected = true, isWifi = false, isMetered = true)
        assertFalse("Cellular should be blocked when wifiOnly is true", cellular.isAllowed(wifiOnly = true))
        assertTrue("Cellular should be allowed when wifiOnly is false", cellular.isAllowed(wifiOnly = false))

        // Unmetered non-Wi-Fi (e.g. Ethernet)
        val ethernet = NetworkStatus(isConnected = true, isWifi = false, isMetered = false)
        assertTrue("Unmetered Ethernet should be allowed when wifiOnly is true", ethernet.isAllowed(wifiOnly = true))

        // Disconnected
        val disconnected = NetworkStatus(isConnected = false, isWifi = false, isMetered = true)
        assertFalse("Disconnected is never allowed", disconnected.isAllowed(wifiOnly = true))
        assertFalse("Disconnected is never allowed", disconnected.isAllowed(wifiOnly = false))
    }

    @Test
    fun `download refuses to start and is held in WAITING_FOR_WIFI when on cellular with wifiOnly enabled`() = runBlocking {
        // Simulate cellular network
        fakeNetwork.setStatus(NetworkStatus(isConnected = true, isWifi = false, isMetered = true))
        fakePrefs.setWifiOnly(true)

        val downloadAttempts = AtomicInteger(0)
        val fakeEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                outputDirectory: File,
                onState: (DownloadState) -> Unit,
            ): DownloadExecutionResult {
                downloadAttempts.incrementAndGet()
                return DownloadExecutionResult.Success("ok")
            }
            override suspend fun cancel(processId: String) = true
        }

        repository = createTestRepository(fakeEngine)
        app.downloadRepository = repository

        val request = DownloadRequest(
            id = "cell-task-1",
            url = "https://example.com/cell1",
            title = "Cellular Download",
            format = format,
        )
        repository.enqueue(request).getOrThrow()

        // Start service
        serviceController = Robolectric.buildService(DownloadService::class.java)
        serviceController.create()
        serviceController.startCommand(0, 1)

        // Give background queue collection time to process
        var stage1 = dao.get(request.id)?.processingStage
        val deadline1 = System.currentTimeMillis() + 2000
        while (stage1 != DownloadProcessingStage.WAITING_FOR_WIFI.name && System.currentTimeMillis() < deadline1) {
            delay(50)
            stage1 = dao.get(request.id)?.processingStage
        }

        // Engine must not have been invoked
        assertEquals("DownloadEngine must not be started on cellular when wifiOnly is enabled", 0, downloadAttempts.get())

        // Database record must be QUEUED with stage WAITING_FOR_WIFI
        val entity = dao.get(request.id)!!
        assertEquals("Database status must remain QUEUED", DownloadTaskStatus.QUEUED.name, entity.status)
        assertEquals("Database stage must be WAITING_FOR_WIFI", DownloadProcessingStage.WAITING_FOR_WIFI.name, stage1)
    }

    @Test
    fun `waiting download automatically resumes when network transitions from cellular to wifi`() = runBlocking {
        // Start on cellular with wifiOnly = true
        fakeNetwork.setStatus(NetworkStatus(isConnected = true, isWifi = false, isMetered = true))
        fakePrefs.setWifiOnly(true)

        val downloadStarted = CompletableDeferred<Unit>()
        val fakeEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                outputDirectory: File,
                onState: (DownloadState) -> Unit,
            ): DownloadExecutionResult {
                downloadStarted.complete(Unit)
                return DownloadExecutionResult.Success("ok")
            }
            override suspend fun cancel(processId: String) = true
        }

        repository = createTestRepository(fakeEngine)
        app.downloadRepository = repository

        val request = DownloadRequest(
            id = "resume-task-1",
            url = "https://example.com/resume1",
            title = "Resume Video",
            format = format,
        )
        repository.enqueue(request).getOrThrow()

        serviceController = Robolectric.buildService(DownloadService::class.java)
        serviceController.create()
        serviceController.startCommand(0, 1)

        var stage2 = dao.get(request.id)?.processingStage
        val deadline2 = System.currentTimeMillis() + 2000
        while (stage2 != DownloadProcessingStage.WAITING_FOR_WIFI.name && System.currentTimeMillis() < deadline2) {
            delay(50)
            stage2 = dao.get(request.id)?.processingStage
        }
        assertEquals(DownloadProcessingStage.WAITING_FOR_WIFI.name, stage2)

        // Now transition to Wi-Fi
        fakeNetwork.setStatus(NetworkStatus(isConnected = true, isWifi = true, isMetered = false))

        // Wait for download to start
        downloadStarted.await()
        assertTrue("Download should have started after Wi-Fi became available", downloadStarted.isCompleted)
    }

    @Test
    fun `active download pauses into WAITING_FOR_WIFI when switching from wifi to cellular`() = runBlocking {
        // Start on Wi-Fi
        fakeNetwork.setStatus(NetworkStatus(isConnected = true, isWifi = true, isMetered = false))
        fakePrefs.setWifiOnly(true)

        val downloadRunningSignal = CompletableDeferred<Unit>()
        val wasCancelled = AtomicBoolean(false)

        val fakeEngine = object : DownloadEngine {
            override suspend fun download(
                request: DownloadRequest,
                outputDirectory: File,
                onState: (DownloadState) -> Unit,
            ): DownloadExecutionResult {
                onState(
                    DownloadState.Downloading(
                        DownloadProgress(percentage = 25f, downloadedBytes = 250L, status = "Downloading…"),
                        DownloadTransferKind.VIDEO,
                    ),
                )
                downloadRunningSignal.complete(Unit)
                while (!wasCancelled.get()) {
                    delay(20)
                }
                return DownloadExecutionResult.Cancelled
            }

            override suspend fun cancel(processId: String): Boolean {
                wasCancelled.set(true)
                return true
            }
        }

        repository = createTestRepository(fakeEngine)
        app.downloadRepository = repository

        val request = DownloadRequest(
            id = "pause-task-1",
            url = "https://example.com/pause1",
            title = "Pause Video",
            format = format,
        )
        repository.enqueue(request).getOrThrow()

        serviceController = Robolectric.buildService(DownloadService::class.java)
        serviceController.create()
        serviceController.startCommand(0, 1)

        // Wait until download is actively running
        downloadRunningSignal.await()

        // Switch to cellular
        fakeNetwork.setStatus(NetworkStatus(isConnected = true, isWifi = false, isMetered = true))

        // Give pause logic time to execute
        delay(200)

        assertTrue("Active engine call must be cancelled when network switches to cellular", wasCancelled.get())

        // The task in database must be reset to QUEUED with stage WAITING_FOR_WIFI (not FAILED or CANCELLED)
        val pausedEntity = dao.get(request.id)!!
        assertEquals("Task must be paused back into QUEUED status", DownloadTaskStatus.QUEUED.name, pausedEntity.status)
        assertEquals("Task stage must be WAITING_FOR_WIFI", DownloadProcessingStage.WAITING_FOR_WIFI.name, pausedEntity.processingStage)
    }

    private fun createTestRepository(engine: DownloadEngine): DownloadRepository {
        val discovery = object : FormatDiscoveryEngine {
            override fun quickFormatCatalog(url: String) = MediaFormatCatalog(url, "Title", emptyList(), emptyList())
            override fun fastVideoPreset() = format
            override fun cachedFormatCatalog(url: String): MediaFormatCatalog? = null
            override suspend fun discoverFormats(url: String) = FormatDiscoveryResult.Failure("not used")
        }
        val exporter = object : StorageExporter {
            override suspend fun prepareDestination(request: DownloadRequest) =
                Result.success(ExportDestination(temporaryFolder.newFolder()))
            override suspend fun exportCompletedFile(
                request: DownloadRequest,
                destination: ExportDestination,
                commandOutput: String,
            ) = Result.success(output)
            override suspend fun cleanup(destination: ExportDestination) {}
            override suspend fun outputExists(output: DownloadOutput) = true
            override suspend fun deleteOutput(output: DownloadOutput) = true
        }
        return DownloadRepository(
            formatDiscoveryEngine = discovery,
            downloadEngine = engine,
            historyStore = RoomDownloadHistoryStore(dao),
            storageExporter = exporter,
        )
    }
}

class FakeNetworkConnectivityManager(
    initialStatus: NetworkStatus = NetworkStatus(isConnected = true, isWifi = true, isMetered = false),
) : NetworkConnectivityManager {
    private val _status = MutableStateFlow(initialStatus)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    fun setStatus(newStatus: NetworkStatus) {
        _status.value = newStatus
    }

    override fun startObserving() {}
    override fun stopObserving() {}
}

class FakeDownloadPreferenceStore(
    initialWifiOnly: Boolean = false,
    initialMaxConcurrent: Int = 2,
) : DownloadPreferenceStore {
    private val _defaultChoice = MutableStateFlow(DefaultDownloadChoice.BEST_VIDEO)
    override val defaultChoice = _defaultChoice.asStateFlow()
    override suspend fun setDefaultChoice(choice: DefaultDownloadChoice) { _defaultChoice.value = choice }

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    override val themeMode = _themeMode.asStateFlow()
    override suspend fun setThemeMode(mode: AppThemeMode) { _themeMode.value = mode }

    private val _wifiOnly = MutableStateFlow(initialWifiOnly)
    override val wifiOnly = _wifiOnly.asStateFlow()
    override suspend fun setWifiOnly(enabled: Boolean) { _wifiOnly.value = enabled }

    private val _maxConcurrentDownloads = MutableStateFlow(initialMaxConcurrent)
    override val maxConcurrentDownloads = _maxConcurrentDownloads.asStateFlow()
    override suspend fun setMaxConcurrentDownloads(limit: Int) { _maxConcurrentDownloads.value = limit }

    private val _vaultViewMode = MutableStateFlow("grid")
    override val vaultViewMode = _vaultViewMode.asStateFlow()
    override suspend fun setVaultViewMode(mode: String) { _vaultViewMode.value = mode }

    private val _allowThirdPartyGateways = MutableStateFlow(false)
    override val allowThirdPartyGateways = _allowThirdPartyGateways.asStateFlow()
    override suspend fun setAllowThirdPartyGateways(enabled: Boolean) { _allowThirdPartyGateways.value = enabled }
}
