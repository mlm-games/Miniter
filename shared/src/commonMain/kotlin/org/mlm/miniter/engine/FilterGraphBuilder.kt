package org.mlm.miniter.engine

import org.mlm.miniter.project.Clip
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.Transition
import org.mlm.miniter.project.TransitionType
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

        var brightness = 0f
        var contrast = 1f
        var saturation = -1f
        var hasBrightnessOrContrast = false

        for (f in filters) {
            when (f.type) {
                FilterType.Brightness -> {
                    brightness = (f.params["value"] ?: 0f) / 100f
                    hasBrightnessOrContrast = true
                }
                FilterType.Contrast -> {
                    contrast = f.params["value"] ?: 1f
                    hasBrightnessOrContrast = true
                }
                FilterType.Saturation -> {
                    saturation = f.params["value"] ?: 1f
                }
                FilterType.Grayscale -> parts.add("hue=s=0")
                FilterType.Blur -> {
                    val r = (f.params["radius"] ?: 5f).toInt().coerceIn(1, 20)
                    parts.add("avgblur=sizeX=$r:sizeY=$r")
                }
                FilterType.Sharpen -> parts.add("unsharp=5:5:1.0:5:5:0.0")
                FilterType.Sepia -> parts.add(
                    "colorchannelmixer=rr=0.393:rg=0.769:rb=0.189:gr=0.349:gg=0.686:gb=0.168:br=0.272:bg=0.534:bb=0.131"
                )
            }
        }

        if (hasBrightnessOrContrast) {
            val offset = (brightness * 255).toInt()
            if (contrast != 1f || offset != 0) {
                val contrastPart = if (contrast != 1f) "(val-128)*$contrast+128" else "val"
                val fullExpr = if (offset != 0) "$contrastPart+$offset" else contrastPart
                parts.add("lutyuv=y=$fullExpr")
            }
        }

        if (saturation >= 0f && saturation != 1f) {
            parts.add("hue=s=$saturation")
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

    fun transitionToXfade(type: TransitionType): String = when (type) {
        TransitionType.CrossFade -> "fade"
        TransitionType.Dissolve -> "dissolve"
        TransitionType.SlideLeft -> "slideleft"
        TransitionType.SlideRight -> "slideright"
    }

    fun buildSegmentedVideoChain(
        videoClips: List<Clip.VideoClip>,
        clipHasAudio: List<Boolean>,
        outWidth: Int,
        outHeight: Int,
        textClips: List<Clip.TextClip>,
        fontPath: String?,
        sb: StringBuilder,
    ): Pair<String, String?> {
        val w = evenUp(outWidth)
        val h = evenUp(outHeight)
        val anyAudio = clipHasAudio.any { it }

        data class Segment(
            val videoLabel: String,
            val audioLabel: String?,
            val durationSec: Double,
            val transitionIn: Transition?,
        )

        val segments = mutableListOf<Segment>()
        var gapIndex = 0
        var prevEndMs = 0L

        for ((i, clip) in videoClips.withIndex()) {
            val gapMs = clip.startMs - prevEndMs
            if (gapMs > 0) {
                val gapSec = gapMs / 1000.0
                val gLabel = "gap$gapIndex"
                sb.append("color=c=black:s=${w}x${h}:d=$gapSec:r=30,format=yuv420p[$gLabel];")
                if (anyAudio) {
                    sb.append("anullsrc=r=44100:cl=stereo,atrim=0:$gapSec,asetpts=PTS-STARTPTS[${gLabel}a];")
                }
                segments.add(Segment(gLabel, if (anyAudio) "${gLabel}a" else null, gapSec, null))
                gapIndex++
            }

            val vf = buildVideoFilterString(
                filters = clip.filters,
                speed = clip.speed,
                outWidth = outWidth,
                outHeight = outHeight,
            )
            val trimFilter = "trim=start=${clip.sourceStartMs / 1000.0}:" +
                    "end=${clip.sourceEndMs / 1000.0},setpts=PTS-STARTPTS"
            sb.append("[$i:v]$trimFilter,$vf[v$i];")

            if (clipHasAudio[i]) {
                val af = buildAudioFilterString(speed = clip.speed, volume = clip.volume)
                val atrimFilter = "atrim=start=${clip.sourceStartMs / 1000.0}:" +
                        "end=${clip.sourceEndMs / 1000.0},asetpts=PTS-STARTPTS"
                val audioFilters = if (af.isNotEmpty()) "$atrimFilter,$af" else atrimFilter
                sb.append("[$i:a]$audioFilters[va$i];")
            } else if (anyAudio) {
                val silenceDur = clip.durationMs / 1000.0
                sb.append("anullsrc=r=44100:cl=stereo,atrim=0:$silenceDur,asetpts=PTS-STARTPTS[va$i];")
            }

            val transition = if (segments.isNotEmpty()) clip.transition else null
            segments.add(
                Segment(
                    "v$i",
                    if (anyAudio) "va$i" else null,
                    clip.durationMs / 1000.0,
                    transition,
                )
            )

            prevEndMs = clip.startMs + clip.durationMs
        }

        if (segments.isEmpty()) return Pair("outv", null)

        var currentV = "[${segments[0].videoLabel}]"
        var accumulatedDuration = segments[0].durationSec
        var chainIdx = 0

        for (i in 1 until segments.size) {
            val seg = segments[i]
            val nextV = "[${seg.videoLabel}]"
            val outLabel = if (i == segments.lastIndex) "outv_raw" else "xf$chainIdx"

            if (seg.transitionIn != null) {
                val tDur = seg.transitionIn.durationMs / 1000.0
                val offset = (accumulatedDuration - tDur).coerceAtLeast(0.0)
                val xfName = transitionToXfade(seg.transitionIn.type)
                sb.append("${currentV}${nextV}xfade=transition=$xfName:duration=$tDur:offset=$offset[$outLabel];")
                accumulatedDuration += seg.durationSec - tDur
            } else {
                sb.append("${currentV}${nextV}concat=n=2:v=1:a=0[$outLabel];")
                accumulatedDuration += seg.durationSec
            }

            currentV = "[$outLabel]"
            chainIdx++
        }

        if (segments.size == 1) {
            sb.append("[${segments[0].videoLabel}]null[outv_raw];")
        }

        val textFilters = buildPostConcatTextFilters(
            videoClips = videoClips,
            textClips = textClips,
            fontPath = fontPath,
        )
        if (textFilters.isNotEmpty()) {
            sb.append("[outv_raw]${textFilters}[outv];")
        } else {
            sb.append("[outv_raw]null[outv];")
        }

        var audioOutLabel: String? = null
        if (anyAudio) {
            var currentA = "[${segments[0].audioLabel}]"
            var aChainIdx = 0

            for (i in 1 until segments.size) {
                val seg = segments[i]
                val nextA = "[${seg.audioLabel}]"
                val outLabel = if (i == segments.lastIndex) "video_audio" else "ac$aChainIdx"
                sb.append("${currentA}${nextA}concat=n=2:v=0:a=1[$outLabel];")
                currentA = "[$outLabel]"
                aChainIdx++
            }

            if (segments.size == 1) {
                sb.append("[${segments[0].audioLabel}]acopy[video_audio];")
            }

            audioOutLabel = "video_audio"
        }

        return Pair("outv", audioOutLabel)
    }
}