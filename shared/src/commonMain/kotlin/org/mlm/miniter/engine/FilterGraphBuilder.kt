package org.mlm.miniter.engine

import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.VideoFilter

object FilterGraphBuilder {

    /**
     * Round up to the nearest even number. Required for yuv420p pixel format
     * which needs dimensions divisible by 2 for chroma subsampling.
     */
    private fun evenUp(value: Int): Int = if (value % 2 == 0) value else value + 1

    fun buildVideoFilterString(
        filters: List<VideoFilter>,
        speed: Float,
        outWidth: Int,
        outHeight: Int,
        opacity: Float = 1.0f,
    ): String {
        val parts = mutableListOf<String>()

        // Ensure even dimensions for yuv420p compatibility
        val w = evenUp(outWidth)
        val h = evenUp(outHeight)

        // Normalize pixel format first
        parts.add("format=yuv420p")

        // Scale to fit within target, force output to even dimensions
        parts.add("scale=$w:$h:force_original_aspect_ratio=decrease:force_divisible_by=2")

        // Pad to exact target dimensions (scale output may be smaller due to aspect ratio)
        parts.add("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black")

        // Merge adjacent eq= parameters
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