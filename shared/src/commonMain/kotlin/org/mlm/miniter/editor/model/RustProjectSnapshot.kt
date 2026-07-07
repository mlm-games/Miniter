package org.mlm.miniter.editor.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class RustProjectSnapshot(
    val id: String,
    val meta: RustProjectMetaSnapshot,
    val timeline: RustTimelineSnapshot,
    @SerialName("export_profile")
    val exportProfile: RustExportProfileSnapshot,
)

@Serializable
data class RustProjectMetaSnapshot(
    val name: String,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("modified_at")
    val modifiedAt: Long,
    @SerialName("schema_version")
    val schemaVersion: Int,
)

@Serializable
data class RustTimelineSnapshot(
    val tracks: List<RustTrackSnapshot> = emptyList(),
)

@Serializable
data class RustTrackSnapshot(
    val id: String,
    val name: String,
    val kind: RustTrackKind,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val clips: List<RustClipSnapshot> = emptyList(),
)

@Serializable
enum class RustTrackKind {
    Video,
    Audio,
    Text,
    Subtitle,
}

@Serializable
data class RustKeyframe(
    val param: String,
    val offset: Long,
    val value: Float,
    val easing: RustEasing = RustEasing.Linear,
)

@Serializable
enum class RustEasing {
    Linear,
    EaseIn,
    EaseOut,
    EaseInOut,
}

@Serializable
data class RustKeyframeCurve(
    val keyframes: List<RustKeyframe> = emptyList(),
) {
    fun evaluate(param: String, timeUs: Long): Float? {
        val relevant = keyframes.filter { it.param == param }.sortedBy { it.offset }
        if (relevant.isEmpty()) return null
        if (relevant.size == 1) return relevant[0].value

        val t = timeUs
        if (t <= relevant.first().offset) return relevant.first().value
        if (t >= relevant.last().offset) return relevant.last().value

        for (i in 0 until relevant.lastIndex) {
            val a = relevant[i]
            val b = relevant[i + 1]
            if (t in a.offset..b.offset) {
                val range = (b.offset - a.offset).toFloat()
                if (range <= 0f) return b.value
                val progress = ((t - a.offset).toFloat() / range).coerceIn(0f, 1f)
                val eased = applyEasing(a.easing, progress)
                return a.value + (b.value - a.value) * eased
            }
        }
        return relevant.last().value
    }
}

private fun applyEasing(easing: RustEasing, t: Float): Float = when (easing) {
    RustEasing.Linear -> t
    RustEasing.EaseIn -> t * t * t
    RustEasing.EaseOut -> 1f - (1f - t) * (1f - t) * (1f - t)
    RustEasing.EaseInOut -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f) / 2f
}

@Serializable
data class RustClipSnapshot(
    val id: String,
    @SerialName("timeline_start")
    val timelineStartUs: Long,
    @SerialName("timeline_duration")
    val timelineDurationUs: Long,
    @SerialName("source_start")
    val sourceStartUs: Long = 0L,
    @SerialName("source_end")
    val sourceEndUs: Long = 0L,
    @SerialName("source_total_duration")
    val sourceTotalDurationUs: Long = 0L,
    val speed: Double = 1.0,
    val volume: Float = 1.0f,
    val opacity: Float = 1.0f,
    val muted: Boolean = false,
    @SerialName("blend_mode")
    val blendMode: RustBlendMode = RustBlendMode.Normal,
    @SerialName("transition_in")
    val transitionIn: RustTransitionSnapshot? = null,
    @SerialName("transition_out")
    val transitionOut: RustTransitionSnapshot? = null,
    val kind: RustClipKindPayload,
    @SerialName("keyframes")
    val keyframes: RustKeyframeCurve = RustKeyframeCurve(),
) {
    val endUs: Long get() = timelineStartUs + timelineDurationUs

    fun overlaps(other: RustClipSnapshot): Boolean {
        return timelineStartUs < other.endUs && other.timelineStartUs < endUs
    }
}

fun RustClipSnapshot.overlapsRange(startUs: Long, endUs: Long): Boolean {
    return timelineStartUs < endUs && startUs < timelineStartUs + timelineDurationUs
}

@Serializable
sealed interface RustClipKindPayload

@Serializable
@SerialName("Video")
data class RustVideoClipKind(
    @SerialName("source_path")
    val sourcePath: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val filters: List<RustVideoEffectSnapshot> = emptyList(),
    @SerialName("audio_filters")
    val audioFilters: List<RustAudioFilterSnapshot> = emptyList(),
    val masks: List<RustMaskEffect> = emptyList(),
) : RustClipKindPayload

