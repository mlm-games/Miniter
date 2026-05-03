use serde::{de, Deserialize, Deserializer, Serialize};
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
    Opacity {
        value: f32,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum AudioFilter {
    Volume { value: f32 },
    FadeIn { duration_us: i64 },
    FadeOut { duration_us: i64 },
    Normalize,
}
