package org.mlm.miniter.ui.components.preview

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.mlm.miniter.editor.model.RustBlendMode
import org.mlm.miniter.editor.model.RustTransitionKind
import kotlin.math.sqrt

/// One entry of the preview's composite stack, in the order the exporter
/// stacks clips: bottom layers first. `video == null` is the export's opaque
/// black canvas, used by fade-through-black.
internal data class PreviewLayer(
    val id: String,
    val video: VisibleMedia.Video?,
    val isPrimary: Boolean = false,
    val alpha: Float = 1f,
    val blend: BlendMode = BlendMode.SrcOver,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val wipe: Shape? = null,
)

/// Blend modes Skia cannot draw, mapped below to the nearest mode it can. The
/// preview approximates these; the export still applies the real formula, so
/// the panel surfaces the approximation instead of hiding it.
internal val approximatedBlendModes: Set<RustBlendMode> = setOf(
    RustBlendMode.LinearBurn,
    RustBlendMode.LinearLight,
    RustBlendMode.VividLight,
    RustBlendMode.PinLight,
    RustBlendMode.HardMix,
    RustBlendMode.Subtract,
    RustBlendMode.Average,
    RustBlendMode.Negation,
    RustBlendMode.Phoenix,
    RustBlendMode.Reflect,
    RustBlendMode.Glow,
)

/// Mirror of `blend_channel` in the exporter, mode by mode. Everything Skia
/// has gets its exact counterpart; the rest fall back to the closest skia mode
/// (mean absolute channel error over a 0..1 grid): LinearLight, VividLight,
/// PinLight, HardMix and Glow to Hardlight, LinearBurn to Multiply, Subtract to
/// Difference, Average to Softlight, Negation to Exclusion, Phoenix to Screen,
/// Reflect to Overlay. LinearDodge is exact as Plus for opaque layers.
internal fun blendModeFor(mode: RustBlendMode): BlendMode = when (mode) {
    RustBlendMode.Normal -> BlendMode.SrcOver
    RustBlendMode.Multiply -> BlendMode.Multiply
    RustBlendMode.Screen -> BlendMode.Screen
    RustBlendMode.Overlay -> BlendMode.Overlay
    RustBlendMode.Darken -> BlendMode.Darken
    RustBlendMode.Lighten -> BlendMode.Lighten
    RustBlendMode.ColorDodge -> BlendMode.ColorDodge
    RustBlendMode.ColorBurn -> BlendMode.ColorBurn
    RustBlendMode.HardLight -> BlendMode.Hardlight
    RustBlendMode.SoftLight -> BlendMode.Softlight
    RustBlendMode.Difference -> BlendMode.Difference
    RustBlendMode.Exclusion -> BlendMode.Exclusion
    RustBlendMode.LinearDodge -> BlendMode.Plus
    RustBlendMode.LinearBurn -> BlendMode.Multiply
    RustBlendMode.LinearLight -> BlendMode.Hardlight
    RustBlendMode.VividLight -> BlendMode.Hardlight
    RustBlendMode.PinLight -> BlendMode.Hardlight
    RustBlendMode.HardMix -> BlendMode.Hardlight
    RustBlendMode.Subtract -> BlendMode.Difference
    RustBlendMode.Average -> BlendMode.Softlight
    RustBlendMode.Negation -> BlendMode.Exclusion
    RustBlendMode.Phoenix -> BlendMode.Screen
    RustBlendMode.Reflect -> BlendMode.Overlay
    RustBlendMode.Glow -> BlendMode.Hardlight
}

/// Clips are treated as neighbouring at the same epsilon the exporter uses.
private const val TRANSITION_ADJACENCY_US: Long = 2_000L

internal fun isNeighbouring(firstEndUs: Long, secondStartUs: Long): Boolean =
    kotlin.math.abs(firstEndUs - secondStartUs) <= TRANSITION_ADJACENCY_US