@Serializable
@SerialName("Audio")
data class RustAudioClipKind(
    @SerialName("source_path")
    val sourcePath: String,
    @SerialName("sample_rate")
    val sampleRate: Int,
    val channels: Int,
    val filters: List<RustAudioFilterSnapshot> = emptyList(),
) : RustClipKindPayload

@Serializable
@SerialName("Text")
data class RustTextClipKind(
    val text: String,
    val style: RustTextStyleSnapshot = RustTextStyleSnapshot(),
) : RustClipKindPayload

@Serializable
@SerialName("Subtitle")
data class RustSubtitleClipKind(
    @SerialName("source_path")
    val sourcePath: String,
    @SerialName("font_path")
    val fontPath: String? = null,
) : RustClipKindPayload

@Serializable
data class RustTextStyleSnapshot(
    @SerialName("font_family")
    val fontFamily: String = "sans-serif",
    @SerialName("font_size")
    val fontSize: Float = 24f,
    val color: String = "FFFFFFFF",
    @SerialName("background_color")
    val backgroundColor: String? = null,
    val alignment: RustTextAlignment = RustTextAlignment.Center,
    @SerialName("position_x")
    val positionX: Float = 0.5f,
    @SerialName("position_y")
    val positionY: Float = 0.9f,
    @SerialName("outline_color")
    val outlineColor: String? = null,
    @SerialName("outline_width")
    val outlineWidth: Float = 0f,
    val shadow: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

@Serializable
enum class RustTextAlignment {
    Left,
    Center,
    Right,
}

@Serializable
data class RustTransitionSnapshot(
    val kind: RustTransitionKind,
    val duration: Long,
)

@Serializable
enum class RustTransitionKind {
    CrossFade,
    SlideLeft,
    SlideRight,
    Dissolve,
}

@Serializable
sealed interface RustVideoFilterSnapshot

@Serializable(with = RustVideoEffectSnapshotSerializer::class)
data class RustVideoEffectSnapshot(
    val filter: RustVideoFilterSnapshot,
    val enabled: Boolean = true,
)

object RustVideoEffectSnapshotSerializer : KSerializer<RustVideoEffectSnapshot> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RustVideoEffectSnapshot") {
        element("enabled", JsonPrimitive.serializer().descriptor, isOptional = true)
        element("filter", RustVideoFilterSnapshot.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): RustVideoEffectSnapshot {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RustVideoEffectSnapshot must be decoded from JSON")
        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonObject && element.containsKey("filter")) {
            val enabled = element["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            val filterElement = element["filter"] ?: throw SerializationException("Missing filter payload")
            val filter = jsonDecoder.json.decodeFromJsonElement(
                RustVideoFilterSnapshot.serializer(),
                filterElement,
            )
            return RustVideoEffectSnapshot(filter = filter, enabled = enabled)
        }

        val filter = jsonDecoder.json.decodeFromJsonElement(
            RustVideoFilterSnapshot.serializer(),
            element,
        )
        return RustVideoEffectSnapshot(filter = filter, enabled = true)
    }

    override fun serialize(encoder: Encoder, value: RustVideoEffectSnapshot) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RustVideoEffectSnapshot must be encoded to JSON")
        val payload = buildJsonObject {
            put("enabled", JsonPrimitive(value.enabled))
            put(
                "filter",
                jsonEncoder.json.encodeToJsonElement(RustVideoFilterSnapshot.serializer(), value.filter),
            )
        }
        jsonEncoder.encodeJsonElement(payload)
    }
}

