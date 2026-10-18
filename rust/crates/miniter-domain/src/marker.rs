use crate::time::Timestamp;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct MarkerId(pub Uuid);

impl MarkerId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }
}

impl Default for MarkerId {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Marker {
    pub id: MarkerId,
    #[serde(default)]
    pub time: Timestamp,
    #[serde(default)]
    pub label: Option<String>,
    #[serde(default)]
    pub color: Option<String>,
}

impl Marker {
    pub fn new(time: Timestamp) -> Self {
        Self {
            id: MarkerId::new(),
            time: time.clamp_non_negative(),
            label: None,
            color: None,
        }
    }
}
