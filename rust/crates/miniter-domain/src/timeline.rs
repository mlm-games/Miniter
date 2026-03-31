use crate::clip::ClipId;
use crate::time::Timestamp;
use crate::track::{Track, TrackId, TrackKind};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Timeline {
    pub tracks: Vec<Track>,
}

impl Timeline {
    pub fn new() -> Self {
        Self { tracks: Vec::new() }
    }

    pub fn with_defaults() -> Self {
        Self {
            tracks: vec![
                Track::new(TrackKind::Video, "Video 1"),
                Track::new(TrackKind::Audio, "Audio 1"),
                Track::new(TrackKind::Text, "Text 1"),
            ],
        }
    }

    pub fn track(&self, id: TrackId) -> Option<&Track> {
        self.tracks.iter().find(|t| t.id == id)
    }

    pub fn track_mut(&mut self, id: TrackId) -> Option<&mut Track> {
        self.tracks.iter_mut().find(|t| t.id == id)
    }

    pub fn track_of_clip(&self, clip_id: ClipId) -> Option<&Track> {
        self.tracks.iter().find(|t| t.clip_by_id(clip_id).is_some())
    }

    pub fn track_of_clip_mut(&mut self, clip_id: ClipId) -> Option<&mut Track> {
        self.tracks
            .iter_mut()
            .find(|t| t.clip_by_id(clip_id).is_some())
    }

    pub fn add_track(&mut self, track: Track) {
        self.tracks.push(track);
    }

    pub fn remove_track(&mut self, id: TrackId) -> Option<Track> {
        let idx = self.tracks.iter().position(|t| t.id == id)?;
        Some(self.tracks.remove(idx))
    }

    pub fn duration_end(&self) -> Timestamp {
        self.tracks
            .iter()
            .map(|t| t.end_timestamp())
            .max()
            .unwrap_or(Timestamp::ZERO)
    }

    pub fn active_tracks_at(&self, t: Timestamp) -> Vec<&Track> {
        self.tracks
            .iter()
            .filter(|track| track.clip_at(t).is_some())
            .collect()
    }
}

impl Default for Timeline {
    fn default() -> Self {
        Self::new()
    }
}
