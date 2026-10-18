package org.mlm.miniter.project

import org.mlm.miniter.engine.ImageData
import kotlin.math.abs
import kotlin.math.sqrt

const val SCENE_HISTOGRAM_BINS = 64
const val SCENE_DOWNSCALE = 2
const val SCENE_MIN_FRAMES = 4
const val SCENE_THRESHOLD_SIGMA = 2.5f
const val SCENE_MIN_DISTANCE = 0.1f
const val SCENE_MIN_CUT_SPACING_US = 500_000L

data class SceneSample(
    val localOffsetUs: Long,
    val signature: FloatArray,
)

data class SceneBoundary(
    val localOffsetUs: Long,
    val distance: Float,
)

fun sceneSampleTimestamps(sourceStartUs: Long, sourceEndUs: Long): List<Long> {
    val startMs = sourceStartUs / 1000L
    val endMs = sourceEndUs / 1000L
    if (endMs < startMs) return emptyList()
    val spanMs = endMs - startMs
    val stepMs = maxOf(AUTO_REFRAME_SAMPLE_STEP_MS, spanMs / AUTO_REFRAME_MAX_SAMPLES + 1L)
    val timestamps = mutableListOf<Long>()
    var timestampMs = startMs
    while (timestampMs <= endMs) {
        timestamps += timestampMs
        if (endMs - timestampMs < stepMs) break
        timestampMs += stepMs
    }
    if (timestamps.last() < endMs) timestamps += endMs
    return timestamps
}

fun luminanceHistogram(frame: ImageData): FloatArray {
    val width = frame.width
    val height = frame.height
    val histogram = FloatArray(SCENE_HISTOGRAM_BINS)
    if (width < 2 || height < 2 || frame.pixels.size < width * height * 4) return histogram
    val pixels = frame.pixels
    var samples = 0
    var y = 0
    while (y + 1 < height) {
        var x = 0
        while (x + 1 < width) {
            var luma = 0f
            for (dy in 0 until SCENE_DOWNSCALE) {
                val row = (y + dy) * width
                for (dx in 0 until SCENE_DOWNSCALE) {
                    val p = (row + x + dx) * 4
                    luma += 0.2126f * (pixels[p].toInt() and 0xFF) +
                        0.7152f * (pixels[p + 1].toInt() and 0xFF) +
                        0.0722f * (pixels[p + 2].toInt() and 0xFF)
                }
            }
            val bin = (luma * SCENE_HISTOGRAM_BINS / (256f * SCENE_DOWNSCALE * SCENE_DOWNSCALE)).toInt()
            histogram[bin.coerceIn(0, SCENE_HISTOGRAM_BINS - 1)] += 1f
            samples += 1
            x += SCENE_DOWNSCALE
        }
        y += SCENE_DOWNSCALE
    }
    if (samples == 0) return histogram
    val scale = 1f / samples
    for (i in histogram.indices) histogram[i] *= scale
    return histogram
}

fun histogramDistance(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || a.size != b.size) return 0f
    var intersection = 0f
    for (i in a.indices) {
        intersection += if (a[i] < b[i]) a[i] else b[i]
    }
    return (1f - intersection).coerceIn(0f, 1f)
}

fun detectSceneBoundaries(samples: List<SceneSample>): List<SceneBoundary> {
    if (samples.size < SCENE_MIN_FRAMES) return emptyList()
    val gaps = LongArray(samples.size - 1) { samples[it + 1].localOffsetUs - samples[it].localOffsetUs }
    val distances = FloatArray(gaps.size) { histogramDistance(samples[it].signature, samples[it + 1].signature) }
    val sortedGaps = gaps.sortedArray()
    val medianGapUs = sortedGaps[sortedGaps.size / 2]
    if (medianGapUs <= 0L) return emptyList()
    val maxGapUs = medianGapUs * 2L
    val regular = BooleanArray(gaps.size) { gaps[it] in 1L..maxGapUs }
    val baselines = distances.filterIndexed { index, _ -> regular[index] }
    if (baselines.size < 2) return emptyList()
    var sum = 0.0
    for (distance in baselines) sum += distance
    val mean = (sum / baselines.size).toFloat()
    var variance = 0.0
    for (distance in baselines) {
        val delta = (distance - mean).toDouble()
        variance += delta * delta
    }
    val std = sqrt(variance / baselines.size).toFloat()
    val threshold = maxOf(mean + SCENE_THRESHOLD_SIGMA * std, SCENE_MIN_DISTANCE)

    val candidates = mutableListOf<SceneBoundary>()
    for (index in 0 until distances.lastIndex) {
        if (!regular[index] || !regular[index + 1]) continue
        if (distances[index] <= threshold) continue
        if (distances[index + 1] >= threshold) continue
        candidates += SceneBoundary(samples[index + 1].localOffsetUs, distances[index])
    }

    val kept = mutableListOf<SceneBoundary>()
    for (candidate in candidates.sortedByDescending { it.distance }) {
        val collides = kept.any { abs(it.localOffsetUs - candidate.localOffsetUs) < SCENE_MIN_CUT_SPACING_US }
        if (!collides) kept += candidate
    }
    return kept.sortedBy { it.localOffsetUs }
}
