package org.mlm.miniter.ui.components.properties

import org.mlm.miniter.editor.model.RustBlurFilterSnapshot
import org.mlm.miniter.editor.model.RustBlurKind
import org.mlm.miniter.editor.model.RustBrightnessFilterSnapshot
import org.mlm.miniter.editor.model.RustCanvasBackgroundFilterSnapshot
import org.mlm.miniter.editor.model.RustChromaKeyFilterSnapshot
import org.mlm.miniter.editor.model.RustColorGradeFilterSnapshot
import org.mlm.miniter.editor.model.RustContrastFilterSnapshot
import org.mlm.miniter.editor.model.RustCropFilterSnapshot
import org.mlm.miniter.editor.model.RustDuotoneFilterSnapshot
import org.mlm.miniter.editor.model.RustGrainFilterSnapshot
import org.mlm.miniter.editor.model.RustHueFilterSnapshot
import org.mlm.miniter.editor.model.RustLutFilterSnapshot
import org.mlm.miniter.editor.model.RustMosaicFilterSnapshot
import org.mlm.miniter.editor.model.RustMosaicKind
import org.mlm.miniter.editor.model.RustPosterizeFilterSnapshot
import org.mlm.miniter.editor.model.RustRotateFilterSnapshot
import org.mlm.miniter.editor.model.RustSaturationFilterSnapshot
import org.mlm.miniter.editor.model.RustVignetteFilterSnapshot
import org.mlm.miniter.editor.model.RustSharpenFilterSnapshot
import org.mlm.miniter.editor.model.RustTransformFilterSnapshot
import org.mlm.miniter.editor.model.RustVideoFilterSnapshot
import org.mlm.miniter.project.FilterDef
import org.mlm.miniter.project.FilterPropertyDef

private fun fmtPct(v: Float) = "${(v * 100).toInt()}%"
private fun fmt1d(v: Float) = "${(v * 10).toInt() / 10f}x"
private fun fmtDeg(v: Float) = "${v.toInt()}°"
private fun fmtEv(v: Float) = "${(v * 10).toInt() / 10f}EV"

