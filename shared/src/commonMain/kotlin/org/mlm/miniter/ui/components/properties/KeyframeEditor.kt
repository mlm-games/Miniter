package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustVideoClipKind

private data class AnimatableParam(
    val key: String,
    val displayName: String,
    val valueRange: ClosedFloatingPointRange<Float>,
    val steps: Int = 0,
    val format: (Float) -> String,
)

private val animatableParams = listOf(
    AnimatableParam("opacity", "Opacity", 0f..1f, format = { "${(it * 100).toInt()}%" }),
    AnimatableParam("volume", "Volume", 0f..2f, 19, format = { "${(it * 100).toInt()}%" }),
    AnimatableParam("transform.scale", "Scale", 0.5f..3f, 24, format = { "${formatFixed(it, 1)}x" }),
    AnimatableParam("transform.translate_x", "Pan X", -1f..1f, format = { "${(it * 100).toInt()}%" }),
    AnimatableParam("transform.translate_y", "Pan Y", -1f..1f, format = { "${(it * 100).toInt()}%" }),
    AnimatableParam("transform.rotate", "Rotate", -180f..180f, format = { "${it.toInt()}°" }),
)

private val easingLabels = mapOf(
    RustEasing.Linear to "Linear",
    RustEasing.EaseIn to "Ease In",
    RustEasing.EaseOut to "Ease Out",
    RustEasing.EaseInOut to "Ease In Out",
)

private fun currentValueForParam(clip: RustClipSnapshot, paramKey: String): Float {
    return when (paramKey) {
        "opacity" -> clip.opacity
        "volume" -> clip.volume
        "transform.scale" -> {
            val kind = clip.kind
            if (kind is RustVideoClipKind) {
                kind.filters.firstNotNullOfOrNull { (it.filter as? RustTransformFilterSnapshot) }?.scale ?: 1f
            } else 1f
        }
        "transform.translate_x" -> {
            val kind = clip.kind
            if (kind is RustVideoClipKind) {
                kind.filters.firstNotNullOfOrNull { (it.filter as? RustTransformFilterSnapshot) }?.translateX ?: 0.5f
            } else 0.5f
        }
        "transform.translate_y" -> {
            val kind = clip.kind
            if (kind is RustVideoClipKind) {
                kind.filters.firstNotNullOfOrNull { (it.filter as? RustTransformFilterSnapshot) }?.translateY ?: 0.5f
            } else 0.5f
        }
        "transform.rotate" -> {
            val kind = clip.kind
            if (kind is RustVideoClipKind) {
                kind.filters.firstNotNullOfOrNull { (it.filter as? RustTransformFilterSnapshot) }?.rotate ?: 0f
            } else 0f
        }
        else -> 0f
    }
}

