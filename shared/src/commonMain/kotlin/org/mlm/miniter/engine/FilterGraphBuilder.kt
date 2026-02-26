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

    private fun escapeDrawtext(text: String): String {
        return text
            .replace("\\", "\\\\\\\\")
            .replace(":", "\\\\:")
            .replace("'", "\\\\'")
            .replace("%", "%%")
    }

    fun buildVideoFilterString(
        filters: List<VideoFilter>,
        speed: Float,
        outWidth: Int,
        outHeight: Int,
        opacity: Float = 1.0f,
        fadeInSec: Double = 0.0,
        fadeOutStartSec: Double = 0.0,
        fadeOutDurationSec: Double = 0.0,
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

        // ── Fade transitions (applied BEFORE speed change) ──
        if (fadeInSec > 0.0) {
            parts.add("fade=t=in:st=0:d=$fadeInSec")
        }
        if (fadeOutDurationSec > 0.0 && fadeOutStartSec >= 0.0) {
            parts.add("fade=t=out:st=$fadeOutStartSec:d=$fadeOutDurationSec")
        }

        if (speed != 1.0f) {
            parts.add("setpts=${1.0 / speed}*PTS")
        }

        return parts.joinToString(",")
    }

    fun buildAudioFilterString(
        speed: Float,
        volume: Float,
        fadeInSec: Double = 0.0,
        fadeOutStartSec: Double = 0.0,
        fadeOutDurationSec: Double = 0.0,
    ): String {
        val parts = mutableListOf<String>()
        if (volume != 1.0f) parts.add("volume=$volume")

        if (fadeInSec > 0.0) {
            parts.add("afade=t=in:st=0:d=$fadeInSec")
        }
        if (fadeOutDurationSec > 0.0) {
            parts.add("afade=t=out:st=$fadeOutStartSec:d=$fadeOutDurationSec")
        }

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
            val overlapStart = maxOf(tc.startMs, timelineStartMs)
            val overlapEnd = minOf(tc.startMs + tc.durationMs, timelineEndMs)
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
            sb.append(":enable=between(t\\,${fmtSec(enableStart)}\\,${fmtSec(enableEnd)})")
            if (fontPath != null) sb.append(":fontfile=$fontPath")
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

    fun generateAssSubtitleContent(
        textClips: List<Clip.TextClip>,
        videoClips: List<Clip.VideoClip>,
        playResX: Int,
        playResY: Int,
    ): String {
        if (textClips.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("PlayResX: $playResX")
        sb.appendLine("PlayResY: $playResY")
        sb.appendLine("WrapStyle: 0")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine()

        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        sb.appendLine("Style: Default,Roboto,24,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1")

        for (tc in textClips) {
            val styleName = "S${tc.id.hashCode().toUInt()}"
            val primary = hexToAssColor(tc.colorHex)
            val bg = tc.backgroundColorHex?.let { hexToAssColor(it) } ?: "&H00000000"
            val fontSize = tc.fontSizeSp.toInt().coerceAtLeast(8)
            val alignment = assAlignment(tc.positionX, tc.positionY)
            val border = if (tc.backgroundColorHex != null) 3 else 1
            sb.appendLine(
                "Style: $styleName,Roboto,$fontSize,$primary,&H000000FF,&H00000000,$bg," +
                        "0,0,0,0,100,100,0,0,$border,2,0,$alignment,10,10,10,1"
            )
        }

        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

        // Multi-track: output preserves timeline positions (fade transitions don't change duration)
        for (tc in textClips) {
            val styleName = "S${tc.id.hashCode().toUInt()}"
            sb.appendLine(
                "Dialogue: 0,${msToAss(tc.startMs.coerceAtLeast(0))},${msToAss(tc.startMs + tc.durationMs)}," +
                        "$styleName,,0,0,0,,${escapeAss(tc.text)}"
            )
        }

        return sb.toString()
    }

    private fun hexToAssColor(hex: String): String {
        val c = hex.removePrefix("#")
        if (c.length < 6) return "&H00FFFFFF"
        val r = c.substring(0, 2)
        val g = c.substring(2, 4)
        val b = c.substring(4, 6)
        return "&H00${b}${g}${r}"
    }

    private fun assAlignment(x: Float, y: Float): Int {
        val col = when { x < 0.33f -> 0; x > 0.66f -> 2; else -> 1 }
        val row = when { y < 0.33f -> 2; y > 0.66f -> 0; else -> 1 }
        return when (row * 3 + col) {
            0 -> 1; 1 -> 2; 2 -> 3; 3 -> 4; 4 -> 5; 5 -> 6; 6 -> 7; 7 -> 8; 8 -> 9; else -> 2
        }
    }

    private fun msToAss(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val cs = (ms % 1000) / 10
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    private fun escapeAss(text: String): String =
        text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")

    data class MultiTrackResult(
        val filterComplex: String,
        val videoOutLabel: String,
        val audioOutLabel: String?,
        val totalVideoInputs: Int,
    )

    /**
     * Builds complete filter_complex with:
     * - Per-track clip chains with gaps
     * - Fade transitions between adjacent clips (preserves duration!)
     * - Multi-track overlay compositing
     * - Text via ASS subtitles (Android) or drawtext (JVM)
     *
     * Transition model:
     * - `clip.transition` = "how this clip ENTERS" (transition in)
     * - First clip on track: transition = fade in from black
     * - Non-first clip: transition = dip-to-black crossfade with predecessor
     *   (predecessor gets matching fade-out, this clip gets fade-in)
     */
    fun buildMultiTrackFilterGraph(
        videoTracks: List<Track>,
        clipHasAudioMap: Map<Int, Boolean>,
        outWidth: Int,
        outHeight: Int,
        textClips: List<Clip.TextClip>,
        fontPath: String?,
        timelineDurationMs: Long,
        useDrawtext: Boolean = false,
        subtitleFilePath: String? = null,
    ): MultiTrackResult {
        val w = evenUp(outWidth)
        val h = evenUp(outHeight)
        val sb = StringBuilder()

        data class IndexedClip(
            val clip: Clip.VideoClip,
            val inputIndex: Int,
            val hasAudio: Boolean,
        )

        // Assign input indices
        var inputIdx = 0
        val tracksWithClips = videoTracks.map { track ->
            val clips = track.clips.filterIsInstance<Clip.VideoClip>().sortedBy { it.startMs }
            clips.map { clip ->
                val ic = IndexedClip(clip, inputIdx, clipHasAudioMap[inputIdx] ?: false)
                inputIdx++
                ic
            }
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

            val segments = mutableListOf<String>()
            val audioSegments = mutableListOf<String>()
            var segIdx = 0
            var prevEndMs = 0L

            for ((clipPos, ic) in indexedClips.withIndex()) {
                val clip = ic.clip
                val i = ic.inputIndex

                // ── Gap before this clip ──
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

                // ── Determine fade in/out for this clip ──
                val clipSourceDurationSec = (clip.sourceEndMs - clip.sourceStartMs) / 1000.0
                val clipOutputDurationSec = clip.durationMs / 1000.0

                var fadeInSec = 0.0
                var fadeOutStartSec = 0.0
                var fadeOutDurationSec = 0.0
                var audioFadeInSec = 0.0
                var audioFadeOutStartSec = 0.0
                var audioFadeOutDurationSec = 0.0

                // This clip's transition = how it ENTERS
                val transitionIn = clip.transition
                if (transitionIn != null) {
                    val tDur = transitionIn.durationMs / 1000.0
                    fadeInSec = tDur.coerceAtMost(clipSourceDurationSec * 0.5)
                    audioFadeInSec = tDur.coerceAtMost(clipOutputDurationSec * 0.5)
                }

                // Next clip's transition = this clip needs to fade OUT
                val nextClip = indexedClips.getOrNull(clipPos + 1)?.clip
                val transitionOut = nextClip?.transition
                if (transitionOut != null) {
                    val tDur = transitionOut.durationMs / 1000.0
                    fadeOutDurationSec = tDur.coerceAtMost(clipSourceDurationSec * 0.5)
                    fadeOutStartSec = clipSourceDurationSec - fadeOutDurationSec
                    audioFadeOutDurationSec = tDur.coerceAtMost(clipOutputDurationSec * 0.5)
                    audioFadeOutStartSec = clipOutputDurationSec - audioFadeOutDurationSec
                }

                // ── Video filter chain ──
                val vf = buildVideoFilterString(
                    filters = clip.filters,
                    speed = clip.speed,
                    outWidth = outWidth,
                    outHeight = outHeight,
                    fadeInSec = fadeInSec,
                    fadeOutStartSec = fadeOutStartSec,
                    fadeOutDurationSec = fadeOutDurationSec,
                )
                val trimFilter = "trim=start=${clip.sourceStartMs / 1000.0}:" +
                        "end=${clip.sourceEndMs / 1000.0},setpts=PTS-STARTPTS"
                val vLabel = "t${trackIdx}c${segIdx}v"
                sb.append("[$i:v]$trimFilter,$vf[$vLabel];")
                segments.add(vLabel)

                // ── Audio ──
                if (ic.hasAudio) {
                    val af = buildAudioFilterString(
                        speed = clip.speed,
                        volume = clip.volume,
                        fadeInSec = audioFadeInSec,
                        fadeOutStartSec = audioFadeOutStartSec,
                        fadeOutDurationSec = audioFadeOutDurationSec,
                    )
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

            // Trailing gap
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

            // Concat segments for this track
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

        // ── Overlay tracks (bottom = base, upper tracks keyed on top) ──
        var currentVideo = "[${trackVideoLabels[0]}]"

        for (i in 1 until trackVideoLabels.size) {
            val overlayLabel = if (i == trackVideoLabels.lastIndex) "outv_raw" else "ov$i"
            sb.append("[${trackVideoLabels[i]}]format=yuva420p,colorkey=black:0.01:0.0[${trackVideoLabels[i]}_alpha];")
            sb.append("${currentVideo}[${trackVideoLabels[i]}_alpha]overlay=0:0:format=auto[$overlayLabel];")
            currentVideo = "[$overlayLabel]"
        }

        if (trackVideoLabels.size == 1) {
            sb.append("[${trackVideoLabels[0]}]null[outv_raw];")
        }

        if (subtitleFilePath != null && textClips.isNotEmpty()) {
            val escapedPath = subtitleFilePath
                .replace("\\", "\\\\\\\\")
                .replace(":", "\\\\:")
                .replace("'", "\\\\'")
            sb.append("[outv_raw]subtitles=$escapedPath[outv];")
        } else if (textClips.isNotEmpty() && useDrawtext) {
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

        // ── Mix audio from all tracks ──
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
}
