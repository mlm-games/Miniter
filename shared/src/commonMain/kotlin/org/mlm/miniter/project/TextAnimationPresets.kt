package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe

enum class TextAnimationMode { In, Out }

data class TextAnimationBase(
    val opacity: Float,
    val positionY: Float,
    val fontSize: Float,
)

data class TextAnimationPreset(
    val label: String,
    val mode: TextAnimationMode,
    val opacityScaleFrom: Float? = null,
    val opacityScaleTo: Float? = null,
    val positionYOffsetFrom: Float? = null,
    val positionYOffsetTo: Float? = null,
    val fontSizeScaleFrom: Float? = null,
    val fontSizeScaleTo: Float? = null,
    val easing: RustEasing = RustEasing.EaseOut,
) {
    val paramKeys: List<String>
        get() = mutableListOf<String>().apply {
            if (opacityScaleFrom != null || opacityScaleTo != null) add(KeyframeParams.OPACITY)
            if (positionYOffsetFrom != null || positionYOffsetTo != null) add(KeyframeParams.TEXT_POSITION_Y)
            if (fontSizeScaleFrom != null || fontSizeScaleTo != null) add(KeyframeParams.TEXT_FONT_SIZE)
        }
}

private const val TEXT_ANIMATION_SPAN_US = 600_000L

val textAnimationPresets: List<TextAnimationPreset> = listOf(
    TextAnimationPreset(
        label = "Fade In",
        mode = TextAnimationMode.In,
        opacityScaleFrom = 0f,
        opacityScaleTo = 1f,
    ),
    TextAnimationPreset(
        label = "Slide Up",
        mode = TextAnimationMode.In,
        positionYOffsetFrom = 0.12f,
        positionYOffsetTo = 0f,
    ),
    TextAnimationPreset(
        label = "Slide Down",
        mode = TextAnimationMode.In,
        positionYOffsetFrom = -0.12f,
        positionYOffsetTo = 0f,
    ),
    TextAnimationPreset(
        label = "Pop In",
        mode = TextAnimationMode.In,
        fontSizeScaleFrom = 0.5f,
        fontSizeScaleTo = 1f,
    ),
    TextAnimationPreset(
        label = "Fade Out",
        mode = TextAnimationMode.Out,
        opacityScaleFrom = 1f,
        opacityScaleTo = 0f,
        easing = RustEasing.EaseIn,
    ),
    TextAnimationPreset(
        label = "Slide Up",
        mode = TextAnimationMode.Out,
        positionYOffsetFrom = 0f,
        positionYOffsetTo = -0.12f,
        easing = RustEasing.EaseIn,
    ),
    TextAnimationPreset(
        label = "Slide Down",
        mode = TextAnimationMode.Out,
        positionYOffsetFrom = 0f,
        positionYOffsetTo = 0.12f,
        easing = RustEasing.EaseIn,
    ),
    TextAnimationPreset(
        label = "Pop Out",
        mode = TextAnimationMode.Out,
        fontSizeScaleFrom = 1f,
        fontSizeScaleTo = 0.5f,
        easing = RustEasing.EaseIn,
    ),
)

fun textAnimationPresetsFor(mode: TextAnimationMode): List<TextAnimationPreset> =
    textAnimationPresets.filter { it.mode == mode }

fun TextAnimationPreset.keyframesFor(durationUs: Long, base: TextAnimationBase): List<RustKeyframe> {
    val spanUs = durationUs.coerceAtMost(TEXT_ANIMATION_SPAN_US)
    val startUs = if (mode == TextAnimationMode.In) 0L else durationUs - spanUs
    val endUs = if (mode == TextAnimationMode.In) spanUs else durationUs

    val frames = mutableListOf<RustKeyframe>()
    frames += animationTrack(
        KeyframeParams.OPACITY,
        opacityScaleFrom?.let { it * base.opacity },
        opacityScaleTo?.let { it * base.opacity },
        startUs,
        endUs,
        easing,
    )
    frames += animationTrack(
        KeyframeParams.TEXT_POSITION_Y,
        positionYOffsetFrom?.let { it + base.positionY },
        positionYOffsetTo?.let { it + base.positionY },
        startUs,
        endUs,
        easing,
    )
    frames += animationTrack(
        KeyframeParams.TEXT_FONT_SIZE,
        fontSizeScaleFrom?.let { it * base.fontSize },
        fontSizeScaleTo?.let { it * base.fontSize },
        startUs,
        endUs,
        easing,
    )

    return frames
}

private fun animationTrack(
    param: String,
    fromValue: Float?,
    toValue: Float?,
    startUs: Long,
    endUs: Long,
    easing: RustEasing,
): List<RustKeyframe> {
    val from = fromValue ?: toValue ?: return emptyList()
    val to = toValue ?: from

    return listOf(
        RustKeyframe(param, startUs, from, easing),
        RustKeyframe(param, endUs, to, easing),
    )
}
