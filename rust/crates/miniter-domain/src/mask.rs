use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
#[derive(Default)]
pub enum BlendMode {
    #[default]
    Normal,
    Multiply,
    Screen,
    Overlay,
    Darken,
    Lighten,
    ColorDodge,
    ColorBurn,
    HardLight,
    SoftLight,
    Difference,
    Exclusion,
    LinearDodge,
    LinearBurn,
    LinearLight,
    VividLight,
    PinLight,
    HardMix,
    Subtract,
    Average,
    Negation,
    Phoenix,
    Reflect,
    Glow,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct MaskPoint {
    pub x: f32,
    pub y: f32,
}

impl MaskPoint {
    fn is_zero(&self) -> bool {
        self.x == 0.0 && self.y == 0.0
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct MaskVertex {
    pub p: MaskPoint,
    #[serde(default, skip_serializing_if = "MaskPoint::is_zero")]
    pub t_in: MaskPoint,
    #[serde(default, skip_serializing_if = "MaskPoint::is_zero")]
    pub t_out: MaskPoint,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
#[non_exhaustive]
pub enum MaskShape {
    Rectangle {
        left: f32,
        top: f32,
        right: f32,
        bottom: f32,
    },
    Ellipse {
        center_x: f32,
        center_y: f32,
        radius_x: f32,
        radius_y: f32,
    },
    Path {
        #[serde(default)]
        vertices: Vec<MaskVertex>,
        #[serde(default = "default_closed")]
        closed: bool,
    },
}

fn default_closed() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
#[non_exhaustive]
pub enum MaskSource {
    Shape {
        shape: MaskShape,
        feather: f32,
        invert: bool,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
#[derive(Default)]
pub enum MaskOperation {
    #[default]
    Alpha,
    Luma,
    InvertAlpha,
    InvertLuma,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
#[derive(Default)]
pub enum MaskComposition {
    #[default]
    Replace,
    Union,
    Intersect,
    Subtract,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskTransform {
    #[serde(default = "default_scale")]
    pub scale: f32,
    #[serde(default)]
    pub translate_x: f32,
    #[serde(default)]
    pub translate_y: f32,
    #[serde(default)]
    pub rotate: f32,
}

fn default_scale() -> f32 {
    1.0
}

impl Default for MaskTransform {
    fn default() -> Self {
        Self {
            scale: 1.0,
            translate_x: 0.0,
            translate_y: 0.0,
            rotate: 0.0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
#[derive(Default)]
pub enum MaskMode {
    #[default]
    Add,
    Subtract,
    Intersect,
    Lighten,
    Darken,
    Difference,
}

impl MaskMode {
    pub fn combine(self, a: f32, m: f32) -> f32 {
        match self {
            MaskMode::Add => a + m - a * m,
            MaskMode::Subtract => a * (1.0 - m),
            MaskMode::Intersect => a * m,
            MaskMode::Lighten => a.max(m),
            MaskMode::Darken => a.min(m),
            MaskMode::Difference => a + m - 2.0 * a * m,
        }
    }

    pub fn start(self) -> f32 {
        match self {
            MaskMode::Subtract | MaskMode::Intersect | MaskMode::Darken => 1.0,
            MaskMode::Add | MaskMode::Lighten | MaskMode::Difference => 0.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskEffect {
    #[serde(default = "default_enabled")]
    pub enabled: bool,
    pub source: MaskSource,
    #[serde(default)]
    pub operation: MaskOperation,
    #[serde(default)]
    pub composition: MaskComposition,
    #[serde(default)]
    pub transform: MaskTransform,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mode: Option<MaskMode>,
    #[serde(default)]
    pub expansion: f32,
    #[serde(default = "default_mask_opacity")]
    pub opacity: f32,
}

fn default_enabled() -> bool {
    true
}

fn default_mask_opacity() -> f32 {
    1.0
}

impl MaskEffect {
    pub fn shape(shape: MaskShape) -> Self {
        Self {
            enabled: true,
            source: MaskSource::Shape {
                shape,
                feather: 0.0,
                invert: false,
            },
            operation: MaskOperation::Alpha,
            composition: MaskComposition::Replace,
            transform: MaskTransform::default(),
            mode: Some(MaskMode::Add),
            expansion: 0.0,
            opacity: 1.0,
        }
    }
}
