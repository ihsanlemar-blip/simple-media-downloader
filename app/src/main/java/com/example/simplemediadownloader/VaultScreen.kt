package com.example.simplemediadownloader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun VaultScreen(
    state: MainUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectPlatform: (String?) -> Unit,
    onToggleViewMode: (VaultViewMode) -> Unit,
    onSelectMediaType: (VaultMediaType) -> Unit,
    onToggleTaskSelection: (String) -> Unit,
    onSelectAllTasks: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelectedTasks: (alsoDeleteStorageFiles: Boolean) -> Unit,
    onPreviewMedia: (DownloadOutput) -> Unit,
    onShareMedia: (DownloadResult.Success) -> Unit,
    onDeleteTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCopyDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val vaultItems = state.filteredVaultTasks
    val platforms = listOf("All", "TikTok", "Instagram", "Facebook", "YouTube", "X", "Reddit")

    var showDeleteConfirmationDialog by rememberSaveable { mutableStateOf(false) }
    var alsoDeleteStorageFiles by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 100.dp),
    ) {
        // Multi-select Batch Toolbar
        if (state.isMultiSelectActive) {
            item(key = "batch_toolbar") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${state.selectedVaultTaskIds.size} selected",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onSelectAllTasks) {
                                Text(stringResource(R.string.action_select_all), fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { showDeleteConfirmationDialog = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.action_delete_selected),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(onClick = onClearSelection) {
                                Icon(imageVector = Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear_selection))
                            }
                        }
                    }
                }
            }
        }

        // View Switchers: Segmented Filter (All / Video / Audio) & Layout Toggle
        item(key = "view_mode_controls") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Media Type Filters
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VaultMediaType.entries.forEach { mediaType ->
                        val isSelected = state.vaultMediaType == mediaType
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectMediaType(mediaType)
                            },
                            label = {
                                Text(
                                    text = when (mediaType) {
                                        VaultMediaType.ALL -> stringResource(R.string.tab_media_all)
                                        VaultMediaType.VIDEOS -> stringResource(R.string.tab_media_video)
                                        VaultMediaType.AUDIO -> stringResource(R.string.tab_media_audio)
                                    },
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }

                // Layout & Multi-select Toggles
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            val newMode = if (state.vaultViewMode == VaultViewMode.GRID) VaultViewMode.LIST else VaultViewMode.GRID
                            onToggleViewMode(newMode)
                        },
                    ) {
                        Icon(
                            imageVector = if (state.vaultViewMode == VaultViewMode.GRID) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                            contentDescription = "Toggle View Mode",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Live Search Bar
        item(key = "search_bar") {
            OutlinedTextField(
                value = state.historySearchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        stringResource(R.string.vault_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    if (state.historySearchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_clear_search),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        // Horizontal Platform Filter Chips
        item(key = "platform_chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                platforms.forEach { platform ->
                    val isSelected = if (platform == "All") state.historyPlatformFilter == null else state.historyPlatformFilter.equals(platform, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectPlatform(if (platform == "All") null else platform)
                        },
                        label = {
                            Text(
                                text = if (platform == "All") stringResource(R.string.filter_all) else platform,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }

        // Content Area: Grid vs List or Empty State
        if (vaultItems.isEmpty()) {
            item(key = "vault_empty_state") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = if (state.historySearchQuery.isNotBlank() || state.historyPlatformFilter != null) {
                                stringResource(R.string.vault_no_matches)
                            } else {
                                stringResource(R.string.vault_empty_title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.vault_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        } else if (state.vaultViewMode == VaultViewMode.GRID) {
            // 2-Column Grid Layout with Action Icons & Thumbnails
            val chunkedItems = vaultItems.chunked(2)
            items(chunkedItems, key = { chunk -> chunk.joinToString { it.id } }) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowItems.forEach { task ->
                        Box(modifier = Modifier.weight(1f)) {
                            VaultGridCard(
                                task = task,
                                isSelected = task.id in state.selectedVaultTaskIds,
                                isMultiSelect = state.isMultiSelectActive,
                                onToggleSelect = { onToggleTaskSelection(task.id) },
                                onPreview = {
                                    if (state.isMultiSelectActive) {
                                        onToggleTaskSelection(task.id)
                                    } else {
                                        (task.state as? DownloadState.Completed)?.output?.let(onPreviewMedia)
                                    }
                                },
                                onShare = onShareMedia,
                                onDelete = onDeleteTask,
                                onCopyDetails = onCopyDetails,
                            )
                        }
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            // Detail List Layout with Smart Date Headers, Top Thumbnails & Action Icons
            val grouped = groupTasksByDate(vaultItems)
            grouped.forEach { (dateHeader, tasksInGroup) ->
                item(key = "header_$dateHeader") {
                    Text(
                        text = stringResource(dateHeader.stringRes),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }

                items(tasksInGroup, key = { it.id }) { task ->
                    VaultListCard(
                        task = task,
                        isSelected = task.id in state.selectedVaultTaskIds,
                        isMultiSelect = state.isMultiSelectActive,
                        onToggleSelect = { onToggleTaskSelection(task.id) },
                        onPreview = {
                            if (state.isMultiSelectActive) {
                                onToggleTaskSelection(task.id)
                            } else {
                                (task.state as? DownloadState.Completed)?.output?.let(onPreviewMedia)
                            }
                        },
                        onShare = onShareMedia,
                        onDelete = onDeleteTask,
                        onRemove = onRemoveTask,
                        onCopyDetails = onCopyDetails,
                    )
                }
            }
        }
    }

    if (showDeleteConfirmationDialog) {
        val selectedTasks = state.tasks.filter { it.id in state.selectedVaultTaskIds }
        val totalBytes = selectedTasks.sumOf {
            (it.state as? DownloadState.Completed)?.output?.fileSizeBytes ?: 0L
        }
        BulkDeleteConfirmationDialog(
            selectedCount = state.selectedVaultTaskIds.size,
            totalBytes = totalBytes,
            alsoDeleteStorageFiles = alsoDeleteStorageFiles,
            onToggleAlsoDeleteStorageFiles = { alsoDeleteStorageFiles = it },
            onDismiss = { showDeleteConfirmationDialog = false },
            onConfirm = {
                val deleteFiles = alsoDeleteStorageFiles
                showDeleteConfirmationDialog = false
                onDeleteSelectedTasks(deleteFiles)
            },
        )
    }
}

@Composable
fun BulkDeleteConfirmationDialog(
    selectedCount: Int,
    totalBytes: Long,
    alsoDeleteStorageFiles: Boolean,
    onToggleAlsoDeleteStorageFiles: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_bulk_delete_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dialog_bulk_delete_message, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Total storage space: ${formatByteCount(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleAlsoDeleteStorageFiles(!alsoDeleteStorageFiles) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = alsoDeleteStorageFiles,
                            onCheckedChange = onToggleAlsoDeleteStorageFiles,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.dialog_bulk_delete_storage_checkbox),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (alsoDeleteStorageFiles) {
                                    stringResource(R.string.dialog_bulk_delete_storage_files_warning)
                                } else {
                                    stringResource(R.string.dialog_bulk_delete_history_only_warning)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun MediaThumbnailHeader(
    task: DownloadTask,
    aspectRatio: Float,
    isMultiSelect: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val completedOutput = (task.state as? DownloadState.Completed)?.output
    val isVideo = task.format.mode == DownloadMode.VIDEO

    var thumbnailBitmap by remember(completedOutput?.contentUri) {
        mutableStateOf<Bitmap?>(completedOutput?.contentUri?.let { MediaThumbnailLoader.getCachedThumbnail(it) })
    }

    LaunchedEffect(completedOutput?.contentUri) {
        val uriStr = completedOutput?.contentUri
        if (!uriStr.isNullOrBlank() && thumbnailBitmap == null) {
            thumbnailBitmap = MediaThumbnailLoader.loadThumbnail(context, uriStr, isVideo)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
    ) {
        val currentBitmap = thumbnailBitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = task.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Gradient scrim for contrast with overlays
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.65f),
                            )
                        )
                    )
            )
        } else {
            PlatformHeroGlow(platform = task.platform, modifier = Modifier.fillMaxSize())
        }

        // Center Play / Audio Action Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(44.dp)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayClick()
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.AudioFile,
                        contentDescription = stringResource(R.string.action_play),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        // Top and Bottom Overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            PlatformBadge(
                platform = task.platform,
                size = 6.dp,
                modifier = Modifier.align(Alignment.TopStart),
            )

            if (isMultiSelect) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Text(
                    text = task.format.extension.uppercase(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun VaultGridCard(
    task: DownloadTask,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onShare: (DownloadResult.Success) -> Unit,
    onDelete: (String) -> Unit,
    onCopyDetails: (String) -> Unit,
) {
    val completedOutput = (task.state as? DownloadState.Completed)?.output
    val haptic = LocalHapticFeedback.current

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 0.5.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = {
                if (isMultiSelect) {
                    onToggleSelect()
                } else {
                    onPreview()
                }
            }),
    ) {
        Column {
            MediaThumbnailHeader(
                task = task,
                aspectRatio = 4f / 3f,
                isMultiSelect = isMultiSelect,
                isSelected = isSelected,
                onToggleSelect = onToggleSelect,
                onPlayClick = onPreview,
            )

            // Info section
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (completedOutput != null) {
                    Text(
                        text = formatByteCount(completedOutput.fileSizeBytes),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = TabularNumberStyle.fontFamily,
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Action icons row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPreview()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.action_open),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    if (completedOutput != null) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onShare(DownloadResult.Success(completedOutput))
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.action_share),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDelete(task.id)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.content_desc_delete_media),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCopyDetails(task.url)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy_link),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultListCard(
    task: DownloadTask,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onShare: (DownloadResult.Success) -> Unit,
    onDelete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCopyDetails: (String) -> Unit,
) {
    val completedOutput = (task.state as? DownloadState.Completed)?.output
    val haptic = LocalHapticFeedback.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 0.5.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = {
                if (isMultiSelect) {
                    onToggleSelect()
                } else {
                    onPreview()
                }
            }),
    ) {
        Column {
            // Media thumbnail picture ABOVE the card
            MediaThumbnailHeader(
                task = task,
                aspectRatio = 16f / 9f,
                isMultiSelect = isMultiSelect,
                isSelected = isSelected,
                onToggleSelect = onToggleSelect,
                onPlayClick = onPreview,
            )

            // Content & Action details
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PlatformBadge(task.platform)
                }

                if (completedOutput != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        Text(
                            text = "${completedOutput.displayName} • ${formatByteCount(completedOutput.fileSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = TabularNumberStyle.fontFamily,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPreview()
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 6.dp,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_open), fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onShare(DownloadResult.Success(completedOutput))
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 6.dp,
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_share), fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Row {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onCopyDetails(task.url)
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.action_copy_link),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDelete(task.id)
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.content_desc_delete_media),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRemove(task.id)
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.content_desc_remove_history),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun groupTasksByDate(tasks: List<DownloadTask>): Map<DateGroup, List<DownloadTask>> {
    val now = LocalDate.now(ZoneId.systemDefault())
    return tasks.groupBy { task ->
        val timestamp = task.completedAt ?: task.startedAt ?: task.createdAt
        val taskDate = if (timestamp > 0L) {
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        } else {
            now
        }
        val daysBetween = ChronoUnit.DAYS.between(taskDate, now)
        when {
            daysBetween <= 0L -> DateGroup.TODAY
            daysBetween == 1L -> DateGroup.YESTERDAY
            daysBetween in 2..7 -> DateGroup.THIS_WEEK
            else -> DateGroup.OLDER
        }
    }
}
