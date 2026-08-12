package com.example.simplemediadownloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.republishDownloadNotifications()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            MaterialTheme {
                DownloaderScreen(
                    viewModel = viewModel,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            viewModel.acceptSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.republishDownloadNotifications()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloaderScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Simple Media Downloader") },
                actions = {
                    Box {
                        TextButton(onClick = { showMenu = true }) {
                            Text("⋮", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (state.isUpdatingBackend) {
                                            "Updating download engine…"
                                        } else {
                                            "Update download engine"
                                        },
                                    )
                                },
                                enabled = state.backend.youtubeDlReady &&
                                    !state.isUpdatingBackend &&
                                    state.activeTaskCount == 0 &&
                                    !state.isDiscoveringFormats,
                                onClick = {
                                    showMenu = false
                                    viewModel.updateYoutubeDl()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackendStatus(state.backend)

            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                enabled = !state.isDiscoveringFormats,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Media URL") },
                placeholder = { Text("https://…") },
                singleLine = false,
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::pasteFromClipboard,
                    enabled = !state.isDiscoveringFormats,
                ) {
                    Text("Paste")
                }
                OutlinedButton(
                    onClick = viewModel::clearUrl,
                    enabled = !state.isDiscoveringFormats,
                ) {
                    Text("Clear")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        onRequestNotificationPermission()
                        viewModel.fastDownload()
                    },
                    enabled = state.backend.ready && state.url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Fast download")
                }
                OutlinedButton(
                    onClick = viewModel::chooseFormat,
                    enabled = state.backend.ready &&
                        !state.isDiscoveringFormats &&
                        state.url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Choose quality")
                }
            }

            if (state.isDiscoveringFormats) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Checking available formats…", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Reading resolutions, audio qualities, and estimated sizes.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (state.tasks.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Downloads (${state.activeTaskCount} active)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.tasks.any { !it.isActive }) {
                        TextButton(onClick = viewModel::clearFinishedTasks) { Text("Clear finished") }
                    }
                }
                state.tasks.forEach { task ->
                    DownloadTaskCard(
                        task = task,
                        onCancel = { viewModel.cancel(task.id) },
                        onOpen = { result -> openFile(context, result.file) },
                        onShare = { result -> shareFile(context, result.file) },
                    )
                }
            }

            HorizontalDivider()
            Text(
                "Download only public content or media you are authorized to save. " +
                    "DRM, private-account, login, and paywall bypasses are not supported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
    }

    state.formatCatalog?.takeIf { state.showFormatPicker }?.let { catalog ->
        FormatPickerDialog(
            catalog = catalog,
            onDismiss = viewModel::dismissFormatPicker,
            onSelected = { format ->
                onRequestNotificationPermission()
                viewModel.download(format)
            },
        )
    }
}

