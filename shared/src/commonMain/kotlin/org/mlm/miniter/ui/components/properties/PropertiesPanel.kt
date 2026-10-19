package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import miniter.shared.generated.resources.Res
import miniter.shared.generated.resources.common_remove
import org.jetbrains.compose.resources.stringResource
import io.github.aakira.napier.Napier
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.SupportedFormats
import org.mlm.miniter.platform.platformPath
import org.mlm.miniter.engine.CubeLutKind
import org.mlm.miniter.engine.parseCubeLut
import org.mlm.miniter.editor.model.RustAdjustmentClipKind
import org.mlm.miniter.editor.model.RustAudioClipKind
import org.mlm.miniter.editor.model.RustAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustCanvasBackgroundFilterSnapshot
import org.mlm.miniter.editor.model.RustChromaKeyFilterSnapshot
import org.mlm.miniter.editor.model.RustColorGradeFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustCropFilterSnapshot
import org.mlm.miniter.editor.model.RustEqualizerAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustMosaicFilterSnapshot
import org.mlm.miniter.editor.model.RustReverbAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFlipFilterSnapshot
import org.mlm.miniter.editor.model.RustHueFilterSnapshot
import org.mlm.miniter.editor.model.RustLutFilterSnapshot
import org.mlm.miniter.editor.model.RustRotateFilterSnapshot
import org.mlm.miniter.editor.model.RustReverseVideoFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustSepiaFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustDenoiseAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustDuckingAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFadeInAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustFadeOutAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustGrayscaleFilterSnapshot
import org.mlm.miniter.editor.model.RustNormalizeAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustReverseAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustSubtitleClipKind
import org.mlm.miniter.editor.model.RustTextClipKind
import org.mlm.miniter.editor.model.RustTextStyleUpdate
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustTransitionKind
import org.mlm.miniter.editor.model.RustVideoClipKind

import org.mlm.miniter.editor.model.RustBlendMode
import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustMaskEffect
import org.mlm.miniter.editor.model.RustMaskMode
import org.mlm.miniter.editor.model.RustMaskOperation
import org.mlm.miniter.editor.model.RustMaskPoint
import org.mlm.miniter.editor.model.RustMaskSource
import org.mlm.miniter.editor.model.RustMaskTransform
import org.mlm.miniter.editor.model.RustMaskVertex
import org.mlm.miniter.editor.model.RustEllipseMaskShape
import org.mlm.miniter.editor.model.RustPathMaskShape
import org.mlm.miniter.editor.model.RustRectangleMaskShape
import org.mlm.miniter.editor.model.RustShapeMaskSource
import org.mlm.miniter.editor.model.RustVideoEffectSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.editor.model.RustVignetteFilterSnapshot
import org.mlm.miniter.editor.model.RustPosterizeFilterSnapshot
import org.mlm.miniter.editor.model.RustDuotoneFilterSnapshot
import org.mlm.miniter.editor.model.RustGrainFilterSnapshot
import org.mlm.miniter.project.KenBurnsPreset
import org.mlm.miniter.project.SpeedCurvePreset
import org.mlm.miniter.project.TextAnimationMode
import org.mlm.miniter.project.TextAnimationPreset
import org.mlm.miniter.project.kenBurnsPresets
import org.mlm.miniter.project.randomKenBurnsPreset
import org.mlm.miniter.project.speedCurvePresets
import org.mlm.miniter.project.speedPresets
import org.mlm.miniter.project.textAnimationPresetsFor
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.defaultOf
import org.mlm.miniter.project.paramDefOrUnknown

