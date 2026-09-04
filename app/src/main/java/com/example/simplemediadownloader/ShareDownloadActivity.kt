package com.example.simplemediadownloader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class ShareDownloadActivity : ComponentActivity() {
    private val viewModel: ShareDownloadViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.continueAfterNotificationPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureDialogWindow()
        viewModel.initialize(ShareIntentParser.parse(intent))
        setContent {
            SimpleMediaDownloaderTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.enqueueSucceeded) {
                    if (state.enqueueSucceeded) finishAndRemoveTask()
                }
                BackHandler {
                    if (viewModel.cancelBeforeEnqueue()) finishAndRemoveTask()
                }
                ShareDownloadContent(
                    state = state,
                    onSelectFormat = viewModel::selectFormat,
                    onToggleAdvanced = viewModel::setAdvancedFormatsVisible,
                    onRetryEngine = viewModel::retryEngineInitialization,
                    onDownload = ::requestDownload,
                    onCancel = {
                        if (viewModel.cancelBeforeEnqueue()) finishAndRemoveTask()
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun configureDialogWindow() {
        window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window.setGravity(Gravity.CENTER)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.55f }
    }

    private fun requestDownload() {
        val requiresPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        when (viewModel.requestDownload(requiresPermission)) {
            ShareDownloadAction.REQUEST_NOTIFICATION_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.continueAfterNotificationPermission()
                }
            }
            ShareDownloadAction.ENQUEUE_STARTED,
            ShareDownloadAction.IGNORED -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareDownloadContent(
    state: ShareDownloadUiState,
    onSelectFormat: (String) -> Unit,
    onToggleAdvanced: (Boolean) -> Unit,
    onRetryEngine: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedMode = state.selectedFormat?.mode ?: DownloadMode.VIDEO
    val modeFormats = state.catalog?.let { catalog ->
        if (selectedMode == DownloadMode.VIDEO) catalog.videoFormats else catalog.audioFormats
    }.orEmpty()
    val commonFormats = remember(modeFormats, selectedMode) {
        commonShareFormats(modeFormats, selectedMode)
    }
    val commonKeys = remember(commonFormats) { commonFormats.mapTo(hashSetOf()) { it.key } }
    val advancedFormats = remember(modeFormats, commonKeys) {
        modeFormats.filterNot { it.key in commonKeys }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .heightIn(max = 720.dp)
                .testTag("share_download_dialog"),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Download shared media",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        state.platform,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("share_platform"),
                    )
                    Text(
                        state.displayUrl.ifBlank { "No usable URL detected" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("share_source_url"),
                    )

                    state.notice?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }

                    if (state.enginePreparing || state.inspectingFormats) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("share_loading"),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text(
                                if (state.enginePreparing) {
                                    "Preparing download engine…"
                                } else {
                                    "Inspecting available formats…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    state.error?.let { error ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    error,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("share_error"),
                                )
                                if (!state.engineReady && !state.enginePreparing && state.sourceUrl != null) {
                                    TextButton(onClick = onRetryEngine) { Text("Retry engine") }
                                }
                            }
                        }
                    }

                    state.catalog?.let { catalog ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedMode == DownloadMode.VIDEO,
                                enabled = catalog.videoFormats.isNotEmpty(),
                                onClick = {
                                    catalog.videoFormats.firstOrNull()?.let { onSelectFormat(it.key) }
                                },
                                label = { Text("Video") },
                            )
                            FilterChip(
                                selected = selectedMode != DownloadMode.VIDEO,
                                enabled = catalog.audioFormats.isNotEmpty(),
                                onClick = {
                                    catalog.audioFormats.firstOrNull()?.let { onSelectFormat(it.key) }
                                },
                                label = { Text("Audio") },
                            )
                        }

                        commonFormats.forEach { format ->
                            ShareFormatRow(
                                format = format,
                                selected = format.key == state.selectedFormatKey,
                                onSelected = { onSelectFormat(format.key) },
                            )
                        }

                        if (advancedFormats.isNotEmpty()) {
                            TextButton(
                                onClick = { onToggleAdvanced(!state.advancedFormatsVisible) },
                            ) {
                                Text(
                                    if (state.advancedFormatsVisible) {
                                        "Hide advanced formats"
                                    } else {
                                        "Advanced formats (${advancedFormats.size})"
                                    },
                                )
                            }
                            if (state.advancedFormatsVisible) {
                                advancedFormats.forEach { format ->
                                    ShareFormatRow(
                                        format = format,
                                        selected = format.key == state.selectedFormatKey,
                                        onSelected = { onSelectFormat(format.key) },
                                    )
                                }
                            }
                        }

                        state.selectedFormat?.let { selected ->
                            HorizontalDivider()
                            Text(
                                "Selected size: ${formatSizeLabel(selected)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("share_selected_size"),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !state.enqueueing,
                        modifier = Modifier.testTag("share_cancel"),
                    ) { Text("Cancel") }
                    Button(
                        onClick = onDownload,
                        enabled = state.canDownload,
                        modifier = Modifier.testTag("share_download"),
                    ) {
                        Text(
                            if (state.enqueueing || state.awaitingNotificationPermission) {
                                "Starting…"
                            } else {
                                "Download"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareFormatRow(
    format: AvailableFormat,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelected)
        Column(modifier = Modifier.weight(1f)) {
            Text(shareFormatLabel(format), fontWeight = FontWeight.SemiBold)
            Text(
                formatSizeLabel(format),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun commonShareFormats(
    formats: List<AvailableFormat>,
    selectedMode: DownloadMode,
): List<AvailableFormat> {
    if (formats.isEmpty()) return emptyList()
    return if (selectedMode == DownloadMode.VIDEO) {
        val commonHeights = setOf(1080, 720, 480)
        buildList {
            add(formats.first())
            formats.filterTo(this) { it.height in commonHeights }
        }.distinctBy(AvailableFormat::key)
    } else {
        buildList {
            formats.firstOrNull { it.mode == DownloadMode.AUDIO_ORIGINAL }?.let(::add)
            formats.filter { it.mode == DownloadMode.AUDIO_MP3 }
                .minByOrNull { kotlin.math.abs(it.bitrateKbps - 192) }
                ?.let(::add)
            if (isEmpty()) add(formats.first())
        }.distinctBy(AvailableFormat::key)
    }
}

internal fun shareFormatLabel(format: AvailableFormat): String = when (format.mode) {
    DownloadMode.VIDEO -> if (format.height > 0) {
        buildString {
            append("${format.height}p video")
            if (!format.requiresFfmpeg) append(" · video + audio")
        }
    } else {
        "Best video"
    }
    DownloadMode.AUDIO_ORIGINAL -> buildString {
        append("Original audio")
        if (format.bitrateKbps > 0) append(" · ${format.bitrateKbps} kbps")
    }
    DownloadMode.AUDIO_MP3 -> "${format.bitrateKbps} kbps MP3"
}