@Composable
fun KeyframeEditor(
    clip: RustClipSnapshot,
    playheadMs: Long,
    onAddKeyframe: (RustKeyframe) -> Unit,
    onRemoveKeyframe: (Int) -> Unit,
    onUpdateKeyframe: (Int, RustKeyframe) -> Unit,
) {
    val clipStartUs = clip.timelineStartUs
    val clipDurationUs = clip.timelineDurationUs
    val clipOffsetMs = (playheadMs * 1000L - clipStartUs).coerceIn(0L, clipDurationUs) / 1000L

    Column {
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text(
            "Keyframes",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))

        val curve = clip.keyframes
        var expandedParam by remember { mutableStateOf<String?>(null) }

        animatableParams.forEach { param ->
            val paramKfs = curve.keyframes
                .filter { it.param == param.key }
                .sortedBy { it.offset }

            KeyframeTrackRow(
                param = param,
                keyframes = paramKfs,
                clipDurationUs = clipDurationUs,
                clipOffsetUs = clipOffsetMs * 1000L,
                isExpanded = expandedParam == param.key,
                onToggleExpand = {
                    expandedParam = if (expandedParam == param.key) null else param.key
                },
                onAddKeyframe = { offsetUs ->
                    onAddKeyframe(RustKeyframe(
                        param = param.key,
                        offset = offsetUs,
                        value = currentValueForParam(clip, param.key),
                        easing = RustEasing.EaseInOut,
                    ))
                },
                onRemoveKeyframe = { indexInParam ->
                    val allIndex = curve.keyframes.indexOf(paramKfs[indexInParam])
                    onRemoveKeyframe(allIndex)
                },
                onUpdateKeyframe = { indexInParam, updated ->
                    val allIndex = curve.keyframes.indexOf(paramKfs[indexInParam])
                    onUpdateKeyframe(allIndex, updated)
                },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun KeyframeTrackRow(
    param: AnimatableParam,
    keyframes: List<RustKeyframe>,
    clipDurationUs: Long,
    clipOffsetUs: Long,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onAddKeyframe: (offsetUs: Long) -> Unit,
    onRemoveKeyframe: (Int) -> Unit,
    onUpdateKeyframe: (Int, RustKeyframe) -> Unit,
) {
    val hasKfs = keyframes.isNotEmpty()
    val primary = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small),
        color = if (isExpanded) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = {
                        onAddKeyframe(clipOffsetUs.coerceAtLeast(0L))
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Diamond,
                        contentDescription = "Add keyframe at playhead",
                        modifier = Modifier.size(16.dp),
                        tint = if (hasKfs) primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }

                Text(
                    param.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (hasKfs) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.width(48.dp),
                )

                Box(modifier = Modifier.weight(1f).height(16.dp)) {
                    if (clipDurationUs > 0L) {
                        val lineColor = MaterialTheme.colorScheme.outlineVariant
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            drawLine(
                                lineColor,
                                Offset(0f, h / 2),
                                Offset(w, h / 2),
                                strokeWidth = 1.5f,
                            )

                            val dotR = 3f
                            for (kf in keyframes) {
                                val x = (kf.offset.toFloat() / clipDurationUs.toFloat() * w)
                                    .coerceIn(dotR, w - dotR)
                                drawCircle(primary, dotR, Offset(x, h / 2))
                            }
                        }
                    }
                }

                if (hasKfs) {
                    Surface(
                        shape = CircleShape,
                        color = primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(18.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${keyframes.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = primary,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f,
                            )
                        }
                    }
                }
            }

            if (isExpanded) {
                if (keyframes.isEmpty()) {
                    Text(
                        "No keyframes. Tap the diamond to add one at the playhead position.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                } else {
                    Column(modifier = Modifier.padding(start = 8.dp, end = 4.dp, bottom = 4.dp)) {
                        keyframes.forEachIndexed { index, kf ->
                            KeyframeRow(
                                keyframe = kf,
                                param = param,
                                onRemove = { onRemoveKeyframe(index) },
                                onUpdate = { updated -> onUpdateKeyframe(index, updated) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyframeRow(
    keyframe: RustKeyframe,
    param: AnimatableParam,
    onRemove: () -> Unit,
    onUpdate: (RustKeyframe) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { editing = !editing }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.Diamond,
                contentDescription = null,
                modifier = Modifier.size(8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${formatFixed(keyframe.offset / 1_000_000f, 1)}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "= ${param.format(keyframe.value)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                easingLabels[keyframe.easing] ?: "Linear",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove keyframe", modifier = Modifier.size(10.dp))
            }
        }

        if (editing) {
            KeyframeEditorForm(
                keyframe = keyframe,
                param = param,
                onUpdate = onUpdate,
            )
        }
    }
}

@Composable
private fun KeyframeEditorForm(
    keyframe: RustKeyframe,
    param: AnimatableParam,
    onUpdate: (RustKeyframe) -> Unit,
) {
    var editingKf by remember(keyframe) { mutableStateOf(keyframe) }

    Column(modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp)) {
        Text("Time: ${formatFixed(editingKf.offset / 1_000_000f, 1)}s", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = (editingKf.offset / 1_000_000f).coerceIn(0f, 30f),
            onValueChange = { editingKf = editingKf.copy(offset = (it * 1_000_000L).toLong()) },
            onValueChangeFinished = { onUpdate(editingKf) },
            valueRange = 0f..30f,
            modifier = Modifier.height(24.dp),
        )

        Spacer(Modifier.height(4.dp))

        Text("Value: ${param.format(editingKf.value)}", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = editingKf.value.coerceIn(param.valueRange),
            onValueChange = { editingKf = editingKf.copy(value = it) },
            onValueChangeFinished = { onUpdate(editingKf) },
            valueRange = param.valueRange,
            steps = param.steps,
            modifier = Modifier.height(24.dp),
        )

        Spacer(Modifier.height(4.dp))

        Text("Easing", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RustEasing.entries.forEach { easing ->
                FilterChip(
                    selected = editingKf.easing == easing,
                    onClick = {
                        editingKf = editingKf.copy(easing = easing)
                        onUpdate(editingKf)
                    },
                    label = { Text(easingLabels[easing] ?: easing.name, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }
    }
}
