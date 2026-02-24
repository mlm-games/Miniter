package org.mlm.miniter.engine

import org.mlm.miniter.project.Clip
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.VideoFilter

object FilterGraphBuilder {

    private fun evenUp(value: Int): Int = if (value % 2 == 0) value else value + 1

    private fun fmtSec(sec: Double): String {
        val ms = (sec * 1000).toLong().coerceAtLeast(0)
        return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }

    private fun escapeDrawtext(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace("%", "%%")
    }

    fun buildVideoFilterString(
        filters: List<VideoFilter>,
        speed: Float,
        outWidth: Int,
        outHeight: Int,
        opacity: Float = 1.0f,
    ): String {
        val parts = mutableListOf<String>()

        val w = evenUp(outWidth)
        val h = evenUp(outHeight)

        parts.add("format=yuv420p")
        parts.add("scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2")
        parts.add("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black")

        val eqParams = mutableMapOf<String, String>()
        for (f in filters) {
            when (f.type) {
                FilterType.Brightness -> {
                    val v = f.params["value"] ?: 0f
                    eqParams["brightness"] = "${v / 100f}"
                }
                FilterType.Contrast -> {
                    val v = f.params["value"] ?: 1f
                    eqParams["contrast"] = "$v"
                }
                FilterType.Saturation -> {
                    val v = f.params["value"] ?: 1f
                    eqParams["saturation"] = "$v"
                }
                FilterType.Grayscale -> parts.add("hue=s=0")
                FilterType.Blur -> {
                    val r = (f.params["radius"] ?: 5f).toInt()
                    parts.add("boxblur=$r:$r")
                }
                FilterType.Sharpen -> parts.add("unsharp=5:5:1.0:5:5:0.0")
                FilterType.Sepia -> parts.add(
                    "colorchannelmixer=rr=0.393:rg=0.769:rb=0.189:gr=0.349:gg=0.686:gb=0.168:br=0.272:bg=0.534:bb=0.131"
                )
            }
        }

        if (eqParams.isNotEmpty()) {
            parts.add("eq=" + eqParams.entries.joinToString(":") { "${it.key}=${it.value}" })
        }

        if (opacity < 1.0f) {
            parts.add("format=rgba,colorchannelmixer=aa=$opacity")
        }

        if (speed != 1.0f) {
            parts.add("setpts=${1.0 / speed}*PTS")
        }

        return parts.joinToString(",")
    }

    /**
     * @param textClips All text clips from the project
     * @param timelineStartMs Video clip start on timeline
     * @param timelineEndMs Video clip end on timeline
     * @param outputOffsetSec Cumulative output seconds before this segment (for post-concat mode)
     * @param perClip true = enable times relative to 0 (JVM per-clip filter);
     *                false = enable times include outputOffsetSec (Android post-concat)
     * @param fontPath Optional .ttf font file path
     */
    fun buildTextOverlayFilters(
        textClips: List<Clip.TextClip>,
        timelineStartMs: Long,
        timelineEndMs: Long,
        outputOffsetSec: Double = 0.0,
        perClip: Boolean = true,
        fontPath: String? = null,
    ): String {
        if (textClips.isEmpty()) return ""

        val entries = mutableListOf<String>()

        for (tc in textClips) {
            val tStart = tc.startMs
            val tEnd = tc.startMs + tc.durationMs

            val overlapStart = maxOf(tStart, timelineStartMs)
            val overlapEnd = minOf(tEnd, timelineEndMs)
            if (overlapStart >= overlapEnd) continue

            val enableStart = if (perClip) {
                (overlapStart - timelineStartMs) / 1000.0
            } else {
                outputOffsetSec + (overlapStart - timelineStartMs) / 1000.0
            }
            val enableEnd = if (perClip) {
                (overlapEnd - timelineStartMs) / 1000.0
            } else {
                outputOffsetSec + (overlapEnd - timelineStartMs) / 1000.0
            }

            val escaped = escapeDrawtext(tc.text)
            val color = tc.colorHex.removePrefix("#").let { "0x$it" }
            val x = "(w-tw)*${tc.positionX}"
            val y = "(h-th)*${tc.positionY}"

            val sb = StringBuilder("drawtext=text='$escaped'")
            sb.append(":fontsize=${tc.fontSizeSp.toInt()}")
            sb.append(":fontcolor=$color")
            sb.append(":x=$x:y=$y")
            // Escape commas inside enable expression so FFmpeg doesn't split on them
            sb.append(":enable='between(t\\,${fmtSec(enableStart)}\\,${fmtSec(enableEnd)})'")
            if (fontPath != null) {
                sb.append(":fontfile='$fontPath'")
            }
            if (tc.backgroundColorHex != null) {
                val bg = tc.backgroundColorHex.removePrefix("#")
                sb.append(":box=1:boxcolor=0x${bg}@0.6:boxborderw=5")
            }

            entries.add(sb.toString())
        }

        return entries.joinToString(",")
    }

    /**
     * Calculate text overlay segments for the entire concatenated output.
     * Used by Android post-concat approach.
     */
    fun buildPostConcatTextFilters(
        videoClips: List<Clip.VideoClip>,
        textClips: List<Clip.TextClip>,
        fontPath: String? = null,
    ): String {
        if (textClips.isEmpty()) return ""

        val sorted = videoClips.sortedBy { it.startMs }
        val allEntries = mutableListOf<String>()
        var outputOffsetMs = 0L

        for (clip in sorted) {
            val clipStart = clip.startMs
            val clipEnd = clip.startMs + clip.durationMs

            val segment = buildTextOverlayFilters(
                textClips = textClips,
                timelineStartMs = clipStart,
                timelineEndMs = clipEnd,
                outputOffsetSec = outputOffsetMs / 1000.0,
                perClip = false,
                fontPath = fontPath,
            )
            if (segment.isNotEmpty()) {
                allEntries.add(segment)
            }

            outputOffsetMs += clip.durationMs
        }

        return allEntries.joinToString(",")
    }

    fun buildAudioFilterString(
        speed: Float,
        volume: Float,
    ): String {
        val parts = mutableListOf<String>()

        if (volume != 1.0f) {
            parts.add("volume=$volume")
        }

        if (speed != 1.0f) {
            var remaining = speed.toDouble()
            while (remaining < 0.5) {
                parts.add("atempo=0.5")
                remaining /= 0.5
            }
            while (remaining > 2.0) {
                parts.add("atempo=2.0")
                remaining /= 2.0
            }
            if (remaining != 1.0) {
                parts.add("atempo=$remaining")
            }
        }

        return parts.joinToString(",")
    }

    fun buildGapInput(durationSec: Double, width: Int, height: Int): String {
        val w = evenUp(width)
        val h = evenUp(height)
        return "color=c=black:s=${w}x${h}:d=$durationSec:r=30"
    }
}