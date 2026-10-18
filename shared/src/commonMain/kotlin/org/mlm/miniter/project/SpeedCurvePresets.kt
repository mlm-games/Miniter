package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe

data class SpeedCurvePreset(
    val label: String,
    val points: List<Pair<Float, Float>>,
)

val speedCurvePresets: List<SpeedCurvePreset> = listOf(
    SpeedCurvePreset("Slow -> Fast", listOf(0f to 0.5f, 1f to 2.0f)),
    SpeedCurvePreset("Fast -> Slow", listOf(0f to 2.0f, 1f to 0.5f)),
    SpeedCurvePreset("Hero", listOf(0f to 1.0f, 0.35f to 0.3f, 0.65f to 0.3f, 1f to 1.0f)),
    SpeedCurvePreset("Montage", listOf(0f to 2.0f, 0.5f to 1.0f, 1f to 2.0f)),
    SpeedCurvePreset("Bullet time", listOf(0f to 1.0f, 0.4f to 0.2f, 0.6f to 0.2f, 1f to 1.0f)),
)

fun SpeedCurvePreset.keyframesFor(durationUs: Long): List<RustKeyframe> =
    points.map { (fraction, speed) ->
        RustKeyframe(
            KeyframeParams.SPEED,
            (fraction.toDouble() * durationUs).toLong(),
            speed,
            RustEasing.Linear,
        )
    }
