use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextOverlay {
    pub text: String,
    pub style: TextStyle,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextStyle {
    pub font_family: String,
    pub font_size: f32,
    pub color: String,
    pub background_color: Option<String>,
    pub alignment: TextAlignment,
    pub position_x: f32,
    pub position_y: f32,
    pub outline_color: Option<String>,
    pub outline_width: f32,
    pub shadow: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TextAlignment {
    Left,
    Center,
    Right,
}

impl Default for TextStyle {
    fn default() -> Self {
        Self {
            font_family: "sans-serif".into(),
            font_size: 24.0,
            color: "FFFFFFFF".into(),
            background_color: None,
            alignment: TextAlignment::Center,
            position_x: 0.5,
            position_y: 0.9,
            outline_color: None,
            outline_width: 0.0,
            shadow: false,
        }
    }
}
