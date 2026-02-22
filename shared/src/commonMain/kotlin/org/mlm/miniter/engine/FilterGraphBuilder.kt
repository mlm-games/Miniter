package org.mlm.miniter.engine

import org.mlm.miniter.project.FilterType
import org.mlm.miniter.project.VideoFilter

object FilterGraphBuilder {

    fun buildVideoFilterString(
        filters: List<VideoFilter>,
        speed: Float,
        outWidth: Int,
        outHeight: Int,
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
                FilterType.Grayscale -> {
                    parts.add("hue=s=0")
                }
                FilterType.Blur -> {
                    val r = (f.params["radius"] ?: 5f).toInt()
                    parts.add("boxblur=$r:$r")
                }
                FilterType.Sharpen -> {
                    parts.add("unsharp=5:5:1.0:5:5:0.0")
                }
                FilterType.Sepia -> {
                    parts.add("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131")
                }
            }
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
            parts.add("atempo=$speed")
        }

        return parts.joinToString(",")
    }
}
