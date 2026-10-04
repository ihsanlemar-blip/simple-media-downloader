package com.example.simplemediadownloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.ImageLoader
import androidx.compose.ui.platform.LocalContext
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class BatchActivity : ComponentActivity() {
    private val viewModel: BatchViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_SOURCE_URL)?.let { (ShareIntentParser.parseText(it) as? SharedUrlResult.Valid)?.url }
        viewModel.initialize(url)
        setContent {
            SimpleMediaDownloaderTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler(state.snapshot != null) { if (state.discovering) viewModel.stopDiscovery() else if (!state.busy) viewModel.closePreview() }
                BatchScreen(state, viewModel::open, viewModel::closePreview, viewModel::select, viewModel::discoverMore,
                    viewModel::page, { viewModel.configure(it) }, viewModel::audioDefault,
                    { viewModel.configure(skipExisting = it) }, { viewModel.configure(prefix = it) },
                    viewModel::estimate, viewModel::pause, viewModel::resume, viewModel::cancel, viewModel::retry, viewModel::delete, viewModel::analyzeRemaining, viewModel::stopDiscovery)
                state.estimate?.let { estimate ->
                    AlertDialog(onDismissRequest = viewModel::dismissEstimate,
                        title = { Text("Confirm batch download") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.snapshot?.parent?.let { parent ->
                                Text("${parent.selectedCount} selected · ${batchFormatLabel(parent.formatChoice)}")
                            }
                            Text("${estimate.newItems} new · ${estimate.alreadyDownloaded} already downloaded · ${estimate.alreadyQueued} already queued")
                            Text(estimate.label)
                            if (estimate.likelyInsufficient) Text("Available storage is likely insufficient. This estimate includes room for temporary sources and a safety margin.", color = MaterialTheme.colorScheme.error)
                            Text("Each item uses the existing download queue. Already queued items are always skipped.")
                        } },
                        confirmButton = { TextButton(onClick = viewModel::enqueue, enabled = !state.busy) { Text("Download selected") } },
                        dismissButton = { TextButton(onClick = viewModel::dismissEstimate) { Text("Back") } })
                }
            }
        }
    }
    companion object { const val EXTRA_SOURCE_URL = "collection_source_url" }
}

