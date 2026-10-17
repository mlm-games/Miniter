package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe

data class KenBurnsPreset(
    val label: String,
    val fromScale: Float,
    val toScale: Float,
    val fromPanX: Float,
    val toPanX: Float,
)

val kenBurnsPresets: List<KenBurnsPreset> = listOf(
    KenBurnsPreset("Zoom In", 1.0f, 1.15f, 0f, 0f),
    KenBurnsPreset("Zoom Out", 1.15f, 1.0f, 0f, 0f),
    KenBurnsPreset("Pan Right", 1.2f, 1.2f, -0.08f, 0.08f),
    KenBurnsPreset("Pan Left", 1.2f, 1.2f, 0.08f, -0.08f),
)

fun randomKenBurnsPreset(): KenBurnsPreset = kenBurnsPresets.random()

fun KenBurnsPreset.keyframesFor(durationUs: Long): List<RustKeyframe> {
    val frames = mutableListOf<RustKeyframe>()

    if (fromScale == toScale) {
        frames += RustKeyframe(
            KeyframeParams.TRANSFORM_SCALE,
            0L,
            fromScale,
            RustEasing.Linear,
        )
    } else {
        frames += RustKeyframe(
            KeyframeParams.TRANSFORM_SCALE,
            0L,
            fromScale,
            RustEasing.EaseInOut,
        )
        frames += RustKeyframe(
            KeyframeParams.TRANSFORM_SCALE,
            durationUs,
            toScale,
            RustEasing.EaseInOut,
        )
    }

    if (fromPanX != toPanX) {
        frames += RustKeyframe(
            KeyframeParams.TRANSFORM_TRANSLATE_X,
            0L,
            fromPanX,
            RustEasing.EaseInOut,
        )
        frames += RustKeyframe(
            KeyframeParams.TRANSFORM_TRANSLATE_X,
            durationUs,
            toPanX,
            RustEasing.EaseInOut,
        )
    }

    return frames
}