@Composable
private fun BackendStatus(backend: BackendState) {
    val text = when {
        backend.initializing -> "Preparing download engine…"
        backend.ffmpegInitializing -> "Ready • preparing converter for an advanced format…"
        backend.ready && backend.ffmpegReady -> "Ready • fast and converted formats available"
        backend.ready -> "Ready • converter loads only when needed"
        else -> "Download engine unavailable • ${backend.error ?: "Initialization failed"}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (backend.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun FormatPickerDialog(
    catalog: MediaFormatCatalog,
    onDismiss: () -> Unit,
    onSelected: (AvailableFormat) -> Unit,
) {
    val initialMode = if (catalog.videoFormats.isNotEmpty()) {
        DownloadMode.VIDEO
    } else {
        DownloadMode.AUDIO_ORIGINAL
    }
    var mode by remember(catalog.sourceUrl) { mutableStateOf(initialMode) }
    val formats = when (mode) {
        DownloadMode.VIDEO -> catalog.videoFormats
        DownloadMode.AUDIO_ORIGINAL,
        DownloadMode.AUDIO_MP3 -> catalog.audioFormats.filter { it.mode == mode }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose format")
                Text(catalog.title, style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == DownloadMode.VIDEO,
                        onClick = { mode = DownloadMode.VIDEO },
                        enabled = catalog.videoFormats.isNotEmpty(),
                        label = { Text("Video (${catalog.videoFormats.size})") },
                    )
                    FilterChip(
                        selected = mode == DownloadMode.AUDIO_ORIGINAL,
                        onClick = { mode = DownloadMode.AUDIO_ORIGINAL },
                        enabled = catalog.audioFormats.any { it.mode == DownloadMode.AUDIO_ORIGINAL },
                        label = { Text("Original") },
                    )
                    FilterChip(
                        selected = mode == DownloadMode.AUDIO_MP3,
                        onClick = { mode = DownloadMode.AUDIO_MP3 },
                        enabled = catalog.audioFormats.any { it.mode == DownloadMode.AUDIO_MP3 },
                        label = { Text("MP3") },
                    )
                }
                Text(
                    "Highest quality is listed first. Tap one format to start downloading.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (catalog.detailsLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Common presets are ready. Exact source sizes are loading in the background.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(formats, key = AvailableFormat::key) { format ->
                        FormatRow(format = format, onClick = { onSelected(format) })
                    }
                }
                Text(
                    "Original audio and native video avoid conversion. MP3 and separate high-quality " +
                        "streams load the converter only when needed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun FormatRow(format: AvailableFormat, onClick: () -> Unit) {
    val title = when (format.mode) {
        DownloadMode.VIDEO -> buildString {
            if (format.height > 0) {
                if (format.isQuickPreset) append("Fast up to ")
                append("${format.height}p")
            } else {
                append("Fast best video")
            }
            if (format.fps > 0) append(" • ${format.fps} fps")
            if (format.width > 0) append(" • ${format.width}×${format.height}")
        }
        DownloadMode.AUDIO_ORIGINAL -> buildString {
            append("Original audio")
            if (format.bitrateKbps > 0) append(" • ${format.bitrateKbps} kbps")
        }
        DownloadMode.AUDIO_MP3 -> "${format.bitrateKbps} kbps MP3"
    }
    val details = buildList {
        add(format.extension.uppercase(Locale.US))
        format.codec.takeIf { it.isNotBlank() && it != "none" }?.let {
            add(it.substringBefore('.').uppercase(Locale.US))
        }
        format.formatNote.takeIf { it.isNotBlank() && !format.isQuickPreset }?.let(::add)
        add(formatSizeLabel(format))
    }.joinToString(" • ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(details, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun formatSizeLabel(format: AvailableFormat): String {
    if (format.isQuickPreset) return "Size loading…"
    val bytes = format.estimatedSizeBytes ?: return "Size unknown"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (value >= 100 || unit == 0) String.format(Locale.US, "%.0f", value)
    else String.format(Locale.US, "%.1f", value)
    return (if (format.sizeIsApproximate) "≈ " else "") + "$number ${units[unit]}"
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onOpen: (DownloadResult.Success) -> Unit,
    onShare: (DownloadResult.Success) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(task.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (task.isActive) {
                Text(task.progress.status, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(
                    progress = { task.progress.percentage / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(String.format(Locale.US, "%.1f%%", task.progress.percentage))
                    task.progress.etaSeconds?.let { Text("ETA ${formatEta(it)}") }
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            } else {
                when (val result = task.result) {
                    is DownloadResult.Success -> {
                        Text(result.file.absolutePath, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onOpen(result) }) { Text("Open") }
                            OutlinedButton(onClick = { onShare(result) }) { Text("Share") }
                        }
                    }
                    DownloadResult.Cancelled -> Text("Cancelled")
                    is DownloadResult.Failure -> Text("Failed: ${result.message}")
                    null -> Text("Finished")
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(progress: DownloadProgress, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(progress.status, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { progress.percentage / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(String.format(Locale.US, "%.1f%% downloaded", progress.percentage))
                progress.etaSeconds?.let { Text("ETA ${formatEta(it)}") }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun SuccessCard(file: File, onOpen: () -> Unit, onShare: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Completed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(file.absolutePath, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpen) { Text("Open") }
                OutlinedButton(onClick = onShare) { Text("Share") }
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail)
        }
    }
}

private fun formatEta(seconds: Long): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
}

private fun fileMimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "m4a" -> "audio/mp4"
    else -> "application/octet-stream"
}

private fun openFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, fileMimeType(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(fileMimeType(file))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share downloaded file"))
}