@Serializable
@SerialName("Brightness")
data class RustBrightnessFilterSnapshot(val value: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Contrast")
data class RustContrastFilterSnapshot(val value: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Saturation")
data class RustSaturationFilterSnapshot(val value: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Grayscale")
data object RustGrayscaleFilterSnapshot : RustVideoFilterSnapshot

@Serializable
@SerialName("Blur")
data class RustBlurFilterSnapshot(val radius: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Sharpen")
data class RustSharpenFilterSnapshot(val amount: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Sepia")
data object RustSepiaFilterSnapshot : RustVideoFilterSnapshot

@Serializable
@SerialName("Transform")
data class RustTransformFilterSnapshot(
    val scale: Float,
    @SerialName("translate_x")
    val translateX: Float,
    @SerialName("translate_y")
    val translateY: Float,
    val rotate: Float = 0f,
) : RustVideoFilterSnapshot

@Serializable
@SerialName("Hue")
data class RustHueFilterSnapshot(val degrees: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Crop")
data class RustCropFilterSnapshot(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : RustVideoFilterSnapshot

@Serializable
@SerialName("Rotate")
data class RustRotateFilterSnapshot(val degrees: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("Flip")
data class RustFlipFilterSnapshot(
    val horizontal: Boolean,
    val vertical: Boolean,
) : RustVideoFilterSnapshot

@Serializable
@SerialName("Speed")
data class RustSpeedFilterSnapshot(val factor: Double) : RustVideoFilterSnapshot

@Serializable
@SerialName("Opacity")
data class RustOpacityFilterSnapshot(val value: Float) : RustVideoFilterSnapshot

@Serializable
@SerialName("BlendMode")
data class RustBlendModeFilterSnapshot(val mode: RustBlendMode) : RustVideoFilterSnapshot

@Serializable
enum class RustBlendMode {
    Normal,
    Multiply,
    Screen,
    Overlay,
}

@Serializable
sealed interface RustMaskShape

@Serializable
@SerialName("Rectangle")
data class RustRectangleMaskShape(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) : RustMaskShape

@Serializable
@SerialName("Ellipse")
data class RustEllipseMaskShape(
    @SerialName("center_x")
    val centerX: Float,
    @SerialName("center_y")
    val centerY: Float,
    @SerialName("radius_x")
    val radiusX: Float,
    @SerialName("radius_y")
    val radiusY: Float,
) : RustMaskShape

@Serializable
sealed interface RustMaskSource

@Serializable
@SerialName("Shape")
data class RustShapeMaskSource(
    val shape: RustMaskShape,
    val feather: Float,
    val invert: Boolean,
) : RustMaskSource

@Serializable
enum class RustMaskOperation {
    Alpha,
    Luma,
    InvertAlpha,
    InvertLuma,
}

@Serializable
enum class RustMaskComposition {
    Replace,
    Union,
    Intersect,
    Subtract,
}

@Serializable
data class RustMaskTransform(
    val scale: Float,
    @SerialName("translate_x")
    val translateX: Float,
    @SerialName("translate_y")
    val translateY: Float,
    val rotate: Float,
)

@Serializable
data class RustMaskEffect(
    val enabled: Boolean,
    val source: RustMaskSource,
    @SerialName("operation")
    val operation: RustMaskOperation = RustMaskOperation.Alpha,
    @SerialName("composition")
    val composition: RustMaskComposition = RustMaskComposition.Replace,
    val transform: RustMaskTransform = RustMaskTransform(1f, 0f, 0f, 0f),
)

@Serializable
sealed interface RustAudioFilterSnapshot

@Serializable
@SerialName("Volume")
data class RustVolumeAudioFilterSnapshot(val value: Float) : RustAudioFilterSnapshot

@Serializable
@SerialName("FadeIn")
data class RustFadeInAudioFilterSnapshot(
    @SerialName("duration_us")
    val durationUs: Long,
) : RustAudioFilterSnapshot

@Serializable
@SerialName("FadeOut")
data class RustFadeOutAudioFilterSnapshot(
    @SerialName("duration_us")
    val durationUs: Long,
) : RustAudioFilterSnapshot

@Serializable
@SerialName("Normalize")
data object RustNormalizeAudioFilterSnapshot : RustAudioFilterSnapshot

@Serializable
data class RustExportProfileSnapshot(
    val format: RustExportFormat = RustExportFormat.Av1Mp4,
    val resolution: RustExportResolution = RustExportResolution.Source,
    val fps: Double = 30.0,
    @SerialName("video_bitrate_kbps")
    val videoBitrateKbps: Int = 8000,
    @SerialName("audio_bitrate_kbps")
    val audioBitrateKbps: Int = 192,
    @SerialName("audio_sample_rate")
    val audioSampleRate: Int = 48_000,
    @SerialName("output_path")
    val outputPath: String = "",
    @SerialName("subtitle_mode")
    val subtitleMode: RustSubtitleMode = RustSubtitleMode.Soft,
    @SerialName("hardware_acceleration")
    val hardwareAcceleration: Boolean = true,
)

@Serializable
enum class RustSubtitleMode {
    Hard,
    Soft,
}

@Serializable
enum class RustExportFormat {
    Mp4,
    Av1Mp4,
    // Av1Mkv,
    // Av1WebM,
    Av1Ivf,
    Mov,
    Opus,
}

@Serializable(with = RustExportResolutionSerializer::class)
sealed interface RustExportResolution {
    @Serializable
    @SerialName("Source")
    data object Source : RustExportResolution

    @Serializable
    @SerialName("Sd480")
    data object Sd480 : RustExportResolution

    @Serializable
    @SerialName("Hd720")
    data object Hd720 : RustExportResolution

    @Serializable
    @SerialName("Hd1080")
    data object Hd1080 : RustExportResolution

    @Serializable
    @SerialName("Uhd4k")
    data object Uhd4k : RustExportResolution

    @Serializable
    @SerialName("Custom")
    data class Custom(
        val width: Int,
        val height: Int,
    ) : RustExportResolution
}

object RustExportResolutionSerializer : KSerializer<RustExportResolution> {
    @Serializable
    private data class Surrogate(val type: String, val width: Int? = null, val height: Int? = null)

    private fun RustExportResolution.toSurrogate() = when (this) {
        RustExportResolution.Source -> Surrogate("Source")
        RustExportResolution.Sd480 -> Surrogate("Sd480")
        RustExportResolution.Hd720 -> Surrogate("Hd720")
        RustExportResolution.Hd1080 -> Surrogate("Hd1080")
        RustExportResolution.Uhd4k -> Surrogate("Uhd4k")
        is RustExportResolution.Custom -> Surrogate("Custom", width, height)
    }

    private fun Surrogate.toResolution() = when (type) {
        "Source" -> RustExportResolution.Source
        "Sd480" -> RustExportResolution.Sd480
        "Hd720" -> RustExportResolution.Hd720
        "Hd1080" -> RustExportResolution.Hd1080
        "Uhd4k" -> RustExportResolution.Uhd4k
        "Custom" -> RustExportResolution.Custom(
            width = width ?: throw SerializationException("Missing Custom.width"),
            height = height ?: throw SerializationException("Missing Custom.height"),
        )
        else -> throw SerializationException("Unknown export resolution type: $type")
    }

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: RustExportResolution) {
        if (encoder is JsonEncoder) {
            val element = when (value) {
                RustExportResolution.Source -> JsonPrimitive("Source")
                RustExportResolution.Sd480 -> JsonPrimitive("Sd480")
                RustExportResolution.Hd720 -> JsonPrimitive("Hd720")
                RustExportResolution.Hd1080 -> JsonPrimitive("Hd1080")
                RustExportResolution.Uhd4k -> JsonPrimitive("Uhd4k")
                is RustExportResolution.Custom -> buildJsonObject {
                    putJsonObject("Custom") {
                        put("width", value.width)
                        put("height", value.height)
                    }
                }
            }
            encoder.encodeJsonElement(element)
            return
        }

        Surrogate.serializer().serialize(encoder, value.toSurrogate())
    }

    override fun deserialize(decoder: Decoder): RustExportResolution {
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                is JsonPrimitive -> when (element.content) {
                    "Source" -> RustExportResolution.Source
                    "Sd480" -> RustExportResolution.Sd480
                    "Hd720" -> RustExportResolution.Hd720
                    "Hd1080" -> RustExportResolution.Hd1080
                    "Uhd4k" -> RustExportResolution.Uhd4k
                    else -> throw SerializationException(
                        "Unknown export resolution: ${element.content}"
                    )
                }

                is JsonObject -> {
                    element["Custom"]?.jsonObject?.let { custom ->
                        RustExportResolution.Custom(
                            width = custom["width"]?.jsonPrimitive?.int
                                ?: throw SerializationException("Missing Custom.width"),
                            height = custom["height"]?.jsonPrimitive?.int
                                ?: throw SerializationException("Missing Custom.height"),
                        )
                    }
                        ?: when (element["type"]?.jsonPrimitive?.content) {
                            "Source" -> RustExportResolution.Source
                            "Sd480" -> RustExportResolution.Sd480
                            "Hd720" -> RustExportResolution.Hd720
                            "Hd1080" -> RustExportResolution.Hd1080
                            "Uhd4k" -> RustExportResolution.Uhd4k
                            "Custom" -> RustExportResolution.Custom(
                                width = element["width"]?.jsonPrimitive?.int
                                    ?: throw SerializationException("Missing width"),
                                height = element["height"]?.jsonPrimitive?.int
                                    ?: throw SerializationException("Missing height"),
                            )
                            else -> throw SerializationException(
                                "Unsupported export resolution JSON: $element"
                            )
                        }
                }

                else -> throw SerializationException(
                    "Unsupported export resolution JSON: $element"
                )
            }
        }

        return Surrogate.serializer().deserialize(decoder).toResolution()
    }
}
