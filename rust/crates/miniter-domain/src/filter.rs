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
}
