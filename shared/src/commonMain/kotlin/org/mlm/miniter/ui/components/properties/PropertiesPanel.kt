package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustSepiaFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustFadeInAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFadeOutAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
import org.mlm.miniter.editor.model.RustNormalizeAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustSubtitleClipKind
import org.mlm.miniter.editor.model.RustTextClipKind
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustVideoEffectSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.paramDefOrUnknown

@Composable
fun PropertiesPanel(
    snapshot: RustProjectSnapshot?,
    selectedClipId: String?,
    playheadMs: Long = 0L,
    onAddFilter: (String, RustVideoEffectSnapshot) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?, String?, Float?, Float?, Boolean?, Boolean?) -> Unit,
    onSetTextTransitionIn: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onSetTextTransitionOut: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onSetOpacity: (String, Float) -> Unit = { _, _ -> },
    onAddAudioFilter: (String, RustAudioFilterSnapshot) -> Unit = { _, _ -> },
    onRemoveAudioFilter: (String, Int) -> Unit = { _, _ -> },
    onUpdateAudioFilterDuration: (String, Int, Long) -> Unit = { _, _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
) {
    val clip = snapshot?.timeline?.tracks
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

        when (val kind = clip.kind) {
            is RustVideoClipKind -> VideoClipProperties(
                clip = clip,
                kind = kind,
                playheadMs = playheadMs,
                onAddFilter = onAddFilter,
                onRemoveFilter = onRemoveFilter,
                onUpdateFilterParams = onUpdateFilterParams,
                onToggleFilterEnabled = onToggleFilterEnabled,
                onMoveFilter = onMoveFilter,
                onSetSpeed = onSetSpeed,
                onSetVolume = onSetVolume,
                onSetTransitionIn = onSetTransitionIn,
                onSetTransitionOut = onSetTransitionOut,
                onSetOpacity = onSetOpacity,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
            )
            is RustAudioClipKind -> AudioClipProperties(clip, kind, playheadMs, onSetVolume, onAddAudioFilter, onRemoveAudioFilter, onUpdateAudioFilterDuration, onAddKeyframe)
            is RustTextClipKind -> TextClipProperties(
                clip,
                kind,
                onUpdateText,
                onUpdateTextStyle,
                onSetTextTransitionIn,
                onSetTextTransitionOut,
            )
            is RustSubtitleClipKind -> SubtitleClipProperties(
                clip = clip,
                kind = kind,
                playheadMs = playheadMs,
                onSetOpacity = onSetOpacity,
                onSetTransitionIn = onSetTextTransitionIn,
                onSetTransitionOut = onSetTextTransitionOut,
                onAddKeyframe = onAddKeyframe,
            )
        }
    }
}

