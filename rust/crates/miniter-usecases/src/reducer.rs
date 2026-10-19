use crate::commands::EditCommand;
use crate::history::History;
use crate::selection::Selection;
use miniter_domain::clip::{Clip, ClipId, ClipKind};
use miniter_domain::filter::{AudioFilter, VideoEffect};
use miniter_domain::keyframe::{Keyframe, KeyframeCurve, param};
use miniter_domain::marker::MarkerId;
use miniter_domain::project::Project;
use miniter_domain::time::{MediaDuration, Timestamp, scale_us_round, unscale_us_round};
use miniter_domain::track::{Track, TrackId};

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
        EditCommand::AddTrack {
            kind,
            name,
            track_id,
        } => {
            let mut track = Track::new(kind, &name);
            if let Some(id) = track_id {
                track.id = id;
            }
            let id = track.id;
            state.project.timeline.add_track(track);
            Ok(EditCommand::RemoveTrack { track_id: id })
        }

        EditCommand::Nop => Ok(EditCommand::Nop),

        EditCommand::RemoveTrack { track_id } => {
            let from_index = find_track_index(state, track_id)?;
            if !state.project.timeline.tracks[from_index].clips.is_empty() {
                return Err(ApplyError::TrackNotEmpty(track_id));
            }
            let track = state
                .project
                .timeline
                .remove_track(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            Ok(EditCommand::RestoreTrack {
                track,
                index: Some(from_index),
            })
        }

        EditCommand::RestoreTrack { track, index } => {
            let at = index
                .unwrap_or(state.project.timeline.tracks.len())
                .min(state.project.timeline.tracks.len());
            let track_id = track.id;
            state.project.timeline.tracks.insert(at, track);
            Ok(EditCommand::RemoveTrack { track_id })
        }

        EditCommand::MoveTrack { track_id, to_index } => {
            let from_index = find_track_index(state, track_id)?;
            if to_index >= state.project.timeline.tracks.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let track = state.project.timeline.tracks.remove(from_index);
            state.project.timeline.tracks.insert(to_index, track);
            Ok(EditCommand::MoveTrack {
                track_id,
                to_index: from_index,
            })
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

        EditCommand::SetTrackSolo { track_id, solo } => {
            let track = state
                .project
                .timeline
                .track_mut(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            let was = track.solo;
            track.solo = solo;
            Ok(EditCommand::SetTrackSolo {
                track_id,
                solo: was,
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

        EditCommand::AddClip { track_id, mut clip } => {
            clip.normalize_source_bounds_in_place();
            ensure_positive_duration(clip.timeline_duration)?;
            ensure_clip_source_bounds(&clip)?;

            let cid = clip.id;
            let track = state
                .project
                .timeline
                .track_mut(track_id)
                .ok_or(ApplyError::TrackNotFound(track_id))?;
            if !track.kind.accepts_clip_kind(&clip.kind) {
                return Err(ApplyError::IncompatibleTrackKind {
                    track_id,
                    clip_id: cid,
                });
            }
            if track.locked {
                return Err(ApplyError::TrackLocked(track_id));
            }
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
            if track.locked {
                return Err(ApplyError::TrackLocked(tid));
            }
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
        } => apply_move_clip(state, clip_id, new_track_id, new_start),

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
            dup.timeline_start = target_start.clamp_non_negative();

            let track = state
                .project
                .timeline
                .track_mut(target_track_id)
                .ok_or(ApplyError::TrackNotFound(target_track_id))?;
            if !track.kind.accepts_clip_kind(&dup.kind) {
                return Err(ApplyError::IncompatibleTrackKind {
                    track_id: target_track_id,
                    clip_id: new_clip_id,
                });
            }
            track.insert_clip(dup).map_err(ApplyError::Overlap)?;

            Ok(EditCommand::RemoveClip {
                clip_id: new_clip_id,
            })
        }

        EditCommand::TrimClipStart {
            clip_id, new_start, ..
        } => apply_trim_clip_start(state, clip_id, new_start),

        EditCommand::TrimClipEnd {
            clip_id,
            new_duration,
        } => apply_trim_clip_end(state, clip_id, new_duration),

        EditCommand::SlipClip { clip_id, delta_us } => apply_slip_clip(state, clip_id, delta_us),

        EditCommand::SplitClip {
            clip_id,
            at,
            new_clip_id,
        } => apply_split_clip(state, clip_id, at, new_clip_id),

        EditCommand::JoinClips { left_id, right_id } => apply_join_clips(state, left_id, right_id),

        EditCommand::SetClipSpeed { clip_id, speed } => {
            if speed <= 0.0 {
                return Err(ApplyError::InvalidSpeed(speed));
            }
            let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
            let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();
            let mut updated = original.clone();
            updated.speed = speed;
            let new_duration =
                timeline_duration_for_clip(&updated, updated.source_start, updated.source_end)?;
            ensure_positive_duration(new_duration)?;
            updated.timeline_duration = new_duration;
            state.project.timeline.tracks[track_idx]
                .can_insert_clip(&updated, Some(clip_id))
                .map_err(ApplyError::Overlap)?;
            let clip = &mut state.project.timeline.tracks[track_idx].clips[clip_idx];
            let old = clip.speed;
            clip.speed = speed;
            clip.timeline_duration = new_duration;
            Ok(EditCommand::SetClipSpeed {
                clip_id,
                speed: old,
            })
        }

        EditCommand::SetClipVolume { clip_id, volume } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.volume;
            // Gain cannot be negative. The mixer treats a non-positive gain as
            // "do not mix", so a negative value loaded from a hand-edited
            // project silently dropped the clip and could empty the whole mix.
            clip.volume = volume.max(0.0);
            Ok(EditCommand::SetClipVolume {
                clip_id,
                volume: old,
            })
        }

        EditCommand::SetClipOpacity { clip_id, opacity } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.opacity;
            clip.opacity = opacity.clamp(0.0, 1.0);
            Ok(EditCommand::SetClipOpacity {
                clip_id,
                opacity: old,
            })
        }

        EditCommand::SetClipBlendMode {
            clip_id,
            blend_mode,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.blend_mode;
            clip.blend_mode = blend_mode;
            Ok(EditCommand::SetClipBlendMode {
                clip_id,
                blend_mode: old,
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

        EditCommand::AddVideoFilter {
            clip_id,
            filter,
            index,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = video_filters_mut(&mut clip.kind, clip_id)?;
            let filter_pos = index.unwrap_or(filters.len()).min(filters.len());
            if let Some(key) = VideoEffect::singleton_key(&filter.filter)
                && let Some(idx) = filters
                    .iter()
                    .position(|fx| VideoEffect::singleton_key(&fx.filter) == Some(key))
            {
                let old = std::mem::replace(&mut filters[idx], filter);
                return Ok(EditCommand::UpdateVideoFilter {
                    clip_id,
                    index: idx,
                    filter: old,
                });
            }
            filters.insert(filter_pos, filter);
            let idx = filter_pos;
            Ok(EditCommand::RemoveVideoFilter {
                clip_id,
                index: idx,
            })
        }

        EditCommand::UpdateVideoFilter {
            clip_id,
            index,
            filter,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = video_filters_mut(&mut clip.kind, clip_id)?;
            if index >= filters.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let old = std::mem::replace(&mut filters[index], filter);
            Ok(EditCommand::UpdateVideoFilter {
                clip_id,
                index,
                filter: old,
            })
        }

        EditCommand::RemoveVideoFilter { clip_id, index } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = video_filters_mut(&mut clip.kind, clip_id)?;
            if index >= filters.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let removed = filters.remove(index);
            Ok(EditCommand::AddVideoFilter {
                clip_id,
                filter: removed,
                index: Some(index),
            })
        }

        EditCommand::AddMask {
            clip_id,
            mask,
            index,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let masks = video_masks_mut(&mut clip.kind, clip_id)?;
            let mask_pos = index.unwrap_or(masks.len()).min(masks.len());
            masks.insert(mask_pos, mask);
            Ok(EditCommand::RemoveMask {
                clip_id,
                index: mask_pos,
            })
        }

        EditCommand::UpdateMask {
            clip_id,
            index,
            mask,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let masks = video_masks_mut(&mut clip.kind, clip_id)?;
            if index >= masks.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let old = std::mem::replace(&mut masks[index], mask);
            Ok(EditCommand::UpdateMask {
                clip_id,
                index,
                mask: old,
            })
        }

        EditCommand::RemoveMask { clip_id, index } => {
            let clip = find_clip_mut(state, clip_id)?;
            let masks = video_masks_mut(&mut clip.kind, clip_id)?;
            if index >= masks.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let removed = masks.remove(index);
            Ok(EditCommand::AddMask {
                clip_id,
                mask: removed,
                index: Some(index),
            })
        }

        EditCommand::SetMaskEnabled {
            clip_id,
            index,
            enabled,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let masks = video_masks_mut(&mut clip.kind, clip_id)?;
            if index >= masks.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let old = masks[index].enabled;
            masks[index].enabled = enabled;
            Ok(EditCommand::SetMaskEnabled {
                clip_id,
                index,
                enabled: old,
            })
        }

        EditCommand::SetVideoFilterEnabled {
            clip_id,
            index,
            enabled,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = video_filters_mut(&mut clip.kind, clip_id)?;
            if index >= filters.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let old = filters[index].enabled;
            filters[index].enabled = enabled;
            Ok(EditCommand::SetVideoFilterEnabled {
                clip_id,
                index,
                enabled: old,
            })
        }

        EditCommand::MoveVideoFilter { clip_id, from, to } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = video_filters_mut(&mut clip.kind, clip_id)?;
            if from >= filters.len() || to >= filters.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            if from == to {
                return Ok(EditCommand::MoveVideoFilter { clip_id, from, to });
            }
            let moved = filters.remove(from);
            filters.insert(to, moved);
            Ok(EditCommand::MoveVideoFilter {
                clip_id,
                from: to,
                to: from,
            })
        }

        EditCommand::AddAudioFilter {
            clip_id,
            filter,
            index,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    let pos = index
                        .unwrap_or(v.audio_filters.len())
                        .min(v.audio_filters.len());
                    v.audio_filters.insert(pos, filter);
                    Ok(EditCommand::RemoveAudioFilter {
                        clip_id,
                        index: pos,
                    })
                }
                ClipKind::Audio(a) => {
                    let pos = index.unwrap_or(a.filters.len()).min(a.filters.len());
                    a.filters.insert(pos, filter);
                    Ok(EditCommand::RemoveAudioFilter {
                        clip_id,
                        index: pos,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::RemoveAudioFilter { clip_id, index } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    if index >= v.audio_filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let removed = v.audio_filters.remove(index);
                    Ok(EditCommand::AddAudioFilter {
                        clip_id,
                        filter: removed,
                        index: Some(index),
                    })
                }
                ClipKind::Audio(a) => {
                    if index >= a.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let removed = a.filters.remove(index);
                    Ok(EditCommand::AddAudioFilter {
                        clip_id,
                        filter: removed,
                        index: Some(index),
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::UpdateAudioFilterDuration {
            clip_id,
            index,
            duration_us,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = audio_filters_mut(&mut clip.kind, clip_id)?;
            let old_duration_us = swap_audio_filter_duration(filters, index, duration_us)?;
            Ok(EditCommand::UpdateAudioFilterDurationInverse {
                clip_id,
                index,
                old_duration_us,
            })
        }

        EditCommand::UpdateAudioFilterDurationInverse {
            clip_id,
            index,
            old_duration_us,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = audio_filters_mut(&mut clip.kind, clip_id)?;
            swap_audio_filter_duration(filters, index, old_duration_us)?;
            Ok(EditCommand::UpdateAudioFilterDuration {
                clip_id,
                index,
                duration_us: old_duration_us,
            })
        }

        EditCommand::UpdateAudioFilter {
            clip_id,
            index,
            filter,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = audio_filters_mut(&mut clip.kind, clip_id)?;
            let old_filter = swap_audio_filter(filters, index, filter)?;
            Ok(EditCommand::UpdateAudioFilterInverse {
                clip_id,
                index,
                old_filter,
            })
        }

        EditCommand::UpdateAudioFilterInverse {
            clip_id,
            index,
            old_filter,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let filters = audio_filters_mut(&mut clip.kind, clip_id)?;
            let current = swap_audio_filter(filters, index, old_filter)?;
            Ok(EditCommand::UpdateAudioFilter {
                clip_id,
                index,
                filter: current,
            })
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

        EditCommand::SetTransitionOut {
            clip_id,
            transition,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.transition_out.take();
            clip.transition_out = transition;
            Ok(EditCommand::SetTransitionOut {
                clip_id,
                transition: old,
            })
        }

        EditCommand::UpdateTextContent { clip_id, text } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Text(t) => {
                    let old = std::mem::replace(&mut t.text, text);
                    Ok(EditCommand::UpdateTextContent { clip_id, text: old })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::UpdateTextStyle { clip_id, style } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Text(t) => {
                    let old = std::mem::replace(&mut t.style, style);
                    Ok(EditCommand::UpdateTextStyle {
                        clip_id,
                        style: old,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::SetSubtitleFont { clip_id, font_path } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Subtitle(s) => {
                    let old = std::mem::replace(&mut s.font_path, font_path);
                    Ok(EditCommand::SetSubtitleFont {
                        clip_id,
                        font_path: old,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::AddKeyframe { clip_id, keyframe } => {
            if keyframe.param == param::SPEED {
                return apply_add_speed_keyframe(state, clip_id, keyframe);
            }
            let clip = find_clip_mut(state, clip_id)?;
            // `insert_sorted` overwrites an existing keyframe at the same
            // (param, offset) instead of duplicating it. If one was replaced,
            // undo has to write the old value back rather than delete the slot,
            // so the inverse is an Update rather than a Remove.
            let replaced = clip
                .keyframes
                .keyframes
                .iter()
                .position(|k| k.param == keyframe.param && k.offset == keyframe.offset)
                .map(|i| clip.keyframes.keyframes[i].clone());
            let idx = clip.keyframes.insert_sorted(keyframe);
            Ok(match replaced {
                Some(previous) => EditCommand::UpdateKeyframe {
                    clip_id,
                    index: idx,
                    keyframe: previous,
                },
                None => EditCommand::RemoveKeyframe {
                    clip_id,
                    index: idx,
                },
            })
        }

        EditCommand::RemoveKeyframe { clip_id, index } => {
            let removes_speed = {
                let clip = find_clip_mut(state, clip_id)?;
                clip.keyframes
                    .keyframes
                    .get(index)
                    .is_some_and(|keyframe| keyframe.param == param::SPEED)
            };
            if removes_speed {
                return apply_remove_speed_keyframe(state, clip_id, index);
            }
            let clip = find_clip_mut(state, clip_id)?;
            if index >= clip.keyframes.keyframes.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let removed = clip.keyframes.keyframes.remove(index);
            Ok(EditCommand::AddKeyframe {
                clip_id,
                keyframe: removed,
            })
        }

        EditCommand::UpdateKeyframe {
            clip_id,
            index,
            keyframe,
        } => {
            let touches_speed = {
                let clip = find_clip_mut(state, clip_id)?;
                keyframe.param == param::SPEED
                    || clip
                        .keyframes
                        .keyframes
                        .get(index)
                        .is_some_and(|current| current.param == param::SPEED)
            };
            if touches_speed {
                return apply_update_speed_keyframe(state, clip_id, index, keyframe);
            }
            let clip = find_clip_mut(state, clip_id)?;
            if index >= clip.keyframes.keyframes.len() {
                return Err(ApplyError::IndexOutOfBounds);
            }
            let old = std::mem::replace(&mut clip.keyframes.keyframes[index], keyframe.clone());
            clip.keyframes.sort_by_offset();
            // The curve is kept in offset order, so changing a keyframe's
            // offset can move it. Re-locate the edited entry *after* the sort:
            // returning the pre-sort index would make the inverse overwrite
            // whichever keyframe slid into that slot.
            let restored = clip
                .keyframes
                .keyframes
                .iter()
                .position(|k| k.param == keyframe.param && k.offset == keyframe.offset)
                .unwrap_or(index);
            Ok(EditCommand::UpdateKeyframe {
                clip_id,
                index: restored,
                keyframe: old,
            })
        }

        EditCommand::AddMarker { mut marker, index } => {
            marker.time = marker.time.clamp_non_negative();
            let marker_id = marker.id;
            state.project.insert_marker(marker, index);
            Ok(EditCommand::RemoveMarker { marker_id })
        }

        EditCommand::RemoveMarker { marker_id } => {
            let (index, marker) = state
                .project
                .remove_marker(marker_id)
                .ok_or(ApplyError::MarkerNotFound(marker_id))?;
            Ok(EditCommand::AddMarker {
                marker,
                index: Some(index),
            })
        }

        EditCommand::MoveMarker {
            marker_id,
            new_time,
        } => {
            let marker = state
                .project
                .marker_mut(marker_id)
                .ok_or(ApplyError::MarkerNotFound(marker_id))?;
            let old_time = marker.time;
            marker.time = new_time.clamp_non_negative();
            state.project.sort_markers();
            Ok(EditCommand::MoveMarker {
                marker_id,
                new_time: old_time,
            })
        }

        EditCommand::RelabelMarker { marker_id, label } => {
            let marker = state
                .project
                .marker_mut(marker_id)
                .ok_or(ApplyError::MarkerNotFound(marker_id))?;
            let old_label = std::mem::replace(&mut marker.label, label);
            Ok(EditCommand::RelabelMarker {
                marker_id,
                label: old_label,
            })
        }

        EditCommand::SetMarkerColor { marker_id, color } => {
            let marker = state
                .project
                .marker_mut(marker_id)
                .ok_or(ApplyError::MarkerNotFound(marker_id))?;
            let old_color = std::mem::replace(&mut marker.color, color);
            Ok(EditCommand::SetMarkerColor {
                marker_id,
                color: old_color,
            })
        }

        EditCommand::SetExportProfile { profile } => {
            let old = std::mem::replace(&mut state.project.export_profile, profile);
            Ok(EditCommand::SetExportProfile { profile: old })
        }

        EditCommand::SetExportRange { range } => {
            let old = std::mem::replace(&mut state.project.export_range, range);
            Ok(EditCommand::SetExportRange { range: old })
        }

        EditCommand::RenameProject { new_name } => {
            let old = std::mem::replace(&mut state.project.meta.name, new_name);
            state.project.meta.modified_at = web_time::SystemTime::now()
                .duration_since(web_time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;
            Ok(EditCommand::RenameProject { new_name: old })
        }

        EditCommand::Batch { label, commands } => {
            let before = state.clone();
            let mut inverses = Vec::with_capacity(commands.len());

            for cmd in commands {
                match apply(state, cmd) {
                    Ok(inverse) => inverses.push(inverse),
                    Err(err) => {
                        *state = before;
                        return Err(err);
                    }
                }
            }

            inverses.reverse();
            Ok(EditCommand::Batch {
                label: format!("Undo {label}"),
                commands: inverses,
            })
        }
    }
}

fn apply_move_clip(
    state: &mut EditorState,
    clip_id: ClipId,
    new_track_id: TrackId,
    new_start: Timestamp,
) -> Result<EditCommand, ApplyError> {
    let (src_track_idx, clip_idx) = find_clip_location(state, clip_id)?;
    let dst_track_idx = find_track_index(state, new_track_id)?;

    let original = state.project.timeline.tracks[src_track_idx].clips[clip_idx].clone();
    let old_track_id = state.project.timeline.tracks[src_track_idx].id;
    let old_start = original.timeline_start;

    let mut moved = original.clone();
    moved.timeline_start = new_start.clamp_non_negative();

    {
        let dst_kind = state.project.timeline.tracks[dst_track_idx].kind;
        if !dst_kind.accepts_clip_kind(&moved.kind) {
            return Err(ApplyError::IncompatibleTrackKind {
                track_id: new_track_id,
                clip_id,
            });
        }
    }

    let clamped_start = if src_track_idx == dst_track_idx {
        let track = &state.project.timeline.tracks[src_track_idx];
        clamp_non_overlapping_start(
            track,
            Some(clip_id),
            moved.timeline_start,
            moved.timeline_duration,
            old_start,
        )
    } else {
        let track = &state.project.timeline.tracks[dst_track_idx];
        clamp_non_overlapping_start(
            track,
            None,
            moved.timeline_start,
            moved.timeline_duration,
            moved.timeline_start,
        )
    };
    moved.timeline_start = clamped_start;

    if src_track_idx == dst_track_idx {
        {
            let track = &state.project.timeline.tracks[src_track_idx];
            track
                .can_insert_clip(&moved, Some(clip_id))
                .map_err(ApplyError::Overlap)?;
        }
        let track = &mut state.project.timeline.tracks[src_track_idx];
        track.clips[clip_idx] = moved;
        track.sort_clips();
    } else {
        let removed = state.project.timeline.tracks[src_track_idx]
            .remove_clip(clip_id)
            .ok_or(ApplyError::ClipNotFound(clip_id))?;

        let mut removed = removed;
        removed.timeline_start = clamped_start;

        state.project.timeline.tracks[dst_track_idx]
            .insert_clip(removed)
            .map_err(ApplyError::Overlap)?;
    }

    Ok(EditCommand::MoveClip {
        clip_id,
        new_track_id: old_track_id,
        new_start: old_start,
    })
}

fn apply_trim_clip_start(
    state: &mut EditorState,
    clip_id: ClipId,
    new_start: Timestamp,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
    let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();

    let min_start = previous_clip_end_before(
        &state.project.timeline.tracks[track_idx],
        clip_id,
        original.timeline_start,
    )
    .unwrap_or(Timestamp::ZERO);
    let clamped_start = new_start.clamp_non_negative().max(min_start);
    if clamped_start >= original.timeline_end() {
        return Err(ApplyError::InvalidDuration);
    }

    let delta_timeline_us = clamped_start.as_micros() - original.timeline_start.as_micros();
    // Before the first `speed` keyframe the rate is constant, and `evaluate`
    // clamps to that value for earlier times, so extending the clip backwards
    // consumes source at the initial rate.
    let backward_rate = initial_speed_rate(&original);
    if delta_timeline_us < 0 {
        let headroom_us = unscale_us_round(original.source_start.as_micros(), backward_rate);
        let earliest_start_us = original
            .timeline_start
            .as_micros()
            .saturating_sub(headroom_us.max(0));
        if clamped_start.as_micros() < earliest_start_us {
            return Err(ApplyError::InvalidSourceBounds);
        }
    }
    let advanced_us = if delta_timeline_us >= 0 {
        original.source_offset_at(MediaDuration::from_micros(delta_timeline_us))
    } else {
        scale_us_round(delta_timeline_us, backward_rate)
    };
    let mut recalculated_source_start = original.source_start.as_micros() + advanced_us;
    if recalculated_source_start < 0 {
        recalculated_source_start = 0;
    }
    if recalculated_source_start >= original.source_end.as_micros() {
        recalculated_source_start = original.source_end.as_micros().saturating_sub(1);
    }

    let mut updated = original.clone();
    updated.timeline_start = clamped_start;
    updated.source_start = MediaDuration::from_micros(recalculated_source_start);
    updated.keyframes = rebase_keyframes(&original.keyframes, delta_timeline_us);
    let new_duration =
        timeline_duration_for_clip(&updated, updated.source_start, updated.source_end)?;
    updated.timeline_duration = new_duration;
    ensure_positive_duration(updated.timeline_duration)?;
    if !matches!(
        original.kind,
        ClipKind::Text(_) | ClipKind::Subtitle(_) | ClipKind::Adjustment(_)
    ) {
        ensure_clip_source_bounds(&updated)?;
    }

    let track = &state.project.timeline.tracks[track_idx];
    track
        .can_insert_clip(&updated, Some(clip_id))
        .map_err(ApplyError::Overlap)?;

    let track = &mut state.project.timeline.tracks[track_idx];
    track.clips[clip_idx] = updated;
    track.sort_clips();

    Ok(EditCommand::TrimClipStart {
        clip_id,
        new_start: original.timeline_start,
        new_source_start: original.source_start,
    })
}

fn apply_trim_clip_end(
    state: &mut EditorState,
    clip_id: ClipId,
    new_duration: MediaDuration,
) -> Result<EditCommand, ApplyError> {
    ensure_positive_duration(new_duration)?;

    let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
    let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();

    let max_duration = match &original.kind {
        ClipKind::Text(_) | ClipKind::Subtitle(_) | ClipKind::Adjustment(_) => new_duration,
        _ => {
            if original.source_total_duration.as_micros() <= original.source_start.as_micros() {
                new_duration
            } else {
                MediaDuration::from_micros(original.local_offset_at_source_offset(
                    original.source_total_duration.as_micros() - original.source_start.as_micros(),
                ))
            }
        }
    };
    let max_by_neighbor = next_clip_start_after(
        &state.project.timeline.tracks[track_idx],
        clip_id,
        original.timeline_start,
    )
    .map(|next_start| {
        MediaDuration::from_micros(
            (next_start.as_micros() - original.timeline_start.as_micros()).max(1),
        )
    });

    let mut clamped_duration = new_duration.min(max_duration);
    if let Some(max_neighbor_duration) = max_by_neighbor {
        clamped_duration = clamped_duration.min(max_neighbor_duration);
    }

    let new_source_end = MediaDuration::from_micros(
        original.source_start.as_micros() + original.source_offset_at(clamped_duration),
    );

    let mut updated = original.clone();
    updated.timeline_duration = clamped_duration;
    updated.source_end = new_source_end;

    if !matches!(
        original.kind,
        ClipKind::Text(_) | ClipKind::Subtitle(_) | ClipKind::Adjustment(_)
    ) {
        ensure_clip_source_bounds(&updated)?;
    }

    let track = &state.project.timeline.tracks[track_idx];
    track
        .can_insert_clip(&updated, Some(clip_id))
        .map_err(ApplyError::Overlap)?;

    let track = &mut state.project.timeline.tracks[track_idx];
    track.clips[clip_idx] = updated;

    Ok(EditCommand::TrimClipEnd {
        clip_id,
        new_duration: original.timeline_duration,
    })
}

fn apply_slip_clip(
    state: &mut EditorState,
    clip_id: ClipId,
    delta_us: i64,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
    let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();

    if !matches!(original.kind, ClipKind::Video(_) | ClipKind::Audio(_)) {
        return Err(ApplyError::WrongClipKind(clip_id));
    }
    if original.keyframes.has_param(param::SPEED) {
        return Err(ApplyError::InvalidCommand(
            "Slip is unavailable on speed-ramped clips",
        ));
    }

    let source_start_us = original.source_start.as_micros();
    let source_end_us = original.source_end.as_micros();
    let headroom_us = source_start_us.max(0);
    let tailroom_us = original
        .source_total_duration
        .as_micros()
        .saturating_sub(source_end_us)
        .max(0);
    let applied_us = delta_us.clamp(-headroom_us, tailroom_us);
    if applied_us == 0 && delta_us != 0 {
        return Err(ApplyError::InvalidSourceBounds);
    }

    let mut updated = original.clone();
    updated.source_start = MediaDuration::from_micros(source_start_us + applied_us);
    updated.source_end = MediaDuration::from_micros(source_end_us + applied_us);
    ensure_clip_source_bounds(&updated)?;

    state.project.timeline.tracks[track_idx].clips[clip_idx] = updated;

    Ok(EditCommand::SlipClip {
        clip_id,
        delta_us: -applied_us,
    })
}

fn apply_split_clip(
    state: &mut EditorState,
    clip_id: ClipId,
    at: Timestamp,
    new_clip_id: ClipId,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
    let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();

    if at <= original.timeline_start || at >= original.timeline_end() {
        return Err(ApplyError::SplitOutOfRange { clip_id, at });
    }

    let left_dur = at - original.timeline_start;
    let right_dur =
        MediaDuration::from_micros(original.timeline_duration.as_micros() - left_dur.as_micros());

    ensure_positive_duration(left_dur)?;
    ensure_positive_duration(right_dur)?;

    let split_source_start = MediaDuration::from_micros(
        original.source_start.as_micros() + original.source_offset_at(left_dur),
    );

    let mut left = original.clone();
    left.timeline_duration = left_dur;
    left.source_end = split_source_start;

    let mut right = original.clone();
    right.id = new_clip_id;
    right.timeline_start = at;
    right.timeline_duration = right_dur;
    right.source_start = split_source_start;
    right.transition_in = None;
    right.keyframes = rebase_keyframes(&original.keyframes, left_dur.as_micros());

    if !matches!(
        original.kind,
        ClipKind::Text(_) | ClipKind::Subtitle(_) | ClipKind::Adjustment(_)
    ) {
        ensure_clip_source_bounds(&left)?;
        ensure_clip_source_bounds(&right)?;
    }

    let track = &state.project.timeline.tracks[track_idx];
    track
        .can_insert_clip(&left, Some(clip_id))
        .map_err(ApplyError::Overlap)?;
    track
        .can_insert_clip(&right, Some(clip_id))
        .map_err(ApplyError::Overlap)?;

    let track = &mut state.project.timeline.tracks[track_idx];
    track.clips[clip_idx] = left;
    track.insert_clip(right).map_err(ApplyError::Overlap)?;

    Ok(EditCommand::Batch {
        label: "Split clip".into(),
        commands: vec![
            EditCommand::RemoveClip {
                clip_id: new_clip_id,
            },
            EditCommand::TrimClipEnd {
                clip_id,
                new_duration: original.timeline_duration,
            },
        ],
    })
}

fn apply_join_clips(
    state: &mut EditorState,
    left_id: ClipId,
    right_id: ClipId,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, left_idx) = find_clip_location(state, left_id)?;
    let track_id = state.project.timeline.tracks[track_idx].id;
    let right_idx = state.project.timeline.tracks[track_idx]
        .clip_index(right_id)
        .ok_or(ApplyError::ClipNotFound(right_id))?;

    let left = state.project.timeline.tracks[track_idx].clips[left_idx].clone();
    let right = state.project.timeline.tracks[track_idx].clips[right_idx].clone();

    let adjacent = right.timeline_start == left.timeline_end();
    let same_speed = right.speed == left.speed && left.speed.is_finite();
    let continuous = if left.speed < 0.0 {
        right.source_end == left.source_start
    } else {
        right.source_start == left.source_end
    };
    // One keyframe curve cannot express a rate step at the cut, so only join
    // when the rates the two clips play at there agree.
    let rate_continuous = (rate_at(&left.keyframes, left.speed, left.timeline_duration)
        - rate_at(&right.keyframes, right.speed, MediaDuration::ZERO))
    .abs()
        <= SPEED_RATE_EPSILON;
    if !(adjacent && same_speed && continuous && rate_continuous) {
        return Err(ApplyError::NotJoinable { left_id, right_id });
    }

    let mut joined = left.clone();
    let boundary = left.timeline_duration;
    joined.timeline_duration = MediaDuration::from_micros(
        left.timeline_duration.as_micros() + right.timeline_duration.as_micros(),
    );
    joined.source_end = right.source_end;
    joined.keyframes = joined_speed_curve(&left, &right, boundary);
    // The cut is gone, so a fade over it goes with it; the inverse restores it.
    let cut_transition = joined.transition_out.take();
    ensure_clip_source_bounds(&joined)?;

    let track = &mut state.project.timeline.tracks[track_idx];
    track.clips.remove(right_idx);
    let left_at = if right_idx < left_idx {
        left_idx - 1
    } else {
        left_idx
    };
    track.clips[left_at] = joined;

    Ok(EditCommand::Batch {
        label: "Join clips".into(),
        commands: vec![
            EditCommand::TrimClipEnd {
                clip_id: left_id,
                new_duration: left.timeline_duration,
            },
            EditCommand::SetTransitionOut {
                clip_id: left_id,
                transition: cut_transition,
            },
            EditCommand::AddClip {
                track_id,
                clip: right,
            },
        ],
    })
}

fn find_track_index(state: &EditorState, track_id: TrackId) -> Result<usize, ApplyError> {
    state
        .project
        .timeline
        .tracks
        .iter()
        .position(|t| t.id == track_id)
        .ok_or(ApplyError::TrackNotFound(track_id))
}

fn find_clip_location(state: &EditorState, clip_id: ClipId) -> Result<(usize, usize), ApplyError> {
    for (track_idx, track) in state.project.timeline.tracks.iter().enumerate() {
        if let Some(clip_idx) = track.clip_index(clip_id) {
            if track.locked {
                return Err(ApplyError::TrackLocked(track.id));
            }
            return Ok((track_idx, clip_idx));
        }
    }
    Err(ApplyError::ClipNotFound(clip_id))
}

fn ensure_positive_duration(duration: MediaDuration) -> Result<(), ApplyError> {
    if duration.is_positive() {
        Ok(())
    } else {
        Err(ApplyError::InvalidDuration)
    }
}

fn ensure_clip_source_bounds(clip: &Clip) -> Result<(), ApplyError> {
    if clip.source_start.is_negative()
        || clip.source_end.is_negative()
        || clip.source_total_duration.is_negative()
        || clip.source_end < clip.source_start
        || clip.source_end > clip.source_total_duration
    {
        Err(ApplyError::InvalidSourceBounds)
    } else {
        Ok(())
    }
}

const SPEED_RATE_EPSILON: f64 = 1.0e-4;

/// Playback rate the curve reports at `at`, falling back to the clip's recorded
/// speed when there is no ramp.
fn rate_at(curve: &KeyframeCurve, recorded: f64, at: MediaDuration) -> f64 {
    let fallback = if recorded.is_finite() && recorded > 0.0 {
        recorded
    } else {
        1.0
    };
    if !curve.has_param(param::SPEED) {
        return fallback;
    }
    curve
        .evaluate(param::SPEED, at)
        .filter(|value| value.is_finite())
        .map(f64::from)
        .unwrap_or(fallback)
}

/// Rate the clip plays at before any `speed` keyframe applies. `evaluate`
/// clamps to the first keyframe for earlier times, so this is also the rate
/// used when extending a clip backwards past its start.
fn initial_speed_rate(clip: &Clip) -> f64 {
    rate_at(&clip.keyframes, clip.speed, MediaDuration::ZERO)
}

fn upsert_key(curve: &mut KeyframeCurve, keyframe: Keyframe) {
    if let Some(pos) = curve
        .keyframes
        .iter()
        .position(|k| k.param == keyframe.param && k.offset == keyframe.offset)
    {
        curve.keyframes[pos] = keyframe;
        return;
    }
    curve.keyframes.push(keyframe);
}

fn upsert_speed_key(curve: &mut KeyframeCurve, offset: MediaDuration, rate: f64) {
    upsert_key(
        curve,
        Keyframe {
            param: param::SPEED.to_string(),
            offset,
            value: if rate.is_finite() { rate as f32 } else { 1.0 },
            easing: Default::default(),
        },
    );
}

/// The joined clip plays the two clips back to back, so its ramp is the left
/// clip's curve up to the cut followed by the right clip's curve rebased onto
/// it. Callers only reach here when the rates agree at the cut, which makes the
/// concatenation exact: linear interpolation between shared endpoints is
/// unchanged by splitting a segment.
fn joined_speed_curve(left: &Clip, right: &Clip, boundary: MediaDuration) -> KeyframeCurve {
    let left_ramped = left.keyframes.has_param(param::SPEED);
    let right_ramped = right.keyframes.has_param(param::SPEED);
    if !left_ramped && !right_ramped {
        return left.keyframes.clone();
    }

    let boundary_us = boundary.as_micros();
    let mut merged = KeyframeCurve::default();
    for keyframe in &left.keyframes.keyframes {
        if keyframe.param == param::SPEED && keyframe.offset.as_micros() >= boundary_us {
            continue;
        }
        merged.keyframes.push(keyframe.clone());
    }

    if !merged.keyframes.iter().any(|k| k.param == param::SPEED) {
        upsert_speed_key(&mut merged, MediaDuration::ZERO, initial_speed_rate(left));
    }
    upsert_speed_key(
        &mut merged,
        boundary,
        rate_at(&right.keyframes, right.speed, MediaDuration::ZERO),
    );

    if right_ramped {
        for keyframe in &right.keyframes.keyframes {
            if keyframe.param != param::SPEED {
                continue;
            }
            upsert_key(
                &mut merged,
                Keyframe {
                    offset: MediaDuration::from_micros(keyframe.offset.as_micros() + boundary_us),
                    ..keyframe.clone()
                },
            );
        }
    }

    merged.sort_by_offset();
    merged
}

/// Re-expresses `curve` in a new local time base, where `offset_us` is where
/// the new clip start falls in the old base. Keyframe offsets are clip-local,
/// so every parameter has to move: a fade-out keyed near the old out-point
/// would otherwise fall beyond a shortened clip and never fire.
fn rebase_keyframes(curve: &KeyframeCurve, offset_us: i64) -> KeyframeCurve {
    if offset_us == 0 || curve.keyframes.is_empty() {
        return curve.clone();
    }

    let mut rebased = KeyframeCurve::default();

    if offset_us > 0 {
        let cut = MediaDuration::from_micros(offset_us);
        let mut params: Vec<&str> = curve.keyframes.iter().map(|k| k.param.as_str()).collect();
        params.sort_unstable();
        params.dedup();
        // Keys at or before the cut are gone, so anchor each parameter at the
        // value it held there and let the surviving keys interpolate from it.
        for param in params {
            if let Some(value) = curve.evaluate(param, cut) {
                rebased.keyframes.push(Keyframe {
                    param: param.to_string(),
                    offset: MediaDuration::ZERO,
                    value,
                    easing: Default::default(),
                });
            }
        }
    }

    for keyframe in &curve.keyframes {
        let shifted = keyframe.offset.as_micros() - offset_us;
        if shifted <= 0 {
            continue;
        }
        rebased.keyframes.push(Keyframe {
            offset: MediaDuration::from_micros(shifted),
            ..keyframe.clone()
        });
    }

    rebased.sort_by_offset();
    rebased
}

fn timeline_duration_for_clip(
    clip: &Clip,
    source_start: MediaDuration,
    source_end: MediaDuration,
) -> Result<MediaDuration, ApplyError> {
    if !clip.keyframes.has_param(param::SPEED) {
        return timeline_duration_from_source_bounds(clip.speed, source_start, source_end);
    }

    let span = (source_end.as_micros() - source_start.as_micros()).max(0);
    let duration = MediaDuration::from_micros(clip.local_offset_at_source_offset(span));
    ensure_positive_duration(duration)?;
    Ok(duration)
}

pub fn timeline_duration_for_speed_keyframes(
    clip: &Clip,
    speed_keyframes: &[Keyframe],
    source_start: MediaDuration,
    source_end: MediaDuration,
) -> Result<MediaDuration, ApplyError> {
    let mut probe = clip.clone();
    probe.keyframes = KeyframeCurve::default();
    for keyframe in speed_keyframes {
        probe.keyframes.insert_sorted(keyframe.clone());
    }
    timeline_duration_for_clip(&probe, source_start, source_end)
}

fn timeline_duration_from_source_bounds(
    speed: f64,
    source_start: MediaDuration,
    source_end: MediaDuration,
) -> Result<MediaDuration, ApplyError> {
    if speed <= 0.0 {
        return Err(ApplyError::InvalidSpeed(speed));
    }
    let source_us = source_end.as_micros() - source_start.as_micros();
    let duration = MediaDuration::from_micros(unscale_us_round(source_us, speed));
    ensure_positive_duration(duration)?;
    Ok(duration)
}

fn find_keyframe_clip_location(
    state: &EditorState,
    clip_id: ClipId,
) -> Result<(usize, usize), ApplyError> {
    let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
    let track = &state.project.timeline.tracks[track_idx];
    if track.locked {
        return Err(ApplyError::TrackLocked(track.id));
    }
    Ok((track_idx, clip_idx))
}

fn commit_speed_keyframe_update(
    state: &mut EditorState,
    track_idx: usize,
    clip_idx: usize,
    mut updated: Clip,
) -> Result<(), ApplyError> {
    let duration = timeline_duration_for_clip(&updated, updated.source_start, updated.source_end)?;
    updated.timeline_duration = duration;
    state.project.timeline.tracks[track_idx]
        .can_insert_clip(&updated, Some(updated.id))
        .map_err(ApplyError::Overlap)?;
    state.project.timeline.tracks[track_idx].clips[clip_idx] = updated;
    Ok(())
}

fn apply_add_speed_keyframe(
    state: &mut EditorState,
    clip_id: ClipId,
    keyframe: Keyframe,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, clip_idx) = find_keyframe_clip_location(state, clip_id)?;
    let mut updated = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();
    let replaced = updated
        .keyframes
        .keyframes
        .iter()
        .position(|k| k.param == keyframe.param && k.offset == keyframe.offset)
        .map(|i| updated.keyframes.keyframes[i].clone());
    let idx = updated.keyframes.insert_sorted(keyframe);
    commit_speed_keyframe_update(state, track_idx, clip_idx, updated)?;
    Ok(match replaced {
        Some(previous) => EditCommand::UpdateKeyframe {
            clip_id,
            index: idx,
            keyframe: previous,
        },
        None => EditCommand::RemoveKeyframe {
            clip_id,
            index: idx,
        },
    })
}

fn apply_remove_speed_keyframe(
    state: &mut EditorState,
    clip_id: ClipId,
    index: usize,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, clip_idx) = find_keyframe_clip_location(state, clip_id)?;
    let mut updated = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();
    if index >= updated.keyframes.keyframes.len() {
        return Err(ApplyError::IndexOutOfBounds);
    }
    let removed = updated.keyframes.keyframes.remove(index);
    commit_speed_keyframe_update(state, track_idx, clip_idx, updated)?;
    Ok(EditCommand::AddKeyframe {
        clip_id,
        keyframe: removed,
    })
}

fn apply_update_speed_keyframe(
    state: &mut EditorState,
    clip_id: ClipId,
    index: usize,
    keyframe: Keyframe,
) -> Result<EditCommand, ApplyError> {
    let (track_idx, clip_idx) = find_keyframe_clip_location(state, clip_id)?;
    let mut updated = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();
    if index >= updated.keyframes.keyframes.len() {
        return Err(ApplyError::IndexOutOfBounds);
    }
    let old = std::mem::replace(&mut updated.keyframes.keyframes[index], keyframe.clone());
    updated.keyframes.sort_by_offset();
    let restored = updated
        .keyframes
        .keyframes
        .iter()
        .position(|k| k.param == keyframe.param && k.offset == keyframe.offset)
        .unwrap_or(index);
    commit_speed_keyframe_update(state, track_idx, clip_idx, updated)?;
    Ok(EditCommand::UpdateKeyframe {
        clip_id,
        index: restored,
        keyframe: old,
    })
}

fn previous_clip_end_before(track: &Track, clip_id: ClipId, start: Timestamp) -> Option<Timestamp> {
    track
        .clips
        .iter()
        .filter(|clip| clip.id != clip_id && clip.timeline_end() <= start)
        .map(|clip| clip.timeline_end())
        .max()
}

fn next_clip_start_after(track: &Track, clip_id: ClipId, start: Timestamp) -> Option<Timestamp> {
    track
        .clips
        .iter()
        .filter(|clip| clip.id != clip_id && clip.timeline_start > start)
        .map(|clip| clip.timeline_start)
        .min()
}

fn clamp_non_overlapping_start(
    track: &Track,
    ignore_clip_id: Option<ClipId>,
    requested_start: Timestamp,
    duration: MediaDuration,
    previous_start: Timestamp,
) -> Timestamp {
    let requested_us = requested_start.clamp_non_negative().as_micros();
    let previous_us = previous_start.clamp_non_negative().as_micros();
    let duration_us = duration.as_micros().max(1);

    let mut intervals: Vec<(i64, i64)> = track
        .clips
        .iter()
        .filter(|clip| Some(clip.id) != ignore_clip_id)
        .map(|clip| {
            (
                clip.timeline_start.as_micros(),
                clip.timeline_end().as_micros(),
            )
        })
        .collect();
    intervals.sort_by_key(|(start, _)| *start);

    if intervals.is_empty() {
        return Timestamp::from_micros(requested_us);
    }

    let mut ranges: Vec<(i64, i64)> = Vec::new();

    let mut gap_start = 0i64;
    for (start, end) in intervals {
        let gap_end = start.max(gap_start);
        if gap_end - gap_start >= duration_us {
            ranges.push((gap_start, gap_end - duration_us));
        }
        gap_start = gap_start.max(end);
    }

    if requested_us >= gap_start {
        return Timestamp::from_micros(requested_us);
    }

    ranges.push((gap_start, gap_start));

    if ranges
        .iter()
        .any(|(start, end)| requested_us >= *start && requested_us <= *end)
    {
        return Timestamp::from_micros(requested_us);
    }

    let left_candidate = ranges
        .iter()
        .filter_map(|(_, end)| {
            if *end < requested_us {
                Some(*end)
            } else {
                None
            }
        })
        .max();

    let right_candidate = ranges
        .iter()
        .filter_map(|(start, _)| {
            if *start > requested_us {
                Some(*start)
            } else {
                None
            }
        })
        .min();

    let chosen = if requested_us > previous_us {
        left_candidate.or(right_candidate).unwrap_or(requested_us)
    } else if requested_us < previous_us {
        right_candidate.or(left_candidate).unwrap_or(requested_us)
    } else {
        match (left_candidate, right_candidate) {
            (Some(left), Some(right)) => {
                if (requested_us - left) <= (right - requested_us) {
                    left
                } else {
                    right
                }
            }
            (Some(left), None) => left,
            (None, Some(right)) => right,
            (None, None) => requested_us,
        }
    };

    Timestamp::from_micros(chosen)
}

fn find_clip_mut(state: &mut EditorState, clip_id: ClipId) -> Result<&mut Clip, ApplyError> {
    let track = state
        .project
        .timeline
        .track_of_clip_mut(clip_id)
        .ok_or(ApplyError::ClipNotFound(clip_id))?;

    if track.locked {
        return Err(ApplyError::TrackLocked(track.id));
    }

    track
        .clip_by_id_mut(clip_id)
        .ok_or(ApplyError::ClipNotFound(clip_id))
}

fn audio_filters_mut(
    kind: &mut ClipKind,
    clip_id: ClipId,
) -> Result<&mut Vec<AudioFilter>, ApplyError> {
    match kind {
        ClipKind::Video(v) => Ok(&mut v.audio_filters),
        ClipKind::Audio(a) => Ok(&mut a.filters),
        _ => Err(ApplyError::WrongClipKind(clip_id)),
    }
}

fn video_filters_mut(
    kind: &mut ClipKind,
    clip_id: ClipId,
) -> Result<&mut Vec<VideoEffect>, ApplyError> {
    match kind {
        ClipKind::Video(v) => Ok(&mut v.filters),
        ClipKind::Adjustment(a) => Ok(&mut a.filters),
        _ => Err(ApplyError::WrongClipKind(clip_id)),
    }
}

fn video_masks_mut(
    kind: &mut ClipKind,
    clip_id: ClipId,
) -> Result<&mut Vec<miniter_domain::mask::MaskEffect>, ApplyError> {
    match kind {
        ClipKind::Video(v) => Ok(&mut v.masks),
        ClipKind::Adjustment(a) => Ok(&mut a.masks),
        _ => Err(ApplyError::WrongClipKind(clip_id)),
    }
}

fn swap_audio_filter_duration(
    filters: &mut [AudioFilter],
    index: usize,
    duration_us: i64,
) -> Result<i64, ApplyError> {
    let filter = filters.get_mut(index).ok_or(ApplyError::IndexOutOfBounds)?;
    let old = match filter {
        AudioFilter::FadeIn { duration_us: d } => *d,
        AudioFilter::FadeOut { duration_us: d } => *d,
        _ => {
            return Err(ApplyError::InvalidCommand(
                "Only fade filters have duration",
            ));
        }
    };
    match filter {
        AudioFilter::FadeIn { duration_us: d } => *d = duration_us,
        AudioFilter::FadeOut { duration_us: d } => *d = duration_us,
        _ => unreachable!(),
    }
    Ok(old)
}

fn swap_audio_filter(
    filters: &mut [AudioFilter],
    index: usize,
    filter: AudioFilter,
) -> Result<AudioFilter, ApplyError> {
    let slot = filters.get_mut(index).ok_or(ApplyError::IndexOutOfBounds)?;
    Ok(std::mem::replace(slot, filter))
}

#[derive(Debug, thiserror::Error)]
pub enum ApplyError {
    #[error("Track not found: {0:?}")]
    TrackNotFound(miniter_domain::track::TrackId),

    #[error("Clip not found: {0:?}")]
    ClipNotFound(ClipId),

    #[error("Marker not found: {0:?}")]
    MarkerNotFound(MarkerId),

    #[error("Track is locked: {0:?}")]
    TrackLocked(miniter_domain::track::TrackId),

    #[error("Track still holds clips: {0:?}")]
    TrackNotEmpty(miniter_domain::track::TrackId),

    #[error("Clip {clip_id:?} is incompatible with track {track_id:?}")]
    IncompatibleTrackKind { track_id: TrackId, clip_id: ClipId },

    #[error("Wrong clip kind for operation: {0:?}")]
    WrongClipKind(ClipId),

    #[error("Split point outside clip range: clip={clip_id:?} at={at:?}")]
    SplitOutOfRange { clip_id: ClipId, at: Timestamp },

    #[error("Clips are not adjacent pieces of one cut: {left_id:?} and {right_id:?}")]
    NotJoinable { left_id: ClipId, right_id: ClipId },

    #[error("Index out of bounds")]
    IndexOutOfBounds,

    #[error("Invalid duration")]
    InvalidDuration,

    #[error("Invalid source bounds")]
    InvalidSourceBounds,

    #[error("Invalid speed: {0}")]
    InvalidSpeed(f64),

    #[error("Invalid command: {0}")]
    InvalidCommand(&'static str),

    #[error("History entry {entry} out of range ({total} entries)")]
    HistoryEntryOutOfRange { entry: usize, total: usize },

    #[error("Edit in progress")]
    EditInProgress,

    #[error(transparent)]
    Overlap(#[from] miniter_domain::track::TrackOverlapError),
}

/// Apply one command as its own undo step.
pub fn dispatch(state: &mut EditorState, cmd: EditCommand) -> Result<(), ApplyError> {
    dispatch_labeled(state, "", cmd)
}

/// Apply one command under a gesture label. Consecutive same-label dispatches
/// (slider ticks, keystrokes) fold into a single undo step through boundary
/// coalescing, without the UI needing explicit gesture hooks.
pub fn dispatch_labeled(
    state: &mut EditorState,
    label: impl Into<String>,
    cmd: EditCommand,
) -> Result<(), ApplyError> {
    state.history.begin(label);
    // A failed apply must still close the transaction it opened. Leaving it
    // open makes `transaction_open()` report true forever, which makes the UI
    // route later edits into `dispatch_open` and silently drop them from the
    // undo history.
    let inverse = match apply(state, cmd.clone()) {
        Ok(inverse) => inverse,
        Err(e) => {
            state.history.commit();
            return Err(e);
        }
    };
    state
        .history
        .record(stabilise(state, cmd, &inverse), inverse);
    state.history.commit();
    Ok(())
}

/// Rewrite a command into its replay-stable form before it is recorded.
///
/// `AddTrack` mints a track id when applied. Recording the caller's original
/// command would leave redo creating a *different* track than the inverse
/// refers to, so the next undo would target a missing id and drop the
/// transaction out of both stacks. Recording the concrete id fixes that.
fn stabilise(state: &EditorState, cmd: EditCommand, inverse: &EditCommand) -> EditCommand {
    match (&cmd, inverse) {
        (EditCommand::AddTrack { kind, name, .. }, EditCommand::RemoveTrack { track_id }) => {
            let _ = state;
            EditCommand::AddTrack {
                kind: *kind,
                name: name.clone(),
                track_id: Some(*track_id),
            }
        }
        _ => cmd,
    }
}

/// Open an explicit gesture transaction; groups subsequent [`dispatch_open`]
/// edits into one undo step until [`commit_edit`] / [`cancel_edit`].
pub fn begin_edit(state: &mut EditorState, label: impl Into<String>) {
    state.history.begin(label);
}

/// Apply inside the open gesture (degrades to a single step when none open).
pub fn dispatch_open(state: &mut EditorState, cmd: EditCommand) -> Result<(), ApplyError> {
    let inverse = apply(state, cmd.clone())?;
    state.history.record(cmd, inverse);
    Ok(())
}

/// Close the open gesture and publish it as one undo step.
pub fn commit_edit(state: &mut EditorState) {
    state.history.commit();
}

/// Roll the open gesture back, applying buffered inverses in reverse.
pub fn cancel_edit(state: &mut EditorState) -> Result<(), ApplyError> {
    if let Some((_, inverse)) = state.history.take_open() {
        for inv in inverse.into_iter().rev() {
            apply(state, inv)?;
        }
    }
    Ok(())
}

/// Returns whether a step was applied. Callers use this to know if the
/// snapshot changed at all; depth alone cannot say (applying the last step
/// leaves both stacks' depths unchanged in the flag the FFI used to report).
pub fn undo(state: &mut EditorState) -> Result<bool, ApplyError> {
    if let Some(txn) = state.history.pop_undo() {
        // On failure the transaction must go back on the undo stack. Dropping
        // it (the `?` used to bail after `pop_undo`) strands it in neither
        // stack while its earlier inverses are already applied, which loses the
        // user's edit permanently.
        if let Err(e) = txn
            .inverse()
            .iter()
            .rev()
            .try_for_each(|inv| apply(state, inv.clone()).map(|_| ()))
        {
            state.history.restore_undo(txn);
            return Err(e);
        }
        state.history.push_redo(txn);
        return Ok(true);
    }
    Ok(false)
}

pub fn redo(state: &mut EditorState) -> Result<bool, ApplyError> {
    if let Some(txn) = state.history.pop_redo() {
        if let Err(e) = txn
            .forward()
            .iter()
            .try_for_each(|fwd| apply(state, fwd.clone()).map(|_| ()))
        {
            state.history.restore_redo(txn);
            return Err(e);
        }
        state.history.push_undo(txn);
        return Ok(true);
    }
    Ok(false)
}

pub fn jump_to_history(state: &mut EditorState, entry: usize) -> Result<bool, ApplyError> {
    if state.history.transaction_open() {
        return Err(ApplyError::EditInProgress);
    }
    let total = state.history.depth();
    if entry >= total {
        return Err(ApplyError::HistoryEntryOutOfRange { entry, total });
    }
    let target = entry + 1;
    let mut moved = false;
    while state.history.undo_depth() > target {
        undo(state)?;
        moved = true;
    }
    while state.history.undo_depth() < target {
        if !redo(state)? {
            break;
        }
        moved = true;
    }
    Ok(moved)
}

#[cfg(test)]
mod tests {
    use super::*;
    use miniter_domain::clip::{ClipKind, VideoClip};
    use miniter_domain::filter::VideoEffect;
    use miniter_domain::mask::{MaskEffect, MaskShape};
    use miniter_domain::project::Project;
    use miniter_domain::track::{Track, TrackKind};
    use miniter_domain::{AudioFilter, VideoFilter};
    use uuid::Uuid;

    fn video_clip(start_us: i64, duration_us: i64) -> Clip {
        Clip {
            id: ClipId(Uuid::new_v4()),
            timeline_start: Timestamp::from_micros(start_us),
            timeline_duration: MediaDuration::from_micros(duration_us),
            source_start: MediaDuration::ZERO,
            source_end: MediaDuration::from_micros(duration_us),
            source_total_duration: MediaDuration::from_micros(duration_us),
            speed: 1.0,
            volume: 1.0,
            opacity: 1.0,
            muted: false,
            blend_mode: Default::default(),
            transition_in: None,
            transition_out: None,
            kind: ClipKind::Video(VideoClip {
                source_path: "sample.mp4".into(),
                width: 1920,
                height: 1080,
                fps: 30.0,
                filters: Vec::<VideoEffect>::new(),
                masks: Default::default(),
                audio_filters: Vec::<AudioFilter>::new(),
            }),
            keyframes: Default::default(),
        }
    }

    fn state_with_tracks(tracks: Vec<Track>) -> EditorState {
        let mut state = EditorState::new(Project::new("test"));
        state.project.timeline.tracks = tracks;
        state
    }

    #[test]
    fn adjacent_clips_do_not_overlap() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let first = video_clip(0, 1_000_000);
        let second = video_clip(1_000_000, 1_000_000);

        track.insert_clip(first).unwrap();
        track.insert_clip(second).unwrap();

        assert_eq!(track.clips.len(), 2);
        assert_eq!(track.clips[0].timeline_end(), track.clips[1].timeline_start);
    }

    #[test]
    fn join_heals_a_split_into_one_clip() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        let tail_id = ClipId(Uuid::new_v4());
        track.insert_clip(clip).unwrap();
        let mut state = state_with_tracks(vec![track]);
        dispatch(
            &mut state,
            EditCommand::SplitClip {
                clip_id,
                at: Timestamp::from_micros(4_000_000),
                new_clip_id: tail_id,
            },
        )
        .unwrap();
        dispatch(
            &mut state,
            EditCommand::JoinClips {
                left_id: clip_id,
                right_id: tail_id,
            },
        )
        .unwrap();
        let clips = &state.project.timeline.tracks[0].clips;
        assert_eq!(clips.len(), 1);
        assert_eq!(clips[0].id, clip_id);
        assert_eq!(clips[0].timeline_start, Timestamp::from_micros(0));
        assert_eq!(
            clips[0].timeline_duration,
            MediaDuration::from_micros(10_000_000)
        );
        assert_eq!(clips[0].source_start, MediaDuration::ZERO);
        assert_eq!(clips[0].source_end, MediaDuration::from_micros(10_000_000));
    }

    #[test]
    fn join_undo_restores_both_pieces() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        let tail_id = ClipId(Uuid::new_v4());
        track.insert_clip(clip).unwrap();
        let mut state = state_with_tracks(vec![track]);
        dispatch(
            &mut state,
            EditCommand::SplitClip {
                clip_id,
                at: Timestamp::from_micros(4_000_000),
                new_clip_id: tail_id,
            },
        )
        .unwrap();
        dispatch(
            &mut state,
            EditCommand::JoinClips {
                left_id: clip_id,
                right_id: tail_id,
            },
        )
        .unwrap();
        assert_eq!(state.project.timeline.tracks[0].clips.len(), 1);

        undo(&mut state).unwrap();

        let clips = &state.project.timeline.tracks[0].clips;
        assert_eq!(clips.len(), 2, "undo must bring the second piece back");
        assert_eq!(clips[0].id, clip_id);
        assert_eq!(clips[1].id, tail_id);
        assert_eq!(
            clips[0].timeline_duration,
            MediaDuration::from_micros(4_000_000)
        );
        assert_eq!(clips[1].timeline_start, Timestamp::from_micros(4_000_000));
        assert_eq!(clips[1].source_start, MediaDuration::from_micros(4_000_000));
        assert_eq!(clips[1].source_end, MediaDuration::from_micros(10_000_000));
    }

    /// `undo`/`redo` must report that they moved whenever they applied a step.
    /// The FFI used to answer `can_undo()`/`can_redo()` instead, which is false
    /// after the last step, so the UI was never told to refresh and kept a
    /// stale snapshot whose clip ids later failed with "Clip not found".
    #[test]
    fn undo_and_redo_report_whether_they_moved() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 2_000_000);
        let id = clip.id;
        track.insert_clip(clip).unwrap();
        let mut state = state_with_tracks(vec![track]);

        assert!(!undo(&mut state).unwrap(), "nothing to undo yet");
        assert!(!redo(&mut state).unwrap(), "nothing to redo yet");

        dispatch(
            &mut state,
            EditCommand::SetClipVolume {
                clip_id: id,
                volume: 0.5,
            },
        )
        .unwrap();

        assert!(undo(&mut state).unwrap(), "the only undo must report moved");
        assert!(!undo(&mut state).unwrap(), "stack is empty now");
        assert!(redo(&mut state).unwrap(), "the only redo must report moved");
        assert!(!redo(&mut state).unwrap(), "stack is empty now");
    }

    #[test]
    fn join_undo_then_join_again() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        let tail_id = ClipId(Uuid::new_v4());
        track.insert_clip(clip).unwrap();
        let mut state = state_with_tracks(vec![track]);
        dispatch(
            &mut state,
            EditCommand::SplitClip {
                clip_id,
                at: Timestamp::from_micros(4_000_000),
                new_clip_id: tail_id,
            },
        )
        .unwrap();
        dispatch(
            &mut state,
            EditCommand::JoinClips {
                left_id: clip_id,
                right_id: tail_id,
            },
        )
        .unwrap();
        undo(&mut state).unwrap();
        assert_eq!(state.project.timeline.tracks[0].clips.len(), 2);
        let ids: Vec<_> = state.project.timeline.tracks[0]
            .clips
            .iter()
            .map(|c| c.id)
            .collect();
        assert_eq!(
            ids,
            vec![clip_id, tail_id],
            "undo must restore the same ids"
        );

        dispatch(
            &mut state,
            EditCommand::JoinClips {
                left_id: clip_id,
                right_id: tail_id,
            },
        )
        .unwrap();
        assert_eq!(state.project.timeline.tracks[0].clips.len(), 1);
    }

    #[test]
    fn join_refuses_pieces_that_skip_media() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let first = video_clip(0, 2_000_000);
        let mut second = video_clip(2_000_000, 2_000_000);
        second.source_start = MediaDuration::from_micros(5_000_000);
        second.source_end = MediaDuration::from_micros(7_000_000);
        let (left, right) = (first.id, second.id);
        track.insert_clip(first).unwrap();
        track.insert_clip(second).unwrap();
        let mut state = state_with_tracks(vec![track]);
        let error = dispatch(
            &mut state,
            EditCommand::JoinClips {
                left_id: left,
                right_id: right,
            },
        )
        .unwrap_err();
        assert!(matches!(error, ApplyError::NotJoinable { .. }), "{error:?}");
        assert_eq!(state.project.timeline.tracks[0].clips.len(), 2);
    }

    #[test]
    fn join_refuses_a_gap_between_pieces() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let first = video_clip(0, 2_000_000);
        let mut second = video_clip(4_000_000, 2_000_000);
        second.source_start = MediaDuration::from_micros(2_000_000);
        second.source_end = MediaDuration::from_micros(4_000_000);
        let (left, right) = (first.id, second.id);
        track.insert_clip(first).unwrap();
        track.insert_clip(second).unwrap();
        let mut state = state_with_tracks(vec![track]);
        let error = dispatch(
            &mut state,
            EditCommand::JoinClips {
                left_id: left,
                right_id: right,
            },
        )
        .unwrap_err();
        assert!(matches!(error, ApplyError::NotJoinable { .. }), "{error:?}");
    }

    #[test]
    fn split_creates_two_adjacent_clips() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();

        let mut state = state_with_tracks(vec![track]);

        dispatch(
            &mut state,
            EditCommand::SplitClip {
                clip_id,
                at: Timestamp::from_micros(5_000_000),
                new_clip_id: ClipId(Uuid::new_v4()),
            },
        )
        .unwrap();

        let clips = &state.project.timeline.tracks[0].clips;
        assert_eq!(clips.len(), 2);
        assert_eq!(clips[0].timeline_start, Timestamp::from_micros(0));
        assert_eq!(
            clips[0].timeline_duration,
            MediaDuration::from_micros(5_000_000)
        );
        assert_eq!(clips[0].source_end, MediaDuration::from_micros(5_000_000));
        assert_eq!(clips[1].timeline_start, Timestamp::from_micros(5_000_000));
        assert_eq!(
            clips[1].timeline_duration,
            MediaDuration::from_micros(5_000_000)
        );
        assert_eq!(clips[1].source_start, MediaDuration::from_micros(5_000_000));
    }

    #[test]
    fn move_clip_across_tracks_transfers_clip() {
        let mut src = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(5_000_000, 2_000_000);
        let clip_id = clip.id;
        src.insert_clip(clip).unwrap();
        let dst = Track::new(TrackKind::Video, "Video 2");
        let dst_id = dst.id;

        let mut state = state_with_tracks(vec![src, dst]);

        dispatch(
            &mut state,
            EditCommand::MoveClip {
                clip_id,
                new_track_id: dst_id,
                new_start: Timestamp::from_micros(10_000_000),
            },
        )
        .unwrap();

        assert!(
            state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .is_none()
        );
        let moved = state.project.timeline.tracks[1]
            .clip_by_id(clip_id)
            .unwrap();
        assert_eq!(moved.timeline_start, Timestamp::from_micros(10_000_000));

        undo(&mut state).unwrap();
        let back = state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap();
        assert_eq!(back.timeline_start, Timestamp::from_micros(5_000_000));
        assert!(
            state.project.timeline.tracks[1]
                .clip_by_id(clip_id)
                .is_none()
        );
    }

    #[test]
    fn move_resolves_overlap_instead_of_failing() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let first = video_clip(0, 5_000_000);
        let first_id = first.id;
        let second = video_clip(10_000_000, 5_000_000);

        track.insert_clip(first).unwrap();
        track.insert_clip(second).unwrap();

        let mut state = state_with_tracks(vec![track]);
        let track_id = state.project.timeline.tracks[0].id;

        dispatch(
            &mut state,
            EditCommand::MoveClip {
                clip_id: first_id,
                new_track_id: track_id,
                new_start: Timestamp::from_micros(12_000_000),
            },
        )
        .unwrap();

        let moved = state.project.timeline.tracks[0]
            .clip_by_id(first_id)
            .unwrap();
        assert_eq!(moved.timeline_start, Timestamp::from_micros(5_000_000));
        assert_eq!(state.project.timeline.tracks[0].clips.len(), 2);
    }

    #[test]
    fn batch_rolls_back_on_failure() {
        let track = Track::new(TrackKind::Video, "Original");
        let track_id = track.id;
        let mut state = state_with_tracks(vec![track]);

        let result = apply(
            &mut state,
            EditCommand::Batch {
                label: "rename and fail".into(),
                commands: vec![
                    EditCommand::RenameTrack {
                        track_id,
                        new_name: "Renamed".into(),
                    },
                    EditCommand::RemoveTrack {
                        track_id: TrackId(Uuid::new_v4()),
                    },
                ],
            },
        );

        assert!(result.is_err());
        assert_eq!(state.project.timeline.tracks[0].name, "Original");
    }

    #[test]
    fn set_speed_recomputes_timeline_duration_from_source_bounds() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();

        let mut state = state_with_tracks(vec![track]);

        dispatch(
            &mut state,
            EditCommand::SetClipSpeed {
                clip_id,
                speed: 2.0,
            },
        )
        .unwrap();

        let clip = &state.project.timeline.tracks[0].clips[0];
        assert_eq!(clip.speed, 2.0);
        assert_eq!(
            clip.timeline_duration,
            MediaDuration::from_micros(5_000_000)
        );
        assert_eq!(clip.source_start, MediaDuration::ZERO);
        assert_eq!(clip.source_end, MediaDuration::from_micros(10_000_000));
    }

    #[test]
    fn update_video_filter_replaces_existing_filter() {
        let mut clip = video_clip(0, 10_000_000);
        if let ClipKind::Video(v) = &mut clip.kind {
            v.filters
                .push(VideoEffect::new(VideoFilter::Brightness { value: 0.2 }));
        }

        let clip_id = clip.id;
        let mut track = Track::new(TrackKind::Video, "Video 1");
        track.insert_clip(clip).unwrap();

        let mut state = state_with_tracks(vec![track]);

        dispatch(
            &mut state,
            EditCommand::UpdateVideoFilter {
                clip_id,
                index: 0,
                filter: VideoEffect::new(VideoFilter::Contrast { value: 1.5 }),
            },
        )
        .unwrap();

        let clip = &state.project.timeline.tracks[0].clips[0];
        match &clip.kind {
            ClipKind::Video(v) => {
                assert!(matches!(
                    v.filters[0].filter,
                    VideoFilter::Contrast { value: _ }
                ));
            }
            _ => panic!("expected video clip"),
        }
    }

    #[test]
    fn set_clip_opacity_is_clamped() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();

        let mut state = state_with_tracks(vec![track]);

        dispatch(
            &mut state,
            EditCommand::SetClipOpacity {
                clip_id,
                opacity: 5.0,
            },
        )
        .unwrap();

        let clip = &state.project.timeline.tracks[0].clips[0];
        assert_eq!(clip.opacity, 1.0);
    }

    #[test]
    fn trim_end_updates_source_end() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();

        let mut state = state_with_tracks(vec![track]);

        dispatch(
            &mut state,
            EditCommand::TrimClipEnd {
                clip_id,
                new_duration: MediaDuration::from_micros(3_000_000),
            },
        )
        .unwrap();

        let clip = &state.project.timeline.tracks[0].clips[0];
        assert_eq!(
            clip.timeline_duration,
            MediaDuration::from_micros(3_000_000)
        );
        assert_eq!(clip.source_end, MediaDuration::from_micros(3_000_000));
    }

    #[test]
    fn move_clip_is_clamped_between_neighbors() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let left = video_clip(0, 5_000_000);
        let mut moving = video_clip(6_000_000, 3_000_000);
        let moving_id = moving.id;
        let right = video_clip(10_000_000, 5_000_000);

        track.insert_clip(left).unwrap();
        track.insert_clip(moving.clone()).unwrap();
        track.insert_clip(right).unwrap();

        let mut state = state_with_tracks(vec![track]);
        let track_id = state.project.timeline.tracks[0].id;

        dispatch(
            &mut state,
            EditCommand::MoveClip {
                clip_id: moving_id,
                new_track_id: track_id,
                new_start: Timestamp::from_micros(12_000_000),
            },
        )
        .unwrap();

        moving = state.project.timeline.tracks[0]
            .clip_by_id(moving_id)
            .cloned()
            .unwrap();
        assert_eq!(moving.timeline_start, Timestamp::from_micros(7_000_000));
    }

    #[test]
    fn trim_end_is_clamped_to_next_clip_start() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let first = video_clip(0, 5_000_000);
        let first_id = first.id;
        let second = video_clip(6_000_000, 4_000_000);

        track.insert_clip(first).unwrap();
        track.insert_clip(second).unwrap();

        let mut state = state_with_tracks(vec![track]);

        dispatch(
            &mut state,
            EditCommand::TrimClipEnd {
                clip_id: first_id,
                new_duration: MediaDuration::from_micros(10_000_000),
            },
        )
        .unwrap();

        let clip = state.project.timeline.tracks[0]
            .clip_by_id(first_id)
            .unwrap();
        assert_eq!(
            clip.timeline_duration,
            MediaDuration::from_micros(5_000_000)
        );
    }

    fn state_with_clip() -> (EditorState, ClipId) {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(0, 10_000_000);
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();
        (state_with_tracks(vec![track]), clip_id)
    }

    fn duration_of(state: &EditorState, clip_id: ClipId) -> i64 {
        state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap()
            .timeline_duration
            .as_micros()
    }

    #[test]
    fn gesture_drag_coalesces_to_one_undo_step() {
        let (mut state, clip_id) = state_with_clip();
        begin_edit(&mut state, "Trim");
        for dur in [9_000_000, 8_000_000, 7_000_000, 6_000_000] {
            dispatch_open(
                &mut state,
                EditCommand::TrimClipEnd {
                    clip_id,
                    new_duration: MediaDuration::from_micros(dur),
                },
            )
            .unwrap();
        }
        commit_edit(&mut state);
        assert_eq!(duration_of(&state, clip_id), 6_000_000);
        assert_eq!(state.history.undo_depth(), 1);
        assert_eq!(state.history.undo_label(), Some("Trim"));
        undo(&mut state).unwrap();
        assert_eq!(duration_of(&state, clip_id), 10_000_000);
        assert!(!state.history.can_undo());
        redo(&mut state).unwrap();
        assert_eq!(duration_of(&state, clip_id), 6_000_000);
    }

    #[test]
    fn labeled_slider_ticks_fold_into_one_step() {
        let (mut state, clip_id) = state_with_clip();
        for vol in [0.8, 0.6, 0.4] {
            dispatch_labeled(
                &mut state,
                "Volume",
                EditCommand::SetClipVolume {
                    clip_id,
                    volume: vol,
                },
            )
            .unwrap();
        }
        assert_eq!(state.history.undo_depth(), 1);
        undo(&mut state).unwrap();
        assert_eq!(
            state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .unwrap()
                .volume,
            1.0
        );
    }

    #[test]
    fn unlabeled_dispatches_stay_separate() {
        let (mut state, clip_id) = state_with_clip();
        for dur in [9_000_000, 8_000_000] {
            dispatch(
                &mut state,
                EditCommand::TrimClipEnd {
                    clip_id,
                    new_duration: MediaDuration::from_micros(dur),
                },
            )
            .unwrap();
        }
        assert_eq!(state.history.undo_depth(), 2);
        undo(&mut state).unwrap();
        assert_eq!(duration_of(&state, clip_id), 9_000_000);
        undo(&mut state).unwrap();
        assert_eq!(duration_of(&state, clip_id), 10_000_000);
    }

    #[test]
    fn cross_commit_merge_undo_restores_pre_gesture_state() {
        let (mut state, clip_id) = state_with_clip();
        for vol in [0.8, 0.6, 0.4] {
            dispatch_labeled(
                &mut state,
                "Volume",
                EditCommand::SetClipVolume {
                    clip_id,
                    volume: vol,
                },
            )
            .unwrap();
        }
        assert_eq!(state.history.undo_depth(), 1);
        undo(&mut state).unwrap();
        assert_eq!(
            state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .unwrap()
                .volume,
            1.0
        );
    }

    #[test]
    fn set_speed_rejects_overlap() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let first = video_clip(0, 5_000_000);
        let first_id = first.id;
        let second = video_clip(5_000_000, 5_000_000);
        track.insert_clip(first).unwrap();
        track.insert_clip(second).unwrap();
        let mut state = state_with_tracks(vec![track]);
        let err = dispatch(
            &mut state,
            EditCommand::SetClipSpeed {
                clip_id: first_id,
                speed: 0.5,
            },
        )
        .unwrap_err();
        assert!(matches!(err, ApplyError::Overlap(_)));
        assert_eq!(duration_of(&state, first_id), 5_000_000);
    }

    #[test]
    fn add_clip_rejects_wrong_track_kind() {
        let track = Track::new(TrackKind::Audio, "Audio 1");
        let track_id = track.id;
        let mut state = state_with_tracks(vec![track]);
        let clip = video_clip(0, 5_000_000);
        let err = apply(
            &mut state,
            EditCommand::AddClip {
                track_id,
                clip: clip.clone(),
            },
        )
        .unwrap_err();
        assert!(matches!(err, ApplyError::IncompatibleTrackKind { .. }));
        let _ = clip;
    }

    #[test]
    fn trim_start_left_extension_needs_source_headroom() {
        let mut track = Track::new(TrackKind::Video, "Video 1");
        let clip = video_clip(5_000_000, 5_000_000);
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();
        let mut state = state_with_tracks(vec![track]);
        let err = dispatch(
            &mut state,
            EditCommand::TrimClipStart {
                clip_id,
                new_start: Timestamp::from_micros(3_000_000),
                new_source_start: MediaDuration::ZERO,
            },
        )
        .unwrap_err();
        assert!(matches!(err, ApplyError::InvalidSourceBounds));
        let clip = state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap();
        assert_eq!(clip.timeline_start, Timestamp::from_micros(5_000_000));
    }

    #[test]
    fn cancel_edit_rolls_the_gesture_back() {
        let (mut state, clip_id) = state_with_clip();
        begin_edit(&mut state, "Trim");
        dispatch_open(
            &mut state,
            EditCommand::TrimClipEnd {
                clip_id,
                new_duration: MediaDuration::from_micros(5_000_000),
            },
        )
        .unwrap();
        assert_eq!(duration_of(&state, clip_id), 5_000_000);
        cancel_edit(&mut state).unwrap();
        assert_eq!(duration_of(&state, clip_id), 10_000_000);
        assert!(!state.history.can_undo());
    }

    #[test]
    fn singleton_video_filters_replace_instead_of_stacking() {
        let (mut state, clip_id) = state_with_clip();
        for _ in 0..2 {
            dispatch(
                &mut state,
                EditCommand::AddVideoFilter {
                    clip_id,
                    filter: VideoEffect::new(VideoFilter::Crop {
                        left: 0.1,
                        top: 0.1,
                        right: 0.9,
                        bottom: 0.9,
                    }),
                    index: None,
                },
            )
            .unwrap();
        }
        let filters = match &state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap()
            .kind
        {
            ClipKind::Video(v) => v.filters.clone(),
            _ => panic!("expected video clip"),
        };
        assert_eq!(filters.len(), 1, "Crop must stay a singleton");

        dispatch(
            &mut state,
            EditCommand::AddVideoFilter {
                clip_id,
                filter: VideoEffect::new(VideoFilter::Speed { factor: 2.0 }),
                index: None,
            },
        )
        .unwrap();
        dispatch(
            &mut state,
            EditCommand::AddVideoFilter {
                clip_id,
                filter: VideoEffect::new(VideoFilter::Reverse),
                index: None,
            },
        )
        .unwrap();
        dispatch(
            &mut state,
            EditCommand::AddVideoFilter {
                clip_id,
                filter: VideoEffect::new(VideoFilter::Speed { factor: 0.5 }),
                index: None,
            },
        )
        .unwrap();
        let filters = match &state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap()
            .kind
        {
            ClipKind::Video(v) => v.filters.clone(),
            _ => panic!("expected video clip"),
        };
        let speeds = filters
            .iter()
            .filter(|fx| matches!(fx.filter, VideoFilter::Speed { .. }))
            .count();
        assert_eq!(speeds, 1, "Speed must stay a singleton");
        assert!(
            filters
                .iter()
                .any(|fx| matches!(fx.filter, VideoFilter::Reverse)),
            "Reverse must be present"
        );
        undo(&mut state).unwrap();
        let filters = match &state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap()
            .kind
        {
            ClipKind::Video(v) => v.filters.clone(),
            _ => panic!("expected video clip"),
        };
        let factor = filters.iter().find_map(|fx| match fx.filter {
            VideoFilter::Speed { factor } => Some(factor),
            _ => None,
        });
        assert_eq!(factor, Some(2.0));
    }

    #[test]
    fn multi_step_redo_replays_whole_transactions() {
        let (mut state, clip_id) = state_with_clip();
        dispatch(
            &mut state,
            EditCommand::TrimClipEnd {
                clip_id,
                new_duration: MediaDuration::from_micros(9_000_000),
            },
        )
        .unwrap();
        dispatch(
            &mut state,
            EditCommand::SetClipVolume {
                clip_id,
                volume: 0.5,
            },
        )
        .unwrap();
        undo(&mut state).unwrap();
        undo(&mut state).unwrap();
        assert_eq!(state.history.redo_depth(), 2);
        redo(&mut state).unwrap();
        assert_eq!(state.history.redo_depth(), 1);
        redo(&mut state).unwrap();
        assert_eq!(state.history.redo_depth(), 0);
        assert_eq!(duration_of(&state, clip_id), 9_000_000);
        assert_eq!(
            state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .unwrap()
                .volume,
            0.5
        );
    }
    // Undo/redo correctness regressions. Each test reproduced a real defect where a
    // single undo silently destroyed unrelated user data.

    fn empty_state() -> EditorState {
        EditorState::new(Project::new("t"))
    }

    fn rect(right: f32) -> MaskEffect {
        MaskEffect::shape(MaskShape::Rectangle {
            left: 0.0,
            top: 0.0,
            right,
            bottom: 1.0,
        })
    }

    fn state_with_masks(masks: Vec<MaskEffect>) -> (EditorState, ClipId) {
        let mut state = empty_state();
        let mut track = Track::new(TrackKind::Video, "V1");
        let mut clip = video_clip(0, 10_000_000);
        if let ClipKind::Video(v) = &mut clip.kind {
            v.masks = masks;
        }
        let clip_id = clip.id;
        track.insert_clip(clip).unwrap();
        state.project.timeline.tracks = vec![track];
        (state, clip_id)
    }

    fn mask_widths(state: &EditorState, clip_id: ClipId) -> Vec<f32> {
        let clip = state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .expect("clip present");
        let ClipKind::Video(v) = &clip.kind else {
            panic!("expected a video clip")
        };
        v.masks
            .iter()
            .map(|m| match &m.source {
                miniter_domain::mask::MaskSource::Shape {
                    shape: MaskShape::Rectangle { right, .. },
                    ..
                } => *right,
                _ => 0.0,
            })
            .collect()
    }

    /// Undo then redo must restore the *same* track. Before the fix, redo
    /// minted a fresh id while the recorded inverse still pointed at the old
    /// one, so the second undo failed with TrackNotFound and dropped the
    /// transaction out of both stacks.
    #[test]
    fn add_track_survives_undo_redo_undo() {
        let mut state = empty_state();
        // A new project already seeds default tracks, so compare against a
        // baseline rather than assuming an empty timeline.
        let baseline = state.project.timeline.tracks.len();
        dispatch(
            &mut state,
            EditCommand::AddTrack {
                kind: TrackKind::Video,
                name: "V1".into(),
                track_id: None,
            },
        )
        .unwrap();
        let added = state
            .project
            .timeline
            .tracks
            .iter()
            .find(|t| t.name == "V1")
            .expect("added track")
            .id;
        assert_eq!(state.project.timeline.tracks.len(), baseline + 1);

        undo(&mut state).expect("first undo");
        assert_eq!(state.project.timeline.tracks.len(), baseline);

        redo(&mut state).expect("redo");
        assert_eq!(state.project.timeline.tracks.len(), baseline + 1);
        assert!(
            state.project.timeline.tracks.iter().any(|t| t.id == added),
            "redo must restore the original track id"
        );

        undo(&mut state).expect("second undo");
        assert_eq!(state.project.timeline.tracks.len(), baseline);
        assert!(
            !state.project.timeline.tracks.iter().any(|t| t.id == added),
            "no leftover track"
        );
    }

    /// Undo of a mid-stack removal must re-insert at the original index.
    /// Appending reordered the composite stack, and the following redo then
    /// deleted whichever mask had slid into that slot.
    #[test]
    fn removing_a_middle_mask_undo_redo_preserves_order() {
        let (mut state, clip_id) = state_with_masks(vec![rect(0.3), rect(0.6), rect(0.9)]);

        dispatch(&mut state, EditCommand::RemoveMask { clip_id, index: 1 }).unwrap();
        assert_eq!(mask_widths(&state, clip_id), vec![0.3, 0.9]);

        undo(&mut state).expect("undo");
        assert_eq!(
            mask_widths(&state, clip_id),
            vec![0.3, 0.6, 0.9],
            "undo must restore the mask at its original index, not append it"
        );

        redo(&mut state).expect("redo");
        assert_eq!(
            mask_widths(&state, clip_id),
            vec![0.3, 0.9],
            "redo must remove the same mask the user removed"
        );
    }

    /// Deleting a video filter from the middle of the stack had the same
    /// reordering defect as masks.
    #[test]
    fn removing_a_middle_video_filter_undo_redo_preserves_order() {
        let (mut state, clip_id) = state_with_masks(vec![]);
        let brightness = VideoEffect::new(VideoFilter::Brightness { value: 0.1 });
        let contrast = VideoEffect::new(VideoFilter::Contrast { value: 0.2 });
        for f in [brightness, contrast] {
            dispatch(
                &mut state,
                EditCommand::AddVideoFilter {
                    clip_id,
                    filter: f,
                    index: None,
                },
            )
            .unwrap();
        }
        let count = |state: &EditorState| {
            let clip = state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .unwrap();
            let ClipKind::Video(v) = &clip.kind else {
                panic!()
            };
            v.filters.len()
        };
        assert_eq!(count(&state), 2);

        dispatch(
            &mut state,
            EditCommand::RemoveVideoFilter { clip_id, index: 0 },
        )
        .unwrap();
        assert_eq!(count(&state), 1);

        undo(&mut state).expect("undo");
        assert_eq!(count(&state), 2, "undo must bring the filter back");

        redo(&mut state).expect("redo");
        assert_eq!(count(&state), 1);

        // And the surviving filter must be the one that was never deleted.
        let clip = state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap();
        let ClipKind::Video(v) = &clip.kind else {
            panic!()
        };
        assert!(
            matches!(v.filters[0].filter, VideoFilter::Contrast { .. }),
            "redo must not delete the untouched filter"
        );
    }

    /// Moving a keyframe's offset re-sorts the curve, so recording the
    /// pre-sort index made the undo overwrite a different keyframe.
    #[test]
    fn updating_keyframe_offset_undo_targets_the_right_entry() {
        let kf = |us: i64| miniter_domain::Keyframe {
            param: "volume".into(),
            offset: MediaDuration::from_micros(us),
            value: 1.0,
            easing: miniter_domain::keyframe::Easing::Linear,
        };
        let (mut state, clip_id) = state_with_masks(vec![]);
        {
            let track = &mut state.project.timeline.tracks[0];
            let clip = track.clip_by_id(clip_id).unwrap().clone();
            let mut clip = clip;
            clip.keyframes.keyframes = vec![kf(1_000_000), kf(5_000_000)];
            let idx = track.clips.iter().position(|c| c.id == clip_id).unwrap();
            track.clips[idx] = clip;
        }

        dispatch(
            &mut state,
            EditCommand::UpdateKeyframe {
                clip_id,
                index: 1,
                keyframe: kf(500_000),
            },
        )
        .unwrap();

        undo(&mut state).expect("undo");

        let clip = state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap();
        let offsets: Vec<i64> = clip
            .keyframes
            .keyframes
            .iter()
            .map(|k| k.offset.as_micros())
            .collect();
        assert_eq!(
            offsets,
            vec![1_000_000, 5_000_000],
            "undo must restore both keyframes, not overwrite the neighbour"
        );
    }

    /// Adding a keyframe over an existing (param, offset) overwrites it, so the
    /// inverse must write the old value back instead of deleting the slot.
    #[test]
    fn adding_over_an_existing_keyframe_undo_restores_the_value() {
        let kf = |us: i64, v: f32| miniter_domain::Keyframe {
            param: "volume".into(),
            offset: MediaDuration::from_micros(us),
            value: v,
            easing: miniter_domain::keyframe::Easing::Linear,
        };
        let (mut state, clip_id) = state_with_masks(vec![]);
        {
            let track = &mut state.project.timeline.tracks[0];
            let mut clip = track.clip_by_id(clip_id).unwrap().clone();
            clip.keyframes.keyframes = vec![kf(1_000_000, 0.2)];
            let idx = track.clips.iter().position(|c| c.id == clip_id).unwrap();
            track.clips[idx] = clip;
        }
        let values = |state: &EditorState| {
            let clip = state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .unwrap();
            clip.keyframes
                .keyframes
                .iter()
                .map(|k| k.value)
                .collect::<Vec<_>>()
        };

        dispatch(
            &mut state,
            EditCommand::AddKeyframe {
                clip_id,
                keyframe: kf(1_000_000, 0.9),
            },
        )
        .unwrap();
        assert_eq!(values(&state), vec![0.9]);

        undo(&mut state).expect("undo");
        assert_eq!(
            values(&state),
            vec![0.2],
            "undo must restore the overwritten value, not delete the keyframe"
        );
    }

    /// Gain cannot be negative, and a non-positive gain makes the mixer skip
    /// the clip entirely rather than silence it. A project carrying a negative
    /// volume (hand-edited JSON) therefore lost whole clips from the mix and
    /// could produce a completely silent export with no error.
    #[test]
    fn negative_clip_volume_is_clamped_to_silence_not_dropped() {
        let (mut state, clip_id) = state_with_masks(vec![]);
        dispatch(
            &mut state,
            EditCommand::SetClipVolume {
                clip_id,
                volume: -1.0,
            },
        )
        .unwrap();

        let clip = state.project.timeline.tracks[0]
            .clip_by_id(clip_id)
            .unwrap();
        assert!(
            clip.volume >= 0.0,
            "volume must never be negative, got {}",
            clip.volume
        );
        assert!(
            state.project.timeline.tracks[0]
                .clip_by_id(clip_id)
                .is_some(),
            "the clip must still exist"
        );
    }

    /// A locked track must refuse clip insertion and deletion. `AddClip` and
    /// `RemoveClip` bypassed the lock check that trim/move/split go through, so
    /// deleting a clip on a locked track succeeded while every other edit on
    /// that track was correctly rejected.
    #[test]
    fn locked_track_refuses_clip_add_and_remove() {
        let (mut state, clip_id) = state_with_masks(vec![]);
        let track_id = state.project.timeline.tracks[0].id;
        dispatch(
            &mut state,
            EditCommand::SetTrackLocked {
                track_id,
                locked: true,
            },
        )
        .unwrap();

        let err = dispatch(&mut state, EditCommand::RemoveClip { clip_id });
        assert!(
            matches!(err, Err(ApplyError::TrackLocked(_))),
            "removing a clip from a locked track must be rejected, got {err:?}"
        );

        let spare = video_clip(20_000_000, 1_000_000);
        let spare_id = spare.id;
        let err = dispatch(
            &mut state,
            EditCommand::AddClip {
                track_id,
                clip: spare,
            },
        );
        assert!(
            matches!(err, Err(ApplyError::TrackLocked(_))),
            "adding a clip to a locked track must be rejected, got {err:?}"
        );

        assert!(
            state.project.timeline.tracks[0]
                .clip_by_id(spare_id)
                .is_none(),
            "the rejected clip must not have been inserted"
        );
    }

    /// A failed edit used to leave its history transaction open forever, which
    /// made the UI route every later coalesced edit into an open transaction
    /// that nothing ever committed, so those edits could not be undone.
    #[test]
    fn failed_dispatch_does_not_leave_a_transaction_open() {
        let (mut state, clip_id) = state_with_masks(vec![]);
        let missing = ClipId(Uuid::new_v4());
        assert!(
            dispatch(
                &mut state,
                EditCommand::RemoveKeyframe {
                    clip_id: missing,
                    index: 0,
                },
            )
            .is_err()
        );
        assert!(
            !state.history.transaction_open(),
            "a failed dispatch must close the transaction it opened"
        );

        dispatch(
            &mut state,
            EditCommand::AddAudioFilter {
                clip_id,
                filter: AudioFilter::Volume { value: 0.5 },
                index: None,
            },
        )
        .unwrap();

        undo(&mut state).expect("the later edit must be undoable");
        assert!(
            !state.history.can_undo(),
            "exactly the one step should have been consumed"
        );
    }

    /// Merging a new same-label edit into the previous undo step must discard
    /// the redo stack; otherwise a stale redo re-applies a transaction that was
    /// branched from a state the merge has since invalidated.
    #[test]
    fn merged_edit_clears_the_redo_stack() {
        let mut state = empty_state();
        for name in ["V1", "V2"] {
            dispatch_labeled(
                &mut state,
                "Trim",
                EditCommand::AddTrack {
                    kind: TrackKind::Video,
                    name: name.into(),
                    track_id: None,
                },
            )
            .unwrap();
        }

        undo(&mut state).expect("undo");
        assert!(state.history.can_redo());

        // A further same-label edit whose boundary coalesces folds into the
        // previous step and must invalidate the redo stack.
        dispatch_labeled(
            &mut state,
            "Trim",
            EditCommand::AddTrack {
                kind: TrackKind::Audio,
                name: "A1".into(),
                track_id: None,
            },
        )
        .unwrap();

        assert!(
            !state.history.can_redo(),
            "a new edit must invalidate redo, including via the merge path"
        );
    }
}

