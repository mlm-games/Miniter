package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BlurOn
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
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransition: (String, Transition?) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?) -> Unit,
    onSetOpacity: (String, Float) -> Unit = { _, _ -> },
    onSetTextDuration: (String, Long) -> Unit = { _, _ -> },
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
                clip, onAddFilter, onRemoveFilter, onUpdateFilterParams, onSetSpeed, onSetVolume, onSetTransition, onSetOpacity
            )
            is Clip.AudioClip -> AudioClipProperties(clip, onSetVolume)
            is Clip.TextClip -> TextClipProperties(clip, onUpdateText, onUpdateTextStyle, onSetTextDuration)
        }
    }
}

@Composable
private fun VideoClipProperties(
    clip: Clip.VideoClip,
    onAddFilter: (String, VideoFilter) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransition: (String, Transition?) -> Unit,
    onSetOpacity: (String, Float) -> Unit = { _, _ -> },
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

    Text("Opacity", style = MaterialTheme.typography.labelMedium)
    var opacityValue by remember(clip.opacity) { mutableFloatStateOf(clip.opacity) }
    Slider(
        value = opacityValue,
        onValueChange = { opacityValue = it },
        onValueChangeFinished = { onSetOpacity(clip.id, opacityValue) },
        valueRange = 0f..1f,
    )
    Text("${(opacityValue * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)

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
                Text("${filter.type.name}: ${String.format("%.1f", paramVal)}",
                    style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = paramVal,
                    onValueChange = { paramVal = it },
                    onValueChangeFinished = {
                        onUpdateFilterParams(clip.id, index, mapOf(paramKey to paramVal))
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
                        onUpdateFilterParams(clip.id, index, mapOf("radius" to radiusVal))
                    },
                    valueRange = 1f..20f,
                    steps = 18,
                )
            }
            else -> { }
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("Transition In", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "Controls how this clip enters. Adds a fade effect to the start of this clip and the end of the previous clip.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    var transExpanded by remember { mutableStateOf(false) }
    val currentTransition = clip.transition

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            OutlinedButton(onClick = { transExpanded = true }) {
                Icon(
                    when (currentTransition?.type) {
                        TransitionType.CrossFade -> Icons.Default.Animation
                        TransitionType.Dissolve -> Icons.Default.BlurOn
                        TransitionType.SlideLeft -> Icons.AutoMirrored.Filled.ArrowBack
                        TransitionType.SlideRight -> Icons.AutoMirrored.Filled.ArrowForward
                        null -> Icons.Default.Block
                    },
                    null,
                    Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(currentTransition?.type?.name ?: "None")
            }
            DropdownMenu(expanded = transExpanded, onDismissRequest = { transExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("None") },
                    leadingIcon = { Icon(Icons.Default.Block, null, Modifier.size(18.dp)) },
                    onClick = { onSetTransition(clip.id, null); transExpanded = false },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Cross Fade")
                            Text("Fades to/from black between clips",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Animation, null, Modifier.size(18.dp)) },
                    onClick = {
                        onSetTransition(clip.id, Transition(TransitionType.CrossFade))
                        transExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Dissolve")
                            Text("Smooth dissolve between clips",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.BlurOn, null, Modifier.size(18.dp)) },
                    onClick = {
                        onSetTransition(clip.id, Transition(TransitionType.Dissolve))
                        transExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Slide Left")
                            Text("Previous slides out left, this enters from right",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)) },
                    onClick = {
                        onSetTransition(clip.id, Transition(TransitionType.SlideLeft))
                        transExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Slide Right")
                            Text("Previous slides out right, this enters from left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp)) },
                    onClick = {
                        onSetTransition(clip.id, Transition(TransitionType.SlideRight))
                        transExpanded = false
                    },
                )
            }
        }
    }

    if (currentTransition != null) {
        Spacer(Modifier.height(8.dp))
        Text("Duration", style = MaterialTheme.typography.labelSmall)
        var transDuration by remember(currentTransition.durationMs) {
            mutableFloatStateOf(currentTransition.durationMs / 1000f)
        }
        Slider(
            value = transDuration,
            onValueChange = { transDuration = it },
            onValueChangeFinished = {
                onSetTransition(
                    clip.id,
                    currentTransition.copy(durationMs = (transDuration * 1000).toLong())
                )
            },
            valueRange = 0.1f..2f,
            steps = 18,
        )
        Text("${String.format("%.1f", transDuration)}s", style = MaterialTheme.typography.labelSmall)

        Spacer(Modifier.height(4.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Previous clip indicator
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.weight(1f).height(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("← Previous", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.width(40.dp).height(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("↔", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.weight(1f).height(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("This →", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
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
    onSetTextDuration: (String, Long) -> Unit = { _, _ -> },
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

    Spacer(Modifier.height(12.dp))

    Text("Duration", style = MaterialTheme.typography.labelMedium)
    var durationSec by remember(clip.durationMs) {
        mutableFloatStateOf(clip.durationMs / 1000f)
    }
    Slider(
        value = durationSec,
        onValueChange = { durationSec = it },
        onValueChangeFinished = {
            onSetTextDuration(clip.id, (durationSec * 1000).toLong())
        },
        valueRange = 0.5f..30f,
        steps = 58,
    )
    Text("${String.format("%.1f", durationSec)}s", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(8.dp))
    Text(
        "Position: (${String.format("%.1f", clip.positionX)}, ${String.format("%.1f", clip.positionY)})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
