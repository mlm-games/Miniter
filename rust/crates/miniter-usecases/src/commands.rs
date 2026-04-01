use miniter_domain::clip::{Clip, ClipId};
use miniter_domain::export::ExportProfile;
use miniter_domain::filter::{AudioFilter, VideoFilter};
use miniter_domain::text_overlay::TextStyle;
use miniter_domain::time::{MediaDuration, Timestamp};
use miniter_domain::track::{TrackId, TrackKind};
use miniter_domain::transition::Transition;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum EditCommand {
    AddTrack {
        kind: TrackKind,
        name: String,
    },
    RemoveTrack {
        track_id: TrackId,
    },
    RestoreTrack {
        track: miniter_domain::track::Track,
    },
    RenameTrack {
        track_id: TrackId,
        new_name: String,
    },
    SetTrackMuted {
        track_id: TrackId,
        muted: bool,
    },
    SetTrackLocked {
        track_id: TrackId,
        locked: bool,
    },
    AddClip {
        track_id: TrackId,
        clip: Clip,
    },
    RemoveClip {
        clip_id: ClipId,
    },
    MoveClip {
        clip_id: ClipId,
        new_track_id: TrackId,
        new_start: Timestamp,
    },
    DuplicateClip {
        source_clip_id: ClipId,
        new_clip_id: ClipId,
        target_track_id: TrackId,
        target_start: Timestamp,
    },
    TrimClipStart {
        clip_id: ClipId,
        new_start: Timestamp,
        new_source_start: MediaDuration,
    },
    TrimClipEnd {
        clip_id: ClipId,
        new_duration: MediaDuration,
    },
    SplitClip {
        clip_id: ClipId,
        at: Timestamp,
        new_clip_id: ClipId,
    },
    SetClipSpeed {
        clip_id: ClipId,
        speed: f64,
    },
    SetClipVolume {
        clip_id: ClipId,
        volume: f32,
    },
    SetClipOpacity {
        clip_id: ClipId,
        opacity: f32,
    },
    SetClipMuted {
        clip_id: ClipId,
        muted: bool,
    },
    AddVideoFilter {
        clip_id: ClipId,
        filter: VideoFilter,
    },
    UpdateVideoFilter {
        clip_id: ClipId,
        index: usize,
        filter: VideoFilter,
    },
    RemoveVideoFilter {
        clip_id: ClipId,
        index: usize,
    },
    AddAudioFilter {
        clip_id: ClipId,
        filter: AudioFilter,
    },
    RemoveAudioFilter {
        clip_id: ClipId,
        index: usize,
    },
    SetTransitionIn {
        clip_id: ClipId,
        transition: Option<Transition>,
    },
    UpdateTextContent {
        clip_id: ClipId,
        text: String,
    },
    UpdateTextStyle {
        clip_id: ClipId,
        style: TextStyle,
    },
    SetExportProfile {
        profile: ExportProfile,
    },
    Batch {
        label: String,
        commands: Vec<EditCommand>,
    },
}