val FILTERS: List<FilterDef> = listOf(
    FilterDef("Transform", "Transform", listOf(
        FilterPropertyDef("scale", "Scale", 0.5f..3f, 24, ::fmt1d, "scale"),
        FilterPropertyDef("translate_x", "Pan X", -1f..1f, format = ::fmtPct, keyframeSuffix = "translate_x"),
        FilterPropertyDef("translate_y", "Pan Y", -1f..1f, format = ::fmtPct, keyframeSuffix = "translate_y"),
        FilterPropertyDef("rotate", "Rotate", -180f..180f, format = ::fmtDeg, keyframeSuffix = "rotate"),
    )),
    FilterDef("Brightness", "Brightness", listOf(
        FilterPropertyDef("value", "Brightness", -100f..100f, 39, { "${it.toInt()}" }, "brightness"),
    )),
    FilterDef("Contrast", "Contrast", listOf(
        FilterPropertyDef("value", "Contrast", 0f..3f, 29, ::fmt1d, "contrast"),
    )),
    FilterDef("Saturation", "Saturation", listOf(
        FilterPropertyDef("value", "Saturation", 0f..3f, 29, ::fmt1d, "saturation"),
    )),
    FilterDef("ColorGrade", "Color Grade", listOf(
        FilterPropertyDef("brightness", "Brightness", -1f..1f, format = ::fmtPct, keyframeSuffix = "grade_brightness"),
        FilterPropertyDef("contrast", "Contrast", 0f..3f, 29, ::fmt1d, "grade_contrast"),
        FilterPropertyDef("saturation", "Saturation", 0f..3f, 29, ::fmt1d, "grade_saturation"),
        FilterPropertyDef("vibrance", "Vibrance", -1f..1f, format = ::fmtPct, keyframeSuffix = "grade_vibrance"),
        FilterPropertyDef("temperature", "Temperature", -1f..1f, format = ::fmtPct, keyframeSuffix = "grade_temperature"),
        FilterPropertyDef("tint", "Tint", -1f..1f, format = ::fmtPct, keyframeSuffix = "grade_tint"),
        FilterPropertyDef("exposure", "Exposure", -2f..2f, 39, ::fmtEv, "grade_exposure"),
        FilterPropertyDef("gamma", "Gamma", 0.2f..3f, 27, ::fmt1d, "grade_gamma"),
        FilterPropertyDef("lift", "Lift", -0.5f..0.5f, format = ::fmtPct, keyframeSuffix = "grade_lift"),
        FilterPropertyDef("gain", "Gain", 0f..2f, 19, ::fmt1d, "grade_gain"),
        FilterPropertyDef("highlights", "Highlights", -1f..1f, format = ::fmtPct, keyframeSuffix = "grade_highlights"),
        FilterPropertyDef("shadows", "Shadows", -1f..1f, format = ::fmtPct, keyframeSuffix = "grade_shadows"),
    )),
    FilterDef("ChromaKey", "Chroma Key", listOf(
        FilterPropertyDef("tolerance", "Tolerance", 0f..1f, format = ::fmtPct, keyframeSuffix = "chroma_tolerance"),
        FilterPropertyDef("softness", "Edge softness", 0f..0.5f, format = ::fmtPct, keyframeSuffix = "chroma_softness"),
        FilterPropertyDef("spill", "Spill removal", 0f..1f, format = ::fmtPct, keyframeSuffix = "chroma_spill"),
    )),
    FilterDef("Lut", "LUT", listOf(
        FilterPropertyDef("strength", "Strength", 0f..1f, format = ::fmtPct, keyframeSuffix = "lut_strength"),
    )),
    FilterDef("Vignette", "Vignette", listOf(
        FilterPropertyDef("amount", "Amount", 0f..1f, format = ::fmtPct, keyframeSuffix = "vignette_amount"),
        FilterPropertyDef("radius", "Radius", 0f..1.5f, format = ::fmtPct, keyframeSuffix = "vignette_radius"),
        FilterPropertyDef("softness", "Softness", 0.05f..2f, format = ::fmtPct, keyframeSuffix = "vignette_softness"),
    )),
    FilterDef("Posterize", "Posterize", listOf(
        FilterPropertyDef("levels", "Levels", 2f..32f, 30, { "${it.toInt()}" }, "posterize_levels"),
    )),
    FilterDef("Grain", "Film Grain", listOf(
        FilterPropertyDef("amount", "Amount", 0f..0.5f, format = ::fmtPct, keyframeSuffix = "grain_amount"),
        FilterPropertyDef("size", "Grain size", 1f..8f, 7, { "${it.toInt()}px" }, "grain_size"),
    )),
    FilterDef("Duotone", "Duotone", emptyList()),
    FilterDef("Blur", "Blur", listOf(
        FilterPropertyDef("radius", "Blur radius", 1f..20f, 18, { "${it.toInt()}px" }, "blur_radius"),
        FilterPropertyDef("angle", "Motion angle", -180f..180f, format = ::fmtDeg, keyframeSuffix = "blur_angle"),
    )),
    FilterDef("Mosaic", "Mosaic", listOf(
        FilterPropertyDef("size", "Cell size", 2f..64f, 61, { "${it.toInt()}px" }, "mosaic_size"),
    )),
    FilterDef("Sharpen", "Sharpen", listOf(
        FilterPropertyDef("amount", "Sharpen amount", 0f..3f, 29, ::fmt1d, "sharpen_amount"),
    )),
    FilterDef("Crop", "Crop", listOf(
        FilterPropertyDef("left", "Crop left", 0f..1f, format = ::fmtPct, keyframeSuffix = "crop_left"),
        FilterPropertyDef("top", "Crop top", 0f..1f, format = ::fmtPct, keyframeSuffix = "crop_top"),
        FilterPropertyDef("right", "Crop right", 0f..1f, format = ::fmtPct, keyframeSuffix = "crop_right"),
        FilterPropertyDef("bottom", "Crop bottom", 0f..1f, format = ::fmtPct, keyframeSuffix = "crop_bottom"),
    )),
    FilterDef("Rotate", "Rotate", listOf(
        FilterPropertyDef("degrees", "Rotate", -180f..180f, format = ::fmtDeg, keyframeSuffix = "rotate_deg"),
    )),
    FilterDef("Hue", "Hue", listOf(
        FilterPropertyDef("degrees", "Hue shift", -180f..180f, format = ::fmtDeg, keyframeSuffix = "hue_deg"),
    )),
    FilterDef("CanvasBackground", "Canvas Background", listOf(
        FilterPropertyDef("blur_radius", "Blur radius", 1f..60f, 29, { "${it.toInt()}px" }, "canvas_blur"),
    )),
)

private val FILTERS_BY_SERIAL_NAME: Map<String, FilterDef> = FILTERS.associateBy { it.serialName }

data class ChromaKeyPreset(val label: String, val color: String)

val chromaKeyPresets: List<ChromaKeyPreset> = listOf(
    ChromaKeyPreset("Green", "FF00FF00"),
    ChromaKeyPreset("Blue", "FF0000FF"),
    ChromaKeyPreset("Magenta", "FFFF00FF"),
    ChromaKeyPreset("Cyan", "FF00FFFF"),
)

data class DuotonePreset(val label: String, val dark: String, val light: String)