@Composable
fun PropertiesPanel(
    snapshot: RustProjectSnapshot?,
    selectedClipId: String?,
    playheadMs: Long = 0L,
    onAddFilter: (String, RustVideoEffectSnapshot) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onApplyFilterToAll: (String, Int) -> Unit = { _, _ -> },
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onReplaceFilter: (String, Int, RustVideoFilterSnapshot) -> Unit = { _, _, _ -> },
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onApplyTransitionToAll: (RustTransitionKind, Long) -> Unit = { _, _ -> },
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, RustTextStyleUpdate) -> Unit,
    onSetTextTransitionIn: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onSetTextTransitionOut: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onSetOpacity: (String, Float) -> Unit = { _, _ -> },
    onSetBlendMode: (String, RustBlendMode) -> Unit = { _, _ -> },
    onApplyKenBurns: (String, KenBurnsPreset) -> Unit = { _, _ -> },
    onApplySpeedCurve: (String, SpeedCurvePreset?) -> Unit = { _, _ -> },
    onFitToCanvas: (String, Boolean) -> Unit = { _, _ -> },
    onAutoReframe: (String) -> Unit = { _ -> },
    autoReframeRunning: Boolean = false,
    onStabilize: (String) -> Unit = { _ -> },
    stabilizeRunning: Boolean = false,
    onFreezeFrame: (String, Float) -> Unit = { _, _ -> },
    onRemoveSilence: (String) -> Unit = { _ -> },
    removeSilenceRunning: Boolean = false,
    onApplyTextAnimation: (String, TextAnimationPreset) -> Unit = { _, _ -> },
    onAddAudioFilter: (String, RustAudioFilterSnapshot) -> Unit = { _, _ -> },
    onRemoveAudioFilter: (String, Int) -> Unit = { _, _ -> },
    onUpdateAudioFilterDuration: (String, Int, Long) -> Unit = { _, _, _ -> },
    onReplaceAudioFilter: (String, Int, RustAudioFilterSnapshot) -> Unit = { _, _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
    onSetSubtitleFont: (String, String?) -> Unit = { _, _ -> },
    onTrackMask: (String, Int) -> Unit = { _, _ -> },
    maskTrackRunning: Boolean = false,
    onAddMask: (String, RustMaskEffect) -> Unit = { _, _ -> },
    onRemoveMask: (String, Int) -> Unit = { _, _ -> },
    onUpdateMask: (String, Int, RustMaskEffect) -> Unit = { _, _, _ -> },
    onSetMaskEnabled: (String, Int, Boolean) -> Unit = { _, _, _ -> },
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
                onApplyFilterToAll = onApplyFilterToAll,
                onUpdateFilterParams = onUpdateFilterParams,
                onReplaceFilter = onReplaceFilter,
                onToggleFilterEnabled = onToggleFilterEnabled,
                onMoveFilter = onMoveFilter,
                onSetSpeed = onSetSpeed,
                onSetVolume = onSetVolume,
                onSetTransitionIn = onSetTransitionIn,
                onSetTransitionOut = onSetTransitionOut,
                onApplyTransitionToAll = onApplyTransitionToAll,
                onSetOpacity = onSetOpacity,
                onSetBlendMode = onSetBlendMode,
                onApplyKenBurns = onApplyKenBurns,
                onApplySpeedCurve = onApplySpeedCurve,
                onFitToCanvas = onFitToCanvas,
                onAutoReframe = onAutoReframe,
                autoReframeRunning = autoReframeRunning,
                onStabilize = onStabilize,
                stabilizeRunning = stabilizeRunning,
                onFreezeFrame = onFreezeFrame,
                onRemoveSilence = onRemoveSilence,
                removeSilenceRunning = removeSilenceRunning,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
                onTrackMask = onTrackMask,
                maskTrackRunning = maskTrackRunning,
                onAddMask = onAddMask,
                onRemoveMask = onRemoveMask,
                onUpdateMask = onUpdateMask,
                onSetMaskEnabled = onSetMaskEnabled,
            )
            is RustAudioClipKind -> AudioClipProperties(
                clip = clip,
                kind = kind,
                playheadMs = playheadMs,
                onSetVolume = onSetVolume,
                onAddAudioFilter = onAddAudioFilter,
                onRemoveAudioFilter = onRemoveAudioFilter,
                onUpdateAudioFilterDuration = onUpdateAudioFilterDuration,
                onReplaceAudioFilter = onReplaceAudioFilter,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
            )
            is RustTextClipKind -> TextClipProperties(
                clip = clip,
                kind = kind,
                playheadMs = playheadMs,
                onUpdateText = onUpdateText,
                onUpdateTextStyle = onUpdateTextStyle,
                onSetTextTransitionIn = onSetTextTransitionIn,
                onSetTextTransitionOut = onSetTextTransitionOut,
                onApplyTextAnimation = onApplyTextAnimation,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
            )
            is RustSubtitleClipKind -> SubtitleClipProperties(
                clip = clip,
                kind = kind,
                playheadMs = playheadMs,
                onSetOpacity = onSetOpacity,
                onSetTransitionIn = onSetTextTransitionIn,
                onSetTransitionOut = onSetTextTransitionOut,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
                onSetSubtitleFont = onSetSubtitleFont,
            )
            is RustAdjustmentClipKind -> AdjustmentClipProperties(
                clip = clip,
                kind = kind,
                playheadMs = playheadMs,
                onAddFilter = onAddFilter,
                onRemoveFilter = onRemoveFilter,
                onApplyFilterToAll = onApplyFilterToAll,
                onUpdateFilterParams = onUpdateFilterParams,
                onReplaceFilter = onReplaceFilter,
                onToggleFilterEnabled = onToggleFilterEnabled,
                onMoveFilter = onMoveFilter,
                onAddMask = onAddMask,
                onRemoveMask = onRemoveMask,
                onUpdateMask = onUpdateMask,
                onSetMaskEnabled = onSetMaskEnabled,
                onAddKeyframe = onAddKeyframe,
                onRemoveKeyframe = onRemoveKeyframe,
                onUpdateKeyframe = onUpdateKeyframe,
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
    onApplyFilterToAll: (String, Int) -> Unit = { _, _ -> },
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onReplaceFilter: (String, Int, RustVideoFilterSnapshot) -> Unit = { _, _, _ -> },
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onSetSpeed: (String, Float) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    onSetTransitionIn: (String, RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (String, RustTransitionSnapshot?) -> Unit,
    onApplyTransitionToAll: (RustTransitionKind, Long) -> Unit = { _, _ -> },
    onSetOpacity: (String, Float) -> Unit = { _, _ -> },
    onSetBlendMode: (String, RustBlendMode) -> Unit = { _, _ -> },
    onApplyKenBurns: (String, KenBurnsPreset) -> Unit = { _, _ -> },
    onApplySpeedCurve: (String, SpeedCurvePreset?) -> Unit = { _, _ -> },
    onFitToCanvas: (String, Boolean) -> Unit = { _, _ -> },
    onAutoReframe: (String) -> Unit = { _ -> },
    autoReframeRunning: Boolean = false,
    onStabilize: (String) -> Unit = { _ -> },
    stabilizeRunning: Boolean = false,
    onFreezeFrame: (String, Float) -> Unit = { _, _ -> },
    onRemoveSilence: (String) -> Unit = { _ -> },
    removeSilenceRunning: Boolean = false,
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
    onTrackMask: (String, Int) -> Unit = { _, _ -> },
    maskTrackRunning: Boolean = false,
    onAddMask: (String, RustMaskEffect) -> Unit = { _, _ -> },
    onRemoveMask: (String, Int) -> Unit = { _, _ -> },
    onUpdateMask: (String, Int, RustMaskEffect) -> Unit = { _, _, _ -> },
    onSetMaskEnabled: (String, Int, Boolean) -> Unit = { _, _, _ -> },
) {
    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceIn(0L, clip.timelineDurationUs)
    val fileName = kind.sourcePath.substringAfterLast("/").substringAfterLast("\\")
    val clipDurationMs = clip.timelineDurationUs / 1000L
    val clipSpeed = clip.speed.toFloat()
    val clipVolume = clip.volume
    val clipOpacity = clip.opacity
    val clipTransition = clip.transitionIn
    val clipFilters = kind.filters
    var showFreezeDialog by remember(clip.id) { mutableStateOf(false) }
    var freezeSeconds by remember(clip.id) { mutableFloatStateOf(2f) }
    val playheadUs = playheadMs * 1000L
    val freezeEnabled = kind.fps.isFinite() && kind.fps > 0.0 &&
        playheadUs >= clip.timelineStartUs &&
        playheadUs < clip.timelineStartUs + clip.timelineDurationUs
    val trackEnabled = !maskTrackRunning && !autoReframeRunning && !stabilizeRunning && !removeSilenceRunning

    Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Duration: ${clipDurationMs / 1000}s | Speed: ${clipSpeed}x",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Speed", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.SPEED, clip, clipOffsetUs, onAddKeyframe)
    }
    var speedValue by remember(clipSpeed) { mutableFloatStateOf(clipSpeed) }
    Slider(
        value = speedValue,
        onValueChange = { speedValue = it },
        onValueChangeFinished = { onSetSpeed(clip.id, speedValue) },
        valueRange = 0.25f..4f,
        steps = 14,
    )
    Text("${formatFixed(speedValue, 2)}x", style = MaterialTheme.typography.labelSmall)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        speedPresets.forEach { (factor, label) ->
            AssistChip(
                onClick = { onSetSpeed(clip.id, factor) },
                label = { Text(label) },
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Speed curve",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        speedCurvePresets.forEach { preset ->
            AssistChip(
                onClick = { onApplySpeedCurve(clip.id, preset) },
                label = { Text(preset.label) },
            )
        }
        AssistChip(
            onClick = { onApplySpeedCurve(clip.id, null) },
            label = { Text("None") },
        )
    }

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

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasFadeIn = clip.transitionIn?.kind == RustTransitionKind.CrossFade
        AssistChip(
            onClick = {
                onSetTransitionIn(
                    clip.id,
                    if (hasFadeIn) null
                    else RustTransitionSnapshot(RustTransitionKind.CrossFade, 500_000L),
                )
            },
            label = { Text(if (hasFadeIn) "Remove fade in" else "Fade in from black") },
        )
        val hasFadeOut = clip.transitionOut?.kind == RustTransitionKind.CrossFade
        AssistChip(
            onClick = {
                onSetTransitionOut(
                    clip.id,
                    if (hasFadeOut) null
                    else RustTransitionSnapshot(RustTransitionKind.CrossFade, 500_000L),
                )
            },
            label = { Text(if (hasFadeOut) "Remove fade out" else "Fade out to black") },
        )
    }
    Spacer(Modifier.height(8.dp))
    FadeTransitionsPair(
        transitionIn = clip.transitionIn,
        transitionOut = clip.transitionOut,
        onSetTransitionIn = { onSetTransitionIn(clip.id, it) },
        onSetTransitionOut = { onSetTransitionOut(clip.id, it) },
        options = videoTransitionOptions,
        durationRange = 0.1f..2f,
        durationSteps = 18,
        inLabel = "Transition In",
        outLabel = "Transition Out",
        inDescription = "Controls how this clip enters. Adds a fade effect to the start of this clip and the end of the previous clip.",
        outDescription = "Controls how this clip exits. Adds a fade effect to the end of this clip and the start of the next clip.",
    )
    clip.transitionIn?.let { transition ->
        TextButton(onClick = { onApplyTransitionToAll(transition.kind, transition.duration) }) {
            Text("Apply to all cuts")
        }
    }

    Spacer(Modifier.height(16.dp))

    Text("Blend", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    var blendExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { blendExpanded = true }) {
            Text(blendModeLabel(clip.blendMode))
        }
        DropdownMenu(expanded = blendExpanded, onDismissRequest = { blendExpanded = false }) {
            blendModeOptions.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSetBlendMode(clip.id, mode)
                        blendExpanded = false
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    Text("Ken Burns", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        kenBurnsPresets.forEach { preset ->
            AssistChip(
                onClick = { onApplyKenBurns(clip.id, preset) },
                label = { Text(preset.label) },
            )
        }
        AssistChip(
            onClick = { onApplyKenBurns(clip.id, randomKenBurnsPreset()) },
            label = { Text("Random") },
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onFitToCanvas(clip.id, false) }) {
            Text("Fit")
        }
        TextButton(onClick = { onFitToCanvas(clip.id, true) }) {
            Text("Fill")
        }
        TextButton(
            onClick = { onAutoReframe(clip.id) },
            enabled = !autoReframeRunning && !stabilizeRunning && !removeSilenceRunning && !maskTrackRunning,
        ) {
            Text("Auto reframe")
        }
        TextButton(
            onClick = { onStabilize(clip.id) },
            enabled = !stabilizeRunning && !autoReframeRunning && !removeSilenceRunning && !maskTrackRunning,
        ) {
            Text("Stabilize")
        }
        TextButton(
            onClick = { onRemoveSilence(clip.id) },
            enabled = !removeSilenceRunning && !autoReframeRunning && !stabilizeRunning && !maskTrackRunning,
        ) {
            Text("Remove silence")
        }
        TextButton(
            onClick = { showFreezeDialog = true },
            enabled = freezeEnabled,
        ) {
            Text("Freeze")
        }
    }
    if (showFreezeDialog) {
        AlertDialog(
            onDismissRequest = { showFreezeDialog = false },
            title = { Text("Freeze frame") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hold the frame at the playhead for:")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { seconds ->
                            FilterChip(
                                selected = freezeSeconds == seconds.toFloat(),
                                onClick = { freezeSeconds = seconds.toFloat() },
                                label = { Text("${seconds}s") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFreezeDialog = false
                        onFreezeFrame(clip.id, freezeSeconds)
                    },
                ) {
                    Text("Freeze")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFreezeDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Spacer(Modifier.height(16.dp))

    Text("Filters", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    VideoFilterStack(
        clip = clip,
        filters = clipFilters,
        clipOffsetUs = clipOffsetUs,
        onAddFilter = onAddFilter,
        onRemoveFilter = onRemoveFilter,
        onApplyFilterToAll = onApplyFilterToAll,
        onUpdateFilterParams = onUpdateFilterParams,
        onReplaceFilter = onReplaceFilter,
        onToggleFilterEnabled = onToggleFilterEnabled,
        onMoveFilter = onMoveFilter,
        onAddKeyframe = onAddKeyframe,
    )

    Spacer(Modifier.height(16.dp))
    Text("Masks", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    MasksSection(
        clipId = clip.id,
        clip = clip,
        clipOffsetUs = clipOffsetUs,
        masks = kind.masks,
        onAddMask = { mask -> onAddMask(clip.id, mask) },
        onRemoveMask = { index -> onRemoveMask(clip.id, index) },
        onUpdateMask = { index, mask -> onUpdateMask(clip.id, index, mask) },
        onSetMaskEnabled = { index, enabled -> onSetMaskEnabled(clip.id, index, enabled) },
        onAddKeyframe = onAddKeyframe,
        onTrackMask = { index -> onTrackMask(clip.id, index) },
        trackEnabled = trackEnabled,
    )

    KeyframeSection(
        clip = clip,
        playheadMs = playheadMs,
        onAddKeyframe = onAddKeyframe,
        onRemoveKeyframe = onRemoveKeyframe,
        onUpdateKeyframe = onUpdateKeyframe,
    )
}

@Composable
private fun VideoFilterStack(
    clip: RustClipSnapshot,
    filters: List<RustVideoEffectSnapshot>,
    clipOffsetUs: Long,
    onAddFilter: (String, RustVideoEffectSnapshot) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onApplyFilterToAll: (String, Int) -> Unit,
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onReplaceFilter: (String, Int, RustVideoFilterSnapshot) -> Unit,
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onAddKeyframe: (String, RustKeyframe) -> Unit,
    addableFilters: List<RustVideoFilterSnapshot> = defaultVideoFilters(),
) {
    val lutScope = rememberCoroutineScope()
    var lutTarget by remember { mutableStateOf<Pair<Int, RustLutFilterSnapshot>?>(null) }
    var lutPickError by remember(clip.id) { mutableStateOf<String?>(null) }
    val lutPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = SupportedFormats.lutExtensions),
    ) { file: PlatformFile? ->
        file?.let { picked ->
            lutScope.launch {
                val path = try {
                    PlatformFileSystem.stageForNativeAccess(picked.platformPath())
                } catch (error: Exception) {
                    Napier.e("LUT file could not be staged", error)
                    lutPickError = "Could not use LUT: ${error.message}"
                    return@launch
                }
                lutPickError = null
                val target = lutTarget
                if (target == null) {
                    onAddFilter(
                        clip.id,
                        RustVideoEffectSnapshot(filter = RustLutFilterSnapshot(path = path), enabled = true),
                    )
                } else {
                    onReplaceFilter(clip.id, target.first, target.second.copy(path = path))
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        filters.forEachIndexed { index, effect ->
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
                IconButton(onClick = { onApplyFilterToAll(clip.id, index) }) {
                    Icon(
                        Icons.Default.DoneAll,
                        contentDescription = "Apply to all clips",
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = { onMoveFilter(clip.id, index, index - 1) },
                    enabled = index > 0,
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, null)
                }
                IconButton(
                    onClick = { onMoveFilter(clip.id, index, index + 1) },
                    enabled = index < filters.lastIndex,
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
            addableFilters.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(filter.displayName()) },
                    onClick = {
                        onAddFilter(clip.id, RustVideoEffectSnapshot(filter = filter, enabled = true))
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("LUT") },
                onClick = {
                    lutTarget = null
                    expanded = false
                    lutPicker.launch()
                },
            )
        }
    }

    lutPickError?.let { message ->
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }

    filters.forEachIndexed { index, effect ->
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

    filters.forEachIndexed { index, effect ->
        val flip = effect.filter as? RustFlipFilterSnapshot ?: return@forEachIndexed
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = flip.horizontal,
                onClick = {
                    onUpdateFilterParams(
                        clip.id, index,
                        mapOf("horizontal" to if (flip.horizontal) 0f else 1f),
                    )
                },
                label = { Text("Flip H") },
            )
            FilterChip(
                selected = flip.vertical,
                onClick = {
                    onUpdateFilterParams(
                        clip.id, index,
                        mapOf("vertical" to if (flip.vertical) 0f else 1f),
                    )
                },
                label = { Text("Flip V") },
            )
        }
    }

    filters.forEachIndexed { index, effect ->
        val chroma = effect.filter as? RustChromaKeyFilterSnapshot ?: return@forEachIndexed
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chromaKeyPresets.forEach { preset ->
                FilterChip(
                    selected = chroma.keyColor.equals(preset.color, ignoreCase = true),
                    onClick = { onReplaceFilter(clip.id, index, chroma.copy(keyColor = preset.color)) },
                    label = { Text(preset.label) },
                )
            }
        }
    }

    filters.forEachIndexed { index, effect ->
        val mosaic = effect.filter as? RustMosaicFilterSnapshot ?: return@forEachIndexed
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mosaicKindOptions.forEach { (kind, label) ->
                FilterChip(
                    selected = mosaic.kind == kind,
                    onClick = { onReplaceFilter(clip.id, index, mosaic.copy(kind = kind)) },
                    label = { Text(label) },
                )
            }
        }
    }

    filters.forEachIndexed { index, effect ->
        val duotone = effect.filter as? RustDuotoneFilterSnapshot ?: return@forEachIndexed
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            duotonePresets.forEach { preset ->
                FilterChip(
                    selected = duotone.dark.equals(preset.dark, ignoreCase = true) &&
                        duotone.light.equals(preset.light, ignoreCase = true),
                    onClick = {
                        onReplaceFilter(
                            clip.id, index,
                            duotone.copy(dark = preset.dark, light = preset.light),
                        )
                    },
                    label = { Text(preset.label) },
                )
            }
        }
    }

    filters.forEachIndexed { index, effect ->
        val blur = effect.filter as? RustBlurFilterSnapshot ?: return@forEachIndexed
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            blurKindOptions.forEach { (kind, label) ->
                FilterChip(
                    selected = blur.kind == kind,
                    onClick = { onReplaceFilter(clip.id, index, blur.copy(kind = kind)) },
                    label = { Text(label) },
                )
            }
        }
    }

    filters.forEachIndexed { index, effect ->
        val lut = effect.filter as? RustLutFilterSnapshot ?: return@forEachIndexed
        Spacer(Modifier.height(8.dp))
        var lutInfo by remember(lut.path) { mutableStateOf<String?>(null) }
        var lutError by remember(lut.path) { mutableStateOf<String?>(null) }
        LaunchedEffect(lut.path) {
            lutInfo = null
            lutError = null
            if (lut.path.isEmpty()) return@LaunchedEffect
            val parsed = try {
                parseCubeLut(PlatformFileSystem.readText(lut.path))
            } catch (error: Exception) {
                Result.failure(error)
            }
            parsed
                .onSuccess {
                    lutInfo = if (it.kind == CubeLutKind.ThreeD) "Size: 3D ${it.size}³" else "Size: 1D ${it.size}"
                }
                .onFailure { lutError = it.message ?: "Invalid LUT file" }
        }

        val lutFileName = lut.path.takeIf { it.isNotEmpty() }
            ?.substringAfterLast("/")
            ?.substringAfterLast("\\")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LUT file", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                lutFileName ?: "None",
                style = MaterialTheme.typography.labelSmall,
                color = if (lutFileName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lutFileName != null) {
                IconButton(
                    onClick = { onReplaceFilter(clip.id, index, lut.copy(path = "")) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove LUT file", modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(
                onClick = {
                    lutTarget = index to lut
                    lutPicker.launch()
                },
                modifier = Modifier.height(32.dp),
            ) {
                Text(if (lutFileName != null) "Change" else "Select", style = MaterialTheme.typography.labelSmall)
            }
        }

        lutInfo?.let { info ->
            Spacer(Modifier.height(4.dp))
            Text(info, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lutError?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AdjustmentClipProperties(
    clip: RustClipSnapshot,
    kind: RustAdjustmentClipKind,
    playheadMs: Long = 0L,
    onAddFilter: (String, RustVideoEffectSnapshot) -> Unit,
    onRemoveFilter: (String, Int) -> Unit,
    onApplyFilterToAll: (String, Int) -> Unit = { _, _ -> },
    onUpdateFilterParams: (String, Int, Map<String, Float>) -> Unit,
    onReplaceFilter: (String, Int, RustVideoFilterSnapshot) -> Unit = { _, _, _ -> },
    onToggleFilterEnabled: (String, Int, Boolean) -> Unit,
    onMoveFilter: (String, Int, Int) -> Unit,
    onAddMask: (String, RustMaskEffect) -> Unit = { _, _ -> },
    onRemoveMask: (String, Int) -> Unit = { _, _ -> },
    onUpdateMask: (String, Int, RustMaskEffect) -> Unit = { _, _, _ -> },
    onSetMaskEnabled: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
) {
    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceIn(0L, clip.timelineDurationUs)

    Text("Adjustment layer", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Duration: ${clip.timelineDurationUs / 1_000_000}s | Applies to every track below",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (clip.muted) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Disabled",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(16.dp))

    Text("Filters", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))

    VideoFilterStack(
        clip = clip,
        filters = kind.filters,
        clipOffsetUs = clipOffsetUs,
        onAddFilter = onAddFilter,
        onRemoveFilter = onRemoveFilter,
        onApplyFilterToAll = onApplyFilterToAll,
        onUpdateFilterParams = onUpdateFilterParams,
        onReplaceFilter = onReplaceFilter,
        onToggleFilterEnabled = onToggleFilterEnabled,
        onMoveFilter = onMoveFilter,
        onAddKeyframe = onAddKeyframe,
        addableFilters = adjustmentLayerFilters(),
    )

    Spacer(Modifier.height(16.dp))
    Text("Masks", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    MasksSection(
        clipId = clip.id,
        clip = clip,
        clipOffsetUs = clipOffsetUs,
        masks = kind.masks,
        onAddMask = { mask -> onAddMask(clip.id, mask) },
        onRemoveMask = { index -> onRemoveMask(clip.id, index) },
        onUpdateMask = { index, mask -> onUpdateMask(clip.id, index, mask) },
        onSetMaskEnabled = { index, enabled -> onSetMaskEnabled(clip.id, index, enabled) },
        onAddKeyframe = onAddKeyframe,
        onTrackMask = {},
        trackEnabled = false,
    )

    KeyframeSection(
        clip = clip,
        playheadMs = playheadMs,
        onAddKeyframe = onAddKeyframe,
        onRemoveKeyframe = onRemoveKeyframe,
        onUpdateKeyframe = onUpdateKeyframe,
    )
}

private fun adjustmentLayerFilters(): List<RustVideoFilterSnapshot> =
    defaultVideoFilters().filterNot {
        it is RustReverseVideoFilterSnapshot || it is RustCanvasBackgroundFilterSnapshot
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
    onReplaceAudioFilter: (String, Int, RustAudioFilterSnapshot) -> Unit = { _, _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
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
            DropdownMenuItem(
                text = { Text("Reverse") },
                onClick = {
                    onAddAudioFilter(clip.id, RustReverseAudioFilterSnapshot)
                    audioFilterExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Equalizer") },
                onClick = {
                    onAddAudioFilter(clip.id, RustEqualizerAudioFilterSnapshot())
                    audioFilterExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Reverb") },
                onClick = {
                    onAddAudioFilter(clip.id, RustReverbAudioFilterSnapshot())
                    audioFilterExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Ducking") },
                onClick = {
                    onAddAudioFilter(clip.id, RustDuckingAudioFilterSnapshot())
                    audioFilterExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Denoise") },
                onClick = {
                    onAddAudioFilter(clip.id, RustDenoiseAudioFilterSnapshot())
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
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
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
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
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
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
                    }
                }
            }
            is RustReverseAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Reverse", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
                    }
                }
            }
            is RustEqualizerAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Equalizer", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
                    }
                }
                var eqExpanded by remember(filter) { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { eqExpanded = true }) {
                        Text(eqPresetLabel(filter.preset))
                    }
                    DropdownMenu(expanded = eqExpanded, onDismissRequest = { eqExpanded = false }) {
                        eqPresetOptions.forEach { (preset, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onReplaceAudioFilter(clip.id, index, filter.copy(preset = preset))
                                    eqExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            is RustReverbAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Reverb", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
                    }
                }
                var reverbExpanded by remember(filter) { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { reverbExpanded = true }) {
                        Text(reverbPresetLabel(filter.preset))
                    }
                    DropdownMenu(expanded = reverbExpanded, onDismissRequest = { reverbExpanded = false }) {
                        reverbPresetOptions.forEach { (preset, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onReplaceAudioFilter(clip.id, index, filter.copy(preset = preset))
                                    reverbExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            is RustDuckingAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ducking", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
                    }
                }
                Text("Lowers by ${(filter.amount * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                var duckAmount by remember(filter) { mutableFloatStateOf(filter.amount) }
                Slider(
                    value = duckAmount,
                    onValueChange = { duckAmount = it },
                    onValueChangeFinished = {
                        onReplaceAudioFilter(clip.id, index, filter.copy(amount = duckAmount))
                    },
                    valueRange = 0f..1f,
                )
                Text("Release ${filter.releaseMs}ms", style = MaterialTheme.typography.labelSmall)
                var duckRelease by remember(filter) { mutableFloatStateOf(filter.releaseMs / 1000f) }
                Slider(
                    value = duckRelease,
                    onValueChange = { duckRelease = it },
                    onValueChangeFinished = {
                        onReplaceAudioFilter(
                            clip.id, index,
                            filter.copy(releaseMs = (duckRelease * 1000f).toLong()),
                        )
                    },
                    valueRange = 0.05f..2f,
                    steps = 38,
                )
            }
            is RustDenoiseAudioFilterSnapshot -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Denoise", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveAudioFilter(clip.id, index) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.common_remove), modifier = Modifier.size(16.dp))
                    }
                }
                Text("Reduces by ${(filter.amount * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                var denoiseAmount by remember(filter) { mutableFloatStateOf(filter.amount) }
                Slider(
                    value = denoiseAmount,
                    onValueChange = { denoiseAmount = it },
                    onValueChangeFinished = {
                        onReplaceAudioFilter(clip.id, index, filter.copy(amount = denoiseAmount))
                    },
                    valueRange = 0f..1f,
                )
            }
            else -> { }
        }
    }

    KeyframeSection(
        clip = clip,
        playheadMs = playheadMs,
        onAddKeyframe = onAddKeyframe,
        onRemoveKeyframe = onRemoveKeyframe,
        onUpdateKeyframe = onUpdateKeyframe,
    )
}

@Composable
private fun TextClipProperties(
    clip: RustClipSnapshot,
    kind: RustTextClipKind,
    playheadMs: Long = 0L,
    onUpdateText: (String, String) -> Unit,
    onUpdateTextStyle: (String, RustTextStyleUpdate) -> Unit,
    onSetTextTransitionIn: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onSetTextTransitionOut: (String, RustTransitionSnapshot?) -> Unit = { _, _ -> },
    onApplyTextAnimation: (String, TextAnimationPreset) -> Unit = { _, _ -> },
    onAddKeyframe: (String, RustKeyframe) -> Unit = { _, _ -> },
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
) {
    val clipOffsetUs = (playheadMs * 1000L - clip.timelineStartUs).coerceIn(0L, clip.timelineDurationUs)
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
                            onUpdateTextStyle(clip.id, RustTextStyleUpdate(positionX = pos.first, positionY = pos.second))
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("X: ${formatFixed(style.positionX * 100f, 0)}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.TEXT_POSITION_X, clip, clipOffsetUs, onAddKeyframe)
    }
    var posX by remember(style.positionX) { mutableFloatStateOf(style.positionX) }
    Slider(
        value = posX,
        onValueChange = { posX = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(positionX = posX)) },
        valueRange = 0f..1f,
        modifier = Modifier.height(28.dp),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Y: ${formatFixed(style.positionY * 100f, 0)}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.TEXT_POSITION_Y, clip, clipOffsetUs, onAddKeyframe)
    }
    var posY by remember(style.positionY) { mutableFloatStateOf(style.positionY) }
    Slider(
        value = posY,
        onValueChange = { posY = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(positionY = posY)) },
        valueRange = 0f..1f,
        modifier = Modifier.height(28.dp),
    )

    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Size", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        KeyframeToggle(KeyframeParams.TEXT_FONT_SIZE, clip, clipOffsetUs, onAddKeyframe)
    }
    var fontSize by remember(style.fontSize) { mutableFloatStateOf(style.fontSize) }
    Slider(
        value = fontSize,
        onValueChange = { fontSize = it },
        onValueChangeFinished = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(fontSize = fontSize)) },
        valueRange = 12f..120f,
        steps = 26,
    )
    Text("${fontSize.toInt()}sp", style = MaterialTheme.typography.labelSmall)

    Spacer(Modifier.height(12.dp))

    val fontPickerText = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = SupportedFormats.fontExtensions),
    ) { file: PlatformFile? ->
        file?.let { onUpdateTextStyle(clip.id, RustTextStyleUpdate(fontFamily = it.platformPath())) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Font", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        val fontLabel = style.fontFamily.takeIf { it != "sans-serif" }
        Text(
            fontLabel?.substringAfterLast("/")?.substringAfterLast("\\") ?: "Default",
            style = MaterialTheme.typography.labelSmall,
            color = if (fontLabel != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fontLabel != null) {
            IconButton(
                onClick = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(fontFamily = "sans-serif")) },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Reset font", modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = { fontPickerText.launch() },
            modifier = Modifier.height(32.dp),
        ) {
            Text(if (fontLabel != null) "Change" else "Select", style = MaterialTheme.typography.labelSmall)
        }
    }

    Spacer(Modifier.height(12.dp))

    Text("Style", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = style.bold,
            onClick = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(bold = !style.bold)) },
            label = { Text("B", fontWeight = FontWeight.ExtraBold) },
        )
        FilterChip(
            selected = style.italic,
            onClick = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(italic = !style.italic)) },
            label = { Text("I", fontStyle = FontStyle.Italic) },
        )
        FilterChip(
            selected = style.backgroundColor != null,
            onClick = {
                val update = if (style.backgroundColor != null) {
                    RustTextStyleUpdate(clearBackground = true)
                } else {
                    RustTextStyleUpdate(backgroundColor = "#000000")
                }
                onUpdateTextStyle(clip.id, update)
            },
            label = { Text("BG") },
        )
    }

    if (style.backgroundColor != null) {
        Spacer(Modifier.height(8.dp))
        Text("Background", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        TextColorSwatches(style.backgroundColor) { hex ->
            onUpdateTextStyle(clip.id, RustTextStyleUpdate(backgroundColor = hex))
        }
    }

    Spacer(Modifier.height(12.dp))

    Text("Color", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    TextColorSwatches(style.color) { hex ->
        onUpdateTextStyle(clip.id, RustTextStyleUpdate(color = hex))
    }

    Spacer(Modifier.height(16.dp))

    Text("Outline", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = style.outlineColor != null,
            onClick = {
                val update = if (style.outlineColor != null) {
                    RustTextStyleUpdate(clearOutline = true)
                } else {
                    RustTextStyleUpdate(outlineColor = "#000000", outlineWidth = 2f)
                }
                onUpdateTextStyle(clip.id, update)
            },
            label = { Text("Outline") },
        )
        if (style.outlineColor != null) {
            Text(
                "${formatFixed(style.outlineWidth, 0)}px",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }

    if (style.outlineColor != null) {
        Spacer(Modifier.height(8.dp))
        TextColorSwatches(style.outlineColor) { hex ->
            onUpdateTextStyle(clip.id, RustTextStyleUpdate(outlineColor = hex))
        }
        Spacer(Modifier.height(8.dp))
        Text("Width", style = MaterialTheme.typography.labelMedium)
        var outlineWidth by remember(style.outlineWidth) { mutableFloatStateOf(style.outlineWidth) }
        Slider(
            value = outlineWidth,
            onValueChange = { outlineWidth = it },
            onValueChangeFinished = {
                onUpdateTextStyle(clip.id, RustTextStyleUpdate(outlineWidth = outlineWidth))
            },
            valueRange = 0f..20f,
            steps = 19,
        )
        Text("${formatFixed(outlineWidth, 0)}px", style = MaterialTheme.typography.labelSmall)
    }

    Spacer(Modifier.height(16.dp))

    Text("Shadow", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = style.shadow,
            onClick = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(shadow = !style.shadow)) },
            label = { Text("Drop shadow") },
        )
    }

    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Curve", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        FilterChip(
            selected = style.curveRadius == 0f,
            onClick = { onUpdateTextStyle(clip.id, RustTextStyleUpdate(curveRadius = 0f)) },
            label = { Text("Straight") },
        )
    }
    var curveRadius by remember(style.curveRadius) { mutableFloatStateOf(style.curveRadius) }
    Slider(
        value = curveRadius,
        onValueChange = { curveRadius = it },
        onValueChangeFinished = {
            val snapped = if (kotlin.math.abs(curveRadius) < 0.5f) 0f else curveRadius
            curveRadius = snapped
            onUpdateTextStyle(clip.id, RustTextStyleUpdate(curveRadius = snapped))
        },
        valueRange = -800f..800f,
    )
    Text(
        if (curveRadius == 0f) "Off" else "${formatFixed(curveRadius, 0)}px radius",
        style = MaterialTheme.typography.labelSmall,
    )

    Spacer(Modifier.height(16.dp))

    Text("Animation", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "In",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        textAnimationPresetsFor(TextAnimationMode.In).forEach { preset ->
            AssistChip(
                onClick = { onApplyTextAnimation(clip.id, preset) },
                label = { Text(preset.label) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Out",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        textAnimationPresetsFor(TextAnimationMode.Out).forEach { preset ->
            AssistChip(
                onClick = { onApplyTextAnimation(clip.id, preset) },
                label = { Text(preset.label) },
            )
        }
    }

    FadeTransitionsPair(
        transitionIn = transitionIn,
        transitionOut = transitionOut,
        onSetTransitionIn = { onSetTextTransitionIn(clip.id, it) },
        onSetTransitionOut = { onSetTextTransitionOut(clip.id, it) },
        options = textTransitionOptions,
    )
    KeyframeSection(
        clip = clip,
        playheadMs = playheadMs,
        onAddKeyframe = onAddKeyframe,
        onRemoveKeyframe = onRemoveKeyframe,
        onUpdateKeyframe = onUpdateKeyframe,
    )
}

private val textColorChoices = listOf(
    "#FFFFFF", "#000000", "#FF0000", "#FFFF00",
    "#00FF00", "#00BFFF", "#FF69B4", "#FFA500",
)

@Composable
private fun TextColorSwatches(
    selectedHex: String?,
    onPick: (String) -> Unit,
) {
    val selected = normalizeTextColorHex(selectedHex)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        textColorChoices.forEach { hex ->
            Surface(
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onPick(hex) },
                shape = CircleShape,
                color = textSwatchColor(hex),
                border = if (selected.equals(hex, ignoreCase = true)) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
            ) {}
        }
    }
}

