package com.example.simplemediadownloader

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["status", "created_at"]),
        Index(value = ["completed_at"]),
        Index(value = ["canonical_url"]),
    ],
)
data class DownloadTaskEntity(
    @PrimaryKey @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "canonical_url") val canonicalUrl: String = "",
    @ColumnInfo(name = "display_title") val displayTitle: String,
    val platform: String,
    @ColumnInfo(name = "format_key") val formatKey: String,
    @ColumnInfo(name = "format_id") val formatId: String,
    @ColumnInfo(name = "companion_audio_format_id") val companionAudioFormatId: String?,
    @ColumnInfo(name = "download_mode") val downloadMode: String,
    @ColumnInfo(name = "file_extension") val fileExtension: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    @ColumnInfo(name = "bitrate_kbps") val bitrateKbps: Int,
    val codec: String,
    @ColumnInfo(name = "format_note") val formatNote: String,
    @ColumnInfo(name = "estimated_size_bytes") val estimatedSizeBytes: Long?,
    @ColumnInfo(name = "size_is_approximate") val sizeIsApproximate: Boolean,
    @ColumnInfo(name = "source_height") val sourceHeight: Int,
    @ColumnInfo(name = "requires_downscale") val requiresDownscale: Boolean,
    @ColumnInfo(name = "is_quick_preset") val isQuickPreset: Boolean,
    val status: String,
    @ColumnInfo(name = "processing_stage") val processingStage: String,
    @ColumnInfo(name = "progress_percent") val progressPercent: Float?,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long? = null,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long? = null,
    @ColumnInfo(name = "speed_bytes_per_second") val speedBytesPerSecond: Long? = null,
    @ColumnInfo(name = "eta_seconds") val etaSeconds: Long?,
    @ColumnInfo(name = "output_content_uri") val outputContentUri: String?,
    @ColumnInfo(name = "output_mime_type") val outputMimeType: String?,
    @ColumnInfo(name = "output_file_size_bytes") val outputFileSizeBytes: Long?,
    @ColumnInfo(name = "output_display_name") val outputDisplayName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "failure_category") val failureCategory: String?,
    @ColumnInfo(name = "failure_message") val failureMessage: String?,
    @ColumnInfo(name = "technical_failure_detail") val technicalFailureDetail: String?,
    @ColumnInfo(name = "http_headers") val httpHeaders: String? = null,
)

object CredentialRedactor {
    private val sensitiveHeaderKeys = setOf(
        "cookie",
        "authorization",
        "proxy-authorization",
        "x-auth-token",
        "session-token",
    )

    private val signatureQueryParamRegex = Regex(
        "(?i)\\b(sig|signature|token|access_token|auth|expire|expires|session|key|secret|sessionId)=([^&#\\s\"'>)]+)",
    )

    private val authHeaderInTextRegex = Regex(
        "(?i)(Authorization|Cookie|Set-Cookie):\\s*([^\\r\\n]+)",
    )

    private val bearerTokenInTextRegex = Regex(
        "(?i)\\bBearer\\s+([A-Za-z0-9._~+/-]+=*)",
    )

    fun sanitizeHeaders(headers: Map<String, String>?, isTerminal: Boolean): Map<String, String>? {
        if (headers.isNullOrEmpty()) return null
        if (!isTerminal) return headers
        val cleaned = headers.filterKeys { key ->
            val lower = key.lowercase(java.util.Locale.US)
            lower !in sensitiveHeaderKeys && !lower.contains("token") && !lower.contains("auth") && !lower.contains("cookie")
        }
        return cleaned.ifEmpty { null }
    }

    fun redactDiagnostics(detail: String?): String? {
        if (detail.isNullOrBlank()) return detail
        var redacted = detail
        redacted = signatureQueryParamRegex.replace(redacted) { matchResult ->
            val paramName = matchResult.groupValues[1]
            "$paramName=[REDACTED]"
        }
        redacted = authHeaderInTextRegex.replace(redacted) { matchResult ->
            val headerName = matchResult.groupValues[1]
            "$headerName: [REDACTED]"
        }
        redacted = bearerTokenInTextRegex.replace(redacted) {
            "Bearer [REDACTED]"
        }
        return redacted
    }
}

