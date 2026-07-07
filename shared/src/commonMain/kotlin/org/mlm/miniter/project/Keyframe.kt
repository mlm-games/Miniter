package org.mlm.miniter.project

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
data class Keyframe(
    val param: String,
    val offsetMs: Long,
    val value: Float,
    val easing: Easing = Easing.Linear
)

@Serializable
enum class Easing { Linear, EaseIn, EaseOut, EaseInOut }

@Serializable
data class KeyframeCurve(
    val keyframes: List<Keyframe> = emptyList()
) {
    fun evaluate(param: String, timeMs: Long): Float? {
        val relevant = keyframes.filter { it.param == param }.sortedBy { it.offsetMs }
        if (relevant.isEmpty()) return null
        if (relevant.size == 1) return relevant[0].value

        val t = timeMs
        if (t <= relevant.first().offsetMs) return relevant.first().value
        if (t >= relevant.last().offsetMs) return relevant.last().value

        for (i in 0 until relevant.lastIndex) {
            val a = relevant[i]
            val b = relevant[i + 1]
            if (t in a.offsetMs..b.offsetMs) {
                val range = (b.offsetMs - a.offsetMs).toFloat()
                if (range <= 0f) return b.value
                val progress = ((t - a.offsetMs).toFloat() / range).coerceIn(0f, 1f)
                val eased = applyEasing(a.easing, progress)
                return a.value + (b.value - a.value) * eased
            }
        }
        return relevant.last().value
    }

    fun toKeyframesSpec(param: String, durationMs: Long): KeyframesSpec<Float>? {
        val relevant = keyframes.filter { it.param == param }.sortedBy { it.offsetMs }
        if (relevant.size < 2) return null

        return keyframes {
            relevant.forEach { kf ->
                kf.value at kf.offsetMs.toInt() using when (kf.easing) {
                    Easing.Linear -> LinearEasing
                    Easing.EaseIn -> CubicBezierEasing(0.42f, 0f, 1f, 1f)
                    Easing.EaseOut -> CubicBezierEasing(0f, 0f, 0.58f, 1f)
                    Easing.EaseInOut -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
                }
            }
            durationMillis = durationMs.toInt()
        }
    }
}

private fun applyEasing(easing: Easing, t: Float): Float = when (easing) {
    Easing.Linear -> t
    Easing.EaseIn -> t * t * t
    Easing.EaseOut -> 1f - (1f - t) * (1f - t) * (1f - t)
    Easing.EaseInOut -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
}

object KeyframeParams {
    const val OPACITY = "opacity"
    const val VOLUME = "volume"
    const val TRANSFORM_SCALE = "transform.scale"
    const val TRANSFORM_TRANSLATE_X = "transform.translate_x"
    const val TRANSFORM_TRANSLATE_Y = "transform.translate_y"
    const val TRANSFORM_ROTATE = "transform.rotate"
    const val TEXT_POSITION_X = "text.position_x"
    const val TEXT_POSITION_Y = "text.position_y"
    const val TEXT_FONT_SIZE = "text.font_size"

    const val MASK_FEATHER = "mask.feather"
    const val MASK_SCALE = "mask.scale"
    const val MASK_TRANSLATE_X = "mask.translate_x"
    const val MASK_TRANSLATE_Y = "mask.translate_y"
    const val MASK_ROTATE = "mask.rotate"
    const val MASK_SHAPE_LEFT = "mask.shape.left"
    const val MASK_SHAPE_TOP = "mask.shape.top"
    const val MASK_SHAPE_RIGHT = "mask.shape.right"
    const val MASK_SHAPE_BOTTOM = "mask.shape.bottom"
    const val MASK_SHAPE_CENTER_X = "mask.shape.center_x"
    const val MASK_SHAPE_CENTER_Y = "mask.shape.center_y"
    const val MASK_SHAPE_RADIUS_X = "mask.shape.radius_x"
    const val MASK_SHAPE_RADIUS_Y = "mask.shape.radius_y"

    fun filterParam(index: Int, name: String) = "filter.$index.$name"
    fun maskParam(index: Int, name: String) = "mask.$index.$name"
}

data class ParamDef(
    val key: String,
    val displayName: String,
    val range: ClosedFloatingPointRange<Float>,
    val default: Float,
    val steps: Int = 0,
    val format: (Float) -> String,
    val color: Color = Color.Gray,
)

private fun fmtPct(v: Float) = "${(v * 100).toInt()}%"
private fun fmt1d(v: Float) = "${(v * 10).toInt() / 10f}x"
private fun fmtDeg(v: Float) = "${v.toInt()}°"
private fun fmt2d(v: Float) = "${(v * 100).toInt() / 100f}"

