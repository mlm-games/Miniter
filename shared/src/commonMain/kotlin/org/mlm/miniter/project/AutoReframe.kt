package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustEasing
import org.mlm.miniter.editor.model.RustKeyframe
import org.mlm.miniter.engine.ImageData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

const val AUTO_REFRAME_SAMPLE_STEP_MS = 100L
const val AUTO_REFRAME_MAX_SAMPLES = 600L
const val AUTO_REFRAME_SMOOTH_WINDOW_MS = 500L

data class AutoReframeSample(
    val sourceMs: Long,
    val localOffsetUs: Long,
    val x: Float,
    val y: Float,
)

fun saliencyCentroid(frame: ImageData): Pair<Float, Float> {
    val width = frame.width
    val height = frame.height
    if (width < 3 || height < 3 || frame.pixels.size < width * height * 4) return 0.5f to 0.5f
    val pixels = frame.pixels
    val gray = FloatArray(width * height)
    for (i in gray.indices) {
        val p = i * 4
        gray[i] = 0.2126f * (pixels[p].toInt() and 0xFF) +
            0.7152f * (pixels[p + 1].toInt() and 0xFF) +
            0.0722f * (pixels[p + 2].toInt() and 0xFF)
    }
    var sx = 0.0
    var sy = 0.0
    var sw = 0.0
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val row = y * width
            val gx = gray[row + x + 1] - gray[row + x - 1]
            val gy = gray[row + width + x] - gray[row - width + x]
            val u = x.toFloat() / width - 0.5f
            val v = y.toFloat() / height - 0.5f
            val bias = max(0.1f, 1f - (u * u + v * v) * 0.8f)
            var energy = ((gx * gx + gy * gy) * bias).toDouble()
            energy *= energy
            sx += energy * x
            sy += energy * y
            sw += energy
        }
    }
    if (sw <= 1e-12) return 0.5f to 0.5f
    val x = ((sx / sw + 0.5) / width).toFloat()
    val y = ((sy / sw + 0.5) / height).toFloat()
    return x to y
}

fun smoothAutoReframeSamples(samples: List<AutoReframeSample>): List<AutoReframeSample> {
    if (samples.size < 2) return samples
    val window = AUTO_REFRAME_SMOOTH_WINDOW_MS.toDouble()
    return samples.map { sample ->
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (other in samples) {
            val distance = abs(other.sourceMs - sample.sourceMs)
            if (distance > AUTO_REFRAME_SMOOTH_WINDOW_MS) continue
            val weight = 1.0 - distance / window
            if (weight <= 0.0) continue
            sx += other.x * weight
            sy += other.y * weight
            sw += weight
        }
        if (sw <= 0.0) sample else sample.copy(x = (sx / sw).toFloat(), y = (sy / sw).toFloat())
    }
}

fun autoReframeKeyframes(
    samples: List<AutoReframeSample>,
    videoWidth: Int,
    videoHeight: Int,
    canvasWidth: Int,
    canvasHeight: Int,
): List<RustKeyframe> {
    if (samples.isEmpty() || videoWidth <= 0 || videoHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
        return emptyList()
    }
    val fitScale = min(
        canvasWidth.toFloat() / videoWidth,
        canvasHeight.toFloat() / videoHeight,
    )
    if (!fitScale.isFinite() || fitScale <= 0f) return emptyList()
    val scale = (max(
        canvasWidth.toFloat() / videoWidth,
        canvasHeight.toFloat() / videoHeight,
    ) / fitScale).coerceAtLeast(1f)
    val fittedWidth = videoWidth * fitScale / canvasWidth
    val fittedHeight = videoHeight * fitScale / canvasHeight
    if (!scale.isFinite() || scale <= 0f || !fittedWidth.isFinite() || !fittedHeight.isFinite()) {
        return emptyList()
    }
    val halfWidth = (0.5f / (scale * fittedWidth)).coerceAtMost(0.5f)
    val halfHeight = (0.5f / (scale * fittedHeight)).coerceAtMost(0.5f)
    if (!halfWidth.isFinite() || !halfHeight.isFinite() || halfWidth <= 0f || halfHeight <= 0f) {
        return emptyList()
    }
    val keyframes = ArrayList<RustKeyframe>(samples.size * 3)
    var lastOffsetUs = Long.MIN_VALUE
    for (sample in samples) {
        if (sample.localOffsetUs == lastOffsetUs) continue
        lastOffsetUs = sample.localOffsetUs
        val centerX = sample.x.coerceIn(halfWidth, 1f - halfWidth)
        val centerY = sample.y.coerceIn(halfHeight, 1f - halfHeight)
        val translateX = scale * fittedWidth * (0.5f - centerX)
        val translateY = scale * fittedHeight * (0.5f - centerY)
        keyframes += RustKeyframe(KeyframeParams.TRANSFORM_SCALE, sample.localOffsetUs, scale, RustEasing.Linear)
        keyframes += RustKeyframe(KeyframeParams.TRANSFORM_TRANSLATE_X, sample.localOffsetUs, translateX, RustEasing.Linear)
        keyframes += RustKeyframe(KeyframeParams.TRANSFORM_TRANSLATE_Y, sample.localOffsetUs, translateY, RustEasing.Linear)
    }
    return keyframes
}

fun autoReframeLocalOffset(clip: RustClipSnapshot, sourceOffsetUs: Long): Long {
    if (sourceOffsetUs <= 0L) return 0L
    val durationUs = clip.timelineDurationUs.coerceAtLeast(0L)
    if (durationUs <= 0L) return 0L
    if (clip.keyframes.keyframes.none { it.param == KeyframeParams.SPEED }) {
        val speed = if (clip.speed.isFinite() && clip.speed > 0.0) clip.speed else 1.0
        return (sourceOffsetUs.toDouble() / speed).roundToLong().coerceIn(0L, durationUs)
    }
    var low = 0L
    var high = durationUs
    while (high - low > 100L) {
        val mid = low + (high - low) / 2
        if (autoReframeSourceOffsetUs(clip, mid) < sourceOffsetUs) low = mid else high = mid
    }
    return high.coerceIn(0L, durationUs)
}

private fun autoReframeSourceOffsetUs(clip: RustClipSnapshot, localOffsetUs: Long): Long {
    if (localOffsetUs <= 0L) return 0L
    val speedKeys = clip.keyframes.keyframes.filter { it.param == KeyframeParams.SPEED }
    if (speedKeys.isEmpty()) {
        val speed = if (clip.speed.isFinite() && clip.speed > 0.0) clip.speed else 1.0
        return (localOffsetUs.toDouble() * speed).roundToLong()
    }
    val fallback = if (clip.speed.isFinite() && clip.speed >= 0.0) clip.speed else 1.0
    fun rateAt(timeUs: Long): Double {
        val value = clip.keyframes.evaluate(KeyframeParams.SPEED, timeUs) ?: return fallback
        return if (value.isFinite() && value >= 0f) value.toDouble() else fallback
    }
    val bounds = mutableListOf(0L)
    for (key in speedKeys) {
        if (key.offset > 0L && key.offset < localOffsetUs) bounds += key.offset
    }
    bounds += localOffsetUs
    bounds.sort()
    var total = 0.0
    for (i in 0 until bounds.lastIndex) {
        val span = (bounds[i + 1] - bounds[i]).toDouble()
        total += (rateAt(bounds[i]) + rateAt(bounds[i + 1])) * 0.5 * span
    }
    return total.roundToLong()
}
