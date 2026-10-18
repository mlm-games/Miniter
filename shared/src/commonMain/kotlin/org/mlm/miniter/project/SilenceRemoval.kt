package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustClipSnapshot

const val SILENCE_PEAK_ENERGY_RATIO = 3.1622777e-5f
const val SILENCE_ENERGY_FLOOR = 1e-6f
const val SILENCE_MIN_RUN_US = 300_000L

data class SilentRun(val startUs: Long, val endUs: Long)

fun detectSilentSourceRuns(
    windowEnergy: List<Float>,
    windowMs: Int,
    sourceStartUs: Long,
    sourceEndUs: Long,
): List<SilentRun> {
    if (windowEnergy.isEmpty() || windowMs <= 0 || sourceEndUs <= sourceStartUs) return emptyList()
    val windowUs = windowMs * 1000L
    val analysedUs = windowEnergy.size.toLong() * windowUs
    val firstIndex = (sourceStartUs / windowUs).coerceIn(0L, windowEnergy.size.toLong()).toInt()
    val endIndexExclusive = ((sourceEndUs + windowUs - 1L) / windowUs)
        .coerceIn(0L, windowEnergy.size.toLong())
        .toInt()
    if (firstIndex >= endIndexExclusive) return emptyList()

    var peakEnergy = 0f
    for (index in firstIndex until endIndexExclusive) {
        if (windowEnergy[index] > peakEnergy) peakEnergy = windowEnergy[index]
    }
    val threshold = maxOf(peakEnergy * SILENCE_PEAK_ENERGY_RATIO, SILENCE_ENERGY_FLOOR)

    val runs = mutableListOf<SilentRun>()
    var index = firstIndex
    while (index < endIndexExclusive) {
        if (windowEnergy[index] >= threshold) {
            index += 1
            continue
        }
        var end = index
        while (end < endIndexExclusive && windowEnergy[end] < threshold) end += 1
        val startUs = (index.toLong() * windowUs - windowUs).coerceAtLeast(sourceStartUs)
        val endUs = (end.toLong() * windowUs + windowUs).coerceIn(0L, minOf(sourceEndUs, analysedUs))
        if (endUs > startUs) runs += SilentRun(startUs, endUs)
        index = end
    }

    val merged = mutableListOf<SilentRun>()
    for (run in runs) {
        val last = merged.lastOrNull()
        if (last != null && run.startUs <= last.endUs) {
            if (run.endUs > last.endUs) merged[merged.lastIndex] = SilentRun(last.startUs, run.endUs)
        } else {
            merged += run
        }
    }
    return merged.filter {
        it.endUs - it.startUs >= SILENCE_MIN_RUN_US &&
            it.startUs > sourceStartUs &&
            it.endUs < sourceEndUs
    }
}

fun silentRunsToLocal(clip: RustClipSnapshot, runs: List<SilentRun>): List<SilentRun> {
    val durationUs = clip.timelineDurationUs
    if (durationUs <= 0L || runs.isEmpty()) return emptyList()
    val mapped = runs.mapNotNull { run ->
        val startUs = autoReframeLocalOffset(clip, run.startUs - clip.sourceStartUs)
        val endUs = autoReframeLocalOffset(clip, run.endUs - clip.sourceStartUs)
        if (endUs <= startUs) null else SilentRun(startUs, endUs)
    }
    val merged = mutableListOf<SilentRun>()
    for (run in mapped) {
        val last = merged.lastOrNull()
        if (last != null && run.startUs <= last.endUs) {
            if (run.endUs > last.endUs) merged[merged.lastIndex] = SilentRun(last.startUs, run.endUs)
        } else {
            merged += run
        }
    }
    return merged.filter { it.startUs > 0L && it.endUs < durationUs }
}
