use crate::time::MediaDuration;
use serde::{Deserialize, Serialize};

pub mod param {
    pub const OPACITY: &str = "opacity";
    pub const VOLUME: &str = "volume";
    pub const TRANSFORM_SCALE: &str = "transform.scale";
    pub const TRANSFORM_TRANSLATE_X: &str = "transform.translate_x";
    pub const TRANSFORM_TRANSLATE_Y: &str = "transform.translate_y";
    pub const TRANSFORM_ROTATE: &str = "transform.rotate";
    pub const TEXT_POSITION_X: &str = "text.position_x";
    pub const TEXT_POSITION_Y: &str = "text.position_y";
    pub const TEXT_FONT_SIZE: &str = "text.font_size";

    pub const MASK_FEATHER: &str = "mask.feather";
    pub const MASK_SCALE: &str = "mask.scale";
    pub const MASK_TRANSLATE_X: &str = "mask.translate_x";
    pub const MASK_TRANSLATE_Y: &str = "mask.translate_y";
    pub const MASK_ROTATE: &str = "mask.rotate";
    pub const MASK_SHAPE_LEFT: &str = "mask.shape.left";
    pub const MASK_SHAPE_TOP: &str = "mask.shape.top";
    pub const MASK_SHAPE_RIGHT: &str = "mask.shape.right";
    pub const MASK_SHAPE_BOTTOM: &str = "mask.shape.bottom";
    pub const MASK_SHAPE_CENTER_X: &str = "mask.shape.center_x";
    pub const MASK_SHAPE_CENTER_Y: &str = "mask.shape.center_y";
    pub const MASK_SHAPE_RADIUS_X: &str = "mask.shape.radius_x";
    pub const MASK_SHAPE_RADIUS_Y: &str = "mask.shape.radius_y";
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
#[derive(Default)]
pub enum Easing {
    #[default]
    Linear,
    EaseIn,
    EaseOut,
    EaseInOut,
}


#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Keyframe {
    pub param: String,
    pub offset: MediaDuration,
    pub value: f32,
    #[serde(default)]
    pub easing: Easing,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[derive(Default)]
pub struct KeyframeCurve {
    pub keyframes: Vec<Keyframe>,
}

impl KeyframeCurve {
    pub fn evaluate(&self, param: &str, local_time: MediaDuration) -> Option<f32> {
        let relevant: Vec<&Keyframe> = self.keyframes.iter().filter(|k| k.param == param).collect();

        if relevant.is_empty() {
            return None;
        }

        if relevant.len() == 1 {
            return Some(relevant[0].value);
        }

        let t = local_time.as_micros();
        if t <= relevant[0].offset.as_micros() {
            return Some(relevant[0].value);
        }
        if t >= relevant[relevant.len() - 1].offset.as_micros() {
            return Some(relevant[relevant.len() - 1].value);
        }

        for i in 0..relevant.len() - 1 {
            let a = relevant[i];
            let b = relevant[i + 1];
            let t_a = a.offset.as_micros();
            let t_b = b.offset.as_micros();

            if t >= t_a && t <= t_b {
                let range = (t_b - t_a) as f32;
                if range <= 0.0 {
                    return Some(b.value);
                }
                let progress = ((t - t_a) as f32 / range).clamp(0.0, 1.0);
                let eased = apply_easing(a.easing, progress);
                return Some(a.value + (b.value - a.value) * eased);
            }
        }

        Some(relevant[relevant.len() - 1].value)
    }

    pub fn insert_sorted(&mut self, kf: Keyframe) -> usize {
        let idx = self
            .keyframes
            .iter()
            .position(|k| k.offset > kf.offset)
            .unwrap_or(self.keyframes.len());
        self.keyframes.insert(idx, kf);
        idx
    }
}


pub fn ease_linear(t: f32) -> f32 {
    t
}

pub fn ease_in(t: f32) -> f32 {
    t * t * t
}

pub fn ease_out(t: f32) -> f32 {
    1.0 - (1.0 - t).powi(3)
}

pub fn ease_in_out(t: f32) -> f32 {
    if t < 0.5 {
        4.0 * t * t * t
    } else {
        1.0 - (-2.0 * t + 2.0).powi(3) / 2.0
    }
}

fn apply_easing(easing: Easing, t: f32) -> f32 {
    match easing {
        Easing::Linear => t,
        Easing::EaseIn => ease_in(t),
        Easing::EaseOut => ease_out(t),
        Easing::EaseInOut => ease_in_out(t),
    }
}
