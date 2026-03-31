use crate::commands::EditCommand;
use crate::history::History;
use crate::selection::Selection;
use miniter_domain::clip::{Clip, ClipId};
use miniter_domain::project::Project;
use miniter_domain::time::{MediaDuration, Timestamp};
use miniter_domain::track::Track;

#[derive(Debug, Clone)]
pub struct EditorState {
    pub project: Project,
    pub selection: Selection,
    pub playhead: Timestamp,
    pub history: History,
}

impl EditorState {
    pub fn new(project: Project) -> Self {
        Self {
            project,
            selection: Selection::default(),
            playhead: Timestamp::ZERO,
            history: History::new(128),
        }
    }
}

pub fn apply(state: &mut EditorState, cmd: EditCommand) -> Result<EditCommand, ApplyError> {
    match cmd {
        EditCommand::AddTrack { kind, name } => {
            let track = Track::new(kind, &name);
            let id = track.id;
            state.project.timeline.add_track(track);
            Ok(EditCommand::RemoveTrack { track_id: id })
        }

        EditCommand::RemoveTrack { track_id } => {
            let track = state
                .project
                .timeline
                .remove_track(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            Ok(EditCommand::RestoreTrack { track })
        }

        EditCommand::RestoreTrack { track } => {
            state.project.timeline.add_track(track.clone());
            Ok(EditCommand::RemoveTrack { track_id: track.id })
        }

        EditCommand::RenameTrack { track_id, new_name } => {
            let track = state
                .project
                .timeline
                .track_mut(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            let old_name = std::mem::replace(&mut track.name, new_name);
            Ok(EditCommand::RenameTrack {
                track_id,
                new_name: old_name,
            })
        }

        EditCommand::SetTrackMuted { track_id, muted } => {
            let track = state
                .project
                .timeline
                .track_mut(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            let was = track.muted;
            track.muted = muted;
            Ok(EditCommand::SetTrackMuted {
                track_id,
                muted: was,
            })
        }

        EditCommand::SetTrackLocked { track_id, locked } => {
            let track = state
                .project
                .timeline
                .track_mut(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            let was = track.locked;
            track.locked = locked;
            Ok(EditCommand::SetTrackLocked {
                track_id,
                locked: was,
            })
        }

        EditCommand::AddClip { track_id, clip } => {
            let cid = clip.id;
            let track = state
                .project
                .timeline
                .track_mut(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            track.insert_clip(clip).map_err(ApplyError::Overlap)?;
            Ok(EditCommand::RemoveClip { clip_id: cid })
        }

        EditCommand::RemoveClip { clip_id } => {
            let track = state
                .project
                .timeline
                .track_of_clip_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;
            let tid = track.id;
            let clip = track
                .remove_clip(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;
            Ok(EditCommand::AddClip {
                track_id: tid,
                clip,
            })
        }

        EditCommand::MoveClip {
            clip_id,
            new_track_id,
            new_start,
        } => {
            let src_track_idx = state
                .project
                .timeline
                .tracks
                .iter()
                .position(|t| t.clip_by_id(clip_id).is_some())
                .ok_or(ApplyError::ClipNotFound(clip_id))?;

            let dst_track_exists = state
                .project
                .timeline
                .tracks
                .iter()
                .any(|t| t.id == new_track_id);
            if !dst_track_exists {
                return Err(ApplyError::TrackNotFound(new_track_id));
            }

            // Get clip data before removing.
            let clip = state.project.timeline.tracks[src_track_idx]
                .clip_by_id(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?
                .clone();
            let old_track_id = state.project.timeline.tracks[src_track_idx].id;
            let old_start = clip.timeline_start;

            let dst_track_idx = state
                .project
                .timeline
                .tracks
                .iter()
                .position(|t| t.id == new_track_id)
                .ok_or(ApplyError::TrackNotFound(new_track_id))?;

            // Remove from source.
            let mut removed_clip = state.project.timeline.tracks[src_track_idx]
                .remove_clip(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;
            removed_clip.timeline_start = new_start;

            state.project.timeline.tracks[dst_track_idx]
                .insert_clip(removed_clip)
                .map_err(ApplyError::Overlap)?;

            Ok(EditCommand::MoveClip {
                clip_id,
                new_track_id: old_track_id,
                new_start: old_start,
            })
        }

        EditCommand::DuplicateClip {
            source_clip_id,
            new_clip_id,
            target_track_id,
            target_start,
        } => {
            let src = state
                .project
                .timeline
                .track_of_clip(source_clip_id)
                .ok_or(ApplyError::ClipNotFound(source_clip_id))?
                .clip_by_id(source_clip_id)
                .ok_or(ApplyError::ClipNotFound(source_clip_id))?
                .clone();

            let mut dup = src;
            dup.id = new_clip_id;
            dup.timeline_start = target_start;

            let track = state
                .project
                .timeline
                .track_mut(target_track_id)
                .ok_or(ApplyError::TrackNotFound(target_track_id))?;
            track.insert_clip(dup).map_err(ApplyError::Overlap)?;

            Ok(EditCommand::RemoveClip {
                clip_id: new_clip_id,
            })
        }

        EditCommand::TrimClipStart {
            clip_id,
            new_start,
            new_source_offset,
        } => {
            let track = state
                .project
                .timeline
                .track_of_clip_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;
            let clip = track
                .clip_by_id_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;

            let old_start = clip.timeline_start;
            let old_offset = clip.source_offset;
            let old_dur = clip.timeline_duration;

            let delta = new_start - old_start;
            clip.timeline_start = new_start;
            clip.source_offset = new_source_offset;
            clip.timeline_duration =
                MediaDuration::from_micros(old_dur.as_micros() - delta.as_micros());

            Ok(EditCommand::TrimClipStart {
                clip_id,
                new_start: old_start,
                new_source_offset: old_offset,
            })
        }

        EditCommand::TrimClipEnd {
            clip_id,
            new_duration,
        } => {
            let track = state
                .project
                .timeline
                .track_of_clip_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;
            let clip = track
                .clip_by_id_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;

            let old_dur = clip.timeline_duration;
            clip.timeline_duration = new_duration;
            Ok(EditCommand::TrimClipEnd {
                clip_id,
                new_duration: old_dur,
            })
        }

        EditCommand::SplitClip {
            clip_id,
            at,
            new_clip_id,
        } => {
            let track = state
                .project
                .timeline
                .track_of_clip_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;

            let clip = track
                .clip_by_id(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?
                .clone();

            if !clip.time_range().contains(at) {
                return Err(ApplyError::SplitOutOfRange { clip_id, at });
            }

            let left_dur = at - clip.timeline_start;
            let right_dur = MediaDuration::from_micros(
                clip.timeline_duration.as_micros() - left_dur.as_micros(),
            );
            let right_source_offset = MediaDuration::from_micros(
                clip.source_offset.as_micros() + (left_dur.as_micros() as f64 * clip.speed) as i64,
            );

            let left = track
                .clip_by_id_mut(clip_id)
                .ok_or(ApplyError::ClipNotFound(clip_id))?;
            left.timeline_duration = left_dur;

            let mut right = clip.clone();
            right.id = new_clip_id;
            right.timeline_start = at;
            right.timeline_duration = right_dur;
            right.source_offset = right_source_offset;
            right.transition_in = None;

            track.insert_clip(right).map_err(ApplyError::Overlap)?;

            Ok(EditCommand::Batch {
                label: "Undo split".into(),
                commands: vec![
                    EditCommand::RemoveClip {
                        clip_id: new_clip_id,
                    },
                    EditCommand::TrimClipEnd {
                        clip_id,
                        new_duration: MediaDuration::from_micros(
                            left_dur.as_micros() + right_dur.as_micros(),
                        ),
                    },
                ],
            })
        }

        EditCommand::SetClipSpeed { clip_id, speed } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.speed;
            clip.speed = speed;
            Ok(EditCommand::SetClipSpeed {
                clip_id,
                speed: old,
            })
        }

        EditCommand::SetClipVolume { clip_id, volume } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.volume;
            clip.volume = volume;
            Ok(EditCommand::SetClipVolume {
                clip_id,
                volume: old,
            })
        }

        EditCommand::SetClipMuted { clip_id, muted } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.muted;
            clip.muted = muted;
            Ok(EditCommand::SetClipMuted {
                clip_id,
                muted: old,
            })
        }

        EditCommand::AddVideoFilter { clip_id, filter } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                miniter_domain::clip::ClipKind::Video(v) => {
                    v.filters.push(filter);
                    let idx = v.filters.len() - 1;
                    Ok(EditCommand::RemoveVideoFilter {
                        clip_id,
                        index: idx,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::RemoveVideoFilter { clip_id, index } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                miniter_domain::clip::ClipKind::Video(v) => {
                    if index >= v.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let removed = v.filters.remove(index);
                    Ok(EditCommand::AddVideoFilter {
                        clip_id,
                        filter: removed,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::AddAudioFilter { clip_id, filter } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                miniter_domain::clip::ClipKind::Video(v) => {
                    v.audio_filters.push(filter);
                    let idx = v.audio_filters.len() - 1;
                    Ok(EditCommand::RemoveAudioFilter {
                        clip_id,
                        index: idx,
                    })
                }
                miniter_domain::clip::ClipKind::Audio(a) => {
                    a.filters.push(filter);
                    let idx = a.filters.len() - 1;
                    Ok(EditCommand::RemoveAudioFilter {
                        clip_id,
                        index: idx,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::RemoveAudioFilter { clip_id, index } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                miniter_domain::clip::ClipKind::Video(v) => {
                    if index >= v.audio_filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let removed = v.audio_filters.remove(index);
                    Ok(EditCommand::AddAudioFilter {
                        clip_id,
                        filter: removed,
                    })
                }
                miniter_domain::clip::ClipKind::Audio(a) => {
                    if index >= a.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let removed = a.filters.remove(index);
                    Ok(EditCommand::AddAudioFilter {
                        clip_id,
                        filter: removed,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::SetTransitionIn {
            clip_id,
            transition,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.transition_in.take();
            clip.transition_in = transition;
            Ok(EditCommand::SetTransitionIn {
                clip_id,
                transition: old,
            })
        }

        EditCommand::SetExportProfile { profile } => {
            let old = std::mem::replace(&mut state.project.export_profile, profile);
            Ok(EditCommand::SetExportProfile { profile: old })
        }

        EditCommand::Batch { label, commands } => {
            let mut inverses = Vec::with_capacity(commands.len());
            for cmd in commands {
                inverses.push(apply(state, cmd)?);
            }
            inverses.reverse();
            Ok(EditCommand::Batch {
                label: format!("Undo {label}"),
                commands: inverses,
            })
        }
    }
}

fn find_clip_mut(state: &mut EditorState, clip_id: ClipId) -> Result<&mut Clip, ApplyError> {
    state
        .project
        .timeline
        .track_of_clip_mut(clip_id)
        .and_then(|t| t.clip_by_id_mut(clip_id))
        .ok_or(ApplyError::ClipNotFound(clip_id))
}

#[derive(Debug, thiserror::Error)]
pub enum ApplyError {
    #[error("Track not found: {0:?}")]
    TrackNotFound(miniter_domain::track::TrackId),

    #[error("Clip not found: {0:?}")]
    ClipNotFound(ClipId),

    #[error("Wrong clip kind for operation: {0:?}")]
    WrongClipKind(ClipId),

    #[error("Split point outside clip range: clip={clip_id:?} at={at:?}")]
    SplitOutOfRange { clip_id: ClipId, at: Timestamp },

    #[error("Index out of bounds")]
    IndexOutOfBounds,

    #[error(transparent)]
    Overlap(#[from] miniter_domain::track::TrackOverlapError),
}

pub fn dispatch(state: &mut EditorState, cmd: EditCommand) -> Result<(), ApplyError> {
    let inverse = apply(state, cmd)?;
    state.history.push(inverse);
    Ok(())
}

pub fn undo(state: &mut EditorState) -> Result<(), ApplyError> {
    if let Some(inverse) = state.history.pop_undo() {
        let redo_cmd = apply(state, inverse)?;
        state.history.push_redo(redo_cmd);
    }
    Ok(())
}

pub fn redo(state: &mut EditorState) -> Result<(), ApplyError> {
    if let Some(redo_cmd) = state.history.pop_redo() {
        let inverse = apply(state, redo_cmd)?;
        state.history.push(inverse);
    }
    Ok(())
}
