package com.example.simplemediadownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Shared by the full picker and share window; expansion never changes selected output. */
@Composable
internal fun AudioFormatSections(
    catalog: MediaFormatCatalog,
    selectedKey: String?,
    enabled: Boolean,
    onSelected: (AvailableFormat) -> Unit,
    defaultBitrateKbps: Int = PlatformAudioPolicy.DEFAULT_MP3_BITRATE_KBPS,
) {
    val platform = PlatformResolver.fromUrl(catalog.sourceUrl)
    val mp3Default = PlatformAudioPolicy.mp3ExpandedByDefault(platform)
    var expanded by rememberSaveable(catalog.sourceUrl) { mutableStateOf(mp3Default) }
    val native = catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_ORIGINAL }
    val mp3 = catalog.audioFormats.filter { it.mode == DownloadMode.AUDIO_MP3 }
    val defaultKey = PlatformAudioPolicy.selectAudio(catalog, defaultBitrateKbps)?.key

    @Composable
    fun nativeSection() {
        Text("Native Audio" + if (!mp3Default) " • Default" else "", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("native_audio_section"))
        Text("Original source audio • Best source format", style = MaterialTheme.typography.bodySmall)
        native.forEach { format ->
            ShareFormatRow(format, format.key == (selectedKey ?: defaultKey), enabled = enabled) { if (enabled) onSelected(format) }
        }
    }
    @Composable
    fun mp3Section() {
        if (mp3.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("mp3_expand")) {
                Text(if (mp3Default) "MP3 • Recommended for compatibility" else "Convert to MP3")
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse MP3 qualities" else "Expand MP3 qualities")
            }
            if (expanded) {
                // Put the shipped recommendation first for YouTube, retain ascending order elsewhere.
                val options = if (mp3Default) mp3.sortedBy { if (it.targetAudioBitrateKbps == defaultBitrateKbps) 0 else it.targetAudioBitrateKbps } else mp3
                options.forEach { format ->
                    ShareFormatRow(format, format.key == (selectedKey ?: defaultKey), enabled = enabled) { if (enabled) onSelected(format) }
                    Text(format.formatNote + if (mp3Default && format.key == defaultKey) " • Default" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("audio_sections")) {
        if (mp3Default) { mp3Section(); nativeSection() } else { nativeSection(); mp3Section() }
    }
}