#[cfg(test)]
mod serde_compat_tests {
    use super::*;
    use miniter_domain::clip::ClipId;
    use uuid::Uuid;

    /// The Kotlin command builders never emit `index`; it only appears on
    /// inverses the reducer generates itself. Every add coming from the UI must
    /// therefore still deserialize.
    fn without_index(json: &str) -> String {
        json.replace(r#","index":null"#, "")
    }

    #[test]
    fn add_commands_omit_index_and_still_parse() {
        let clip_id = ClipId(Uuid::new_v4());
        for cmd in [
            EditCommand::AddVideoFilter {
                clip_id,
                filter: VideoEffect::new(miniter_domain::filter::VideoFilter::Brightness {
                    value: 0.1,
                }),
                index: None,
            },
            EditCommand::AddMask {
                clip_id,
                mask: miniter_domain::mask::MaskEffect::shape(
                    miniter_domain::mask::MaskShape::Ellipse {
                        center_x: 0.5,
                        center_y: 0.5,
                        radius_x: 0.5,
                        radius_y: 0.5,
                    },
                ),
                index: None,
            },
            EditCommand::AddAudioFilter {
                clip_id,
                filter: miniter_domain::filter::AudioFilter::Volume { value: 1.0 },
                index: None,
            },
        ] {
            let json = serde_json::to_string(&cmd).expect("serialize");
            let stripped = without_index(&json);
            assert!(
                !stripped.contains(r#""index""#),
                "index should be absent in {stripped}"
            );
            serde_json::from_str::<EditCommand>(&stripped)
                .unwrap_or_else(|e| panic!("{stripped} failed to parse: {e}"));
        }
    }

    #[test]
    fn positional_inverse_round_trips() {
        let clip_id = ClipId(Uuid::new_v4());
        let json = serde_json::to_string(&EditCommand::AddMask {
            clip_id,
            mask: miniter_domain::mask::MaskEffect::shape(
                miniter_domain::mask::MaskShape::Ellipse {
                    center_x: 0.5,
                    center_y: 0.5,
                    radius_x: 0.5,
                    radius_y: 0.5,
                },
            ),
            index: Some(3),
        })
        .expect("serialize");
        assert!(json.contains(r#""index":3"#), "{json}");
        serde_json::from_str::<EditCommand>(&json).expect("parse");
    }
}
