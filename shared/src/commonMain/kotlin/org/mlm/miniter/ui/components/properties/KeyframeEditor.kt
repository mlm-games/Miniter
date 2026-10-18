package org.mlm.miniter.ui.components.properties

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.editor.model.RustTextClipKind
import org.mlm.miniter.editor.model.videoFilters
import org.mlm.miniter.project.KeyframeParams
import org.mlm.miniter.project.ParamDef
import org.mlm.miniter.project.paramDefOrUnknown

private val easingOptions = listOf(
    RustEasing.Linear,
    RustEasing.EaseIn,
    RustEasing.EaseOut,
    RustEasing.EaseInOut,
    RustEasing.Bezier(0.42f, 0f, 0.58f, 1f),
)

private fun easingLabel(easing: RustEasing): String = when (easing) {
    RustEasing.Linear -> "Linear"
    RustEasing.EaseIn -> "Ease In"
    RustEasing.EaseOut -> "Ease Out"
    RustEasing.EaseInOut -> "Ease In Out"
    is RustEasing.Bezier -> "Bezier"
}

private fun paramDefFor(param: String): ParamDef {
    if (param.startsWith("filter.")) {
        val suffix = param.split(".").drop(2).joinToString(".")
        for (filter in FILTERS) {
            for (prop in filter.properties) {
                if (prop.keyframeSuffix == suffix) {
                    return ParamDef(param, prop.displayName, prop.range, default = 0f, prop.steps, prop.format)
                }
            }
        }
    }
    return paramDefOrUnknown(param)
}

