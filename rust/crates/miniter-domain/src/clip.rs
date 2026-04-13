use crate::filter::{AudioFilter, VideoFilter};
use crate::text_overlay::TextOverlay;
use crate::time::{MediaDuration, Timestamp};
use crate::transition::Transition;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

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
pub enum ClipKind {
    Video(VideoClip),
    Audio(AudioClip),
    Text(TextOverlay),
    Subtitle(SubtitleClip),
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

    pub fn normalize_source_bounds_in_place(&mut self) {
        if self.speed <= 0.0 {
            self.speed = 1.0;
        }

        if self.source_start.is_negative() {
            self.source_start = MediaDuration::ZERO;
        }

        if self.source_end.is_zero() && !self.timeline_duration.is_zero() {
            let inferred = self.source_start.as_micros()
                + (self.timeline_duration.as_micros() as f64 * self.speed) as i64;
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
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VideoClip {
    pub source_path: String,
    pub width: u32,
    pub height: u32,
    pub fps: f64,
    pub filters: Vec<VideoFilter>,
    pub audio_filters: Vec<AudioFilter>,
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
}
