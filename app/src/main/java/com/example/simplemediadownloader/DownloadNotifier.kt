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
    private var lastPercentage = -1
    private var lastStatus: String? = null

    @Synchronized
    fun showProgress(progress: DownloadProgress, force: Boolean = false) {
        if (!canPostNotifications()) return
        val percentage = progress.percentage.coerceIn(0f, 100f).toInt()
        if (!force && percentage == lastPercentage && progress.status == lastStatus) return

        lastPercentage = percentage
        lastStatus = progress.status
        val detail = buildString {
            append(String.format(Locale.US, "%.1f%%", progress.percentage))
            progress.etaSeconds?.let { append(" • ETA ${formatEta(it)}") }
        }
        notify(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(progress.status)
                .setContentText(detail)
                .setProgress(100, percentage, false)
                .setContentIntent(openAppIntent())
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
    }

    @Synchronized
    fun showCompleted(file: File) {
        lastPercentage = 100
        lastStatus = "Completed"
        showTerminal(
            title = "Download completed",
            detail = file.name,
            icon = android.R.drawable.stat_sys_download_done,
        )
    }

    @Synchronized
    fun showCancelled() {
        lastPercentage = -1
        lastStatus = null
        showTerminal("Download cancelled", "The download was stopped.")
    }

    @Synchronized
    fun showFailed(message: String) {
        lastPercentage = -1
        lastStatus = null
        showTerminal(
            title = "Download failed",
            detail = message.take(180),
            icon = android.R.drawable.stat_notify_error,
        )
    }

    private fun showTerminal(
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
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
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
    private fun notify(notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_ID = "media_downloads"
        private const val NOTIFICATION_ID = 4107

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
