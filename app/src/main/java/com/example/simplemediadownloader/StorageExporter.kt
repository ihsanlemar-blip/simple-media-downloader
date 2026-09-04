package com.example.simplemediadownloader

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

data class ExportDestination(val directory: File)

interface StorageExporter {
    suspend fun prepareDestination(request: DownloadRequest): Result<ExportDestination>

    suspend fun exportCompletedFile(
        request: DownloadRequest,
        destination: ExportDestination,
        commandOutput: String,
    ): Result<DownloadOutput>

    suspend fun cleanup(destination: ExportDestination)

    suspend fun outputExists(output: DownloadOutput): Boolean

    suspend fun deleteOutput(output: DownloadOutput): Boolean = false

    suspend fun cleanupAbandonedExports(): Int = 0
}

internal class DownloadsStorageExporter(
    context: Context,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val workspaceRoot: File = File(context.cacheDir, WORKING_FOLDER),
    private val mediaStoreWriter: MediaStoreWriter = ContentResolverMediaStoreWriter(
        context.contentResolver,
    ),
    private val availableBytes: () -> Long = {
        StatFs(context.cacheDir.absolutePath).availableBytes
    },
) : StorageExporter {
    override suspend fun prepareDestination(
        request: DownloadRequest,
    ): Result<ExportDestination> = withContext(dispatchers.io) {
        resultOf {
            check(workspaceRoot.exists() || workspaceRoot.mkdirs()) {
                "Could not create the temporary download workspace."
            }
            val directory = taskDirectory(request.id)
            deleteWorkspace(directory, requireSuccess = true)

            request.format.estimatedSizeBytes
                ?.takeIf { it > 0L }
                ?.let { estimate ->
                    val required = StorageCapacityPolicy.requiredBytes(
                        estimatedMediaBytes = estimate,
                        requiresProcessing = request.format.requiresFfmpeg,
                    )
                    val available = availableBytes()
                    check(available >= required) {
                        "Not enough available storage. This download needs about " +
                            "${StorageCapacityPolicy.formatBytes(required)} free."
                    }
                }

            check(directory.mkdirs()) { "Could not create the temporary download workspace." }
            ExportDestination(directory)
        }
    }

    override suspend fun exportCompletedFile(
        request: DownloadRequest,
        destination: ExportDestination,
        commandOutput: String,
    ): Result<DownloadOutput> = withContext(dispatchers.io) {
        resultOf {
            val source = WorkingFileLocator.find(destination.directory, commandOutput)
                ?: throw FileNotFoundException(
                    "yt-dlp completed without a final file in the temporary workspace.",
                )
            check(source.length() > 0L) { "The processed media file is empty." }
            val displayName = MediaExportPolicy.sanitizeDisplayName(
                source.name,
                request.format.extension,
            )
            val mimeType = MediaExportPolicy.mimeType(displayName, request.format.mode)
            mediaStoreWriter.write(
                source = source,
                requestedDisplayName = displayName,
                mimeType = mimeType,
                taskId = request.id,
            )
        }
    }

    override suspend fun cleanup(destination: ExportDestination) =
        withContext(NonCancellable + dispatchers.io) {
            deleteWorkspace(destination.directory, requireSuccess = false)
        }

    override suspend fun outputExists(output: DownloadOutput): Boolean =
        withContext(dispatchers.io) { mediaStoreWriter.exists(output.contentUri) }

    override suspend fun deleteOutput(output: DownloadOutput): Boolean =
        withContext(dispatchers.io) { mediaStoreWriter.delete(output.contentUri) }

    override suspend fun cleanupAbandonedExports(): Int =
        withContext(dispatchers.io) { mediaStoreWriter.cleanupAbandonedPendingRows() }

    private fun taskDirectory(taskId: String): File {
        val safeId = taskId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
            .ifBlank { taskId.hashCode().toUInt().toString(16) }
        val directory = File(workspaceRoot, "task-$safeId")
        check(isInsideWorkspace(directory)) { "Invalid temporary workspace path." }
        return directory
    }

    private fun deleteWorkspace(directory: File, requireSuccess: Boolean) {
        check(isInsideWorkspace(directory)) { "Refusing to clean a path outside the workspace." }
        if (directory.exists()) {
            val deleted = directory.deleteRecursively()
            if (requireSuccess) check(deleted) { "Could not clean the temporary workspace." }
        }
    }

    private fun isInsideWorkspace(file: File): Boolean {
        val rootPath = workspaceRoot.canonicalFile.toPath()
        return file.canonicalFile.toPath().startsWith(rootPath) && file.canonicalFile != workspaceRoot.canonicalFile
    }

    companion object {
        const val OUTPUT_FOLDER = "MediaDownloader"
        private const val WORKING_FOLDER = "media-download-work"
    }
}