private fun normalizeTextColorHex(hex: String?): String? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    val rgb = when (digits.length) {
        8 -> digits.substring(2)
        6 -> digits
        else -> return null
    }
    return "#$rgb"
}

private fun textSwatchColor(hex: String): Color = try {
    Color((hex.removePrefix("#").toLong(16) or 0xFF000000).toInt())
} catch (_: Exception) {
    Color.White
}

private fun defaultVideoFilters(): List<RustVideoFilterSnapshot> = listOf(
    RustTransformFilterSnapshot(scale = defaultOf(KeyframeParams.TRANSFORM_SCALE), translateX = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_X), translateY = defaultOf(KeyframeParams.TRANSFORM_TRANSLATE_Y), rotate = defaultOf(KeyframeParams.TRANSFORM_ROTATE)),
    RustBrightnessFilterSnapshot(0f),
    RustContrastFilterSnapshot(1f),
    RustSaturationFilterSnapshot(1f),
    RustColorGradeFilterSnapshot(),
    RustCropFilterSnapshot(left = 0f, top = 0f, right = 0f, bottom = 0f),
    RustBlurFilterSnapshot(5f),
    RustSharpenFilterSnapshot(1f),
    RustGrayscaleFilterSnapshot,
    RustSepiaFilterSnapshot,
    RustRotateFilterSnapshot(degrees = 0f),
    RustHueFilterSnapshot(degrees = 0f),
    RustFlipFilterSnapshot(horizontal = false, vertical = false),
    RustVignetteFilterSnapshot(),
    RustGrainFilterSnapshot(),
    RustDuotoneFilterSnapshot("FF1A1A3E", "FFF2C14E"),
    RustPosterizeFilterSnapshot(),
    RustChromaKeyFilterSnapshot(),
    RustMosaicFilterSnapshot(),
    RustReverseVideoFilterSnapshot,
    RustCanvasBackgroundFilterSnapshot(),
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
    onRemoveKeyframe: (String, Int) -> Unit = { _, _ -> },
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit = { _, _, _ -> },
    onSetSubtitleFont: (String, String?) -> Unit = { _, _ -> },
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

    Spacer(Modifier.height(8.dp))

    val fontPicker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = SupportedFormats.fontExtensions),
    ) { file: PlatformFile? ->
        file?.let { onSetSubtitleFont(clip.id, it.platformPath()) }
    }

    val fontFileName = kind.fontPath?.substringAfterLast("/")?.substringAfterLast("\\")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Font", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            fontFileName ?: "None",
            style = MaterialTheme.typography.labelSmall,
            color = if (fontFileName != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fontFileName != null) {
            IconButton(
                onClick = { onSetSubtitleFont(clip.id, null) },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove font", modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = { fontPicker.launch() },
            modifier = Modifier.height(32.dp),
        ) {
            Text(if (fontFileName != null) "Change" else "Select", style = MaterialTheme.typography.labelSmall)
        }
    }

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

    FadeTransitionsPair(
        transitionIn = transitionIn,
        transitionOut = transitionOut,
        onSetTransitionIn = { onSetTransitionIn(clip.id, it) },
        onSetTransitionOut = { onSetTransitionOut(clip.id, it) },
        options = subtitleTransitionOptions,
    )
    KeyframeSection(
        clip = clip,
        playheadMs = playheadMs,
        onAddKeyframe = onAddKeyframe,
        onRemoveKeyframe = onRemoveKeyframe,
        onUpdateKeyframe = onUpdateKeyframe,
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
        is RustFlipFilterSnapshot -> "Flip"
        RustReverseVideoFilterSnapshot -> "Reverse"
        else -> "Filter"
    }

