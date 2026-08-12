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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

class DownloadNotifier(private val context: Context) {
    private val lastStates = mutableMapOf<String, Pair<Int, String>>()

    @Synchronized
    fun showProgress(task: DownloadTask, force: Boolean = false) {
        if (!canPostNotifications()) return
        val progress = task.progress
        val percentage = progress.percentage.coerceIn(0f, 100f).toInt()
        val newState = percentage to progress.status
        if (!force && lastStates[task.id] == newState) return

        lastStates[task.id] = newState
        val detail = buildString {
            append(String.format(Locale.US, "%.1f%%", progress.percentage))
            progress.etaSeconds?.let { append(" • ETA ${formatEta(it)}") }
        }
        notify(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("${task.title}: ${progress.status}")
                .setContentText(detail)
                .setProgress(100, percentage, false)
                .setContentIntent(openAppIntent(task.id))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .build(),
            task.id,
        )
    }

    @Synchronized
    fun showCompleted(taskId: String, file: File) {
        lastStates.remove(taskId)
        showTerminal(
            taskId = taskId,
            title = "Download completed",
            detail = file.name,
            icon = android.R.drawable.stat_sys_download_done,
        )
    }

    @Synchronized
    fun showCancelled(taskId: String) {
        lastStates.remove(taskId)
        showTerminal(taskId, "Download cancelled", "The download was stopped.")
    }

    @Synchronized
    fun showFailed(taskId: String, message: String) {
        lastStates.remove(taskId)
        showTerminal(
            taskId = taskId,
            title = "Download failed",
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
            notificationId(taskId),
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

    @SuppressLint("MissingPermission")
    private fun notify(notification: android.app.Notification, taskId: String) {
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(taskId), notification)
        }
    }

    private fun notificationId(taskId: String): Int = taskId.hashCode() and 0x7fffffff

    companion object {
        private const val CHANNEL_ID = "media_downloads"
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Live media download progress"
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
    }
}
