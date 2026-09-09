package com.example.simplemediadownloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewList
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    onDeleteSelectedTasks: () -> Unit,
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
                                Text("Select all", fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = onDeleteSelectedTasks) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete selected",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(onClick = onClearSelection) {
                                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Done")
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
                                    text = mediaType.label,
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
                        "Search saved media or creator…",
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
                                contentDescription = "Clear search",
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
                                text = platform,
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
                                "No media matches your search"
                            } else {
                                "Vault is Empty"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Downloaded videos and audio tracks will appear here with instant offline preview.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        } else if (state.vaultViewMode == VaultViewMode.GRID) {
            // 2-Column Grid Layout
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
                            )
                        }
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            // Detail List Layout with Smart Date Headers
            val grouped = groupTasksByDate(vaultItems)
            grouped.forEach { (dateHeader, tasksInGroup) ->
                item(key = "header_$dateHeader") {
                    Text(
                        text = dateHeader,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }

                items(tasksInGroup, key = { it.id }) { task ->
                    FinishedTaskCard(
                        task = task,
                        onOpen = { result -> onPreviewMedia(result.output) },
                        onShare = onShareMedia,
                        onRetry = { onRetryTask(task.id) },
                        onRemove = { onRemoveTask(task.id) },
                        onDelete = { onDeleteTask(task.id) },
                        onCopyDetails = onCopyDetails,
                    )
                }
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
) {
    val completedOutput = (task.state as? DownloadState.Completed)?.output
    val isVideo = task.format.mode == DownloadMode.VIDEO

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
            .clickable(onClick = onPreview),
    ) {
        Column {
            // Media Poster Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            ) {
                PlatformHeroGlow(platform = task.platform, modifier = Modifier.fillMaxSize())

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.AudioFile,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }

                // Badges overlay
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
                }
            }

            // Info section
            Column(
                modifier = Modifier.padding(12.dp),
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
            }
        }
    }
}

private fun groupTasksByDate(tasks: List<DownloadTask>): Map<String, List<DownloadTask>> {
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
            daysBetween <= 0L -> "Today"
            daysBetween == 1L -> "Yesterday"
            daysBetween in 2..7 -> "Earlier this Week"
            else -> "Older"
        }
    }
}