internal fun easeInOut(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t
    else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f

/// `opacity_pair`: (outgoing, incoming).
internal fun opacityPair(kind: RustTransitionKind, progress: Float): Pair<Float, Float> = when (kind) {
    RustTransitionKind.CrossFade, RustTransitionKind.Dissolve -> 1f - progress to progress
    RustTransitionKind.ZoomIn, RustTransitionKind.ZoomOut -> 1f - progress to progress
    RustTransitionKind.FadeThroughBlack ->
        if (progress < 0.5f) (1f - 2f * progress).coerceIn(0f, 1f) to 0f
        else 0f to (2f * progress - 1f).coerceIn(0f, 1f)
    else -> 1f to 1f
}

/// Displacement of the incoming layer, in canvas fractions.
internal fun slideOffset(kind: RustTransitionKind, progress: Float): Float = when (kind) {
    RustTransitionKind.SlideLeft -> 1f - progress
    RustTransitionKind.SlideRight -> -1f + progress
    else -> 0f
}

internal fun slideOffsetY(kind: RustTransitionKind, progress: Float): Float = when (kind) {
    RustTransitionKind.SlideUp -> 1f - progress
    RustTransitionKind.SlideDown -> -1f + progress
    else -> 0f
}

/// Displacement of the outgoing layer. Only text motion uses it; the exporter's
/// clip composite slides the incoming clip over a stationary outgoing one.
internal fun slideExitOffset(kind: RustTransitionKind, progress: Float): Float = when (kind) {
    RustTransitionKind.SlideLeft -> -progress
    RustTransitionKind.SlideRight -> progress
    else -> 0f
}

internal fun slideExitOffsetY(kind: RustTransitionKind, progress: Float): Float = when (kind) {
    RustTransitionKind.SlideUp -> -progress
    RustTransitionKind.SlideDown -> progress
    else -> 0f
}

/// (outgoing, incoming) scales.
internal fun transitionScales(kind: RustTransitionKind, progress: Float): Pair<Float, Float> = when (kind) {
    RustTransitionKind.ZoomIn -> 1f to (1.6f - 0.6f * progress)
    RustTransitionKind.ZoomOut -> (1f - 0.4f * progress) to 1f
    else -> 1f to 1f
}

internal fun transitionInProgress(clipStartUs: Long, durationUs: Long, playheadUs: Long): Float {
    if (durationUs <= 0L) return 1f
    return ((playheadUs - clipStartUs).toDouble() / durationUs.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun transitionOutFadeStartUs(clipEndUs: Long, durationUs: Long): Long {
    val fadeUs = minOf(durationUs, clipEndUs).coerceAtLeast(0L)
    return clipEndUs - fadeUs
}

internal fun transitionOutProgress(clipEndUs: Long, durationUs: Long, playheadUs: Long): Float {
    val fadeUs = minOf(durationUs, clipEndUs).coerceAtLeast(0L)
    if (fadeUs <= 0L) return 1f
    return ((playheadUs - (clipEndUs - fadeUs)).toDouble() / fadeUs.toDouble()).toFloat().coerceIn(0f, 1f)
}

private const val WIPE_FEATHER = 1.5f

private fun wipeEdge(progress: Float, extent: Float): Float =
    progress * (extent + WIPE_FEATHER * 2f) - WIPE_FEATHER

/// The exporter feathers a wipe over 1.5 canvas pixels; at preview scale that
/// is under a pixel, so the reveal is a hard-edged clip on the same edge.
internal fun wipeReveal(kind: RustTransitionKind, progress: Float, canvas: Rect): Shape? {
    val p = progress.coerceIn(0f, 1f)
    val outline = when (kind) {
        RustTransitionKind.WipeFromLeft -> {
            val edge = wipeEdge(p, canvas.width).coerceAtLeast(0f)
            Outline.Rectangle(Rect(canvas.left, canvas.top, canvas.left + edge, canvas.bottom))
        }
        RustTransitionKind.WipeFromRight -> {
            val edge = wipeEdge(p, canvas.width).coerceAtLeast(0f)
            Outline.Rectangle(Rect(canvas.right - edge, canvas.top, canvas.right, canvas.bottom))
        }
        RustTransitionKind.WipeFromTop -> {
            val edge = wipeEdge(p, canvas.height).coerceAtLeast(0f)
            Outline.Rectangle(Rect(canvas.left, canvas.top, canvas.right, canvas.top + edge))
        }
        RustTransitionKind.WipeFromBottom -> {
            val edge = wipeEdge(p, canvas.height).coerceAtLeast(0f)
            Outline.Rectangle(Rect(canvas.left, canvas.bottom - edge, canvas.right, canvas.bottom))
        }
        RustTransitionKind.IrisOpen -> {
            val halfW = canvas.width / 2f
            val halfH = canvas.height / 2f
            val maxRadius = sqrt(halfW * halfW + halfH * halfH)
            val radius = (wipeEdge(p, maxRadius * 2f) - maxRadius).coerceAtLeast(0f)
            val center = canvas.center
            Outline.Rounded(
                RoundRect(
                    rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                    cornerRadius = CornerRadius(radius),
                )
            )
        }
        else -> return null
    }
    return FixedOutlineShape(outline)
}

private class FixedOutlineShape(private val outline: Outline) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        outline
}
