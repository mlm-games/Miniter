use serde::{Deserialize, Serialize};

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
    Hue {
        degrees: f32,
    },
    Blur {
        radius: f32,
    },
    Sharpen {
        amount: f32,
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
    Equalizer { bands: Vec<EqBand> },
    NoiseReduction { strength: f32 },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EqBand {
    pub frequency: f32,
    pub gain: f32,
    pub q: f32,
}
