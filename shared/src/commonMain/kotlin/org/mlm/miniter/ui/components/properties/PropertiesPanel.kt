package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.miniter.project.*

@Composable
fun PropertiesPanel(
    project: MinterProject?,
    selectedClipId: String?,
    onAddFilter: (String, VideoFilter) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetTransition: (String, Transition?) -> Unit,
) {
    val clip = project?.timeline?.tracks
        ?.flatMap { it.clips }
        ?.find { it.id == selectedClipId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Properties",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(12.dp))

        if (clip == null) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Select a clip to edit its properties",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        when (clip) {
            is Clip.VideoClip -> VideoClipProperties(clip, onAddFilter, onRemoveFilter, onSetSpeed, onSetTransition)
            is Clip.AudioClip -> AudioClipProperties(clip)
            is Clip.TextClip -> TextClipProperties(clip)
        }
    }
}

@Composable
private fun VideoClipProperties(
    clip: Clip.VideoClip,
    onAddFilter: (String, VideoFilter) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetTransition: (String, Transition?) -> Unit,
) {
    val fileName = clip.sourcePath.substringAfterLast("/").substringAfterLast("\\")

    Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Duration: ${clip.durationMs / 1000}s | Speed: ${clip.speed}x",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Text("Speed", style = MaterialTheme.typography.labelMedium)
    var speedValue by remember(clip.speed) { mutableFloatStateOf(clip.speed) }
    Slider(
        value = speedValue,
        onValueChange = { speedValue = it },
        onValueChangeFinished = { onSetSpeed(clip.id, speedValue) },
        valueRange = 0.25f..4f,
        steps = 14,
    )
    Text("${String.format("%.2f", speedValue)}x", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(16.dp))

    Text("Filters", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    for ((index, filter) in clip.filters.withIndex()) {
        InputChip(
            selected = true,
            onClick = { onRemoveFilter(clip.id, index) },
            label = { Text(filter.type.name) },
            trailingIcon = {
                Icon(
                    Icons.Default.Close, "Remove",
                    modifier = Modifier.size(16.dp)
                )
            },
            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
        )
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("+ Add Filter") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterType.entries.forEach { filterType ->
                DropdownMenuItem(
                    text = { Text(filterType.name) },
                    onClick = {
                        onAddFilter(clip.id, VideoFilter(filterType))
                        expanded = false
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Text("Transition", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    var transExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { transExpanded = true }) {
            Text(clip.transition?.type?.name ?: "None")
        }
        DropdownMenu(expanded = transExpanded, onDismissRequest = { transExpanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = { onSetTransition(clip.id, null); transExpanded = false }
            )
            TransitionType.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name) },
                    onClick = {
                        onSetTransition(clip.id, Transition(t))
                        transExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AudioClipProperties(clip: Clip.AudioClip) {
    Text("Audio Clip", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Volume: ${(clip.volume * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
    )
    Text(
        "Fade in: ${clip.fadeInMs}ms | Fade out: ${clip.fadeOutMs}ms",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun TextClipProperties(clip: Clip.TextClip) {
    Text("Text Overlay", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = clip.text,
        onValueChange = { /* TODO: wire to viewmodel */ },
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("Size: ${clip.fontSizeSp}sp | Color: ${clip.colorHex}", style = MaterialTheme.typography.labelSmall)
}