private fun RustVideoEffectSnapshot.displayName(): String = filter.displayName()

@Composable
private fun KeyframeSection(
    clip: RustClipSnapshot,
    playheadMs: Long,
    onAddKeyframe: (String, RustKeyframe) -> Unit,
    onRemoveKeyframe: (String, Int) -> Unit,
    onUpdateKeyframe: (String, Int, RustKeyframe) -> Unit,
) {
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
}

@Composable
private fun FadeTransitionsPair(
    transitionIn: RustTransitionSnapshot?,
    transitionOut: RustTransitionSnapshot?,
    onSetTransitionIn: (RustTransitionSnapshot?) -> Unit,
    onSetTransitionOut: (RustTransitionSnapshot?) -> Unit,
    options: List<TransitionOption> = fadeTransitionOptions,
    durationRange: ClosedFloatingPointRange<Float> = 0.1f..3f,
    durationSteps: Int = 28,
    inLabel: String = "Fade In",
    outLabel: String = "Fade Out",
    inDescription: String? = null,
    outDescription: String? = null,
) {
    TransitionSelector(
        label = inLabel,
        description = inDescription,
        currentTransition = transitionIn,
        options = options,
        durationRange = durationRange,
        durationSteps = durationSteps,
        onSetTransition = onSetTransitionIn,
    )
    Spacer(Modifier.height(12.dp))
    TransitionSelector(
        label = outLabel,
        description = outDescription,
        currentTransition = transitionOut,
        options = options,
        durationRange = durationRange,
        durationSteps = durationSteps,
        onSetTransition = onSetTransitionOut,
    )
}

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

