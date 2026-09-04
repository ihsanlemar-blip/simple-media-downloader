package com.example.simplemediadownloader

import java.util.Locale

data class ParsedDownloadUpdate(
    val state: DownloadState,
    val progress: DownloadProgress,
)

/** The single parser for structured and legacy yt-dlp progress output. */
class YtDlpProgressParser(
    private val format: AvailableFormat,
) {
    fun parse(
        line: String,
        libraryProgress: Float,
        libraryEtaSeconds: Long,
        previous: DownloadProgress = DownloadProgress(),
    ): ParsedDownloadUpdate {
        processingState(line, previous)?.let { return it }
        val transfer = parseStructured(line) ?: parseLegacy(line, libraryProgress, libraryEtaSeconds)
        if (transfer != null) {
            val kind = transfer.kind ?: defaultTransferKind()
            val status = if (kind == DownloadTransferKind.AUDIO) {
                "Downloading audio…"
            } else {
                "Downloading video…"
            }
            val progress = DownloadProgress(
                percentage = transfer.percentage,
                downloadedBytes = transfer.downloadedBytes,
                totalBytes = transfer.totalBytes,
                speedBytesPerSecond = transfer.speedBytesPerSecond,
                etaSeconds = transfer.etaSeconds,
                status = status,
            )
            return ParsedDownloadUpdate(DownloadState.Downloading(progress, kind), progress)
        }
        val progress = previous.copy(
            percentage = null,
            speedBytesPerSecond = null,
            etaSeconds = null,
            status = "Preparing download…",
        )
        return ParsedDownloadUpdate(DownloadState.Preparing(progress), progress)
    }

    private fun processingState(line: String, previous: DownloadProgress): ParsedDownloadUpdate? {
        val statusAndState: Pair<String, (DownloadProgress) -> DownloadState> = when {
            line.contains("ExtractAudio", ignoreCase = true) ->
                "Converting to MP3…" to DownloadState::Converting
            format.requiresDownscale && (
                line.contains("VideoConvertor", ignoreCase = true) ||
                    line.contains("Converting video", ignoreCase = true)
                ) -> "Converting video…" to DownloadState::Converting
            line.contains("Merger", ignoreCase = true) ||
                line.contains("Merging", ignoreCase = true) ->
                "Merging video and audio…" to DownloadState::Merging
            line.contains("Fixup", ignoreCase = true) ||
                line.contains("Post-process", ignoreCase = true) ||
                line.contains("MoveFiles", ignoreCase = true) ->
                "Saving media…" to DownloadState::Saving
            else -> return null
        }
        val progress = previous.copy(
            percentage = null,
            speedBytesPerSecond = null,
            etaSeconds = null,
            status = statusAndState.first,
        )
        return ParsedDownloadUpdate(statusAndState.second(progress), progress)
    }

    private fun parseStructured(line: String): TransferProgress? {
        val payload = line.substringAfter(PROGRESS_MARKER, missingDelimiterValue = "")
        if (payload.isEmpty()) return null
        val fields = payload.split('|')
        if (fields.size < 7) return null
        val downloaded = fields[0].numericBytes()
        val exactTotal = fields[1].numericBytes()
        val estimatedTotal = fields[2].numericBytes()
        val total = exactTotal ?: estimatedTotal
        val percentage = trustworthyPercentage(downloaded, total)
        val vcodec = fields[5]
        val acodec = fields[6]
        val kind = when {
            vcodec.isUsableCodec() -> DownloadTransferKind.VIDEO
            acodec.isUsableCodec() -> DownloadTransferKind.AUDIO
            else -> null
        }
        return TransferProgress(
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSecond = fields[3].numericBytes(),
            etaSeconds = fields[4].numericBytes(),
            percentage = percentage,
            kind = kind,
        )
    }

    private fun parseLegacy(
        line: String,
        libraryProgress: Float,
        libraryEtaSeconds: Long,
    ): TransferProgress? {
        if (!line.contains("[download]", ignoreCase = true)) return null
        val percent = LEGACY_PERCENT.find(line)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val totalMatch = LEGACY_TOTAL.find(line)
        val total = totalMatch?.let {
            parseHumanBytes(it.groupValues[1], it.groupValues[2])
        }
        val trustedPercent = percent?.takeIf { total != null && it.isFinite() }
        val downloaded = if (trustedPercent != null && total != null) {
            (total * (trustedPercent / 100.0)).toLong().coerceIn(0L, total)
        } else {
            null
        }
        val speedMatch = LEGACY_SPEED.find(line)
        val speed = speedMatch?.let {
            parseHumanBytes(it.groupValues[1], it.groupValues[2])
        }
        return TransferProgress(
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSecond = speed,
            etaSeconds = parseEta(line) ?: libraryEtaSeconds.takeIf { it >= 0L },
            percentage = trustedPercent?.coerceIn(0f, 100f),
            kind = defaultTransferKind(),
        ).takeIf {
            percent != null || total != null || speed != null ||
                (libraryProgress.isFinite() && libraryProgress > 0f)
        }
    }

    private fun parseEta(line: String): Long? {
        val value = LEGACY_ETA.find(line)?.groupValues?.getOrNull(1) ?: return null
        val parts = value.split(':').mapNotNull(String::toLongOrNull)
        if (parts.size !in 2..3) return null
        return if (parts.size == 3) {
            parts[0] * 3600L + parts[1] * 60L + parts[2]
        } else {
            parts[0] * 60L + parts[1]
        }
    }

    private fun defaultTransferKind(): DownloadTransferKind =
        if (format.mode == DownloadMode.VIDEO) DownloadTransferKind.VIDEO
        else DownloadTransferKind.AUDIO

    private fun trustworthyPercentage(downloaded: Long?, total: Long?): Float? {
        if (downloaded == null || total == null || total <= 0L) return null
        return (downloaded.toDouble() * 100.0 / total.toDouble()).toFloat().coerceIn(0f, 100f)
    }

    private fun String.numericBytes(): Long? = trim()
        .takeUnless { it.isEmpty() || it.equals("NA", true) || it.equals("None", true) }
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.toLong()

    private fun String.isUsableCodec(): Boolean =
        isNotBlank() && !equals("none", true) && !equals("NA", true)

    private fun parseHumanBytes(number: String, unit: String): Long? {
        val value = number.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val multiplier = when (unit.lowercase(Locale.US)) {
            "b" -> 1.0
            "kb", "kib" -> 1024.0
            "mb", "mib" -> 1024.0 * 1024.0
            "gb", "gib" -> 1024.0 * 1024.0 * 1024.0
            "tb", "tib" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> return null
        }
        return (value * multiplier).takeIf { it <= Long.MAX_VALUE.toDouble() }?.toLong()
    }

    private data class TransferProgress(
        val downloadedBytes: Long?,
        val totalBytes: Long?,
        val speedBytesPerSecond: Long?,
        val etaSeconds: Long?,
        val percentage: Float?,
        val kind: DownloadTransferKind?,
    )

    companion object {
        const val PROGRESS_MARKER = "__SMD_PROGRESS__"
        const val PROGRESS_TEMPLATE =
            "download:$PROGRESS_MARKER%(progress.downloaded_bytes)s|" +
                "%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|" +
                "%(progress.speed)s|%(progress.eta)s|%(info.vcodec)s|%(info.acodec)s"
        private val LEGACY_PERCENT = Regex("""\[download]\s+(\d+(?:\.\d+)?)%""")
        private val LEGACY_TOTAL = Regex(
            """\bof\s+~?\s*(\d+(?:\.\d+)?)\s*(B|Ki?B|Mi?B|Gi?B|Ti?B)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val LEGACY_SPEED = Regex(
            """\bat\s+(\d+(?:\.\d+)?)\s*(B|Ki?B|Mi?B|Gi?B|Ti?B)/s\b""",
            RegexOption.IGNORE_CASE,
        )
        private val LEGACY_ETA = Regex("""\bETA\s+(\d{1,3}:\d{2}(?::\d{2})?)""")
    }
}
