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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            SimpleMediaDownloaderTheme(themeMode = state.themeMode) {
                MainAppScaffold(
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
private fun MainAppScaffold(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pendingCancelId by remember { mutableStateOf<String?>(null) }
    var showCancelAllConfirmation by remember { mutableStateOf(false) }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Column {
                            Text(
                                text = when (state.currentTab) {
                                    NavigationTab.GATEWAY -> "Simple Media Downloader"
                                    NavigationTab.TRANSFERS -> "Active Transfers"
                                    NavigationTab.VAULT -> "Media Vault"
                                    NavigationTab.SETTINGS -> "Preferences"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = when (state.currentTab) {
                                    NavigationTab.GATEWAY -> "Multi-Platform Stream Engine"
                                    NavigationTab.TRANSFERS -> "${state.activeTaskCount} active download(s)"
                                    NavigationTab.VAULT -> "${state.filteredVaultTasks.size} saved item(s)"
                                    NavigationTab.SETTINGS -> "Theme, Concurrency & Cache"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            FloatingNavBar(
                currentTab = state.currentTab,
                onTabSelected = viewModel::setTab,
                activeTransfersCount = state.activeTaskCount,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Crossfade(
                targetState = state.currentTab,
                animationSpec = tween(durationMillis = 200),
                label = "tab_crossfade",
            ) { tab ->
                when (tab) {
                    NavigationTab.GATEWAY -> {
                        GatewayScreen(
                            state = state,
                            onUrlChange = viewModel::setUrl,
                            onClearUrl = viewModel::clearUrl,
                            onPasteUrl = viewModel::pasteFromClipboard,
                            onFastDownload = {
                                onRequestNotificationPermission()
                                viewModel.fastDownload()
                            },
                            onExploreFormats = viewModel::chooseFormat,
                            onRetryBackend = viewModel::retryYoutubeDlInitialization,
                            onNavigateToVault = { viewModel.setTab(NavigationTab.VAULT) },
                            onPreviewMedia = viewModel::setPreviewMedia,
                        )
                    }

                    NavigationTab.TRANSFERS -> {
                        TransfersScreen(
                            state = state,
                            onCancelTask = { pendingCancelId = it },
                            onCancelAll = { showCancelAllConfirmation = true },
                            onNavigateToGateway = { viewModel.setTab(NavigationTab.GATEWAY) },
                        )
                    }

                    NavigationTab.VAULT -> {
                        VaultScreen(
                            state = state,
                            onSearchQueryChange = viewModel::setHistorySearchQuery,
                            onSelectPlatform = viewModel::setHistoryPlatformFilter,
                            onToggleViewMode = viewModel::setVaultViewMode,
                            onSelectMediaType = viewModel::setVaultMediaType,
                            onToggleTaskSelection = viewModel::toggleVaultTaskSelection,
                            onSelectAllTasks = viewModel::selectAllVaultTasks,
                            onClearSelection = viewModel::clearVaultSelection,
                            onDeleteSelectedTasks = viewModel::deleteSelectedVaultTasks,
                            onPreviewMedia = viewModel::setPreviewMedia,
                            onShareMedia = { result -> shareFile(context, result.output) },
                            onDeleteTask = { pendingDeleteId = it },
                            onRemoveTask = { pendingRemoveId = it },
                            onRetryTask = viewModel::retryTask,
                            onCopyDetails = { details -> copyDetails(context, details) },
                        )
                    }

                    NavigationTab.SETTINGS -> {
                        SettingsScreen(
                            state = state,
                            onThemeSelect = viewModel::setThemeMode,
                            onDefaultChoiceSelect = viewModel::setDefaultDownloadChoice,
                            onWifiOnlyToggle = viewModel::setWifiOnly,
                            onMaxConcurrentSelect = viewModel::setMaxConcurrentDownloads,
                            onClearCache = viewModel::clearAppCache,
                            onRetryEngine = viewModel::retryYoutubeDlInitialization,
                        )
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Format Picker
    state.formatCatalog?.takeIf { state.showFormatPicker }?.let { catalog ->
        FormatPickerBottomSheet(
            catalog = catalog,
            selectionEnabled = state.backend.ready,
            onDismiss = viewModel::dismissFormatPicker,
            onSelected = { format ->
                onRequestNotificationPermission()
                viewModel.download(format)
            },
        )
    }

    // In-App Media Player Preview Bottom Sheet
    state.previewMedia?.let { output ->
        MediaPreviewBottomSheet(
            output = output,
            onDismiss = { viewModel.setPreviewMedia(null) },
            onShare = { shareFile(context, output) },
            onOpenExternal = { openFile(context, output) },
        )
    }

    // Confirmation Dialogs
    pendingCancelId?.let { taskId ->
        ConfirmationDialog(
            title = "Cancel transfer?",
            message = "The active transfer will be stopped and temporary files will be cleaned up.",
            confirmLabel = "Cancel transfer",
            onDismiss = { pendingCancelId = null },
            onConfirm = {
                pendingCancelId = null
                viewModel.cancel(taskId)
            },
        )
    }

    if (showCancelAllConfirmation) {
        ConfirmationDialog(
            title = "Cancel all transfers?",
            message = "Every active and queued transfer will be immediately stopped.",
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
            message = "The history entry will be removed. The saved media file will remain in storage.",
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
            title = "Delete media file?",
            message = "The media file and its library entry will be permanently deleted from device storage.",
            confirmLabel = "Delete",
            onDismiss = { pendingDeleteId = null },
            onConfirm = {
                pendingDeleteId = null
                viewModel.deleteMediaAndHistory(taskId)
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
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        },
    )
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

internal fun buildProgressDescription(task: DownloadTask): String = buildString {
    append(task.progress.status)
    task.progress.downloadedBytes?.let { append(", ${formatByteCount(it)} downloaded") }
    task.progress.totalBytes?.let { append(" of ${formatByteCount(it)}") }
    task.progress.speedBytesPerSecond?.takeIf { it > 0L }?.let {
        append(", ${formatByteCount(it)} per second")
    }
    task.progress.etaSeconds?.let {
        val min = it / 60
        val sec = it % 60
        append(", ${if (min > 0) "${min}m ${sec}s" else "${sec}s"} remaining")
    }
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

private fun copyDetails(context: android.content.Context, details: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Download error details", details))
    Toast.makeText(context, "Error details copied to clipboard", Toast.LENGTH_SHORT).show()
}
