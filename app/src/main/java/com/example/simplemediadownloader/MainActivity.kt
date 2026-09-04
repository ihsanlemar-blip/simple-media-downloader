package com.example.simplemediadownloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        setContent {
            SimpleMediaDownloaderTheme {
                DownloaderScreen(
                    viewModel = viewModel,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                )
            }
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
    var showDefaultChoiceDialog by remember { mutableStateOf(false) }
    var pendingCancelId by remember { mutableStateOf<String?>(null) }
    var showCancelAllConfirmation by remember { mutableStateOf(false) }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showClearFinishedConfirmation by remember { mutableStateOf(false) }
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
                        TextButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.semantics { contentDescription = "More options" },
                        ) {
                            Text("⋮", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Default: ${state.defaultDownloadChoice.label}") },
                                onClick = {
                                    showMenu = false
                                    showDefaultChoiceDialog = true
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
            CompactBackendStatus(
                backend = state.backend,
                onRetry = viewModel::retryYoutubeDlInitialization,
            )
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::setUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Media URL") },
                placeholder = { Text("https://…") },
                singleLine = false,
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = viewModel::pasteFromClipboard,
                ) {
                    Text("Paste")
                }
                OutlinedButton(
                    onClick = viewModel::clearUrl,
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
                    enabled = state.url.isNotBlank() &&
                        (state.backend.ready ||
                            state.defaultDownloadChoice == DefaultDownloadChoice.ALWAYS_ASK),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(state.defaultDownloadChoice.actionLabel)
                }
                OutlinedButton(
                    onClick = viewModel::chooseFormat,
                    enabled = state.url.isNotBlank(),
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
                    Row {
                        if (state.activeTaskCount > 0) {
                            TextButton(onClick = { showCancelAllConfirmation = true }) {
                                Text("Cancel all")
                            }
                        }
                        if (state.tasks.any { !it.isActive }) {
                            TextButton(onClick = { showClearFinishedConfirmation = true }) {
                                Text("Clear finished")
                            }
                        }
                    }
                }
                state.tasks.forEach { task ->
                    DownloadTaskCard(
                        task = task,
                        onCancel = { pendingCancelId = task.id },
                        onRetry = { viewModel.retryTask(task.id) },
                        onOpen = { result -> openFile(context, result.output) },
                        onShare = { result -> shareFile(context, result.output) },
                        onRemove = { pendingRemoveId = task.id },
                        onDelete = { pendingDeleteId = task.id },
                        onCopyDetails = { details -> copyDetails(context, details) },
                    )
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No downloads yet", fontWeight = FontWeight.Bold)
                        Text(
                            "Paste a public media link above to start your first download.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            selectionEnabled = state.backend.ready,
            onDismiss = viewModel::dismissFormatPicker,
            onSelected = { format ->
                onRequestNotificationPermission()
                viewModel.download(format)
            },
        )
    }

    if (showDefaultChoiceDialog) {
        DefaultDownloadChoiceDialog(
            selected = state.defaultDownloadChoice,
            onDismiss = { showDefaultChoiceDialog = false },
            onSelected = { choice ->
                showDefaultChoiceDialog = false
                viewModel.setDefaultDownloadChoice(choice)
            },
        )
    }

    pendingCancelId?.let { taskId ->
        ConfirmationDialog(
            title = "Cancel download?",
            message = "The current transfer will stop and temporary files will be cleaned up.",
            confirmLabel = "Cancel download",
            onDismiss = { pendingCancelId = null },
            onConfirm = {
                pendingCancelId = null
                viewModel.cancel(taskId)
            },
        )
    }
    if (showCancelAllConfirmation) {
        ConfirmationDialog(
            title = "Cancel all downloads?",
            message = "Every queued and active download will be stopped.",
            confirmLabel = "Cancel all",
            onDismiss = { showCancelAllConfirmation = false },
            onConfirm = {
                showCancelAllConfirmation = false
                viewModel.cancelAll()
            },
        )
    }
    pendingRemoveId?.let { taskId ->
        ConfirmationDialog(
            title = "Remove from history?",
            message = "The history entry will be removed. Any saved media will remain on the device.",
            confirmLabel = "Remove",
            onDismiss = { pendingRemoveId = null },
            onConfirm = {
                pendingRemoveId = null
                viewModel.removeHistoryEntry(taskId)
            },
        )
    }
    pendingDeleteId?.let { taskId ->
        ConfirmationDialog(
            title = "Delete saved media?",
            message = "The public media file and its history entry will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { pendingDeleteId = null },
            onConfirm = {
                pendingDeleteId = null
                viewModel.deleteMediaAndHistory(taskId)
            },
        )
    }
    if (showClearFinishedConfirmation) {
        ConfirmationDialog(
            title = "Clear finished history?",
            message = "Completed, cancelled, and failed entries will be removed. Saved media will remain.",
            confirmLabel = "Clear history",
            onDismiss = { showClearFinishedConfirmation = false },
            onConfirm = {
                showClearFinishedConfirmation = false
                viewModel.clearFinishedTasks()
            },
        )
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

@Composable
private fun CompactBackendStatus(
    backend: BackendState,
    onRetry: () -> Unit,
) {
    when {
        backend.ready -> Unit
        backend.initializing -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text(
                "Preparing download engine…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = backend.error ?: "Download engine initialization failed",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun DefaultDownloadChoiceDialog(
    selected: DefaultDownloadChoice,
    onDismiss: () -> Unit,
    onSelected: (DefaultDownloadChoice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default download") },
        text = {
            Column {
                DefaultDownloadChoice.entries.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(choice) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == choice,
                            onClick = { onSelected(choice) },
                        )
                        Text(choice.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FormatPickerDialog(
    catalog: MediaFormatCatalog,
    selectionEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelected: (AvailableFormat) -> Unit,
) {
    val initialMode = if (catalog.videoFormats.isNotEmpty()) {
        DownloadMode.VIDEO
    } else {
        DownloadMode.AUDIO_ORIGINAL
    }
    var mode by remember(catalog.sourceUrl) { mutableStateOf(initialMode) }
    var advancedMode by rememberSaveable(catalog.sourceUrl) { mutableStateOf(false) }
    val formats = when (mode) {
        DownloadMode.VIDEO -> catalog.videoFormats
        DownloadMode.AUDIO_ORIGINAL,
        DownloadMode.AUDIO_MP3 -> catalog.audioFormats.filter { it.mode == mode }
    }
    val displayedFormats = if (advancedMode) formats else commonShareFormats(formats, mode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Choose format")
                Text(
                    text = catalog.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !advancedMode,
                        onClick = { advancedMode = false },
                        label = { Text("Simple") },
                    )
                    FilterChip(
                        selected = advancedMode,
                        onClick = { advancedMode = true },
                        label = { Text("Advanced (${formats.size})") },
                    )
                }
                if (catalog.detailsLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!selectionEnabled) {
                    Text(
                        "Preparing the download engine before formats can be selected.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedFormats, key = AvailableFormat::key) { format ->
                        FormatRow(
                            format = format,
                            enabled = selectionEnabled,
                            onClick = { onSelected(format) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun FormatRow(
    format: AvailableFormat,
    enabled: Boolean,
    onClick: () -> Unit,
) {
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
        DownloadMode.AUDIO_MP3 -> if (format.bitrateKbps > 0) "${format.bitrateKbps} kbps MP3" else "MP3 audio"
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
            .clickable(enabled = enabled, onClick = onClick),
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
internal fun DownloadTaskCard(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (DownloadResult.Success) -> Unit,
    onShare: (DownloadResult.Success) -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onCopyDetails: (String) -> Unit,
) {
    var technicalDetailsVisible by rememberSaveable(task.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Download task for ${task.title}" },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                PlatformBadge(task.platform)
            }
            Text(task.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (task.isActive) {
                TaskProgress(task)
                OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            } else {
                when (val state = task.state) {
                    is DownloadState.Completed -> {
                        val result = DownloadResult.Success(state.output)
                        Text("Completed", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${state.output.displayName} • ${formatByteCount(state.output.fileSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpen(result) }) { Text("Open") }
                            OutlinedButton(onClick = { onShare(result) }) { Text("Share") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDelete) { Text("Delete media") }
                            TextButton(onClick = onRemove) { Text("Remove history") }
                        }
                    }
                    is DownloadState.Failed -> {
                        Text(
                            state.category.label,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(state.message)
                        val technical = state.technicalDetail
                            ?: "No additional technical details were recorded."
                        TextButton(
                            onClick = { technicalDetailsVisible = !technicalDetailsVisible },
                        ) {
                            Text(if (technicalDetailsVisible) "Hide details" else "Technical details")
                        }
                        if (technicalDetailsVisible) {
                            Text(
                                technical,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { onCopyDetails(technical) }) {
                                Text("Copy details")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRetry) { Text("Retry") }
                            TextButton(onClick = onRemove) { Text("Remove history") }
                        }
                    }
                    DownloadState.Cancelled -> {
                        Text("Cancelled")
                        TextButton(onClick = onRemove) { Text("Remove history") }
                    }
                    else -> Text("Finished")
                }
            }
        }
    }
}

@Composable
private fun TaskProgress(task: DownloadTask) {
    val progress = task.progress
    val announcement = buildProgressDescription(task)
    Column(
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = announcement
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(progress.status, fontWeight = FontWeight.SemiBold)
        if (progress.isDeterminate) {
            val fraction = (progress.percentage!! / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    },
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    },
            )
        }
        val transferText = buildList {
            progress.downloadedBytes?.let { downloaded ->
                val total = progress.totalBytes
                add(
                    if (total != null && total > 0L) {
                        "${formatByteCount(downloaded)} of ${formatByteCount(total)}"
                    } else {
                        "${formatByteCount(downloaded)} downloaded"
                    },
                )
            }
            progress.speedBytesPerSecond?.takeIf { it > 0L }?.let {
                add("${formatByteCount(it)}/s")
            }
            progress.etaSeconds?.let { add("ETA ${formatEta(it)}") }
            if (progress.isDeterminate) {
                add(String.format(Locale.US, "%.1f%%", progress.percentage))
            }
        }.joinToString(" • ")
        if (transferText.isNotBlank()) {
            Text(transferText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlatformBadge(platform: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            platform,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

internal fun buildProgressDescription(task: DownloadTask): String = buildString {
    append(task.progress.status)
    task.progress.downloadedBytes?.let { append(", ${formatByteCount(it)} downloaded") }
    task.progress.totalBytes?.let { append(" of ${formatByteCount(it)}") }
    task.progress.speedBytesPerSecond?.takeIf { it > 0L }?.let {
        append(", ${formatByteCount(it)} per second")
    }
    task.progress.etaSeconds?.let { append(", ${formatEta(it)} remaining") }
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

private fun openFile(context: android.content.Context, output: DownloadOutput) {
    val uri = output.contentUri.toUri()
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, output.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply {
            clipData = ClipData.newUri(context.contentResolver, output.displayName, uri)
        }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file type.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: android.content.Context, output: DownloadOutput) {
    val uri = output.contentUri.toUri()
    val intent = Intent(Intent.ACTION_SEND)
        .setType(output.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply {
            clipData = ClipData.newUri(context.contentResolver, output.displayName, uri)
        }
    context.startActivity(
        Intent.createChooser(intent, "Share downloaded file")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

internal fun formatByteCount(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val format = if (unit == 0 || value >= 100.0) "%.0f" else "%.1f"
    return String.format(Locale.US, "$format %s", value, units[unit])
}

private fun copyDetails(context: android.content.Context, details: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Download error details", details))
    Toast.makeText(context, "Details copied", Toast.LENGTH_SHORT).show()
}