fun currentValueForParam(clip: RustClipSnapshot, paramKey: String): Float {
    return when (paramKey) {
        KeyframeParams.OPACITY -> clip.opacity
        KeyframeParams.VOLUME -> clip.volume
        KeyframeParams.SPEED -> clip.speed.toFloat()
        KeyframeParams.TEXT_POSITION_X -> (clip.kind as? RustTextClipKind)?.style?.positionX ?: 0.5f
        KeyframeParams.TEXT_POSITION_Y -> (clip.kind as? RustTextClipKind)?.style?.positionY ?: 0.9f
        KeyframeParams.TEXT_FONT_SIZE -> (clip.kind as? RustTextClipKind)?.style?.fontSize ?: 24f
        else -> {
            if (paramKey.startsWith("filter.")) {
                val parts = paramKey.split(".")
                if (parts.size >= 3) {
                    val idx = parts[1].toIntOrNull() ?: return 0f
                    val suffix = parts.drop(2).joinToString(".")
                    val paramKeyMapped = FILTERS
                        .flatMap { it.properties }
                        .firstOrNull { it.keyframeSuffix == suffix }
                        ?.paramKey ?: return 0f
                    val filter = clip.videoFilters.getOrNull(idx)?.filter ?: return 0f
                    readFilterProperty(filter, paramKeyMapped)
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
    val clipOffsetUs = (playheadMs * 1000L - clipStartUs).coerceIn(0L, clipDurationUs)

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
                    KeyframeParams.SPEED,
                    KeyframeParams.TRANSFORM_SCALE,
                    KeyframeParams.TRANSFORM_TRANSLATE_X,
                    KeyframeParams.TRANSFORM_TRANSLATE_Y,
                    KeyframeParams.TRANSFORM_ROTATE,
                    KeyframeParams.TEXT_POSITION_X,
                    KeyframeParams.TEXT_POSITION_Y,
                    KeyframeParams.TEXT_FONT_SIZE,
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
            val sorted = kfs.sortedBy { it.offset }
            KeyframeTrackRow(
                param = param,
                keyframes = kfs,
                clipDurationUs = clipDurationUs,
                clipOffsetUs = clipOffsetUs,
                onAddKeyframe = { offsetUs ->
                    onAddKeyframe(RustKeyframe(
                        param = param,
                        offset = offsetUs,
                        value = currentValueForParam(clip, param),
                        easing = RustEasing.EaseInOut,
                    ))
                },
                onRemoveKeyframe = { indexInParam ->
                    val allIndex = curve.keyframes.indexOf(sorted[indexInParam])
                    onRemoveKeyframe(allIndex)
                },
                onUpdateKeyframe = { indexInParam, updated ->
                    val allIndex = curve.keyframes.indexOf(sorted[indexInParam])
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
    val def = paramDefFor(param)
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
                    val sorted = keyframes.sortedBy { it.offset }
                    sorted.forEachIndexed { index, kf ->
                        KeyframeRow(
                            keyframe = kf,
                            param = param,
                            clipDurationUs = clipDurationUs,
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
    clipDurationUs: Long,
    onRemove: () -> Unit,
    onUpdate: (RustKeyframe) -> Unit,
) {
    val def = paramDefFor(param)
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
                easingLabel(keyframe.easing),
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
                clipDurationUs = clipDurationUs,
                onUpdate = onUpdate,
            )
        }
    }
}

@Composable
private fun KeyframeEditorForm(
    keyframe: RustKeyframe,
    param: String,
    clipDurationUs: Long,
    onUpdate: (RustKeyframe) -> Unit,
) {
    val def = paramDefFor(param)
    var editingKf by remember(keyframe) { mutableStateOf(keyframe) }
    val maxSecs = (clipDurationUs / 1_000_000f).coerceAtLeast(0.1f)

    Column(modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp)) {
        Text("Time: ${formatFixed(editingKf.offset / 1_000_000f, 1)}s", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = (editingKf.offset / 1_000_000f).coerceIn(0f, maxSecs),
            onValueChange = { editingKf = editingKf.copy(offset = (it * 1_000_000L).toLong()) },
            onValueChangeFinished = { onUpdate(editingKf) },
            valueRange = 0f..maxSecs,
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            easingOptions.forEach { easing ->
                FilterChip(
                    selected = editingKf.easing == easing ||
                        (easing is RustEasing.Bezier && editingKf.easing is RustEasing.Bezier),
                    onClick = {
                        val current = editingKf.easing
                        val next = if (easing is RustEasing.Bezier && current is RustEasing.Bezier) current else easing
                        editingKf = editingKf.copy(easing = next)
                        onUpdate(editingKf)
                    },
                    label = { Text(easingLabel(easing), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        val easing = editingKf.easing
        if (easing is RustEasing.Bezier) {
            Spacer(Modifier.height(6.dp))
            BezierEasingEditor(
                easing = easing,
                onChange = { updated -> editingKf = editingKf.copy(easing = updated) },
                onCommit = { onUpdate(editingKf) },
            )
        }
    }
}

@Composable
private fun BezierEasingEditor(
    easing: RustEasing.Bezier,
    onChange: (RustEasing.Bezier) -> Unit,
    onCommit: () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BezierCurvePreview(
                x1 = easing.x1,
                y1 = easing.y1,
                x2 = easing.x2,
                y2 = easing.y2,
                modifier = Modifier.size(76.dp),
            )
            Column(Modifier.weight(1f)) {
                BezierSlider("x1", easing.x1, onChange = { onChange(easing.copy(x1 = it)) }, onCommit = onCommit)
                BezierSlider("y1", easing.y1, onChange = { onChange(easing.copy(y1 = it)) }, onCommit = onCommit)
                BezierSlider("x2", easing.x2, onChange = { onChange(easing.copy(x2 = it)) }, onCommit = onCommit)
                BezierSlider("y2", easing.y2, onChange = { onChange(easing.copy(y2 = it)) }, onCommit = onCommit)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                onClick = {
                    onChange(RustEasing.Bezier(0.42f, 0f, 0.58f, 1f))
                    onCommit()
                },
            ) {
                Text("Ease In Out", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = {
                    onChange(RustEasing.Bezier(0f, 0f, 0.58f, 1f))
                    onCommit()
                },
            ) {
                Text("Ease Out", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun BezierSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(18.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(22.dp),
        )
        Text(
            formatFixed(value, 2),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
    }
}

@Composable
private fun BezierCurvePreview(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    modifier: Modifier = Modifier,
) {
    val curveColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.tertiary
    val frameColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val pad = 6f
        val w = size.width - pad * 2f
        val h = size.height - pad * 2f
        fun px(x: Float) = pad + x.coerceIn(0f, 1f) * w
        fun py(y: Float) = pad + (1f - y.coerceIn(0f, 1f)) * h
        drawRect(frameColor, topLeft = Offset(px(0f), py(1f)), size = Size(w, h), style = Stroke(1f))
        val path = Path().apply {
            moveTo(px(0f), py(0f))
            cubicTo(px(x1), py(y1), px(x2), py(y2), px(1f), py(1f))
        }
        drawPath(path, curveColor, style = Stroke(width = 2f))
        drawLine(handleColor, Offset(px(0f), py(0f)), Offset(px(x1), py(y1)), strokeWidth = 1f)
        drawLine(handleColor, Offset(px(1f), py(1f)), Offset(px(x2), py(y2)), strokeWidth = 1f)
        drawCircle(handleColor, radius = 3f, center = Offset(px(x1), py(y1)))
        drawCircle(handleColor, radius = 3f, center = Offset(px(x2), py(y2)))
        drawCircle(curveColor, radius = 3f, center = Offset(px(0f), py(0f)))
        drawCircle(curveColor, radius = 3f, center = Offset(px(1f), py(1f)))
    }
}
