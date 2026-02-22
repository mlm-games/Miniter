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
    onSetVolume: (String, Float) -> Unit,
    onSetTransition: (String, Transition?) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?) -> Unit,
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
            is Clip.VideoClip -> VideoClipProperties(
                clip, onAddFilter, onRemoveFilter, onSetSpeed, onSetVolume, onSetTransition
            )
            is Clip.AudioClip -> AudioClipProperties(clip, onSetVolume)
            is Clip.TextClip -> TextClipProperties(clip, onUpdateText, onUpdateTextStyle)
        }
    }
}

@Composable
private fun VideoClipProperties(
    clip: Clip.VideoClip,
    onAddFilter: (String, VideoFilter) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
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

    Text("Volume", style = MaterialTheme.typography.labelMedium)
    var volumeValue by remember(clip.volume) { mutableFloatStateOf(clip.volume) }
    Slider(
        value = volumeValue,
        onValueChange = { volumeValue = it },
        onValueChangeFinished = { onSetVolume(clip.id, volumeValue) },
        valueRange = 0f..2f,
        steps = 19,
    )
    Text("${(volumeValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(16.dp))

    Text("Filters", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    for ((index, filter) in clip.filters.withIndex()) {
        InputChip(
            selected = true,
            onClick = { onRemoveFilter(clip.id, index) },
            label = { Text(filter.type.name) },
            trailingIcon = {
                Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
            },
            modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
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
                    },
                )
            }
        }
    }

    clip.filters.forEachIndexed { index, filter ->
        when (filter.type) {
            FilterType.Brightness, FilterType.Contrast, FilterType.Saturation -> {
                val paramKey = "value"
                val currentVal = filter.params[paramKey] ?: when (filter.type) {
                    FilterType.Brightness -> 0f
                    FilterType.Contrast -> 1f
                    FilterType.Saturation -> 1f
                    else -> 0f
                }
                val range = when (filter.type) {
                    FilterType.Brightness -> -100f..100f
                    FilterType.Contrast -> 0f..3f
                    FilterType.Saturation -> 0f..3f
                    else -> 0f..1f
                }
                var paramVal by remember(filter) { mutableFloatStateOf(currentVal) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${filter.type.name}: ${String.format("%.1f", paramVal)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = paramVal,
                    onValueChange = { paramVal = it },
                    onValueChangeFinished = {
                        onRemoveFilter(clip.id, index)
                        onAddFilter(clip.id, filter.copy(params = mapOf(paramKey to paramVal)))
                    },
                    valueRange = range,
                )
            }
            FilterType.Blur -> {
                val currentRadius = filter.params["radius"] ?: 5f
                var radiusVal by remember(filter) { mutableFloatStateOf(currentRadius) }
                Spacer(Modifier.height(8.dp))
                Text("Blur radius: ${radiusVal.toInt()}", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = radiusVal,
                    onValueChange = { radiusVal = it },
                    onValueChangeFinished = {
                        onRemoveFilter(clip.id, index)
                        onAddFilter(clip.id, filter.copy(params = mapOf("radius" to radiusVal)))
                    },
                    valueRange = 1f..20f,
                    steps = 18,
                )
            }
            else -> { }
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
                onClick = { onSetTransition(clip.id, null); transExpanded = false },
            )
            TransitionType.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name) },
                    onClick = {
                        onSetTransition(clip.id, Transition(t))
                        transExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioClipProperties(
    clip: Clip.AudioClip,
    onSetVolume: (String, Float) -> Unit,
) {
    Text("Audio Clip", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))

    Text("Volume", style = MaterialTheme.typography.labelMedium)
    var volumeValue by remember(clip.volume) { mutableFloatStateOf(clip.volume) }
    Slider(
        value = volumeValue,
        onValueChange = { volumeValue = it },
        onValueChangeFinished = { onSetVolume(clip.id, volumeValue) },
        valueRange = 0f..2f,
        steps = 19,
    )
    Text("${(volumeValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(8.dp))
    Text(
        "Fade in: ${clip.fadeInMs}ms | Fade out: ${clip.fadeOutMs}ms",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun TextClipProperties(
    clip: Clip.TextClip,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?) -> Unit,
) {
    Text("Text Overlay", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))

    var textValue by remember(clip.text) { mutableStateOf(clip.text) }
    OutlinedTextField(
        value = textValue,
        onValueChange = { textValue = it },
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 3,
    )
    LaunchedEffect(textValue) {
        kotlinx.coroutines.delay(500)
        if (textValue != clip.text) {
            onUpdateText(clip.id, textValue)
        }
    }

    Spacer(Modifier.height(12.dp))

    Text("Font Size", style = MaterialTheme.typography.labelMedium)
    var fontSize by remember(clip.fontSizeSp) { mutableFloatStateOf(clip.fontSizeSp) }
    Slider(
        value = fontSize,
        onValueChange = { fontSize = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, fontSize, null) },
        valueRange = 12f..72f,
        steps = 14,
    )
    Text("${fontSize.toInt()}sp", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(8.dp))

    Text("Color", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val colorPresets = listOf(
            "#FFFFFF" to "White",
            "#000000" to "Black",
            "#FF0000" to "Red",
            "#FFFF00" to "Yellow",
            "#00FF00" to "Green",
            "#00BFFF" to "Blue",
        )
        colorPresets.forEach { (hex, name) ->
            FilterChip(
                selected = clip.colorHex.equals(hex, ignoreCase = true),
                onClick = { onUpdateTextStyle(clip.id, null, hex) },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Position: (${String.format("%.1f", clip.positionX)}, ${String.format("%.1f", clip.positionY)})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