private fun fmtPct(v: Float) = "${(v * 100).toInt()}%"
private fun fmt1d(v: Float) = "${(v * 10).toInt() / 10f}x"
private fun fmtDeg(v: Float) = "${v.toInt()}°"

@Composable
private fun MasksSection(
    clipId: String,
    clip: RustClipSnapshot,
    clipOffsetUs: Long,
    masks: List<RustMaskEffect>,
    onAddMask: (RustMaskEffect) -> Unit,
    onRemoveMask: (Int) -> Unit,
    onUpdateMask: (Int, RustMaskEffect) -> Unit,
    onSetMaskEnabled: (Int, Boolean) -> Unit,
    onAddKeyframe: (String, RustKeyframe) -> Unit,
    onTrackMask: (Int) -> Unit,
    trackEnabled: Boolean,
) {
    var selectedIndex by remember(clipId) { mutableIntStateOf(0) }
    val selectedMaskIndex = selectedIndex.coerceIn(0, (masks.size - 1).coerceAtLeast(0))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        masks.forEachIndexed { index, mask ->
            MaskChipRow(
                index = index,
                mask = mask,
                selected = index == selectedMaskIndex,
                onSelect = { selectedIndex = index },
                onRemove = { onRemoveMask(index) },
                onToggleEnabled = { enabled -> onSetMaskEnabled(index, enabled) },
            )
            MaskEditor(
                index = index,
                mask = mask,
                clip = clip,
                clipOffsetUs = clipOffsetUs,
                onAddKeyframe = onAddKeyframe,
                onUpdate = { updated -> onUpdateMask(index, updated) },
            )
        }

        var expanded by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                AssistChip(
                    onClick = { expanded = true },
                    label = { Text("+ Add Mask") },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Rectangle Shape") },
                        onClick = {
                            onAddMask(RustMaskEffect(
                                enabled = true,
                                source = RustShapeMaskSource(RustRectangleMaskShape(0.1f, 0.1f, 0.9f, 0.9f), feather = 0f, invert = false),
                                mode = RustMaskMode.Add,
                            ))
                            selectedIndex = masks.size
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Ellipse Shape") },
                        onClick = {
                            onAddMask(RustMaskEffect(
                                enabled = true,
                                source = RustShapeMaskSource(RustEllipseMaskShape(0.5f, 0.5f, 0.3f, 0.3f), feather = 0f, invert = false),
                                mode = RustMaskMode.Add,
                            ))
                            selectedIndex = masks.size
                            expanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Path Shape") },
                        onClick = {
                            onAddMask(RustMaskEffect(
                                enabled = true,
                                source = RustShapeMaskSource(defaultPathMaskShape(), feather = 0f, invert = false),
                                mode = RustMaskMode.Add,
                            ))
                            selectedIndex = masks.size
                            expanded = false
                        },
                    )
                }
            }
            TextButton(
                onClick = { onTrackMask(selectedMaskIndex) },
                enabled = trackEnabled,
            ) {
                Text("Track")
            }
        }
    }
}

