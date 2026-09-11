package com.example.simplemediadownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class DownloadNotifier(private val context: Context) {
    private val lastStates = mutableMapOf<String, Pair<Int, String>>()
    private val lastProgressNotificationAt = mutableMapOf<String, Long>()

    @Synchronized
    fun showProgress(task: DownloadTask, force: Boolean = false) {
        if (!canPostNotifications()) return
        val progress = task.progress
        val percentage = progress.percentage?.coerceIn(0f, 100f)?.toInt() ?: -1
        val newState = percentage to progress.status
        if (!force && lastStates[task.id] == newState) return
        val now = SystemClock.elapsedRealtime()
        val lastUpdate = lastProgressNotificationAt[task.id]
        if (!force && lastUpdate != null && now - lastUpdate < PROGRESS_UPDATE_INTERVAL_MS) return

        lastStates[task.id] = newState
        lastProgressNotificationAt[task.id] = now
        val detail = buildString {
            progress.downloadedBytes?.let { downloaded ->
                append(formatBytes(downloaded))
                progress.totalBytes?.let { append(" of ${formatBytes(it)}") }
            }
            progress.speedBytesPerSecond?.takeIf { it > 0L }?.let {
                if (isNotEmpty()) append(" • ")
                append("${formatBytes(it)}/s")
            }
            progress.etaSeconds?.let {
                if (isNotEmpty()) append(" • ")
                append("ETA ${formatEta(it)}")
            }
            if (isEmpty()) append(progress.status)
        }
        notify(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("${task.title}: ${progress.status}")
                .setContentText(detail)
                .setProgress(100, percentage.coerceAtLeast(0), !progress.isDeterminate)
                .setContentIntent(openAppIntent(task.id))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.action_cancel),
                    DownloadService.cancelPendingIntent(context, task.id),
                )
                .build(),
            task.id,
        )
    }

    @Synchronized
    fun showCompleted(taskId: String, output: DownloadOutput) {
        lastStates.remove(taskId)
        lastProgressNotificationAt.remove(taskId)
        showTerminal(
            taskId = taskId,
            title = context.getString(R.string.notification_download_completed),
            detail = output.displayName,
            icon = android.R.drawable.stat_sys_download_done,
        )
    }

    @Synchronized
    fun showCancelled(taskId: String) {
        lastStates.remove(taskId)
        lastProgressNotificationAt.remove(taskId)
        showTerminal(
            taskId = taskId,
            title = context.getString(R.string.notification_download_cancelled),
            detail = context.getString(R.string.notification_download_stopped),
        )
    }

    @Synchronized
    fun showFailed(taskId: String, message: String) {
        lastStates.remove(taskId)
        lastProgressNotificationAt.remove(taskId)
        showTerminal(
            taskId = taskId,
            title = context.getString(R.string.notification_download_failed),
            detail = message.take(180),
            icon = android.R.drawable.stat_notify_error,
        )
    }

    private fun showTerminal(
        taskId: String,
        title: String,
        detail: String,
        icon: Int = android.R.drawable.stat_sys_warning,
    ) {
        if (!canPostNotifications()) return
        notify(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent(taskId))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
            taskId,
        )
    }

    private fun openAppIntent(taskId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            DownloadNotificationIds.forTask(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun dismiss(taskId: String) {
        lastStates.remove(taskId)
        lastProgressNotificationAt.remove(taskId)
        NotificationManagerCompat.from(context).cancel(DownloadNotificationIds.forTask(taskId))
    }

    @SuppressLint("MissingPermission")
    private fun notify(notification: android.app.Notification, taskId: String) {
        runCatching {
            NotificationManagerCompat.from(context).notify(
                DownloadNotificationIds.forTask(taskId),
                notification,
            )
        }
    }

    companion object {
        internal const val CHANNEL_ID = "media_downloads"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1_000L
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setSound(null, null)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        private fun formatEta(seconds: Long): String {
            val minutes = seconds / 60
            val remaining = seconds % 60
            return if (minutes > 0) "${minutes}m ${remaining}s" else "${remaining}s"
        }

        private fun formatBytes(bytes: Long): String {
            val mib = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mib >= 1024.0) {
                String.format(Locale.US, "%.1f GB", mib / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mib)
            }
        }
    }
}

internal object DownloadNotificationIds {
    const val FOREGROUND = 42
    private const val TASK_ID_OFFSET = 1_000
    private const val TASK_ID_MASK = 0x3fffffff

    fun forTask(taskId: String): Int = TASK_ID_OFFSET + (taskId.hashCode() and TASK_ID_MASK)
}
