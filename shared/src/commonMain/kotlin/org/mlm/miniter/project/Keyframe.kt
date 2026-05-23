package org.mlm.miniter.project

import androidx.compose.animation.core.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class Keyframe(
    val param: String,          // e.g. "opacity", "transform.scale", "transform.translate_x"
    val offsetMs: Long,         // milliseconds for easy Compose interop
    val value: Float,
    val easing: Easing = Easing.Linear
)

@Serializable
enum class Easing { Linear, EaseIn, EaseOut, EaseInOut }

@Serializable
data class KeyframeCurve(
    val keyframes: List<Keyframe> = emptyList()
) {
    // for previews / live UI
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
                    Easing.EaseIn -> CubicBezierEasing(0.42f, 0f, 1f, 1f) // approx EaseIn
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

    fun filterParam(index: Int, name: String) = "filter.$index.$name"
}

data class AnimatableParam(
    val key: String,
    val displayName: String,
    val valueRange: ClosedFloatingPointRange<Float>,
    val steps: Int = 0,
    val format: (Float) -> String,
)
