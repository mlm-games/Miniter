use crate::time::MediaDuration;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransitionKind {
    CrossFade,
    SlideLeft,
    SlideRight,
    Dissolve,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Transition {
    pub kind: TransitionKind,
    pub duration: MediaDuration,
}

impl Transition {
    pub fn new(kind: TransitionKind, duration: MediaDuration) -> Self {
        Self { kind, duration }
    }
}
