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
    val cookieJar = ScopedCookieJar()
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(SafeDns())
        .addInterceptor(SecurityInterceptor(allowCleartextHttp = true))
        .build()
    private val backendInitialization = BackendInitializationState()
    val backendState = backendInitialization.state
    lateinit var downloadDatabase: DownloadDatabase
        private set
    lateinit var downloadRepository: DownloadRepository
        internal set
    lateinit var downloadPreferenceStore: DownloadPreferenceStore
        internal set
    lateinit var networkConnectivityManager: NetworkConnectivityManager
    lateinit var storageExporter: StorageExporter
        internal set
    lateinit var formatDiscoveryEngine: FormatDiscoveryEngine
        internal set

    override fun onCreate() {
        super.onCreate()
        downloadDatabase = DownloadDatabase.create(this)
        downloadPreferenceStore = SharedPreferencesDownloadPreferenceStore(this, dispatchers)
        networkConnectivityManager = DefaultNetworkConnectivityManager(this)

        initNewPipe()

        val newPipeDiscoveryEngine = NewPipeFormatDiscoveryEngine(dispatchers, downloadPreferenceStore)
        val cachingDiscoveryEngine = CachingFormatDiscoveryEngine(
            delegate = newPipeDiscoveryEngine,
            scope = applicationScope,
        )
        formatDiscoveryEngine = cachingDiscoveryEngine
        val exporter = DownloadsStorageExporter(this, dispatchers)
        storageExporter = exporter
        downloadRepository = DownloadRepository(
            formatDiscoveryEngine = cachingDiscoveryEngine,
            downloadEngine = OkHttpDownloadEngine(
                dispatchers = dispatchers,
                client = okHttpClient,
                discoveryEngine = cachingDiscoveryEngine,
            ),
            historyStore = RoomDownloadHistoryStore(
                downloadDatabase.downloadTaskDao(),
                dispatchers,
            ),
            storageExporter = exporter,
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


    private fun debugInitializationFailure(component: String, error: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "$component initialization failed (${error.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "SimpleMediaDownloader"
    }
}
