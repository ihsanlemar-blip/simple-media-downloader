package com.example.simplemediadownloader

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe

class SimpleMediaDownloaderApp : Application() {
    val dispatchers = AppDispatchers()
    private val applicationScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    val okHttpClient: OkHttpClient = OkHttpClient.Builder().build()
    private val backendInitialization = BackendInitializationState()
    val backendState = backendInitialization.state
    lateinit var downloadDatabase: DownloadDatabase
        private set
    lateinit var downloadRepository: DownloadRepository
        private set
    lateinit var downloadPreferenceStore: DownloadPreferenceStore
        private set

    override fun onCreate() {
        super.onCreate()
        downloadDatabase = DownloadDatabase.create(this)
        downloadPreferenceStore = SharedPreferencesDownloadPreferenceStore(this, dispatchers)

        initNewPipe()

        val newPipeDiscoveryEngine = NewPipeFormatDiscoveryEngine(dispatchers)
        val formatDiscoveryEngine = CachingFormatDiscoveryEngine(
            delegate = newPipeDiscoveryEngine,
            scope = applicationScope,
        )
        downloadRepository = DownloadRepository(
            formatDiscoveryEngine = formatDiscoveryEngine,
            downloadEngine = OkHttpDownloadEngine(
                dispatchers = dispatchers,
                client = okHttpClient,
                discoveryEngine = formatDiscoveryEngine,
            ),
            historyStore = RoomDownloadHistoryStore(
                downloadDatabase.downloadTaskDao(),
                dispatchers,
            ),
            storageExporter = DownloadsStorageExporter(this, dispatchers),
        )
        DownloadNotifier.createChannel(this)
    }

    fun retryYoutubeDlInitialization() {
        initNewPipe()
    }

    private fun initNewPipe() {
        try {
            NewPipe.init(OkHttpNewPipeDownloader(okHttpClient))
            backendInitialization.youtubeDlReady()
            backendInitialization.ffmpegReady()
        } catch (error: Exception) {
            debugInitializationFailure("NewPipe", error)
            backendInitialization.youtubeDlFailed(
                "The download engine could not be prepared: ${error.localizedMessage}",
            )
        }
    }

    suspend fun ensureFfmpeg(): Result<Unit> {
        backendInitialization.ffmpegReady()
        return Result.success(Unit)
    }

    private fun debugInitializationFailure(component: String, error: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "$component initialization failed (${error.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "SimpleMediaDownloader"
    }
}
