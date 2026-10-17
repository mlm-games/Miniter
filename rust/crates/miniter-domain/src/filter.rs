use crate::mask::BlendMode;
use serde::{Deserialize, Deserializer, Serialize, de};
use serde_json::Value;

#[allow(dead_code)]
fn default_effect_enabled() -> bool {
    true
}

#[derive(Debug, Clone, Serialize)]
pub struct VideoEffect {
    #[serde(default = "default_effect_enabled")]
    pub enabled: bool,
    pub filter: VideoFilter,
}

impl VideoEffect {
    pub fn new(filter: VideoFilter) -> Self {
        Self {
            enabled: true,
            filter,
        }
    }

    /// Singleton semantics: at most one of these filter types may be
    /// active on a clip; adding a second replaces the first.
    pub fn is_singleton(filter: &VideoFilter) -> bool {
        matches!(
            filter,
            VideoFilter::Crop { .. }
                | VideoFilter::Speed { .. }
                | VideoFilter::Reverse
                | VideoFilter::CanvasBackground { .. }
        )
    }

    /// Discriminant key used to find a conflicting singleton.
    pub fn singleton_key(filter: &VideoFilter) -> Option<&'static str> {
        match filter {
            VideoFilter::Crop { .. } => Some("Crop"),
            VideoFilter::Speed { .. } => Some("Speed"),
            VideoFilter::Reverse => Some("Reverse"),
            VideoFilter::CanvasBackground { .. } => Some("CanvasBackground"),
            _ => None,
        }
    }
}

impl<'de> Deserialize<'de> for VideoEffect {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = Value::deserialize(deserializer)?;

