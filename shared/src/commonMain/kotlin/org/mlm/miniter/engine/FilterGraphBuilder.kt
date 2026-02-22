package org.mlm.miniter.engine

import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.VideoFilter

object FilterGraphBuilder {

    fun buildVideoFilterString(
        filters: List<VideoFilter>,
        speed: Float,
        outWidth: Int,
        outHeight: Int,
        opacity: Float = 1.0f,
    ): String {
        val parts = mutableListOf<String>()

        parts.add("scale=$outWidth:$outHeight:force_original_aspect_ratio=decrease")
        parts.add("pad=$outWidth:$outHeight:(ow-iw)/2:(oh-ih)/2")

        for (f in filters) {
            when (f.type) {
                FilterType.Brightness -> {
                    val v = f.params["value"] ?: 0f
                    parts.add("eq=brightness=${v / 100f}")
                }
                FilterType.Contrast -> {
                    val v = f.params["value"] ?: 1f
                    parts.add("eq=contrast=$v")
                }
                FilterType.Saturation -> {
                    val v = f.params["value"] ?: 1f
                    parts.add("eq=saturation=$v")
                }
                FilterType.Grayscale -> parts.add("hue=s=0")
                FilterType.Blur -> {
                    val r = (f.params["radius"] ?: 5f).toInt()
                    parts.add("boxblur=$r:$r")
                }
                FilterType.Sharpen -> parts.add("unsharp=5:5:1.0:5:5:0.0")
                FilterType.Sepia -> parts.add(
                    "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
                )
            }
        }

        if (opacity < 1.0f) {
            parts.add("format=rgba,colorchannelmixer=aa=${opacity}")
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
            while (remaining > 100.0) {
                parts.add("atempo=100.0")
                remaining /= 100.0
            }
            if (remaining != 1.0) {
                parts.add("atempo=$remaining")
            }
        }

        return parts.joinToString(",")
    }

    fun buildGapInput(durationSec: Double, width: Int, height: Int): String {
        return "color=c=black:s=${width}x${height}:d=$durationSec:r=30"
    }
}
