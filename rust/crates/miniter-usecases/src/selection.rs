use miniter_domain::clip::ClipId;
use miniter_domain::time::TimeRange;
use miniter_domain::track::TrackId;

#[derive(Debug, Clone, Default)]
pub struct Selection {
    pub clips: Vec<ClipId>,
    pub tracks: Vec<TrackId>,
    pub time_range: Option<TimeRange>,
}

impl Selection {
    pub fn clear(&mut self) {
        self.clips.clear();
        self.tracks.clear();
        self.time_range = None;
    }

    pub fn select_clip(&mut self, id: ClipId) {
        if !self.clips.contains(&id) {
            self.clips.push(id);
        }
    }

    pub fn deselect_clip(&mut self, id: ClipId) {
        self.clips.retain(|c| *c != id);
    }

    pub fn toggle_clip(&mut self, id: ClipId) {
        if self.clips.contains(&id) {
            self.deselect_clip(id);
        } else {
            self.select_clip(id);
        }
    }

    pub fn is_clip_selected(&self, id: ClipId) -> bool {
        self.clips.contains(&id)
    }

    pub fn select_track(&mut self, id: TrackId) {
        if !self.tracks.contains(&id) {
            self.tracks.push(id);
        }
    }

    pub fn set_range(&mut self, range: TimeRange) {
        self.time_range = Some(range);
    }
}
