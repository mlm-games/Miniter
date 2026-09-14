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
}

fn default_enabled() -> bool {
    true
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
        }
    }
}
