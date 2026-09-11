package com.example.simplemediadownloader

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    private lateinit var app: SimpleMediaDownloaderApp
    private lateinit var repository: DownloadRepository
    private lateinit var notifier: DownloadNotifier
    private lateinit var preferenceStore: DownloadPreferenceStore
    private lateinit var networkConnectivityManager: NetworkConnectivityManager
    private lateinit var serviceScope: CoroutineScope
    private val scheduler = DownloadQueueScheduler()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val recoveryMutex = Mutex()
    private val schedulingMutex = Mutex()
    private val cleanupStarted = AtomicBoolean(false)
    private var recoveryComplete = false
    @Volatile
    private var latestStartId = 0
    private var lastForegroundUpdateAt = 0L

    override fun onCreate() {
        super.onCreate()
        app = application as SimpleMediaDownloaderApp
        repository = app.downloadRepository
        notifier = DownloadNotifier(this)
        preferenceStore = app.downloadPreferenceStore
        networkConnectivityManager = app.networkConnectivityManager
        networkConnectivityManager.startObserving()
        scheduler.setConcurrencyLimit(preferenceStore.maxConcurrentDownloads.value)
        serviceScope = CoroutineScope(SupervisorJob() + app.dispatchers.io)
        DownloadNotifier.createChannel(this)

        serviceScope.launch {
            combine(
                preferenceStore.wifiOnly,
                networkConnectivityManager.status,
            ) { wifiOnly, status ->
                wifiOnly to status
            }.collect {
                schedulePersistedQueue()
            }
        }

        serviceScope.launch {
            preferenceStore.maxConcurrentDownloads.collect { limit ->
                scheduler.setConcurrencyLimit(limit)
                schedulePersistedQueue()
            }
        }

        // Promotion happens before database or backend initialization work.
        ServiceCompat.startForeground(
            this,
            DownloadNotificationIds.FOREGROUND,
            buildForegroundNotification(getString(R.string.notification_preparing_queue)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.hasExtra(EXTRA_CONCURRENCY) == true) {
            intent.getIntExtra(EXTRA_CONCURRENCY, DownloadQueueScheduler.DEFAULT_CONCURRENCY)
                .coerceIn(1, DownloadQueueScheduler.MAX_CONCURRENCY)
                .let(scheduler::setConcurrencyLimit)
        }

        serviceScope.launch {
            when (intent?.action) {
                ACTION_CANCEL -> intent.getStringExtra(EXTRA_TASK_ID)?.let { cancelTask(it) }
                ACTION_CANCEL_ALL -> cancelAll()
                ACTION_REFRESH -> refreshNotifications()
                ACTION_ENQUEUE, null -> schedulePersistedQueue()
                else -> schedulePersistedQueue()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service owns the work; removing the activity task must not cancel it.
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        debugLog("Foreground service timed out for type $fgsType")
        beginProcessCleanup("Android stopped the data-transfer service after its time limit.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        networkConnectivityManager.stopObserving()
        val remaining = activeJobs.keys.toList()
        if (remaining.isNotEmpty()) {
            beginProcessCleanup("The download service stopped while work was active.")
        }
        activeJobs.values.forEach(Job::cancel)
        remaining.forEach(notifier::dismiss)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private suspend fun schedulePersistedQueue() = schedulingMutex.withLock {
        ensureRecovery()
        val isAllowed = networkConnectivityManager.isNetworkAllowed(preferenceStore.wifiOnly.value)

        if (!isAllowed) {
            val activeTaskIds = activeJobs.keys.toList()
            for (taskId in activeTaskIds) {
                pauseActiveTask(taskId)
            }
            val queued = repository.queuedRequests()
            queued.forEach {
                repository.markWaitingForWifi(it.id)
                scheduler.enqueue(it.id)
            }
            updateForegroundNotification(force = true)
            return@withLock
        }

        repository.queuedRequests().forEach { scheduler.enqueue(it.id) }
        scheduler.takeReady().forEach { taskId ->
            val request = repository.request(taskId)
            if (request == null) {
                scheduler.complete(taskId)
            } else {
                repository.prepareTask(taskId, "Preparing download…")
                launchTask(request)
            }
        }
        updateForegroundNotification(force = true)
        stopIfIdle()
    }

    private suspend fun pauseActiveTask(taskId: String) {
        val job = activeJobs.remove(taskId)
        scheduler.complete(taskId)
        scheduler.enqueue(taskId)
        repository.pauseForWifi(taskId)
        job?.cancel()
    }

    private suspend fun ensureRecovery() = recoveryMutex.withLock {
        if (recoveryComplete) return
        repository.cleanupAbandonedExports()
        repository.restoreRecoverableTasks().forEach { scheduler.enqueue(it.id) }
        recoveryComplete = true
    }

    private fun launchTask(request: DownloadRequest) {
        val taskId = request.id
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val backend = app.backendState
                    .filter { !it.initializing }
                    .first()
                if (!backend.ready) {
                    val message = backend.error ?: "The download engine could not start."
                    repository.failTask(
                        taskId = taskId,
                        message = message,
                        category = DownloadFailureCategory.UNKNOWN_FAILURE,
                        technicalDetail = backend.error,
                    )
                    notifier.showFailed(taskId, message)
                    return@launch
                }



                repository.download(request) { state -> notifyState(request, state) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "The download service could not complete this task."
                runCatching {
                    repository.failTask(
                        taskId = taskId,
                        message = message,
                        category = DownloadFailureCategory.UNKNOWN_FAILURE,
                        technicalDetail = error.stackTraceToString().take(2_000),
                    )
                }
                notifier.showFailed(taskId, message)
                debugLog("Download task failed in the service (${error.javaClass.simpleName})")
            }
        }
        activeJobs[taskId] = job
        job.invokeOnCompletion { error ->
            activeJobs.remove(taskId, job)
            scheduler.complete(taskId)
            if (error != null && error !is CancellationException) {
                debugLog("Download task ended unexpectedly (${error.javaClass.simpleName})")
            }
            serviceScope.launch { schedulePersistedQueue() }
        }
        job.start()
    }

    private suspend fun cancelTask(taskId: String) {
        schedulingMutex.withLock {
            scheduler.cancelWaiting(taskId)
            val activeJob = activeJobs[taskId]
            val cancelled = repository.cancel(taskId)
            if (cancelled) {
                activeJob?.cancel()
                notifier.showCancelled(taskId)
            }
            updateForegroundNotification(force = true)
        }
        schedulePersistedQueue()
    }

    private suspend fun cancelAll() {
        ensureRecovery()
        schedulingMutex.withLock {
            val taskIds = (scheduler.waitingTaskIds() + scheduler.activeTaskIds()).distinct()
            taskIds.forEach { taskId ->
                scheduler.cancelWaiting(taskId)
                if (repository.cancel(taskId)) {
                    activeJobs[taskId]?.cancel()
                    notifier.showCancelled(taskId)
                }
            }
            updateForegroundNotification(force = true)
        }
        schedulePersistedQueue()
    }

    private suspend fun refreshNotifications() {
        ensureRecovery()
        repository.activeTasks.first().forEach { task ->
            notifier.showProgress(task, force = true)
        }
        schedulePersistedQueue()
    }

    private fun notifyState(request: DownloadRequest, state: DownloadState) {
        when (state) {
            is DownloadState.Completed -> notifier.showCompleted(request.id, state.output)
            DownloadState.Cancelled -> notifier.showCancelled(request.id)
            is DownloadState.Failed -> notifier.showFailed(request.id, state.message)
            else -> notifier.showProgress(
                DownloadTask(
                    id = request.id,
                    url = request.url,
                    title = request.title,
                    format = request.format,
                    state = state,
                ),
            )
        }
        updateForegroundNotification()
    }

    private suspend fun stopIfIdle() {
        if (activeJobs.isNotEmpty() || !scheduler.isIdle()) return
        if (repository.queuedRequests().isNotEmpty()) return
        if (stopSelfResult(latestStartId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    @Synchronized
    private fun updateForegroundNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastForegroundUpdateAt < FOREGROUND_UPDATE_INTERVAL_MS) return
        lastForegroundUpdateAt = now
        val active = scheduler.activeTaskIds().size
        val queued = scheduler.waitingTaskIds().size
        val isAllowed = networkConnectivityManager.isNetworkAllowed(preferenceStore.wifiOnly.value)
        val detail = when {
            !isAllowed && (active > 0 || queued > 0) -> getString(R.string.notification_waiting_wifi)
            active > 0 && queued > 0 -> getString(R.string.notification_active_and_queued, active, queued)
            active > 0 -> getString(R.string.notification_active_downloads, active)
            queued > 0 -> getString(R.string.notification_queued_downloads, queued)
            else -> getString(R.string.notification_finishing_queue)
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                DownloadNotificationIds.FOREGROUND,
                buildForegroundNotification(detail),
            )
        }
    }

    private fun buildForegroundNotification(detail: String): Notification =
        NotificationCompat.Builder(this, DownloadNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_channel_name))
            .setContentText(detail)
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_cancel_all),
                cancelAllPendingIntent(this),
            )
            .build()

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        DownloadNotificationIds.FOREGROUND,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun beginProcessCleanup(reason: String) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val taskIds = activeJobs.keys.toList()
        if (taskIds.isEmpty()) return
        val jobs = activeJobs.values.toList()
        CoroutineScope(SupervisorJob() + app.dispatchers.io).launch {
            taskIds.forEach { taskId -> repository.cancel(taskId) }
            withTimeoutOrNull(PROCESS_CLEANUP_TIMEOUT_MS) { jobs.joinAll() }
            taskIds.forEach { taskId -> repository.markInterrupted(taskId, reason) }
            debugLog("Active tasks were marked interrupted during service cleanup")
        }
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val ACTION_ENQUEUE =
            "com.example.simplemediadownloader.action.ENQUEUE"
        private const val ACTION_CANCEL =
            "com.example.simplemediadownloader.action.CANCEL"
        private const val ACTION_CANCEL_ALL =
            "com.example.simplemediadownloader.action.CANCEL_ALL"
        private const val ACTION_REFRESH =
            "com.example.simplemediadownloader.action.REFRESH"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_CONCURRENCY = "concurrency"
        private const val FOREGROUND_UPDATE_INTERVAL_MS = 1_000L
        private const val PROCESS_CLEANUP_TIMEOUT_MS = 2_000L

        fun enqueue(
            context: Context,
            taskId: String,
            concurrency: Int = DownloadQueueScheduler.DEFAULT_CONCURRENCY,
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_CONCURRENCY, concurrency)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, taskId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_TASK_ID, taskId),
            )
        }

        fun cancelAll(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            )
        }

        fun refresh(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_REFRESH),
            )
        }

        internal fun cancelPendingIntent(context: Context, taskId: String): PendingIntent =
            PendingIntent.getService(
                context,
                DownloadNotificationIds.forTask(taskId),
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_TASK_ID, taskId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun cancelAllPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                DownloadNotificationIds.FOREGROUND,
                Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