@Composable
private fun VideoClipProperties(
    clip: RustClipSnapshot,
    kind: RustVideoClipKind,
    playheadMs: Long = 0L,
    onAddFilter: (String, RustVideoEffectSnapshot) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onSetOpacity: (String, Float) -> Unit = { _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
) {
    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceIn(0L, clip.timelineDurationUs)
    val fileName = kind.sourcePath.substringAfterLast("/").substringAfterLast("\\")
    val clipDurationMs = clip.timelineDurationUs / 1000L
    val clipSpeed = clip.speed.toFloat()
    val clipVolume = clip.volume
    val clipOpacity = clip.opacity
    val clipTransition = clip.transitionIn
    val clipFilters = kind.filters

    Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Duration: ${clipDurationMs / 1000}s | Speed: ${clipSpeed}x",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Text("Speed", style = MaterialTheme.typography.labelMedium)
    var speedValue by remember(clipSpeed) { mutableFloatStateOf(clipSpeed) }
    Slider(
        value = speedValue,
        onValueChange = { speedValue = it },
        onValueChangeFinished = { onSetSpeed(clip.id, speedValue) },
        valueRange = 0.25f..4f,
        steps = 14,
    )
    Text("${formatFixed(speedValue, 2)}x", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Volume", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.VOLUME, clip, clipOffsetUs, onAddKeyframe)
    }
    var volumeValue by remember(clipVolume) { mutableFloatStateOf(clipVolume) }
    Slider(
        value = volumeValue,
        onValueChange = { volumeValue = it },
        onValueChangeFinished = { onSetVolume(clip.id, volumeValue) },
        valueRange = 0f..2f,
        steps = 19,
    )
    Text(paramDefOrUnknown(KeyframeParams.VOLUME).format(volumeValue), style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.OPACITY, clip, clipOffsetUs, onAddKeyframe)
    }
    var opacityValue by remember(clipOpacity) { mutableFloatStateOf(clipOpacity) }
    Slider(
        value = opacityValue,
        onValueChange = { opacityValue = it },
        onValueChangeFinished = { onSetOpacity(clip.id, opacityValue) },
        valueRange = 0f..1f,
    )
    Text(paramDefOrUnknown(KeyframeParams.OPACITY).format(opacityValue), style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(16.dp))

    Text("Filters", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        clipFilters.forEachIndexed { index, effect ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = effect.enabled,
                    onClick = { onToggleFilterEnabled(clip.id, index, !effect.enabled) },
                    label = { Text(effect.displayName()) },
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onMoveFilter(clip.id, index, index - 1) },
                    enabled = index > 0,
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, null)
                }
                IconButton(
                    onClick = { onMoveFilter(clip.id, index, index + 1) },
                    enabled = index < clipFilters.lastIndex,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null)
                }
                IconButton(onClick = { onRemoveFilter(clip.id, index) }) {
                    Icon(Icons.Default.Close, null)
                }
            }
        }
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("+ Add Filter") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            defaultVideoFilters().forEach { filter ->
                DropdownMenuItem(
                    text = { Text(filter.displayName()) },
                    onClick = {
                        onAddFilter(clip.id, RustVideoEffectSnapshot(filter = filter, enabled = true))
                        expanded = false
                    },
                )
            }
        }
    }

    clipFilters.forEachIndexed { index, effect ->
        val filter = effect.filter
        val def = filterDefByType(filter) ?: return@forEachIndexed
        def.properties.forEach { prop ->
            val animParamKey = KeyframeParams.filterParam(index, prop.keyframeSuffix)
            val currentVal = readFilterProperty(filter, prop.paramKey)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${prop.displayName}: ${prop.format(currentVal)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                KeyframeToggle(animParamKey, clip, clipOffsetUs, onAddKeyframe)
            }
            var paramVal by remember(filter, prop.paramKey) { mutableFloatStateOf(currentVal) }
            Slider(
                value = paramVal,
                onValueChange = { paramVal = it },
                onValueChangeFinished = {
                    onUpdateFilterParams(clip.id, index, mapOf(prop.paramKey to paramVal))
                },
                valueRange = prop.range,
                steps = prop.steps,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    KeyframeEditor(
        clip = clip,
        playheadMs = playheadMs,
        onAddKeyframe = { kf -> onAddKeyframe(clip.id, kf) },
        onRemoveKeyframe = { index -> onRemoveKeyframe(clip.id, index) },
        onUpdateKeyframe = { index, kf -> onUpdateKeyframe(clip.id, index, kf) },
    )

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    TransitionSelector(
        label = "Transition In",
        description = "Controls how this clip enters. Adds a fade effect to the start of this clip and the end of the previous clip.",
        currentTransition = clip.transitionIn,
        options = videoTransitionOptions,
        onSetTransition = { onSetTransitionIn(clip.id, it) },
    )

    Spacer(Modifier.height(16.dp))

    TransitionSelector(
        label = "Transition Out",
        description = "Controls how this clip exits. Adds a fade effect to the end of this clip and the start of the next clip.",
        currentTransition = clip.transitionOut,
        options = videoTransitionOptions,
        onSetTransition = { onSetTransitionOut(clip.id, it) },
    )
}

@Composable
private fun AudioClipProperties(
    clip: RustClipSnapshot,
    kind: RustAudioClipKind,
    playheadMs: Long = 0L,
    onSetVolume: (String, Float) -> Unit,
    onAddAudioFilter: (String, RustAudioFilterSnapshot) -> Unit,
    onRemoveAudioFilter: (String, Int) -> Unit,
    onUpdateAudioFilterDuration: (String, Int, Long) -> Unit,
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
) {
    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceIn(0L, clip.timelineDurationUs)
    val clipVolume = clip.volume
    val audioFilters = kind.filters

    Text("Audio Clip", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Volume", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.VOLUME, clip, clipOffsetUs, onAddKeyframe)
    }
    var volumeValue by remember(clipVolume) { mutableFloatStateOf(clipVolume) }
    Slider(
        value = volumeValue,
        onValueChange = { volumeValue = it },
        onValueChangeFinished = { onSetVolume(clip.id, volumeValue) },
        valueRange = 0f..2f,
        steps = 19,
    )
    Text(paramDefOrUnknown(KeyframeParams.VOLUME).format(volumeValue), style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(16.dp))
    Text("Audio Filters", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    var audioFilterExpanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { audioFilterExpanded = true },
            label = { Text("+ Add Audio Filter") },
        )
        DropdownMenu(expanded = audioFilterExpanded, onDismissRequest = { audioFilterExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Fade In") },
                onClick = {
                    onAddAudioFilter(clip.id, RustFadeInAudioFilterSnapshot(500_000L))
                    audioFilterExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Fade Out") },
                onClick = {
                    onAddAudioFilter(clip.id, RustFadeOutAudioFilterSnapshot(500_000L))
                    audioFilterExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Normalize") },
                onClick = {
                    onAddAudioFilter(clip.id, RustNormalizeAudioFilterSnapshot)
                    audioFilterExpanded = false
                },
            )
        }
    }

    audioFilters.forEachIndexed { index, filter ->
        when (filter) {
            is RustFadeInAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Text("Fade In", style = MaterialTheme.typography.labelSmall)
                var fadeInValue by remember(filter.durationUs) { mutableFloatStateOf(filter.durationUs / 1_000_000f) }
                Slider(
                    value = fadeInValue,
                    onValueChange = { fadeInValue = it },
                    onValueChangeFinished = {
                        onUpdateAudioFilterDuration(clip.id, index, (fadeInValue * 1_000_000L).toLong())
                    },
                    valueRange = 0f..3f,
                    steps = 29,
                )
                Text("${formatFixed(fadeInValue, 1)}s", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                    }
                }
            }
            is RustFadeOutAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Text("Fade Out", style = MaterialTheme.typography.labelSmall)
                var fadeOutValue by remember(filter.durationUs) { mutableFloatStateOf(filter.durationUs / 1_000_000f) }
                Slider(
                    value = fadeOutValue,
                    onValueChange = { fadeOutValue = it },
                    onValueChangeFinished = {
                        onUpdateAudioFilterDuration(clip.id, index, (fadeOutValue * 1_000_000L).toLong())
                    },
                    valueRange = 0f..3f,
                    steps = 29,
                )
                Text("${formatFixed(fadeOutValue, 1)}s", style = MaterialTheme.typography.labelSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                    }
                }
            }
            is RustNormalizeAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Normalize", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                    }
                }
            }
            else -> { }
        }
    }
}