internal interface MediaStoreWriter {
    suspend fun write(
        source: File,
        requestedDisplayName: String,
        mimeType: String,
        taskId: String,
    ): DownloadOutput

    suspend fun exists(contentUri: String): Boolean
    suspend fun delete(contentUri: String): Boolean = false
    suspend fun cleanupAbandonedPendingRows(): Int = 0
}

internal class ContentResolverMediaStoreWriter(
    private val resolver: ContentResolver,
) : MediaStoreWriter {
    private val nameMutex = Mutex()
    private val reservedNames = mutableMapOf<String, MutableSet<String>>()

    override suspend fun write(
        source: File,
        requestedDisplayName: String,
        mimeType: String,
        taskId: String,
    ): DownloadOutput {
        val target = MediaExportPolicy.targetFor(mimeType)
        val reservationKey = "${target.collection}|${target.relativePath.lowercase(Locale.US)}"
        val (displayName, contentUri) = nameMutex.withLock {
            val unavailable = existingNames(target) + reservedNames[reservationKey].orEmpty()
            val availableName = MediaExportPolicy.collisionSafeName(
                requestedDisplayName,
                unavailable,
            )
            val values = MediaExportPolicy.pendingValues(
                displayName = availableName,
                mimeType = mimeType,
                relativePath = target.relativePath,
                taskId = taskId,
            )
            val uri = resolver.insert(target.collection, values)
                ?: error("MediaStore did not create an output row.")
            reservedNames.getOrPut(reservationKey, ::mutableSetOf).add(availableName)
            availableName to uri
        }

        try {
            resolver.openOutputStream(contentUri, "w")?.use { output ->
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            } ?: error("MediaStore could not open the output stream.")

            val published = resolver.update(
                contentUri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.TITLE, displayName.substringBeforeLast('.'))
                },
                null,
                null,
            )
            check(published == 1) { "MediaStore did not publish the completed media." }
            return MediaExportPolicy.output(
                contentUri = contentUri,
                displayName = displayName,
                mimeType = mimeType,
                fileSizeBytes = source.length(),
            )
        } catch (error: Throwable) {
            runCatching { resolver.delete(contentUri, null, null) }
            throw error
        } finally {
            withContext(NonCancellable) {
                nameMutex.withLock {
                    reservedNames[reservationKey]?.let { names ->
                        names.remove(displayName)
                        if (names.isEmpty()) reservedNames.remove(reservationKey)
                    }
                }
            }
        }
    }

    override suspend fun exists(contentUri: String): Boolean {
        val uri = contentUri.toUri()
            .takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: return false
        return runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    override suspend fun delete(contentUri: String): Boolean {
        val uri = contentUri.toUri()
            .takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: return false
        return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    override suspend fun cleanupAbandonedPendingRows(): Int {
        val collections = listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        )
        return collections.sumOf { collection ->
            val rowIds = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                        "${MediaStore.MediaColumns.TITLE} LIKE ?",
                    arrayOf("1", "${MediaExportPolicy.PENDING_TITLE_PREFIX}%"),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getLong(idColumn))
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
            rowIds.count { rowId ->
                runCatching {
                    resolver.delete(ContentUris.withAppendedId(collection, rowId), null, null) > 0
                }.getOrDefault(false)
            }
        }
    }

    private fun existingNames(target: MediaStoreTarget): Set<String> {
        val names = mutableSetOf<String>()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        resolver.query(
            target.collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            selection,
            arrayOf(target.relativePath, "${target.relativePath}/"),
            null,
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) names += cursor.getString(nameColumn)
        }
        return names
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
    }
}

internal data class MediaStoreTarget(
    val collection: Uri,
    val relativePath: String,
)

