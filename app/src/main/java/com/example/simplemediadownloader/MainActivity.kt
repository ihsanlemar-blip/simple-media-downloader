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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDefaultChoiceDialog by remember { mutableStateOf(false) }
    var pendingCancelId by remember { mutableStateOf<String?>(null) }
    var showCancelAllConfirmation by remember { mutableStateOf(false) }
    var pendingRemoveId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showClearFinishedConfirmation by remember { mutableStateOf(false) }

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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                text = "Simple Media Downloader",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Flagship Media Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // Theme Switcher Button
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showThemeDialog = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Palette,
                            contentDescription = "Change app theme",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Overflow Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                text = {
                                    Column {
                                        Text("Default Action", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            state.defaultDownloadChoice.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showDefaultChoiceDialog = true
                                },
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Palette,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                text = {
                                    Column {
                                        Text("Appearance", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            state.themeMode.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showThemeDialog = true
                                },
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
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            // Engine status banner if initializing or error
            item(key = "backend_status") {
                BackendStatusAlert(
                    backend = state.backend,
                    onRetry = viewModel::retryYoutubeDlInitialization,
                )
            }

            // Hero Input Bar with Clipboard Gateway
            item(key = "hero_input") {
                HeroInputBar(
                    url = state.url,
                    onUrlChange = viewModel::setUrl,
                    onClearUrl = viewModel::clearUrl,
                    onPasteUrl = viewModel::pasteFromClipboard,
                    onFastDownload = {
                        onRequestNotificationPermission()
                        viewModel.fastDownload()
                    },
                    onExploreFormats = viewModel::chooseFormat,
                    fastDownloadLabel = state.defaultDownloadChoice.actionLabel,
                    canDownload = state.url.isNotBlank() &&
                        (state.backend.ready || state.defaultDownloadChoice == DefaultDownloadChoice.ALWAYS_ASK),
                    isDiscovering = state.isDiscoveringFormats,
                    backendReady = state.backend.ready,
                )
            }

            // Shimmer Format Discovery Skeleton Card
            if (state.isDiscoveringFormats) {
                item(key = "discovery_skeleton") {
                    FormatDiscoverySkeletonCard()
                }
            }

            // Active Downloads Section
            if (state.activeTasks.isNotEmpty()) {
                item(key = "active_downloads_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Active Transfers (${state.activeTasks.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(onClick = { showCancelAllConfirmation = true }) {
                            Text("Cancel all", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                items(
                    count = state.activeTasks.size,
                    key = { index -> state.activeTasks[index].id },
                ) { index ->
                    val task = state.activeTasks[index]
                    ActiveDownloadCard(
                        task = task,
                        onCancel = { pendingCancelId = task.id },
                    )
                }
            }

            // Download History Section with Search and Platform Filtering
            item(key = "download_history_section") {
                DownloadHistorySection(
                    tasks = state.filteredHistoryTasks,
                    searchQuery = state.historySearchQuery,
                    onSearchQueryChange = viewModel::setHistorySearchQuery,
                    selectedPlatform = state.historyPlatformFilter,
                    onSelectPlatform = viewModel::setHistoryPlatformFilter,
                    onOpen = { result -> openFile(context, result.output) },
                    onShare = { result -> shareFile(context, result.output) },
                    onRetry = viewModel::retryTask,
                    onRemove = { id -> pendingRemoveId = id },
                    onDelete = { id -> pendingDeleteId = id },
                    onCopyDetails = { details -> copyDetails(context, details) },
                    onClearFinished = { showClearFinishedConfirmation = true },
                )
            }

            // Welcome / Supported Platforms footer card
            if (state.tasks.isEmpty()) {
                item(key = "welcome_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Multi-Platform Downloader",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Copy any public video link from TikTok, Instagram Reels, Facebook, YouTube, X, or Reddit. Original titles and media tags are preserved automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf("TikTok", "Instagram", "Facebook", "YouTube", "X", "Reddit").forEach { platform ->
                                    PlatformBadge(platform, size = 6.dp)
                                }
                            }
                        }
                    }
                }
            }

            // Legal & Disclaimer note
            item(key = "disclaimer") {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    text = "Download only authorized public media. DRM, private-account, login, and paywall bypasses are not supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
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

    // Theme Switcher Dialog
    if (showThemeDialog) {
        AppThemeDialog(
            currentTheme = state.themeMode,
            onDismiss = { showThemeDialog = false },
            onThemeSelect = { mode ->
                showThemeDialog = false
                viewModel.setThemeMode(mode)
            },
        )
    }

    // Default Download Choice Dialog
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

    // Confirmation Dialogs
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
            message = "Completed, cancelled, and failed entries will be removed. Saved media will remain intact.",
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
private fun BackendStatusAlert(
    backend: BackendState,
    onRetry: () -> Unit,
) {
    when {
        backend.ready -> Unit
        backend.initializing -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Initializing media extraction engine…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        else -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = backend.error ?: "Download engine initialization failed",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Retry engine init",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatDiscoverySkeletonCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(16.dp),
                    )
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp),
                    )
                }
            }
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                shape = RoundedCornerShape(10.dp),
            )
        }
    }
}

@Composable
private fun AppThemeDialog(
    currentTheme: AppThemeMode,
    onDismiss: () -> Unit,
    onThemeSelect: (AppThemeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("App Appearance", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    val isSelected = currentTheme == mode
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onThemeSelect(mode) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onThemeSelect(mode) },
                            )
                            Column {
                                Text(
                                    text = mode.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = when (mode) {
                                        AppThemeMode.SYSTEM -> "Syncs with Android system setting"
                                        AppThemeMode.DYNAMIC -> "Dynamic wallpaper Monet palette (Android 12+)"
                                        AppThemeMode.AMOLED_DARK -> "True pitch black (#000000) for OLED panels"
                                        AppThemeMode.LIGHT -> "Clean, high-contrast light theme"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
private fun DefaultDownloadChoiceDialog(
    selected: DefaultDownloadChoice,
    onDismiss: () -> Unit,
    onSelected: (DefaultDownloadChoice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text("Default Download Action", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DefaultDownloadChoice.entries.forEach { choice ->
                    val isSelected = selected == choice
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelected(choice) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelected(choice) },
                            )
                            Text(
                                text = choice.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
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
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
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