@Composable
internal fun BatchScreen(
    state: BatchUiState, onOpen: (String) -> Unit, onBack: () -> Unit,
    onSelect: (String?, Boolean) -> Unit, onMore: () -> Unit, onPage: (Int) -> Unit,
    onFormat: (BatchFormatChoice) -> Unit, onAudio: () -> Unit, onSkip: (Boolean) -> Unit, onPrefix: (Boolean) -> Unit,
    onEstimate: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit,
    onAnalyze: () -> Unit = {}, onStopDiscovery: () -> Unit = {},
) {
    val context = LocalContext.current
    val thumbnails = remember(context) {
        ImageLoader.Builder(context).okHttpClient {
            OkHttpClient.Builder().dns(SafeDns()).cookieJar(CookieJar.NO_COOKIES)
                .addInterceptor(SecurityInterceptor()).addNetworkInterceptor(SecurityInterceptor()).build()
        }.build()
    }
    DisposableEffect(thumbnails) { onDispose { thumbnails.shutdown() } }
    val snapshot = state.snapshot
    val parent = snapshot?.parent
    val editable = parent?.status in setOf("DISCOVERING", "READY", "FAILED")
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp).testTag("batch_list"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)) {
            item { Text(parent?.title ?: "Batch downloads", style = MaterialTheme.typography.headlineSmall) }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            (state.error ?: parent?.error)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (parent == null) {
                item { Text("Paste a playlist or profile link in the Gateway to discover a collection. Downloads start only after selection and confirmation.") }
                items(state.parents, key = { it.batchId }) { batch ->
                    OutlinedButton(onClick = { onOpen(batch.batchId) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${batch.title ?: batch.platform} · ${batch.selectedCount} selected")
                    }
                }
            } else {
                item { TextButton(onClick = onBack, enabled = !state.busy) { Text("All batches") } }
                parent.thumbnailUrl?.takeIf(NetworkSecurityPolicy::isAllowedShareUrl)?.let { thumbnail -> item {
                    AsyncImage(thumbnail, "Playlist thumbnail", imageLoader = thumbnails, modifier = Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Crop)
                } }
                parent.author?.let { author -> item { Text(author, style = MaterialTheme.typography.titleMedium) } }
                item { Text("${parent.discoveredCount}" + (parent.totalItemCount?.let { " of $it" } ?: "") + " items found · ${parent.selectedCount} selected") }
                if (state.discovering) item {
                    Text("Analyzing playlist…", Modifier.testTag("playlist_analyzing"))
                    TextButton(onStopDiscovery, modifier = Modifier.testTag("playlist_stop")) { Text("Stop analyzing (keep discovered items)") }
                }
                item { Text("${snapshot.status.name.replace('_', ' ')} · ${snapshot.progress.completed} / ${snapshot.progress.selected} completed\n${snapshot.progress.running} active · ${snapshot.progress.queued} queued · ${snapshot.progress.failed} failed · ${snapshot.progress.cancelled} cancelled") }
                if (editable) {
                    item { Text("Choose one format for all selected items", style = MaterialTheme.typography.titleMedium) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(parent.downloadMode == "VIDEO", { onFormat(BatchFormatChoice(DownloadMode.VIDEO)) }, label = { Text("Video") }, enabled = !state.busy)
                        FilterChip(parent.downloadMode != "VIDEO", onAudio, label = { Text("Audio") }, enabled = !state.busy)
                    } }
                    if (parent.downloadMode == "VIDEO") {
                        item { Text("Best available at or below the selected resolution") }
                        for (height in listOf(0, 1080, 720, 480)) item {
                            FilterChip(parent.maximumHeight == height, { onFormat(BatchFormatChoice(DownloadMode.VIDEO, height)) },
                                label = { Text(if (height == 0) "Best available" else "${height}p") }, enabled = !state.busy)
                        }
                    } else {
                        item { FilterChip(parent.downloadMode == "AUDIO_ORIGINAL", { onFormat(BatchFormatChoice(DownloadMode.AUDIO_ORIGINAL)) }, label = { Text("Native Audio") }, enabled = !state.busy) }
                        for (bitrate in PlatformAudioPolicy.MP3_BITRATES) item {
                            FilterChip(parent.downloadMode == "AUDIO_MP3" && parent.mp3BitrateKbps == bitrate,
                                { onFormat(BatchFormatChoice(DownloadMode.AUDIO_MP3, mp3BitrateKbps = bitrate)) }, label = { Text("MP3 $bitrate kbps") }, enabled = !state.busy)
                        }
                    }
                    item { Row { Checkbox(parent.skipExisting, onSkip, enabled = !state.busy); Text("Skip already downloaded (disable to re-download)", Modifier.weight(1f).padding(top = 12.dp)) } }
                    item { Row { Checkbox(parent.prefixOrder, onPrefix, enabled = !state.busy); Text("Number filenames in collection order", Modifier.weight(1f).padding(top = 12.dp)) } }
                    item { Row {
                        TextButton(onClick = { onSelect(null, true) }, enabled = !state.busy, modifier = Modifier.testTag("batch_select_all")) { Text("Select all") }
                        TextButton(onClick = { onSelect(null, false) }, enabled = !state.busy) { Text("Deselect all") }
                    } }
                }
                items(state.items, key = { it.itemId }) { item ->
                    Row(Modifier.fillMaxWidth().testTag("batch_item_${item.position}")) {
                        Checkbox(item.selected, { onSelect(item.itemId, it) }, enabled = editable && !state.busy && item.unavailableReason == null)
                        item.thumbnailUrl?.takeIf(NetworkSecurityPolicy::isAllowedShareUrl)?.let { thumbnail ->
                            AsyncImage(thumbnail, "Thumbnail for ${item.title ?: "item ${item.position + 1}"}",
                                imageLoader = thumbnails, modifier = Modifier.padding(top = 8.dp, end = 8.dp).size(width = 72.dp, height = 48.dp), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f).padding(top = 8.dp)) {
                            Text("${item.position + 1}. ${item.title ?: "Untitled media"}")
                            item.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            item.durationSeconds?.takeIf { it > 0 }?.let { Text(playlistDurationLabel(it), style = MaterialTheme.typography.bodySmall) }
                            item.unavailableReason?.let { Text("Skipped: $it", color = MaterialTheme.colorScheme.error) }
                            item.skipReason?.let { Text("Skipped: already ${it.lowercase()}") }
                        }
                    }
                }
                item { Row {
                    TextButton({ onPage(state.page - 1) }, enabled = state.page > 0 && !state.busy) { Text("Previous") }
                    TextButton({ onPage(state.page + 1) }, enabled = (state.page + 1) * 50 < parent.discoveredCount && !state.busy) { Text("Next page") }
                } }
                if (editable && parent.hasMore) {
                    item { OutlinedButton(onMore, enabled = !state.busy) { Text("Discover next 50 items") } }
                    item { OutlinedButton(onAnalyze, enabled = !state.busy) { Text("Analyze remaining playlist") } }
                }
                if (editable) item { Button(onEstimate, enabled = parent.selectedCount > 0 && !state.busy, modifier = Modifier.testTag("batch_review")) { Text("Review selected downloads") } }
                else {
                    if (snapshot.status in setOf(BatchStatus.RUNNING, BatchStatus.QUEUED)) item { OutlinedButton(onPause, enabled = !state.busy) { Text("Pause batch") } }
                    if (snapshot.status == BatchStatus.PAUSED) item { Button(onResume, enabled = !state.busy) { Text("Resume batch") } }
                    if (snapshot.progress.failed > 0) item { OutlinedButton(onRetry, enabled = !state.busy) { Text("Retry failed") } }
                }
                if (snapshot.status != BatchStatus.CANCELLED) item { TextButton(onCancel, enabled = !state.busy) { Text("Cancel batch (keep completed media)") } }
                if (snapshot.progress.running == 0 && snapshot.progress.queued == 0) item { TextButton(onDelete, enabled = !state.busy) { Text("Delete batch history (keep media and item history)") } }
                item { Text("Individual downloads remain in Transfers and Vault with their usual cancel, retry, open, share, and delete actions.", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

internal fun batchFormatLabel(choice: BatchFormatChoice): String = when (choice.mode) {
    DownloadMode.AUDIO_MP3 -> "MP3 ${choice.mp3BitrateKbps} kbps"
    DownloadMode.AUDIO_ORIGINAL -> "Native Audio"
    DownloadMode.VIDEO -> if (choice.maximumHeight == 0) "Video · Best available" else "Video · ${choice.maximumHeight}p"
}