@Composable
private fun TextClipProperties(
    clip: RustClipSnapshot,
    kind: RustTextClipKind,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, Float?, String?, String?, Float?, Float?, Boolean?, Boolean?) -> Unit,
    onSetTextTransitionIn: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onSetTextTransitionOut: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
) {
    val style = kind.style
    val transitionIn = clip.transitionIn
    val transitionOut = clip.transitionOut

    Text("Text Overlay", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))

    var textValue by remember(clip.id) { mutableStateOf(kind.text) }
    OutlinedTextField(
        value = textValue,
        onValueChange = { textValue = it },
        label = { Text("Text") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 3,
    )
    LaunchedEffect(textValue) {
        if (textValue != kind.text) onUpdateText(clip.id, textValue)
    }
    DisposableEffect(clip.id) {
        onDispose {
            if (textValue != kind.text) onUpdateText(clip.id, textValue)
        }
    }

    Spacer(Modifier.height(16.dp))

    Text("Position", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    val presets = listOf(
        "↖" to (0.1f to 0.1f),
        "↑" to (0.5f to 0.05f),
        "↗" to (0.9f to 0.1f),
        "←" to (0.1f to 0.5f),
        "•" to (0.5f to 0.5f),
        "→" to (0.9f to 0.5f),
        "↙" to (0.1f to 0.9f),
        "↓" to (0.5f to 0.9f),
        "↘" to (0.9f to 0.9f),
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (col in 0..2) {
                    val (label, pos) = presets[row * 3 + col]
                    val isSelected = kotlin.math.abs(style.positionX - pos.first) < 0.15f &&
                            kotlin.math.abs(style.positionY - pos.second) < 0.15f
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onUpdateTextStyle(clip.id, null, null, null, pos.first, pos.second, null, null)
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Text("X: ${formatFixed(style.positionX * 100f, 0)}%", style = MaterialTheme.typography.labelSmall)
    var posX by remember(style.positionX) { mutableFloatStateOf(style.positionX) }
    Slider(
        value = posX,
        onValueChange = { posX = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, null, null, null, posX, null, null, null) },
        valueRange = 0f..1f,
        modifier = Modifier.height(28.dp),
    )

    Text("Y: ${formatFixed(style.positionY * 100f, 0)}%", style = MaterialTheme.typography.labelSmall)
    var posY by remember(style.positionY) { mutableFloatStateOf(style.positionY) }
    Slider(
        value = posY,
        onValueChange = { posY = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, null, null, null, null, posY, null, null) },
        valueRange = 0f..1f,
        modifier = Modifier.height(28.dp),
    )

    Spacer(Modifier.height(12.dp))

    Text("Size", style = MaterialTheme.typography.labelMedium)
    var fontSize by remember(style.fontSize) { mutableFloatStateOf(style.fontSize) }
    Slider(
        value = fontSize,
        onValueChange = { fontSize = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, fontSize, null, null, null, null, null, null) },
        valueRange = 12f..120f,
        steps = 26,
    )
    Text("${fontSize.toInt()}sp", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(12.dp))

    Text("Style", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = style.bold,
            onClick = { onUpdateTextStyle(clip.id, null, null, null, null, null, !style.bold, null) },
            label = { Text("B", fontWeight = FontWeight.ExtraBold) },
        )
        FilterChip(
            selected = style.italic,
            onClick = { onUpdateTextStyle(clip.id, null, null, null, null, null, null, !style.italic) },
            label = { Text("I", fontStyle = FontStyle.Italic) },
        )
        FilterChip(
            selected = style.backgroundColor != null,
            onClick = {
                val newBg = if (style.backgroundColor != null) null else "#000000"
                onUpdateTextStyle(clip.id, null, null, newBg, null, null, null, null)
            },
            label = { Text("BG") },
        )
    }

    Spacer(Modifier.height(12.dp))

    Text("Color", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    val colors = listOf(
        "#FFFFFF", "#000000", "#FF0000", "#FFFF00",
        "#00FF00", "#00BFFF", "#FF69B4", "#FFA500",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        colors.forEach { hex ->
            val c = try {
                val v = hex.removePrefix("#").toLong(16) or 0xFF000000
                androidx.compose.ui.graphics.Color(v.toInt())
            } catch (_: Exception) {
                androidx.compose.ui.graphics.Color.White
            }
            Surface(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onUpdateTextStyle(clip.id, null, hex, null, null, null, null, null) },
                shape = CircleShape,
                color = c,
                border = if (style.color.removePrefix("FF").let { "#$it" }.equals(hex, ignoreCase = true))
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {}
        }
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    TransitionSelector(
        label = "Fade In",
        currentTransition = transitionIn,
        options = fadeTransitionOptions,
        durationRange = 0.1f..3f,
        durationSteps = 28,
        onSetTransition = { onSetTextTransitionIn(clip.id, it) },
    )

    Spacer(Modifier.height(12.dp))

    TransitionSelector(
        label = "Fade Out",
        currentTransition = transitionOut,
        options = fadeTransitionOptions,
        durationRange = 0.1f..3f,
        durationSteps = 28,
        onSetTransition = { onSetTextTransitionOut(clip.id, it) },
    )
}

private fun defaultVideoFilters(): List<RustVideoFilterSnapshot> = listOf(
    RustTransformFilterSnapshot(scale = 1f, translateX = 0.5f, translateY = 0.5f, rotate = 0f),
    RustBrightnessFilterSnapshot(0f),
    RustContrastFilterSnapshot(1f),
    RustSaturationFilterSnapshot(1f),
    RustGrayscaleFilterSnapshot,
    RustBlurFilterSnapshot(5f),
    RustSharpenFilterSnapshot(1f),
    RustSepiaFilterSnapshot,
)

@Composable
private fun SubtitleClipProperties(
    clip: RustClipSnapshot,
    kind: RustSubtitleClipKind,
    playheadMs: Long = 0L,
    onSetOpacity: (String, Float) -> Unit,
    onSetTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
) {
    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceIn(0L, clip.timelineDurationUs)
    var opacityValue by remember(clip.opacity) { mutableFloatStateOf(clip.opacity) }
    var summary by remember(kind.sourcePath) { mutableStateOf<AssFeatureSummary?>(null) }
    var loadError by remember(kind.sourcePath) { mutableStateOf<String?>(null) }
    var loading by remember(kind.sourcePath) { mutableStateOf(true) }

    val transitionIn = clip.transitionIn
    val transitionOut = clip.transitionOut

    LaunchedEffect(kind.sourcePath) {
        loading = true
        loadError = null
        summary = null
        try {
            val content = PlatformFileSystem.readText(kind.sourcePath)
            summary = parseAssFeatureSummary(content)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to read subtitle file"
        } finally {
            loading = false
        }
    }

    Text("Subtitle Clip", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    val fileName = kind.sourcePath.substringAfterLast("/").substringAfterLast("\\")

    Text(
        "Source: $fileName",
        style = MaterialTheme.typography.labelMedium,
    )

    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.OPACITY, clip, clipOffsetUs, onAddKeyframe)
    }
    Slider(
        value = opacityValue,
        onValueChange = { opacityValue = it },
        onValueChangeFinished = { onSetOpacity(clip.id, opacityValue) },
        valueRange = 0f..1f,
    )
    Text(paramDefOrUnknown(KeyframeParams.OPACITY).format(opacityValue), style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(12.dp))

    TransitionSelector(
        label = "Fade In",
        currentTransition = transitionIn,
        options = subtitleTransitionOptions,
        onSetTransition = { onSetTransitionIn(clip.id, it) },
    )

    Spacer(Modifier.height(8.dp))

    TransitionSelector(
        label = "Fade Out",
        currentTransition = transitionOut,
        options = subtitleTransitionOptions,
        onSetTransition = { onSetTransitionOut(clip.id, it) },
    )

    Spacer(Modifier.height(16.dp))

    Text("ASS Features", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    when {
        loading -> Text("Analyzing subtitle file...", style = MaterialTheme.typography.labelSmall)
        loadError != null -> Text("Could not inspect file: $loadError", style = MaterialTheme.typography.labelSmall)
        summary != null -> {
            val s = summary!!
            Text("Script: ${s.scriptType ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
            if (s.playResX != null && s.playResY != null) {
                Text("Script resolution: ${s.playResX}x${s.playResY}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Styles: ${s.styleCount} | Dialogue: ${s.dialogueCount} | Comments: ${s.commentCount}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Karaoke: ${s.karaokeTags} | Animations: ${s.transformTags} | Position tags: ${s.posTags + s.moveTags}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Clipping: ${s.clipTags} | Drawings: ${s.drawingTags}",
                style = MaterialTheme.typography.labelSmall,
            )
            if (!s.looksLikeAss) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "This file does not look like ASS/SSA. Export rendering may fail.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Text(
        "Timeline start: ${clip.timelineStartUs / 1000}ms",
        style = MaterialTheme.typography.labelSmall,
    )
}

private data class AssFeatureSummary(
    val looksLikeAss: Boolean,
    val scriptType: String?,
    val playResX: Int?,
    val playResY: Int?,
    val styleCount: Int,
    val dialogueCount: Int,
    val commentCount: Int,
    val karaokeTags: Int,
    val transformTags: Int,
    val posTags: Int,
    val moveTags: Int,
    val clipTags: Int,
    val drawingTags: Int,
)

private fun parseAssFeatureSummary(content: String): AssFeatureSummary {
    val normalized = content.replace("\r\n", "\n")
    val looksLikeAss = normalized.contains("[Script Info]", ignoreCase = true) ||
        normalized.contains("ScriptType:", ignoreCase = true)

    val scriptType = Regex("""(?im)^ScriptType\s*:\s*(.+)$""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
    val playResX = Regex("""(?im)^PlayResX\s*:\s*(\d+)\s*$""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val playResY = Regex("""(?im)^PlayResY\s*:\s*(\d+)\s*$""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    fun Regex.countMatches(): Int = findAll(normalized).count()

    return AssFeatureSummary(
        looksLikeAss = looksLikeAss,
        scriptType = scriptType,
        playResX = playResX,
        playResY = playResY,
        styleCount = Regex("""(?im)^Style\s*:""").countMatches(),
        dialogueCount = Regex("""(?im)^Dialogue\s*:""").countMatches(),
        commentCount = Regex("""(?im)^Comment\s*:""").countMatches(),
        karaokeTags = Regex("""\\(?:k|K|kf|ko|kt)\d+""", RegexOption.IGNORE_CASE).countMatches(),
        transformTags = Regex("""\\t\(""", RegexOption.IGNORE_CASE).countMatches(),
        posTags = Regex("""\\pos\(""", RegexOption.IGNORE_CASE).countMatches(),
        moveTags = Regex("""\\move\(""", RegexOption.IGNORE_CASE).countMatches(),
        clipTags = Regex("""\\i?clip\(""", RegexOption.IGNORE_CASE).countMatches(),
        drawingTags = Regex("""\\p\d+""", RegexOption.IGNORE_CASE).countMatches(),
    )
}

private fun RustVideoFilterSnapshot.displayName(): String =
    filterDefByType(this)?.displayName ?: when (this) {
        RustGrayscaleFilterSnapshot -> "Grayscale"
        RustSepiaFilterSnapshot -> "Sepia"
        else -> "Filter"
    }

private fun RustVideoEffectSnapshot.displayName(): String = filter.displayName()

@Composable
private fun KeyframeToggle(
    paramKey: String,
    clip: RustClipSnapshot,
    clipOffsetUs: Long,
    onAddKeyframe: (String, RustKeyframe) -> Unit,
) {
    val hasKeyframes = clip.keyframes.keyframes.any { it.param == paramKey }
    val primary = MaterialTheme.colorScheme.primary

    IconButton(
        onClick = {
            onAddKeyframe(clip.id, RustKeyframe(
                param = paramKey,
                offset = clipOffsetUs.coerceAtLeast(0L),
                value = currentValueForParam(clip, paramKey),
                easing = RustEasing.EaseInOut,
            ))
        },
        modifier = Modifier.size(24.dp),
    ) {
        Icon(
            Icons.Default.Diamond,
            contentDescription = "Toggle keyframe",
            modifier = Modifier.size(14.dp),
            tint = if (hasKeyframes) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

internal fun formatFixed(value: Float, decimals: Int): String {
    val safeDecimals = decimals.coerceAtLeast(0)
    var scale = 1
    repeat(safeDecimals) { scale *= 10 }

    val negative = value < 0f
    val rounded = kotlin.math.round(kotlin.math.abs(value) * scale).toInt()
    val intPart = rounded / scale

    if (safeDecimals == 0) {
        return (if (negative) "-" else "") + intPart.toString()
    }

    val fracPart = rounded % scale
    return (if (negative) "-" else "") + intPart.toString() + "." + fracPart.toString().padStart(safeDecimals, '0')
}
