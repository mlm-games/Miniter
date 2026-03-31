use crate::filter::{AudioFilter, VideoFilter};
use crate::text_overlay::TextOverlay;
use crate::time::{MediaDuration, Timestamp};
use crate::transition::Transition;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Clip {
    pub id: ClipId,
    pub timeline_start: Timestamp,
    pub timeline_duration: MediaDuration,
    pub source_offset: MediaDuration,
    pub speed: f64,
    pub volume: f32,
    pub muted: bool,
    pub transition_in: Option<Transition>,
    pub kind: ClipKind,
}

impl Clip {
    pub fn timeline_end(&self) -> Timestamp {
        self.timeline_start + self.timeline_duration
    }

    pub fn source_duration(&self) -> MediaDuration {
        MediaDuration::from_micros((self.timeline_duration.as_micros() as f64 * self.speed) as i64)
    }

    pub fn time_range(&self) -> crate::time::TimeRange {
        crate::time::TimeRange::new(self.timeline_start, self.timeline_end())
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
