use miniter_domain::Keyframe;
use miniter_domain::clip::{Clip, ClipId};
use miniter_domain::export::ExportProfile;
use miniter_domain::filter::{AudioFilter, VideoEffect};
use miniter_domain::mask::{BlendMode, MaskEffect};
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
        /// Identity to create the track under. `None` mints a fresh id, which
        /// is right for a new edit but wrong for a replay: undo/redo must
        /// address the same track, or the second undo removes an id that no
        /// longer exists and the transaction is lost.
        #[serde(default)]
        track_id: Option<TrackId>,
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
    /// Join two adjacent pieces of one cut: the left clip absorbs the right,
    /// extending its duration and source range to cover both. The pieces must
    /// touch on the timeline and continue each other in media time.
    JoinClips {
        left_id: ClipId,
        right_id: ClipId,
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
        filter: VideoEffect,
        /// Insert position for undo/redo of a removal. `None` appends, which is
        /// what a fresh edit wants; replaying a removal must restore the
        /// original slot or the filter stack order changes.
        #[serde(default)]
        index: Option<usize>,
    },
    UpdateVideoFilter {
        clip_id: ClipId,
        index: usize,
        filter: VideoEffect,
    },
    RemoveVideoFilter {
        clip_id: ClipId,
        index: usize,
    },
    SetVideoFilterEnabled {
        clip_id: ClipId,
        index: usize,
        enabled: bool,
    },
    MoveVideoFilter {
        clip_id: ClipId,
        from: usize,
        to: usize,
    },
    AddAudioFilter {
        clip_id: ClipId,
        filter: AudioFilter,
        /// See [`EditCommand::AddVideoFilter::index`].
        #[serde(default)]
        index: Option<usize>,
    },
    RemoveAudioFilter {
        clip_id: ClipId,
        index: usize,
    },
    UpdateAudioFilterDuration {
        clip_id: ClipId,
        index: usize,
        duration_us: i64,
    },
    UpdateAudioFilterDurationInverse {
        clip_id: ClipId,
        index: usize,
        old_duration_us: i64,
    },
    SetTransitionIn {
        clip_id: ClipId,
        #[serde(default)]
        transition: Option<Transition>,
    },
    SetTransitionOut {
        clip_id: ClipId,
        #[serde(default)]
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
    SetSubtitleFont {
        clip_id: ClipId,
        #[serde(default)]
        font_path: Option<String>,
    },
    SetExportProfile {
        profile: ExportProfile,
    },
    RenameProject {
        new_name: String,
    },
    AddKeyframe {
        clip_id: ClipId,
        keyframe: Keyframe,
    },
    RemoveKeyframe {
        clip_id: ClipId,
        index: usize,
    },
    UpdateKeyframe {
        clip_id: ClipId,
        index: usize,
        keyframe: Keyframe,
    },
    SetClipBlendMode {
        clip_id: ClipId,
        blend_mode: BlendMode,
    },
    AddMask {
        clip_id: ClipId,
        mask: MaskEffect,
        /// See [`EditCommand::AddVideoFilter::index`].
        #[serde(default)]
        index: Option<usize>,
    },
    UpdateMask {
        clip_id: ClipId,
        index: usize,
        mask: MaskEffect,
    },
    RemoveMask {
        clip_id: ClipId,
        index: usize,
    },
    SetMaskEnabled {
        clip_id: ClipId,
        index: usize,
        enabled: bool,
    },
    Batch {
        label: String,
        commands: Vec<EditCommand>,
    },
    Nop,
}
