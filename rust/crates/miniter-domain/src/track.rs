use crate::clip::{Clip, ClipId};
use crate::time::Timestamp;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct TrackId(pub Uuid);

impl TrackId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for TrackId {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TrackKind {
    Video,
    Audio,
    Text,
    Subtitle,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Track {
    pub id: TrackId,
    pub name: String,
    pub kind: TrackKind,
    pub muted: bool,
    pub locked: bool,
    pub clips: Vec<Clip>,
}

impl Track {
    pub fn new(kind: TrackKind, name: impl Into<String>) -> Self {
        Self {
            id: TrackId::new(),
            name: name.into(),
            kind,
            muted: false,
            locked: false,
            clips: Vec::new(),
        }
    }

    pub fn clip_at(&self, t: Timestamp) -> Option<&Clip> {
        self.clips.iter().find(|c| c.time_range().contains(t))
    }

    pub fn clip_by_id(&self, id: ClipId) -> Option<&Clip> {
        self.clips.iter().find(|c| c.id == id)
    }

    pub fn clip_by_id_mut(&mut self, id: ClipId) -> Option<&mut Clip> {
        self.clips.iter_mut().find(|c| c.id == id)
    }

    pub fn clip_index(&self, id: ClipId) -> Option<usize> {
        self.clips.iter().position(|c| c.id == id)
    }

    pub fn can_insert_clip(
        &self,
        clip: &Clip,
        ignore_clip_id: Option<ClipId>,
    ) -> Result<(), TrackOverlapError> {
        let range = clip.time_range();
        let overlaps = self
            .clips
            .iter()
            .filter(|existing| Some(existing.id) != ignore_clip_id)
            .any(|existing| existing.time_range().overlaps(range));

        if overlaps {
            Err(TrackOverlapError {
                clip_id: clip.id,
                track_id: self.id,
            })
        } else {
            Ok(())
        }
    }

    pub fn insert_clip(&mut self, clip: Clip) -> Result<(), TrackOverlapError> {
        self.can_insert_clip(&clip, None)?;
        self.clips.push(clip);
        self.sort_clips();
        Ok(())
    }

    pub fn remove_clip(&mut self, id: ClipId) -> Option<Clip> {
        let idx = self.clip_index(id)?;
        Some(self.clips.remove(idx))
    }

    pub fn sort_clips(&mut self) {
        self.clips.sort_by_key(|c| c.timeline_start);
    }

    pub fn end_timestamp(&self) -> Timestamp {
        self.clips
            .iter()
            .map(|c| c.timeline_end())
            .max()
            .unwrap_or(Timestamp::ZERO)
    }
}

#[derive(Debug, Clone, thiserror::Error)]
#[error("Clip {clip_id:?} overlaps an existing clip on track {track_id:?}")]
pub struct TrackOverlapError {
    pub clip_id: ClipId,
    pub track_id: TrackId,
}