val MASK_PARAM_DEFS: Map<String, ParamDef> = listOf(
    ParamDef("feather", "Feather", 0f..0.2f, default = 0f, format = ::fmtPct, color = Color(0xFFE040FB)),
    ParamDef("scale", "Scale", 0.1f..5f, default = 1f, 24, format = ::fmt1d, color = Color(0xFFE040FB)),
    ParamDef("translate_x", "Pan X", -1f..1f, default = 0f, format = ::fmtPct, color = Color(0xFFE040FB)),
    ParamDef("translate_y", "Pan Y", -1f..1f, default = 0f, format = ::fmtPct, color = Color(0xFFE040FB)),
    ParamDef("rotate", "Rotate", -180f..180f, default = 0f, format = ::fmtDeg, color = Color(0xFFE040FB)),
    ParamDef("left", "Left", 0f..1f, default = 0.1f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("top", "Top", 0f..1f, default = 0.1f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("right", "Right", 0f..1f, default = 0.9f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("bottom", "Bottom", 0f..1f, default = 0.9f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("center_x", "Center X", 0f..1f, default = 0.5f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("center_y", "Center Y", 0f..1f, default = 0.5f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("radius_x", "Radius X", 0f..1f, default = 0.3f, format = ::fmtPct, color = Color(0xFFCE93D8)),
    ParamDef("radius_y", "Radius Y", 0f..1f, default = 0.3f, format = ::fmtPct, color = Color(0xFFCE93D8)),
).associateBy { it.key }

val ALL_PARAMS: List<ParamDef> = listOf(
    ParamDef(KeyframeParams.OPACITY, "Opacity", 0f..1f, default = 1f, format = ::fmtPct, color = Color(0xFF5B8DEF)),
    ParamDef(KeyframeParams.VOLUME, "Volume", 0f..2f, default = 1f, 19, format = ::fmtPct, color = Color(0xFF4CAF50)),
    ParamDef(KeyframeParams.TRANSFORM_SCALE, "Scale", 0.5f..3f, default = 1f, 24, format = ::fmt1d, color = Color(0xFFFF9800)),
    ParamDef(KeyframeParams.TRANSFORM_TRANSLATE_X, "Pan X", -1f..1f, default = 0.0f, format = ::fmtPct, color = Color(0xFFFF9800)),
    ParamDef(KeyframeParams.TRANSFORM_TRANSLATE_Y, "Pan Y", -1f..1f, default = 0.0f, format = ::fmtPct, color = Color(0xFFFF9800)),
    ParamDef(KeyframeParams.TRANSFORM_ROTATE, "Rotate", -180f..180f, default = 0f, format = ::fmtDeg, color = Color(0xFFFF9800)),
    ParamDef(KeyframeParams.TEXT_POSITION_X, "Text X", 0f..1f, default = 0.5f, format = ::fmtPct, color = Color(0xFF26A69A)),
    ParamDef(KeyframeParams.TEXT_POSITION_Y, "Text Y", 0f..1f, default = 0.9f, format = ::fmtPct, color = Color(0xFF26A69A)),
    ParamDef(KeyframeParams.TEXT_FONT_SIZE, "Font Size", 12f..120f, default = 24f, 26, format = { "${it.toInt()}sp" }, color = Color(0xFF26A69A)),
    ParamDef(KeyframeParams.MASK_FEATHER, "Mask Feather", 0f..100f, default = 0f, format = { "${it.toInt()}px" }, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SCALE, "Mask Scale", 0.1f..5f, default = 1f, format = ::fmt1d, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_TRANSLATE_X, "Mask X", -1f..1f, default = 0f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_TRANSLATE_Y, "Mask Y", -1f..1f, default = 0f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_ROTATE, "Mask Rotate", -180f..180f, default = 0f, format = ::fmtDeg, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_LEFT, "Shape Left", 0f..1f, default = 0.25f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_TOP, "Shape Top", 0f..1f, default = 0.25f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_RIGHT, "Shape Right", 0f..1f, default = 0.75f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_BOTTOM, "Shape Bottom", 0f..1f, default = 0.75f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_CENTER_X, "Center X", 0f..1f, default = 0.5f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_CENTER_Y, "Center Y", 0f..1f, default = 0.5f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_RADIUS_X, "Radius X", 0f..0.5f, default = 0.25f, format = ::fmtPct, color = Color(0xFF7E57C2)),
    ParamDef(KeyframeParams.MASK_SHAPE_RADIUS_Y, "Radius Y", 0f..0.5f, default = 0.25f, format = ::fmtPct, color = Color(0xFF7E57C2)),
)

val ALL_PARAMS_BY_KEY: Map<String, ParamDef> = ALL_PARAMS.associateBy { it.key }

fun defaultOf(key: String): Float = ALL_PARAMS_BY_KEY[key]?.default ?: 0f

fun paramDefOrUnknown(key: String): ParamDef = ALL_PARAMS_BY_KEY[key] ?: ParamDef(
    key = key,
    displayName = key,
    range = 0f..1f,
    default = 0f,
    format = ::fmt2d,
)