val duotonePresets: List<DuotonePreset> = listOf(
    DuotonePreset("Noir", "FF000000", "FFFFFFFF"),
    DuotonePreset("Sunset", "FF1A1A3E", "FFF2C14E"),
    DuotonePreset("Ice", "FF0B2545", "FF9FD8F7"),
    DuotonePreset("Emerald", "FF04231A", "FF7FE3B0"),
    DuotonePreset("Rose", "FF2B0A18", "FFF7A8C4"),
)

val mosaicKindOptions: List<Pair<RustMosaicKind, String>> = listOf(
    RustMosaicKind.Square to "Square",
    RustMosaicKind.Hexagon to "Hexagon",
    RustMosaicKind.Triangle to "Triangle",
)

val blurKindOptions: List<Pair<RustBlurKind, String>> = listOf(
    RustBlurKind.Stack to "Stack",
    RustBlurKind.Box to "Box",
    RustBlurKind.Gaussian to "Gaussian",
    RustBlurKind.Motion to "Motion",
    RustBlurKind.Radial to "Radial",
    RustBlurKind.Zoom to "Zoom",
)

fun filterDefByType(filter: RustVideoFilterSnapshot): FilterDef? = when (filter) {
    is RustBrightnessFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Brightness"]
    is RustContrastFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Contrast"]
    is RustSaturationFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Saturation"]
    is RustColorGradeFilterSnapshot -> FILTERS_BY_SERIAL_NAME["ColorGrade"]
    is RustChromaKeyFilterSnapshot -> FILTERS_BY_SERIAL_NAME["ChromaKey"]
    is RustLutFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Lut"]
    is RustVignetteFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Vignette"]
    is RustPosterizeFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Posterize"]
    is RustGrainFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Grain"]
    is RustDuotoneFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Duotone"]
    is RustMosaicFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Mosaic"]
    is RustBlurFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Blur"]
    is RustSharpenFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Sharpen"]
    is RustTransformFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Transform"]
    is RustCropFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Crop"]
    is RustRotateFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Rotate"]
    is RustHueFilterSnapshot -> FILTERS_BY_SERIAL_NAME["Hue"]
    is RustCanvasBackgroundFilterSnapshot -> FILTERS_BY_SERIAL_NAME["CanvasBackground"]
    else -> null
}

fun readFilterProperty(filter: RustVideoFilterSnapshot, paramKey: String): Float = when (filter) {
    is RustBrightnessFilterSnapshot -> filter.value
    is RustContrastFilterSnapshot -> filter.value
    is RustSaturationFilterSnapshot -> filter.value
    is RustColorGradeFilterSnapshot -> when (paramKey) {
        "brightness" -> filter.brightness
        "contrast" -> filter.contrast
        "saturation" -> filter.saturation
        "vibrance" -> filter.vibrance
        "temperature" -> filter.temperature
        "tint" -> filter.tint
        "exposure" -> filter.exposure
        "gamma" -> filter.gamma
        "lift" -> filter.lift
        "gain" -> filter.gain
        "highlights" -> filter.highlights
        "shadows" -> filter.shadows
        else -> 0f
    }
    is RustChromaKeyFilterSnapshot -> when (paramKey) {
        "tolerance" -> filter.tolerance
        "softness" -> filter.softness
        "spill" -> filter.spill
        else -> 0f
    }
    is RustLutFilterSnapshot -> when (paramKey) {
        "strength" -> filter.strength
        else -> 0f
    }
    is RustMosaicFilterSnapshot -> when (paramKey) {
        "size" -> filter.size
        else -> 0f
    }
    is RustVignetteFilterSnapshot -> when (paramKey) {
        "amount" -> filter.amount
        "radius" -> filter.radius
        "softness" -> filter.softness
        else -> 0f
    }
    is RustPosterizeFilterSnapshot -> when (paramKey) {
        "levels" -> filter.levels
        else -> 0f
    }
    is RustGrainFilterSnapshot -> when (paramKey) {
        "amount" -> filter.amount
        "size" -> filter.size
        else -> 0f
    }
    is RustBlurFilterSnapshot -> when (paramKey) {
        "radius" -> filter.radius
        "angle" -> filter.angle
        else -> 0f
    }
    is RustSharpenFilterSnapshot -> filter.amount
    is RustCropFilterSnapshot -> when (paramKey) {
        "left" -> filter.left
        "top" -> filter.top
        "right" -> filter.right
        "bottom" -> filter.bottom
        else -> 0f
    }
    is RustRotateFilterSnapshot -> filter.degrees
    is RustHueFilterSnapshot -> filter.degrees
    is RustCanvasBackgroundFilterSnapshot -> when (paramKey) {
        "blur_radius" -> filter.blurRadius
        else -> 0f
    }
    is RustTransformFilterSnapshot -> when (paramKey) {
        "scale" -> filter.scale
        "translate_x" -> filter.translateX
        "translate_y" -> filter.translateY
        "rotate" -> filter.rotate
        else -> 0f
    }
    else -> 0f
}
