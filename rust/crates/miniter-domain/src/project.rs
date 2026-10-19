use crate::export::{ExportProfile, ExportRange};
use crate::marker::{Marker, MarkerId};
use crate::time::Timestamp;
use crate::timeline::Timeline;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct ProjectId(pub Uuid);

impl ProjectId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for ProjectId {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectMeta {
    pub name: String,
    pub created_at: i64,
    pub modified_at: i64,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Project {
    pub id: ProjectId,
    pub meta: ProjectMeta,
    pub timeline: Timeline,
    pub export_profile: ExportProfile,
    #[serde(default)]
    pub markers: Vec<Marker>,
    #[serde(default)]
    pub export_range: Option<ExportRange>,
}

impl Project {
    pub fn new(name: impl Into<String>) -> Self {
        let now = web_time::SystemTime::now()
            .duration_since(web_time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;
        Self {
            id: ProjectId::new(),
            meta: ProjectMeta {
                name: name.into(),
                created_at: now,
                modified_at: now,
                schema_version: 2,
            },
            timeline: Timeline::with_defaults(),
            export_profile: ExportProfile::default(),
            markers: Vec::new(),
            export_range: None,
        }
    }

    pub fn marker(&self, id: MarkerId) -> Option<&Marker> {
        self.markers.iter().find(|marker| marker.id == id)
    }

    pub fn marker_mut(&mut self, id: MarkerId) -> Option<&mut Marker> {
        self.markers.iter_mut().find(|marker| marker.id == id)
    }

    pub fn marker_insert_index(&self, time: Timestamp) -> usize {
        self.markers.partition_point(|marker| marker.time <= time)
    }

    pub fn insert_marker(&mut self, marker: Marker, index: Option<usize>) {
        let at = index
            .unwrap_or_else(|| self.marker_insert_index(marker.time))
            .min(self.markers.len());
        self.markers.insert(at, marker);
    }

    pub fn remove_marker(&mut self, id: MarkerId) -> Option<(usize, Marker)> {
        let index = self.markers.iter().position(|marker| marker.id == id)?;
        Some((index, self.markers.remove(index)))
    }

    pub fn sort_markers(&mut self) {
        self.markers.sort_by_key(|marker| marker.time);
    }

    pub fn normalize(mut self) -> Self {
        for track in &mut self.timeline.tracks {
            for clip in &mut track.clips {
                clip.normalize_source_bounds_in_place();
            }
        }
        self.markers.retain(|marker| marker.time.as_micros() >= 0);
        self.sort_markers();
        self
    }

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string_pretty(self)
    }

    pub fn from_json(json: &str) -> Result<Self, serde_json::Error> {
        let project: Self = serde_json::from_str(json)?;
        Ok(project.normalize())
    }
}