internal object MediaExportPolicy {
    const val PENDING_TITLE_PREFIX = "smd-pending:"
    private val unsafeCharacters = Regex("[\\p{Cc}\\p{Cf}\\\\/:*?\"<>|]")
    private val whitespace = Regex("\\s+")
    private val validExtension = Regex("[A-Za-z0-9]{1,10}")
    private val reservedBaseNames = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { number ->
            add("COM$number")
            add("LPT$number")
        }
    }

    fun sanitizeDisplayName(originalName: String, fallbackExtension: String): String {
        val sourceName = originalName.substringAfterLast('/').substringAfterLast('\\')
        val sourceExtension = sourceName.substringAfterLast('.', "")
            .takeIf { validExtension.matches(it) }
        val fallback = fallbackExtension.takeIf { validExtension.matches(it) }
        val extension = (sourceExtension ?: fallback ?: "bin").lowercase(Locale.US)
        val rawBase = if (sourceExtension != null) sourceName.dropLast(sourceExtension.length + 1) else sourceName
        var base = rawBase
            .replace(unsafeCharacters, "_")
            .replace(whitespace, " ")
            .trim(' ', '.')
            .take(MAX_DISPLAY_NAME_LENGTH - extension.length - 1)
            .trimEnd(' ', '.')
            .ifBlank { "downloaded-media" }
        if (base.uppercase(Locale.US) in reservedBaseNames) base += "_"
        return "$base.$extension"
    }

    fun mimeType(displayName: String, mode: DownloadMode): String =
        when (displayName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> if (mode == DownloadMode.VIDEO) "video/webm" else "audio/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> if (mode == DownloadMode.VIDEO) "video/3gpp" else "audio/3gpp"
            else -> "application/octet-stream"
        }

    fun collisionSafeName(requestedName: String, unavailableNames: Collection<String>): String {
        val unavailable = unavailableNames.mapTo(mutableSetOf()) { it.lowercase(Locale.US) }
        if (requestedName.lowercase(Locale.US) !in unavailable) return requestedName
        val extension = requestedName.substringAfterLast('.', "")
        val base = requestedName.dropLast(extension.length + 1)
        var suffix = 1
        while (true) {
            val suffixText = " ($suffix)"
            val allowedBaseLength =
                (MAX_DISPLAY_NAME_LENGTH - extension.length - suffixText.length - 1).coerceAtLeast(1)
            val collisionBase = base.take(allowedBaseLength).trimEnd(' ', '.').ifBlank {
                "downloaded-media"
            }
            val candidate = "$collisionBase$suffixText.$extension"
            if (candidate.lowercase(Locale.US) !in unavailable) return candidate
            suffix++
        }
    }

    fun targetFor(mimeType: String): MediaStoreTarget = when {
        mimeType.startsWith("video/") -> MediaStoreTarget(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_MOVIES}/${DownloadsStorageExporter.OUTPUT_FOLDER}",
        )
        mimeType.startsWith("audio/") -> MediaStoreTarget(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_MUSIC}/${DownloadsStorageExporter.OUTPUT_FOLDER}",
        )
        else -> MediaStoreTarget(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_DOWNLOADS}/${DownloadsStorageExporter.OUTPUT_FOLDER}",
        )
    }

    fun pendingValues(
        displayName: String,
        mimeType: String,
        relativePath: String,
        taskId: String,
    ): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        put(MediaStore.MediaColumns.TITLE, "$PENDING_TITLE_PREFIX$taskId")
    }

    fun output(
        contentUri: Uri,
        displayName: String,
        mimeType: String,
        fileSizeBytes: Long,
    ): DownloadOutput = DownloadOutput(
        contentUri = contentUri.toString(),
        mimeType = mimeType,
        fileSizeBytes = fileSizeBytes,
        displayName = displayName,
    )

    private const val MAX_DISPLAY_NAME_LENGTH = 180
}

internal object StorageCapacityPolicy {
    private const val HEADROOM_BYTES = 32L * 1024 * 1024

    fun requiredBytes(estimatedMediaBytes: Long, requiresProcessing: Boolean): Long {
        val multiplier = if (requiresProcessing) 3L else 2L
        val mediaBytes = if (estimatedMediaBytes > Long.MAX_VALUE / multiplier) {
            Long.MAX_VALUE
        } else {
            estimatedMediaBytes * multiplier
        }
        return if (mediaBytes > Long.MAX_VALUE - HEADROOM_BYTES) {
            Long.MAX_VALUE
        } else {
            mediaBytes + HEADROOM_BYTES
        }
    }

    fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            String.format(Locale.US, "%.1f GiB", gib)
        } else {
            String.format(Locale.US, "%.0f MiB", bytes.toDouble() / (1024.0 * 1024.0))
        }
    }
}

internal object WorkingFileLocator {
    fun find(directory: File, commandOutput: String): File? {
        val markedPath = commandOutput.lineSequence()
            .lastOrNull { it.startsWith(YtDlpDownloadEngine.OUTPUT_MARKER) }
            ?.removePrefix(YtDlpDownloadEngine.OUTPUT_MARKER)
            ?.trim()
            ?.trim('"')
        val marked = markedPath?.let(::File)?.takeIf { isCompletedFile(it, directory) }
        if (marked != null) return marked
        return directory.listFiles()
            .orEmpty()
            .filter { isCompletedFile(it, directory) }
            .maxByOrNull(File::lastModified)
    }

    private fun isCompletedFile(file: File, directory: File): Boolean = runCatching {
        val canonical = file.canonicalFile
        canonical.isFile &&
            canonical.length() > 0L &&
            canonical.toPath().startsWith(directory.canonicalFile.toPath()) &&
            TEMPORARY_SUFFIXES.none { canonical.name.endsWith(it, ignoreCase = true) }
    }.getOrDefault(false)

    private val TEMPORARY_SUFFIXES = listOf(".part", ".ytdl", ".tmp", ".temp")
}

private inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