fun DownloadRecord.toEntity(): DownloadTaskEntity {
    val isTerminal = status in setOf(
        DownloadTaskStatus.COMPLETED,
        DownloadTaskStatus.FAILED,
        DownloadTaskStatus.CANCELLED,
        DownloadTaskStatus.INTERRUPTED,
    )
    val sanitizedHeaders = CredentialRedactor.sanitizeHeaders(format.httpHeaders, isTerminal)
    val sanitizedTechnicalDetail = CredentialRedactor.redactDiagnostics(technicalFailureDetail)
    val sanitizedFailureMessage = CredentialRedactor.redactDiagnostics(failureMessage)

    return DownloadTaskEntity(
        taskId = taskId,
        sourceUrl = sourceUrl,
        canonicalUrl = NormalizedMediaUrl.from(sourceUrl),
        displayTitle = displayTitle,
        platform = platform,
        formatKey = format.key,
        formatId = format.formatId,
        companionAudioFormatId = format.companionAudioFormatId,
        downloadMode = format.mode.name,
        fileExtension = format.extension,
        width = format.width,
        height = format.height,
        fps = format.fps,
        bitrateKbps = format.bitrateKbps,
        codec = format.codec,
        formatNote = format.formatNote,
        estimatedSizeBytes = format.estimatedSizeBytes,
        sizeIsApproximate = format.sizeIsApproximate,
        sourceHeight = format.sourceHeight,
        requiresDownscale = format.requiresDownscale,
        isQuickPreset = format.isQuickPreset,
        status = status.name,
        processingStage = stage.name,
        progressPercent = progressPercent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        etaSeconds = etaSeconds,
        outputContentUri = output?.contentUri,
        outputMimeType = output?.mimeType,
        outputFileSizeBytes = output?.fileSizeBytes,
        outputDisplayName = output?.displayName,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
        failureCategory = failureCategory?.name,
        failureMessage = sanitizedFailureMessage,
        technicalFailureDetail = sanitizedTechnicalDetail,
        httpHeaders = serializeHeaders(sanitizedHeaders),
    )
}

fun DownloadTaskEntity.toRecord(): DownloadRecord = DownloadRecord(
    taskId = taskId,
    sourceUrl = sourceUrl,
    displayTitle = displayTitle,
    platform = platform,
    format = AvailableFormat(
        key = formatKey,
        mode = enumValueOrDefault(downloadMode, DownloadMode.VIDEO),
        formatId = formatId,
        companionAudioFormatId = companionAudioFormatId,
        extension = fileExtension,
        width = width,
        height = height,
        fps = fps,
        bitrateKbps = bitrateKbps,
        codec = codec,
        formatNote = formatNote,
        estimatedSizeBytes = estimatedSizeBytes,
        sizeIsApproximate = sizeIsApproximate,
        sourceHeight = sourceHeight,
        requiresDownscale = requiresDownscale,
        isQuickPreset = isQuickPreset,
        httpHeaders = deserializeHeaders(httpHeaders),
    ),
    status = enumValueOrDefault(status, DownloadTaskStatus.FAILED),
    stage = enumValueOrDefault(processingStage, DownloadProcessingStage.FAILED),
    progressPercent = progressPercent,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    etaSeconds = etaSeconds,
    output = outputContentUri?.let { uri ->
        DownloadOutput(
            contentUri = uri,
            mimeType = outputMimeType ?: "application/octet-stream",
            fileSizeBytes = outputFileSizeBytes ?: 0L,
            displayName = outputDisplayName ?: "Downloaded media",
        )
    },
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
    failureCategory = failureCategory?.let {
        enumValueOrDefault(it, DownloadFailureCategory.UNKNOWN_FAILURE)
    },
    failureMessage = failureMessage,
    technicalFailureDetail = technicalFailureDetail,
)

private fun serializeHeaders(headers: Map<String, String>?): String? {
    if (headers.isNullOrEmpty()) return null
    val json = org.json.JSONObject()
    headers.forEach { (k, v) -> json.put(k, v) }
    return json.toString()
}

private fun deserializeHeaders(raw: String?): Map<String, String>? {
    if (raw.isNullOrBlank()) return null
    return try {
        val json = org.json.JSONObject(raw)
        val map = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.getString(key)
        }
        map
    } catch (_: Exception) {
        null
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