        if value.get("filter").is_some() {
            #[derive(Deserialize)]
            struct Wire {
                enabled: Option<bool>,
                filter: VideoFilter,
            }

            let wire: Wire = serde_json::from_value(value).map_err(de::Error::custom)?;
            Ok(Self {
                enabled: wire.enabled.unwrap_or(true),
                filter: wire.filter,
            })
        } else {
            let filter: VideoFilter = serde_json::from_value(value).map_err(de::Error::custom)?;
            Ok(Self::new(filter))
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
#[non_exhaustive]
pub enum VideoFilter {
    Brightness {
        value: f32,
    },
    Contrast {
        value: f32,
    },
    Saturation {
        value: f32,
    },
    Grayscale,
    Blur {
        radius: f32,
        #[serde(default)]
        kind: BlurKind,
        #[serde(default)]
        angle: f32,
    },
    Sharpen {
        amount: f32,
    },
    Sepia,

    Hue {
        degrees: f32,
    },
    Crop {
        left: f32,
        top: f32,
        right: f32,
        bottom: f32,
    },
    Rotate {
        degrees: f32,
    },
    Flip {
        horizontal: bool,
        vertical: bool,
    },
    Transform {
        scale: f32,
        translate_x: f32,
        translate_y: f32,
        rotate: f32,
    },
    Speed {
        factor: f64,
    },
    /// Reverse playback: frames are sampled back-to-front from the
    /// source range.
    Reverse,
    /// Canvas background fill for letterboxed frames: replaces the default
    /// black bars when the source aspect differs from the export frame.
    CanvasBackground {
        #[serde(default)]
        mode: CanvasBackgroundMode,
        /// Solid color as ARGB hex (matches `TextStyle` color encoding).
        /// Used when `mode` is `Color`.
        #[serde(default = "default_canvas_color")]
        color: String,
        /// Blur radius in pixels for `Blur` mode.
        #[serde(default = "default_canvas_blur")]
        blur_radius: f32,
    },
    ColorGrade {
        #[serde(default)]
        brightness: f32,
        #[serde(default = "default_grade_unity")]
        contrast: f32,
        #[serde(default = "default_grade_unity")]
        saturation: f32,
        #[serde(default)]
        vibrance: f32,
        #[serde(default)]
        temperature: f32,
        #[serde(default)]
        tint: f32,
        #[serde(default)]
        exposure: f32,
        #[serde(default = "default_grade_unity")]
        gamma: f32,
        #[serde(default)]
        lift: f32,
        #[serde(default = "default_grade_unity")]
        gain: f32,
        #[serde(default)]
        highlights: f32,
        #[serde(default)]
        shadows: f32,
    },
    ChromaKey {
        #[serde(default = "default_chroma_key_color")]
        key_color: String,
        #[serde(default = "default_chroma_tolerance")]
        tolerance: f32,
        #[serde(default = "default_chroma_softness")]
        softness: f32,
        #[serde(default = "default_chroma_spill")]
        spill: f32,
    },
    /// Path to a `.cube` LUT applied to the encoded values. An empty or
    /// unreadable file leaves the frame unchanged.
    Lut {
        #[serde(default)]
        path: String,
        #[serde(default = "default_lut_strength")]
        strength: f32,
    },
    Mosaic {
        #[serde(default)]
        kind: MosaicKind,
        #[serde(default = "default_mosaic_size")]
        size: f32,
    },
    Posterize {
        #[serde(default = "default_posterize_levels")]
        levels: f32,
    },
    Duotone {
        #[serde(default = "default_duotone_dark")]
        dark: String,
        #[serde(default = "default_duotone_light")]
        light: String,
    },
    Grain {
        #[serde(default = "default_grain_amount")]
        amount: f32,
        #[serde(default = "default_grain_size")]
        size: f32,
    },
    Vignette {
        #[serde(default = "default_vignette_amount")]
        amount: f32,
        #[serde(default = "default_vignette_radius")]
        radius: f32,
        #[serde(default = "default_vignette_softness")]
        softness: f32,
    },
    Opacity {
        value: f32,
    },
    BlendMode {
        mode: BlendMode,
    },
}

fn default_canvas_color() -> String {
    "FF000000".to_string()
}

fn default_canvas_blur() -> f32 {
    24.0
}

fn default_grade_unity() -> f32 {
    1.0
}

fn default_chroma_key_color() -> String {
    "FF00FF00".to_string()
}

fn default_chroma_tolerance() -> f32 {
    0.25
}

fn default_chroma_softness() -> f32 {
    0.1
}

fn default_chroma_spill() -> f32 {
    0.5
}

fn default_mosaic_size() -> f32 {
    16.0
}

fn default_vignette_amount() -> f32 {
    0.5
}

fn default_vignette_radius() -> f32 {
    0.6
}

fn default_vignette_softness() -> f32 {
    0.4
}

fn default_posterize_levels() -> f32 {
    8.0
}

fn default_duotone_dark() -> String {
    "FF1A1A3E".to_string()
}

fn default_duotone_light() -> String {
    "FFF2C14E".to_string()
}

fn default_grain_amount() -> f32 {
    0.15
}

fn default_grain_size() -> f32 {
    2.0
}

fn default_lut_strength() -> f32 {
    1.0
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum BlurKind {
    #[default]
    Stack,
    Box,
    Gaussian,
    Motion,
    Radial,
    Zoom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum MosaicKind {
    #[default]
    Square,
    Hexagon,
    Triangle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum CanvasBackgroundMode {
    /// Solid `color` fill.
    #[default]
    Color,
    /// Source frame scaled to cover, blurred by `blur_radius`, dimmed.
    Blur,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
#[non_exhaustive]
pub enum AudioFilter {
    Volume {
        value: f32,
    },
    FadeIn {
        duration_us: i64,
    },
    FadeOut {
        duration_us: i64,
    },
    Normalize,
    /// Reverse playback of the clip's audio samples.
    Reverse,
    /// Constant-power stereo pan: -1.0 is full left, 0.0 is centred, 1.0 is
    /// full right. Mono sources pan from centre; values outside [-1, 1] clamp.
    Pan {
        value: f32,
    },
    /// Play one source channel (0-based): mono clipitems split off a
    /// multichannel file carry exactly one. Out-of-range indices silence.
    Channel {
        index: u16,
    },
    /// Graphic equaliser preset applied to the clip's decoded samples.
    Equalizer {
        #[serde(default)]
        preset: EqPreset,
    },
    /// Room reverb mixed under the clip's dry signal.
    Reverb {
        #[serde(default)]
        preset: ReverbPreset,
    },
    /// Lowers this clip's level while anything mixed before it is loud, which
    /// ducks a music bed under a voiceover on another track.
    Ducking {
        #[serde(default = "default_ducking_amount")]
        amount: f32,
        #[serde(default = "default_ducking_release_ms")]
        release_ms: i64,
    },
}

fn default_ducking_amount() -> f32 {
    0.6
}

fn default_ducking_release_ms() -> i64 {
    400
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum EqPreset {
    #[default]
    Natural,
    AmRadio,
    BassBooster,
    BassReducer,
    BgmMode,
    HipHop,
    Jazz,
    Pop,
    RnB,
    Rock,
    TrebleBooster,
    TrebleReducer,
    Voice,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum ReverbPreset {
    #[default]
    Studio,
    Bathroom,
    Cathedral,
    Cave,
    Arena,
    Church,
    ConcertHall,
    EchoRoom,
    HallDelay,
    SpringReverb,
}
