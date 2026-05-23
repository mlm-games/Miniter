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
import org.mlm.miniter.editor.model.RustVideoClipKind
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.paramDefOrUnknown

private val easingLabels = mapOf(
    RustEasing.Linear to "Linear",
    RustEasing.EaseIn to "Ease In",
    RustEasing.EaseOut to "Ease Out",
    RustEasing.EaseInOut to "Ease In Out",
)

fun currentValueForParam(clip: RustClipSnapshot, paramKey: String): Float {
    return when (paramKey) {
        KeyframeParams.OPACITY -> clip.opacity
        KeyframeParams.VOLUME -> clip.volume
        else -> {
            if (paramKey.startsWith("filter.")) {
                val parts = paramKey.split(".")
                if (parts.size >= 3) {
                    val idx = parts[1].toIntOrNull() ?: return 0f
                    val propKey = parts[2]
                    val kind = clip.kind as? RustVideoClipKind ?: return 0f
                    val filter = kind.filters.getOrNull(idx)?.filter ?: return 0f
                    readFilterProperty(filter, propKey)
                } else 0f
            } else 0f
        }
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
        val grouped = curve.keyframes.groupBy { it.param }.toList()
            .sortedBy { (param, _) ->
                val order = listOf(
                    KeyframeParams.OPACITY,
                    KeyframeParams.VOLUME,
                    KeyframeParams.TRANSFORM_SCALE,
                    KeyframeParams.TRANSFORM_TRANSLATE_X,
                    KeyframeParams.TRANSFORM_TRANSLATE_Y,
                    KeyframeParams.TRANSFORM_ROTATE,
                )
                order.indexOf(param).let { if (it < 0) Int.MAX_VALUE else it }
            }

        if (grouped.isEmpty()) {
            Text(
                "No keyframes. Click the diamond icon next to any property to add one.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        grouped.forEach { (param, kfs) ->
            KeyframeTrackRow(
                param = param,
                keyframes = kfs,
                clipDurationUs = clipDurationUs,
                clipOffsetUs = clipOffsetMs * 1000L,
                onAddKeyframe = { offsetUs ->
                    onAddKeyframe(RustKeyframe(
                        param = param,
                        offset = offsetUs,
                        value = currentValueForParam(clip, param),
                        easing = RustEasing.EaseInOut,
                    ))
                },
                onRemoveKeyframe = { indexInParam ->
                    val allIndex = curve.keyframes.indexOf(kfs[indexInParam])
                    onRemoveKeyframe(allIndex)
                },
                onUpdateKeyframe = { indexInParam, updated ->
                    val allIndex = curve.keyframes.indexOf(kfs[indexInParam])
                    onUpdateKeyframe(allIndex, updated)
                },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun KeyframeTrackRow(
    param: String,
    keyframes: List<RustKeyframe>,
    clipDurationUs: Long,
    clipOffsetUs: Long,
    onAddKeyframe: (offsetUs: Long) -> Unit,
    onRemoveKeyframe: (Int) -> Unit,
    onUpdateKeyframe: (Int, RustKeyframe) -> Unit,
) {
    val def = paramDefOrUnknown(param)
    val primary = MaterialTheme.colorScheme.primary
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = { onAddKeyframe(clipOffsetUs.coerceAtLeast(0L)) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Diamond,
                        contentDescription = "Add keyframe at playhead",
                        modifier = Modifier.size(16.dp),
                        tint = primary,
                    )
                }

                Text(
                    def.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(48.dp),
                )

                Box(modifier = Modifier.weight(1f).height(16.dp)) {
                    if (clipDurationUs > 0L) {
                        val lineColor = MaterialTheme.colorScheme.outlineVariant
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            drawLine(lineColor, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.5f)
                            val dotR = 3f
                            for (kf in keyframes) {
                                val x = (kf.offset.toFloat() / clipDurationUs.toFloat() * w)
                                    .coerceIn(dotR, w - dotR)
                                drawCircle(primary, dotR, Offset(x, h / 2))
                            }
                        }
                    }
                }

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

            if (expanded) {
                Column(modifier = Modifier.padding(start = 8.dp, end = 4.dp, bottom = 4.dp)) {
                    keyframes.sortedBy { it.offset }.forEachIndexed { index, kf ->
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

@Composable
private fun KeyframeRow(
    keyframe: RustKeyframe,
    param: String,
    onRemove: () -> Unit,
    onUpdate: (RustKeyframe) -> Unit,
) {
    val def = paramDefOrUnknown(param)
    var editing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth()
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
                "= ${def.format(keyframe.value)}",
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
    param: String,
    onUpdate: (RustKeyframe) -> Unit,
) {
    val def = paramDefOrUnknown(param)
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

        Text("Value: ${def.format(editingKf.value)}", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = editingKf.value.coerceIn(def.range),
            onValueChange = { editingKf = editingKf.copy(value = it) },
            onValueChangeFinished = { onUpdate(editingKf) },
            valueRange = def.range,
            steps = def.steps,
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
