package com.example.simplemediadownloader

object DownloadOptions {
    fun formatSelector(format: AvailableFormat): String = when (format.mode) {
        DownloadMode.VIDEO -> format.companionAudioFormatId?.let {
            "${format.formatId}+$it/${format.formatId}"
        } ?: format.formatId

        DownloadMode.AUDIO_MP3 -> format.formatId
    }

    fun audioBitrate(format: AvailableFormat): String =
        "${format.bitrateKbps.takeIf { it > 0 }?.coerceIn(32, 320) ?: 192}K"

    fun variantSuffix(format: AvailableFormat): String {
        val safeId = format.formatId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        return when (format.mode) {
            DownloadMode.VIDEO -> "video-${format.height.takeIf { it > 0 } ?: "unknown"}p-$safeId"
            DownloadMode.AUDIO_MP3 -> "audio-${audioBitrate(format).lowercase()}-$safeId"
        }
    }
}
