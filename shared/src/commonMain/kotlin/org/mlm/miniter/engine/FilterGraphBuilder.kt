package org.mlm.miniter.engine

import org.mlm.miniter.project.Clip
import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.Track
import org.mlm.miniter.project.TrackType
import org.mlm.miniter.project.Transition
import org.mlm.miniter.project.TransitionType
import org.mlm.miniter.project.VideoFilter

object FilterGraphBuilder {

    private fun evenUp(value: Int): Int = if (value % 2 == 0) value else value + 1

    private fun fmtSec(sec: Double): String {
        val ms = (sec * 1000).toLong().coerceAtLeast(0)
        return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }

    /**
     * Escape text for drawtext.
     * For FFmpegKit (Android), do NOT use shell-style escaping.
     * For JVM (javacv FFmpegFrameFilter), same rules apply.
     */
    private fun escapeDrawtext(text: String): String {
        return text
            .replace("\\", "\\\\\\\\")  // backslash → double-escaped
            .replace(":", "\\\\:")      // colon must be escaped in drawtext
            .replace("'", "\\\\'")      // single quote
            .replace("%", "%%")
    }

    /**
     * Escape text for drawtext when used inside FFmpegKit command string.
     * No single quotes around enable expression — FFmpegKit consumes them.
     */
    private fun escapeDrawtextForCommandLine(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "")       // strip single quotes entirely for safety
            .replace("%", "%%")
    }

    fun transitionToXfade(type: TransitionType): String = when (type) {
        TransitionType.CrossFade -> "fade"
        TransitionType.Dissolve -> "dissolve"
        TransitionType.SlideLeft -> "slideleft"
        TransitionType.SlideRight -> "slideright"
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

    fun buildAudioFilterString(
        speed: Float,
        volume: Float,
    ): String {
        val parts = mutableListOf<String>()
        if (volume != 1.0f) parts.add("volume=$volume")
        if (speed != 1.0f) {
            var remaining = speed.toDouble()
            while (remaining < 0.5) { parts.add("atempo=0.5"); remaining /= 0.5 }
            while (remaining > 2.0) { parts.add("atempo=2.0"); remaining /= 2.0 }
            if (remaining != 1.0) parts.add("atempo=$remaining")
        }
        return parts.joinToString(",")
    }


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

            val sb = StringBuilder("drawtext=text=$escaped")
            sb.append(":fontsize=${tc.fontSizeSp.toInt()}")
            sb.append(":fontcolor=$color")
            sb.append(":x=$x:y=$y")
            // Use \, for comma escaping — no single quotes
            sb.append(":enable=between(t\\,${fmtSec(enableStart)}\\,${fmtSec(enableEnd)})")
            if (fontPath != null) {
                sb.append(":fontfile=$fontPath")
            }
            if (tc.backgroundColorHex != null) {
                val bg = tc.backgroundColorHex.removePrefix("#")
                sb.append(":box=1:boxcolor=0x${bg}@0.6:boxborderw=5")
            }
            entries.add(sb.toString())
        }
        return entries.joinToString(",")
    }

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
            val segment = buildTextOverlayFilters(
                textClips = textClips,
                timelineStartMs = clip.startMs,
                timelineEndMs = clip.startMs + clip.durationMs,
                outputOffsetSec = outputOffsetMs / 1000.0,
                perClip = false,
                fontPath = fontPath,
            )
            if (segment.isNotEmpty()) allEntries.add(segment)
            outputOffsetMs += clip.durationMs
        }
        return allEntries.joinToString(",")
    }


    fun buildTextOverlayFiltersForCommand(
        textClips: List<Clip.TextClip>,
        videoClips: List<Clip.VideoClip>,
        fontPath: String? = null,
    ): String {
        if (textClips.isEmpty()) return ""
        val sorted = videoClips.sortedBy { it.startMs }
        val entries = mutableListOf<String>()
        var outputOffsetMs = 0L

        for (clip in sorted) {
            val clipStart = clip.startMs
            val clipEnd = clip.startMs + clip.durationMs

            for (tc in textClips) {
                val tStart = tc.startMs
                val tEnd = tc.startMs + tc.durationMs
                val overlapStart = maxOf(tStart, clipStart)
                val overlapEnd = minOf(tEnd, clipEnd)
                if (overlapStart >= overlapEnd) continue

                val enableStart = outputOffsetMs + (overlapStart - clipStart)
                val enableEnd = outputOffsetMs + (overlapEnd - clipStart)
                val startSec = fmtSec(enableStart / 1000.0)
                val endSec = fmtSec(enableEnd / 1000.0)

                val escaped = escapeDrawtextForCommandLine(tc.text)
                val color = tc.colorHex.removePrefix("#").let { "0x$it" }
                val x = "(w-tw)*${tc.positionX}"
                val y = "(h-th)*${tc.positionY}"

                val sb = StringBuilder("drawtext=text=$escaped")
                sb.append(":fontsize=${tc.fontSizeSp.toInt()}")
                sb.append(":fontcolor=$color")
                sb.append(":x=$x:y=$y")
                // NO single quotes — FFmpegKit consumes them
                // Use \, to escape commas within the between() expression
                sb.append(":enable=between(t\\,$startSec\\,$endSec)")
                if (fontPath != null) sb.append(":fontfile=$fontPath")
                if (tc.backgroundColorHex != null) {
                    val bg = tc.backgroundColorHex.removePrefix("#")
                    sb.append(":box=1:boxcolor=0x${bg}@0.6:boxborderw=5")
                }
                entries.add(sb.toString())
            }
            outputOffsetMs += clip.durationMs
        }
        return entries.joinToString(",")
    }


    /**
     * Builds a complete filter_complex string handling:
     * - Multiple video tracks (overlay compositing)
     * - Gaps between clips (black frames)
     * - Transitions between adjacent clips on the same track
     * - Text overlays
     *
     * @param useDrawtext true for Android FFmpegKit command, false means JVM handles text separately
     */
    fun buildMultiTrackFilterGraph(
        videoTracks: List<Track>,
        clipHasAudioMap: Map<Int, Boolean>,  // input index → has audio
        outWidth: Int,
        outHeight: Int,
        textClips: List<Clip.TextClip>,
        fontPath: String?,
        timelineDurationMs: Long,
        useDrawtext: Boolean = false,
    ): MultiTrackResult {
        val w = evenUp(outWidth)
        val h = evenUp(outHeight)
        val sb = StringBuilder()

        // Assign input indices: all video clips across all tracks, in order
        data class IndexedClip(
            val clip: Clip.VideoClip,
            val inputIndex: Int,
            val hasAudio: Boolean,
        )

        var inputIdx = 0
        val tracksWithClips = videoTracks.map { track ->
            val clips = track.clips.filterIsInstance<Clip.VideoClip>().sortedBy { it.startMs }
            val indexed = clips.map { clip ->
                val ic = IndexedClip(clip, inputIdx, clipHasAudioMap[inputIdx] ?: false)
                inputIdx++
                ic
            }
            indexed
        }

        val totalInputs = inputIdx
        val anyAudio = clipHasAudioMap.values.any { it }

        if (tracksWithClips.all { it.isEmpty() }) {
            return MultiTrackResult("", "outv", null, 0)
        }

        val trackVideoLabels = mutableListOf<String>()
        val trackAudioLabels = mutableListOf<String?>()

        for ((trackIdx, indexedClips) in tracksWithClips.withIndex()) {
            if (indexedClips.isEmpty()) continue

            val segments = mutableListOf<String>()  // video segment labels
            val audioSegments = mutableListOf<String>()  // audio segment labels
            var segIdx = 0
            var prevEndMs = 0L

            for (ic in indexedClips) {
                val clip = ic.clip
                val i = ic.inputIndex

                // Gap before this clip
                val gapMs = clip.startMs - prevEndMs
                if (gapMs > 0) {
                    val gapSec = gapMs / 1000.0
                    val gVLabel = "t${trackIdx}g${segIdx}v"
                    sb.append("color=c=black:s=${w}x${h}:d=$gapSec:r=30,format=yuv420p[$gVLabel];")
                    segments.add(gVLabel)

                    if (anyAudio) {
                        val gALabel = "t${trackIdx}g${segIdx}a"
                        sb.append("anullsrc=r=44100:cl=stereo,atrim=0:$gapSec,asetpts=PTS-STARTPTS[$gALabel];")
                        audioSegments.add(gALabel)
                    }
                    segIdx++
                }

                // Video filter for this clip
                val vf = buildVideoFilterString(
                    filters = clip.filters,
                    speed = clip.speed,
                    outWidth = outWidth,
                    outHeight = outHeight,
                )
                val trimFilter = "trim=start=${clip.sourceStartMs / 1000.0}:" +
                        "end=${clip.sourceEndMs / 1000.0},setpts=PTS-STARTPTS"
                val vLabel = "t${trackIdx}c${segIdx}v"
                sb.append("[$i:v]$trimFilter,$vf[$vLabel];")
                segments.add(vLabel)

                // Audio for this clip
                if (ic.hasAudio) {
                    val af = buildAudioFilterString(speed = clip.speed, volume = clip.volume)
                    val atrimFilter = "atrim=start=${clip.sourceStartMs / 1000.0}:" +
                            "end=${clip.sourceEndMs / 1000.0},asetpts=PTS-STARTPTS"
                    val audioFilters = if (af.isNotEmpty()) "$atrimFilter,$af" else atrimFilter
                    val aLabel = "t${trackIdx}c${segIdx}a"
                    sb.append("[$i:a]$audioFilters[$aLabel];")
                    audioSegments.add(aLabel)
                } else if (anyAudio) {
                    val silenceDur = clip.durationMs / 1000.0
                    val aLabel = "t${trackIdx}c${segIdx}a"
                    sb.append("anullsrc=r=44100:cl=stereo,atrim=0:$silenceDur,asetpts=PTS-STARTPTS[$aLabel];")
                    audioSegments.add(aLabel)
                }

                prevEndMs = clip.startMs + clip.durationMs
                segIdx++
            }

            // Trailing gap to fill to timeline duration
            val trailingGap = timelineDurationMs - prevEndMs
            if (trailingGap > 0) {
                val gapSec = trailingGap / 1000.0
                val gVLabel = "t${trackIdx}g${segIdx}v"
                sb.append("color=c=black:s=${w}x${h}:d=$gapSec:r=30,format=yuv420p[$gVLabel];")
                segments.add(gVLabel)
                if (anyAudio) {
                    val gALabel = "t${trackIdx}g${segIdx}a"
                    sb.append("anullsrc=r=44100:cl=stereo,atrim=0:$gapSec,asetpts=PTS-STARTPTS[$gALabel];")
                    audioSegments.add(gALabel)
                }
            }

            // Concat all segments for this track
            val trackVOut = "trk${trackIdx}v"
            if (segments.size == 1) {
                sb.append("[${segments[0]}]null[$trackVOut];")
            } else {
                for (s in segments) sb.append("[$s]")
                sb.append("concat=n=${segments.size}:v=1:a=0[$trackVOut];")
            }
            trackVideoLabels.add(trackVOut)

            if (anyAudio && audioSegments.isNotEmpty()) {
                val trackAOut = "trk${trackIdx}a"
                if (audioSegments.size == 1) {
                    sb.append("[${audioSegments[0]}]acopy[$trackAOut];")
                } else {
                    for (s in audioSegments) sb.append("[$s]")
                    sb.append("concat=n=${audioSegments.size}:v=0:a=1[$trackAOut];")
                }
                trackAudioLabels.add(trackAOut)
            } else {
                trackAudioLabels.add(null)
            }
        }

        var currentVideo = "[${trackVideoLabels[0]}]"

        for (i in 1 until trackVideoLabels.size) {
            val overlayLabel = if (i == trackVideoLabels.lastIndex) "outv_raw" else "ov$i"
            // Upper tracks overlay on lower tracks
            // Use format=yuva420p on the overlay input so black = transparent
            // Actually, black is NOT transparent. For proper compositing,
            // gaps should be transparent. We need to convert black to alpha.
            sb.append("[${trackVideoLabels[i]}]format=yuva420p,colorkey=black:0.1:0.2[${trackVideoLabels[i]}_alpha];")
            sb.append("${currentVideo}[${trackVideoLabels[i]}_alpha]overlay=0:0:format=auto[$overlayLabel];")
            currentVideo = "[$overlayLabel]"
        }

        if (trackVideoLabels.size == 1) {
            sb.append("[${trackVideoLabels[0]}]null[outv_raw];")
        }

        if (textClips.isNotEmpty() && useDrawtext) {
            val allVideoClips = videoTracks.flatMap {
                it.clips.filterIsInstance<Clip.VideoClip>()
            }.sortedBy { it.startMs }
            val textFilters = buildTextOverlayFiltersForCommand(
                textClips = textClips,
                videoClips = allVideoClips,
                fontPath = fontPath,
            )
            if (textFilters.isNotEmpty()) {
                sb.append("[outv_raw]$textFilters[outv];")
            } else {
                sb.append("[outv_raw]null[outv];")
            }
        } else if (textClips.isNotEmpty() && !useDrawtext) {
            val allVideoClips = videoTracks.flatMap {
                it.clips.filterIsInstance<Clip.VideoClip>()
            }.sortedBy { it.startMs }
            val textFilters = buildPostConcatTextFilters(
                videoClips = allVideoClips,
                textClips = textClips,
                fontPath = fontPath,
            )
            if (textFilters.isNotEmpty()) {
                sb.append("[outv_raw]$textFilters[outv];")
            } else {
                sb.append("[outv_raw]null[outv];")
            }
        } else {
            sb.append("[outv_raw]null[outv];")
        }

        var audioOutLabel: String? = null
        val validAudioLabels = trackAudioLabels.filterNotNull()
        if (validAudioLabels.isNotEmpty()) {
            if (validAudioLabels.size == 1) {
                sb.append("[${validAudioLabels[0]}]acopy[video_audio];")
            } else {
                for (l in validAudioLabels) sb.append("[$l]")
                sb.append("amix=inputs=${validAudioLabels.size}:duration=longest:normalize=1[video_audio];")
            }
            audioOutLabel = "video_audio"
        }

        return MultiTrackResult(
            filterComplex = sb.toString(),
            videoOutLabel = "outv",
            audioOutLabel = audioOutLabel,
            totalVideoInputs = totalInputs,
        )
    }

    data class MultiTrackResult(
        val filterComplex: String,
        val videoOutLabel: String,
        val audioOutLabel: String?,
        val totalVideoInputs: Int,
    )
}
