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
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    @SerialName("transition_in")
    val transitionIn: RustTransitionSnapshot? = null,
    @SerialName("transition_out")
    val transitionOut: RustTransitionSnapshot? = null,
    val kind: RustClipKindPayload,
)

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
    val filters: List<RustVideoFilterSnapshot> = emptyList(),
    @SerialName("audio_filters")
    val audioFilters: List<RustAudioFilterSnapshot> = emptyList(),
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
@SerialName("Equalizer")
data class RustEqualizerAudioFilterSnapshot(
    val bands: List<RustEqBandSnapshot>,
) : RustAudioFilterSnapshot

@Serializable
@SerialName("NoiseReduction")
data class RustNoiseReductionAudioFilterSnapshot(
    val strength: Float,
) : RustAudioFilterSnapshot

@Serializable
data class RustEqBandSnapshot(
    val frequency: Float,
    val gain: Float,
    val q: Float,
)

@Serializable
data class RustExportProfileSnapshot(
    val format: RustExportFormat = RustExportFormat.Mp4,
    val resolution: RustExportResolution = RustExportResolution.Hd1080,
    val fps: Double = 30.0,
    @SerialName("video_bitrate_kbps")
    val videoBitrateKbps: Int = 8000,
    @SerialName("audio_bitrate_kbps")
    val audioBitrateKbps: Int = 192,
    @SerialName("audio_sample_rate")
    val audioSampleRate: Int = 48_000,
    @SerialName("output_path")
    val outputPath: String = "",
)

@Serializable
enum class RustExportFormat {
    Mp4,
    Mov,
    Av1Ivf,
}

@Serializable(with = RustExportResolutionSerializer::class)
sealed interface RustExportResolution {
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
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("RustExportResolution")

    override fun serialize(encoder: Encoder, value: RustExportResolution) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("RustExportResolutionSerializer only supports JSON")

        val element = when (value) {
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

        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): RustExportResolution {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("RustExportResolutionSerializer only supports JSON")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> when (element.content) {
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
}