@Composable
private fun MaskChipRow(
    index: Int,
    mask: RustMaskEffect,
    selected: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = mask.enabled,
            onCheckedChange = onToggleEnabled,
        )
        Text(
            "${index + 1}. ${maskLabel(mask)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove mask", modifier = Modifier.size(16.dp))
        }
    }
}

private fun defaultPathMaskShape(): RustPathMaskShape = RustPathMaskShape(
    vertices = listOf(
        RustMaskVertex(RustMaskPoint(0.2f, 0.2f)),
        RustMaskVertex(RustMaskPoint(0.8f, 0.2f)),
        RustMaskVertex(RustMaskPoint(0.8f, 0.8f)),
        RustMaskVertex(RustMaskPoint(0.2f, 0.8f)),
    ),
)

private fun maskLabel(mask: RustMaskEffect): String = when (val s = mask.source) {
    is RustShapeMaskSource -> when (s.shape) {
        is RustRectangleMaskShape -> "Rectangle"
        is RustEllipseMaskShape -> "Ellipse"
        is RustPathMaskShape -> "Path"
    }
}

@Composable
private fun MaskEditor(
    index: Int,
    mask: RustMaskEffect,
    clip: RustClipSnapshot,
    clipOffsetUs: Long,
    onAddKeyframe: (String, RustKeyframe) -> Unit,
    onUpdate: (RustMaskEffect) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {

            val src = mask.source as? RustShapeMaskSource
            if (src != null) {
                ShapeTypeEditor(
                    shape = src.shape,
                    maskIndex = index,
                    clip = clip,
                    clipOffsetUs = clipOffsetUs,
                    onAddKeyframe = onAddKeyframe,
                    onShapeChange = { newShape ->
                        onUpdate(mask.copy(source = src.copy(shape = newShape)))
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            val t = mask.transform
            Text("Transform", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            MaskSlider(
                "Scale", t.scale, 0.1f..5f, 24, ::fmt1d,
                trailing = { KeyframeToggle(KeyframeParams.maskParam(index, "scale"), clip, clipOffsetUs, onAddKeyframe) },
            ) { onUpdate(mask.copy(transform = t.copy(scale = it))) }
            MaskSlider(
                "Pan X", t.translateX, -1f..1f, format = ::fmtPct,
                trailing = { KeyframeToggle(KeyframeParams.maskParam(index, "translate_x"), clip, clipOffsetUs, onAddKeyframe) },
            ) { onUpdate(mask.copy(transform = t.copy(translateX = it))) }
            MaskSlider(
                "Pan Y", t.translateY, -1f..1f, format = ::fmtPct,
                trailing = { KeyframeToggle(KeyframeParams.maskParam(index, "translate_y"), clip, clipOffsetUs, onAddKeyframe) },
            ) { onUpdate(mask.copy(transform = t.copy(translateY = it))) }
            MaskSlider(
                "Rotate", t.rotate, -180f..180f, format = ::fmtDeg,
                trailing = { KeyframeToggle(KeyframeParams.maskParam(index, "rotate"), clip, clipOffsetUs, onAddKeyframe) },
            ) { onUpdate(mask.copy(transform = t.copy(rotate = it))) }

            Spacer(Modifier.height(8.dp))

            MaskSlider("Opacity", mask.opacity, 0f..1f, format = ::fmtPct) { onUpdate(mask.copy(opacity = it)) }
            MaskSlider("Expansion", mask.expansion, -500f..500f, format = { "${it.toInt()}px" }) {
                onUpdate(mask.copy(expansion = it))
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Feather", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                KeyframeToggle(KeyframeParams.maskParam(index, "feather"), clip, clipOffsetUs, onAddKeyframe)
                Text("${src?.feather?.toInt() ?: 0}px", style = MaterialTheme.typography.labelSmall)
            }
            var featherLocal by remember(src?.feather ?: 0f) { mutableFloatStateOf(src?.feather ?: 0f) }
            Slider(
                value = featherLocal,
                onValueChange = { featherLocal = it },
                onValueChangeFinished = {
                    if (src != null) {
                        onUpdate(mask.copy(source = src.copy(feather = featherLocal)))
                    }
                },
                valueRange = 0f..100f,
                modifier = Modifier.height(24.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Invert", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = src?.invert ?: false,
                    onCheckedChange = {
                        if (src != null) {
                            onUpdate(mask.copy(source = src.copy(invert = it)))
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            Text("Operation", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RustMaskOperation.entries.forEach { op ->
                    FilterChip(
                        selected = mask.operation == op,
                        onClick = { onUpdate(mask.copy(operation = op)) },
                        label = { Text(opLabel(op), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(if (mask.mode == null) "Mode (legacy)" else "Mode", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RustMaskMode.entries.forEach { mode ->
                    FilterChip(
                        selected = mask.mode == mode,
                        onClick = { onUpdate(mask.copy(mode = mode)) },
                        label = { Text(modeLabel(mode), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShapeTypeEditor(
    shape: org.mlm.miniter.editor.model.RustMaskShape,
    maskIndex: Int,
    clip: RustClipSnapshot,
    clipOffsetUs: Long,
    onAddKeyframe: (String, RustKeyframe) -> Unit,
    onShapeChange: (org.mlm.miniter.editor.model.RustMaskShape) -> Unit,
) {
    Text("Shape", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = shape is RustRectangleMaskShape,
            onClick = { onShapeChange(RustRectangleMaskShape(0.1f, 0.1f, 0.9f, 0.9f)) },
            label = { Text("Rect", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.height(28.dp),
        )
        FilterChip(
            selected = shape is RustEllipseMaskShape,
            onClick = { onShapeChange(RustEllipseMaskShape(0.5f, 0.5f, 0.3f, 0.3f)) },
            label = { Text("Ellipse", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.height(28.dp),
        )
        FilterChip(
            selected = shape is RustPathMaskShape,
            onClick = { onShapeChange(defaultPathMaskShape()) },
            label = { Text("Path", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.height(28.dp),
        )
    }

    Spacer(Modifier.height(4.dp))

    when (shape) {
        is RustRectangleMaskShape -> {
            MaskSlider("Left", shape.left, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.left"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(left = it)) }
            MaskSlider("Top", shape.top, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.top"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(top = it)) }
            MaskSlider("Right", shape.right, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.right"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(right = it)) }
            MaskSlider("Bottom", shape.bottom, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.bottom"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(bottom = it)) }
        }
        is RustEllipseMaskShape -> {
            MaskSlider("Center X", shape.centerX, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.center_x"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(centerX = it)) }
            MaskSlider("Center Y", shape.centerY, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.center_y"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(centerY = it)) }
            MaskSlider("Radius X", shape.radiusX, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.radius_x"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(radiusX = it)) }
            MaskSlider("Radius Y", shape.radiusY, 0f..1f, format = ::fmtPct, trailing = { KeyframeToggle(KeyframeParams.maskParam(maskIndex, "shape.radius_y"), clip, clipOffsetUs, onAddKeyframe) }) { onShapeChange(shape.copy(radiusY = it)) }
        }
        is RustPathMaskShape -> PathMaskEditor(shape, onShapeChange)
    }
}

@Composable
private fun PathMaskEditor(
    shape: RustPathMaskShape,
    onShapeChange: (RustPathMaskShape) -> Unit,
) {
    Text("Vertices", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    shape.vertices.forEachIndexed { index, vertex ->
        key(index) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MaskNumberField("X", vertex.p.x, Modifier.weight(1f)) { x ->
                    val vertices = shape.vertices.toMutableList()
                    vertices[index] = vertex.copy(p = vertex.p.copy(x = x))
                    onShapeChange(shape.copy(vertices = vertices))
                }
                MaskNumberField("Y", vertex.p.y, Modifier.weight(1f)) { y ->
                    val vertices = shape.vertices.toMutableList()
                    vertices[index] = vertex.copy(p = vertex.p.copy(y = y))
                    onShapeChange(shape.copy(vertices = vertices))
                }
                IconButton(
                    onClick = {
                        onShapeChange(shape.copy(vertices = shape.vertices.filterIndexed { i, _ -> i != index }))
                    },
                    enabled = shape.vertices.size > 3,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove vertex", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Closed", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Switch(
            checked = shape.closed,
            onCheckedChange = { onShapeChange(shape.copy(closed = it)) },
        )
        AssistChip(
            onClick = {
                onShapeChange(shape.copy(vertices = shape.vertices + RustMaskVertex(RustMaskPoint(0.5f, 0.5f))))
            },
            label = { Text("+ Vertex") },
        )
    }
}

@Composable
private fun MaskNumberField(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(formatFixed(value, 3)) }
    LaunchedEffect(value) {
        if (text.toFloatOrNull() != value) text = formatFixed(value, 3)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toFloatOrNull()?.takeIf { it.isFinite() }?.let(onValueChange)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun MaskSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { formatFixed(it, 2) },
    trailing: @Composable (() -> Unit)? = null,
    onValueChangeFinished: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: ${format(value)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
    var local by remember(value) { mutableFloatStateOf(value) }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onValueChangeFinished(local) },
        valueRange = range,
        steps = steps,
        modifier = Modifier.height(24.dp),
    )
}

private fun opLabel(op: RustMaskOperation): String = when (op) {
    RustMaskOperation.Alpha -> "Alpha"
    RustMaskOperation.Luma -> "Luma"
    RustMaskOperation.InvertAlpha -> "α⁻¹"
    RustMaskOperation.InvertLuma -> "L⁻¹"
}

private fun modeLabel(mode: RustMaskMode): String = when (mode) {
    RustMaskMode.Add -> "Add"
    RustMaskMode.Subtract -> "Subtract"
    RustMaskMode.Intersect -> "Intersect"
    RustMaskMode.Lighten -> "Lighten"
    RustMaskMode.Darken -> "Darken"
    RustMaskMode.Difference -> "Difference"
}
