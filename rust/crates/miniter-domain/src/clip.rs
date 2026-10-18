use crate::filter::{AudioFilter, VideoEffect};
use crate::keyframe::{KeyframeCurve, param};
use crate::mask::{BlendMode, MaskEffect};
use crate::text_overlay::TextOverlay;
use crate::time::{MediaDuration, Timestamp, scale_us_round};
use crate::transition::Transition;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

const SPEED_RAMP_BOUND_GROWTH_LIMIT: u32 = 40;

fn default_opacity() -> f32 {
    1.0
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct ClipId(pub Uuid);

impl ClipId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for ClipId {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
#[non_exhaustive]
pub enum ClipKind {
    Video(VideoClip),
    Audio(AudioClip),
    Text(TextOverlay),
    Subtitle(SubtitleClip),
    Adjustment(AdjustmentClip),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Clip {
    pub id: ClipId,
    pub timeline_start: Timestamp,
    pub timeline_duration: MediaDuration,

    #[serde(default)]
    pub source_start: MediaDuration,
    #[serde(default)]
    pub source_end: MediaDuration,
    #[serde(default)]
    pub source_total_duration: MediaDuration,

    pub speed: f64,
    pub volume: f32,

    #[serde(default = "default_opacity")]
    pub opacity: f32,

    pub muted: bool,
    pub transition_in: Option<Transition>,
    pub transition_out: Option<Transition>,
    pub kind: ClipKind,

    #[serde(default)]
    pub keyframes: KeyframeCurve,

    #[serde(default)]
    pub blend_mode: BlendMode,
}

impl Clip {
    pub fn timeline_end(&self) -> Timestamp {
        self.timeline_start + self.timeline_duration
    }

    pub fn source_duration(&self) -> MediaDuration {
        MediaDuration::from_micros(self.source_end.as_micros() - self.source_start.as_micros())
    }

    pub fn time_range(&self) -> crate::time::TimeRange {
        crate::time::TimeRange::new(self.timeline_start, self.timeline_end())
    }

    /// Source time consumed after `local_offset` of timeline time. A keyframed
    /// `speed` curve makes the rate vary, so the offset is the integral of the
    /// curve rather than `local_offset * speed`. Video and audio both read the
    /// source position through this so they cannot drift apart.
    pub fn source_offset_at(&self, local_offset: MediaDuration) -> i64 {
        speed_ramp_source_offset(&self.keyframes, self.speed, local_offset)
    }

    /// Inverse of [`Clip::source_offset_at`]. The speed curve keeps the
    /// mapping monotonic, so a bisection recovers the local offset.
    pub fn local_offset_at_source_offset(&self, source_offset_us: i64) -> i64 {
        if source_offset_us <= 0 {
            return 0;
        }

        if !self.keyframes.has_param(param::SPEED) {
            let speed = if self.speed.is_finite() && self.speed > 0.0 {
                self.speed
            } else {
                1.0
            };
            return (source_offset_us as f64 / speed).round() as i64;
        }

        let mut low = 0i64;
        let mut high = self.timeline_duration.as_micros().max(1);
        // A clip trimmed short must still be extendable back out to the source
        // it has left, which can need more local time than it currently has, so
        // grow the bound until the ramp has consumed the requested source.
        let mut growth = 0;
        while growth < SPEED_RAMP_BOUND_GROWTH_LIMIT
            && self.source_offset_at(MediaDuration::from_micros(high)) < source_offset_us
        {
            high = high.saturating_mul(2);
            growth += 1;
        }
        while high - low > 100 {
            let mid = low + (high - low) / 2;
            if self.source_offset_at(MediaDuration::from_micros(mid)) < source_offset_us {
                low = mid;
            } else {
                high = mid;
            }
        }
        high
    }

    pub fn normalize_source_bounds_in_place(&mut self) {
        // Speed 0 is a freeze frame and survives; negative and non-finite
        // speeds reset to normal playback.
        if !self.speed.is_finite() || self.speed < 0.0 {
            self.speed = 1.0;
        }

        if self.source_start.is_negative() {
            self.source_start = MediaDuration::ZERO;
        }

        if self.source_end.is_zero() && !self.timeline_duration.is_zero() {
            self.keyframes.sort_by_offset();
            let inferred =
                self.source_start.as_micros() + self.source_offset_at(self.timeline_duration);
            self.source_end =
                MediaDuration::from_micros(inferred.max(self.source_start.as_micros()));
        }

        if self.source_end < self.source_start {
            self.source_end = self.source_start;
        }

        if self.source_total_duration < self.source_end {
            self.source_total_duration = self.source_end;
        }

        self.opacity = self.opacity.clamp(0.0, 1.0);
        self.keyframes.sort_by_offset();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VideoClip {
    pub source_path: String,
    pub width: u32,
    pub height: u32,
    pub fps: f64,
    pub filters: Vec<VideoEffect>,
    pub audio_filters: Vec<AudioFilter>,
    #[serde(default)]
    pub masks: Vec<MaskEffect>,
}

/// Integrates a keyframed `speed` curve over `local_offset`. Exposed as a free
/// function so the audio mixer, which works from a scheduled snapshot rather
/// than a `Clip`, uses the exact same mapping as the renderer.
pub fn speed_ramp_source_offset(
    keyframes: &KeyframeCurve,
    speed: f64,
    local_offset: MediaDuration,
) -> i64 {
    let total_us = local_offset.as_micros();
    if total_us <= 0 {
        return 0;
    }

    if !keyframes.has_param(param::SPEED) {
        return scale_us_round(total_us, speed);
    }

    let fallback = if speed.is_finite() && speed >= 0.0 {
        speed
    } else {
        1.0
    };
    let sanitise = |value: f32| -> f64 {
        if value.is_finite() && value >= 0.0 {
            f64::from(value)
        } else {
            fallback
        }
    };
    let rate_at = |time_us: i64| -> f64 {
        keyframes
            .evaluate(param::SPEED, MediaDuration::from_micros(time_us))
            .map(sanitise)
            .unwrap_or(fallback)
    };

    // The rate is linear between consecutive `speed` keys and constant outside
    // them, so the trapezoid rule is exact on each segment. Sampling uniformly
    // instead would straddle the keys and misreport the consumed source.
    let mut bounds: Vec<i64> = vec![0];
    for keyframe in &keyframes.keyframes {
        if keyframe.param != param::SPEED {
            continue;
        }
        let offset = keyframe.offset.as_micros();
        if offset > 0 && offset < total_us {
            bounds.push(offset);
        }
    }
    bounds.push(total_us);
    bounds.sort_unstable();
    bounds.dedup();

    let mut accumulated = 0.0f64;
    for pair in bounds.windows(2) {
        let span = (pair[1] - pair[0]) as f64;
        accumulated += (rate_at(pair[0]) + rate_at(pair[1])) * 0.5 * span;
    }
    accumulated.round() as i64
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioClip {
    pub source_path: String,
    pub sample_rate: u32,
    pub channels: u16,
    pub filters: Vec<AudioFilter>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubtitleClip {
    pub source_path: String,
    #[serde(default)]
    pub font_path: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AdjustmentClip {
    #[serde(default)]
    pub filters: Vec<VideoEffect>,
    #[serde(default)]
    pub masks: Vec<MaskEffect>,
}
