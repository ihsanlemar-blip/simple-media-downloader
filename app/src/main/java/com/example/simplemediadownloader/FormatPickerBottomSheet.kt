package com.example.simplemediadownloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatPickerBottomSheet(
    catalog: MediaFormatCatalog,
    selectionEnabled: Boolean,
    onDismiss: () -> Unit,
    onSelected: (AvailableFormat) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

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
    val platform = PlatformResolver.fromUrl(catalog.sourceUrl)
    val brandGradient = PlatformDesignTokens.getBrandGradient(platform)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = CircleShape,
            ) {
                Box(modifier = Modifier.size(width = 36.dp, height = 4.dp))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Ambient Media Preview Banner Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                border = BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Ambient Brand Glow Accent in background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        brandGradient.first().copy(alpha = 0.12f),
                                        brandGradient.last().copy(alpha = 0.04f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )

                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Thumbnail with high-res rounded frame
                        if (!catalog.thumbnailUrl.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                shadowElevation = 3.dp,
                                modifier = Modifier.size(width = 76.dp, height = 76.dp),
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(catalog.thumbnailUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = stringResource(R.string.format_preview_thumbnail),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(width = 76.dp, height = 76.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = PlatformDesignTokens.getPlatformIcon(platform),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }

                        // Title & Metadata
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlatformBadge(platform, size = 6.dp)
                            }
                            Text(
                                text = catalog.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (!catalog.author.isNullOrBlank()) {
                                Text(
                                    text = "@${catalog.author.removePrefix("@")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // Segmented Quality Control Mode Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = mode == DownloadMode.VIDEO,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        mode = DownloadMode.VIDEO
                    },
                    enabled = catalog.videoFormats.isNotEmpty(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.VideoFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(stringResource(R.string.mode_video_count, catalog.videoFormats.size), fontWeight = FontWeight.SemiBold) },
                    shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = mode == DownloadMode.AUDIO_ORIGINAL,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        mode = DownloadMode.AUDIO_ORIGINAL
                    },
                    enabled = catalog.audioFormats.any { it.mode == DownloadMode.AUDIO_ORIGINAL },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(stringResource(R.string.mode_audio_original), fontWeight = FontWeight.SemiBold) },
                    shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = mode == DownloadMode.AUDIO_MP3,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        mode = DownloadMode.AUDIO_MP3
                    },
                    enabled = catalog.audioFormats.any { it.mode == DownloadMode.AUDIO_MP3 },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.AudioFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(stringResource(R.string.mode_audio_mp3_chip), fontWeight = FontWeight.SemiBold) },
                    shape = RoundedCornerShape(12.dp),
                )
            }

            // Simple vs Advanced Stream Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (mode == DownloadMode.VIDEO) stringResource(R.string.choose_resolution) else stringResource(R.string.choose_quality),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                FilterChip(
                    selected = advancedMode,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        advancedMode = !advancedMode
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (advancedMode) Icons.Rounded.Layers else Icons.Rounded.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    label = {
                        Text(
                            text = if (advancedMode) {
                                stringResource(R.string.format_all_streams, formats.size)
                            } else {
                                stringResource(R.string.format_recommended)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }

            // Loading details indicator
            if (catalog.detailsLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Formats List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(displayedFormats, key = AvailableFormat::key) { format ->
                    FormatRadioCard(
                        format = format,
                        enabled = selectionEnabled,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelected(format)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun FormatRadioCard(
    format: AvailableFormat,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = when (format.mode) {
        DownloadMode.VIDEO -> buildString {
            if (format.height > 0) {
                if (format.isQuickPreset) append("Up to ")
                append("${format.height}p")
                if (format.height >= 1080) append(" Full HD")
                else if (format.height >= 720) append(" HD")
            } else {
                append("Best Video Quality")
            }
            if (format.fps > 0) append(" • ${format.fps} fps")
        }
        DownloadMode.AUDIO_ORIGINAL -> buildString {
            append("Original Audio Track")
            if (format.bitrateKbps > 0) append(" • ${format.bitrateKbps} kbps")
        }
        DownloadMode.AUDIO_MP3 -> if (format.bitrateKbps > 0) "${format.bitrateKbps} kbps MP3 Audio" else "Standard MP3 Audio"
    }

    val details = buildList {
        add(format.extension.uppercase(Locale.US))
        format.codec.takeIf { it.isNotBlank() && it != "none" }?.let {
            add(it.substringBefore('.').uppercase(Locale.US))
        }
        format.formatNote.takeIf { it.isNotBlank() && !format.isQuickPreset }?.let(::add)
        add(formatSizeLabel(format))
    }.joinToString(" • ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (format.mode == DownloadMode.VIDEO) Icons.Rounded.PlayCircle else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (format.isDataSaver) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = stringResource(R.string.data_saver_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = TabularNumberStyle.fontFamily,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.action_select_format),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

