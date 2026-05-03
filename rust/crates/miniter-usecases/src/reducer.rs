use crate::commands::EditCommand;
use crate::history::History;
use crate::selection::Selection;
use miniter_domain::clip::{Clip, ClipId, ClipKind};
use miniter_domain::filter::{AudioFilter, VideoEffect};
use miniter_domain::project::Project;
use miniter_domain::time::{MediaDuration, Timestamp};
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
        EditCommand::AddTrack { kind, name } => {
            let track = Track::new(kind, &name);
            let id = track.id;
            state.project.timeline.add_track(track);
            Ok(EditCommand::RemoveTrack { track_id: id })
        }

        EditCommand::Nop => Ok(EditCommand::Nop),

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
            let (src_track_idx, clip_idx) = find_clip_location(state, clip_id)?;
            let dst_track_idx = find_track_index(state, new_track_id)?;

            let original = state.project.timeline.tracks[src_track_idx].clips[clip_idx].clone();
            let old_track_id = state.project.timeline.tracks[src_track_idx].id;
            let old_start = original.timeline_start;

            let mut moved = original.clone();
            moved.timeline_start = new_start.clamp_non_negative();

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
                    old_start,
                )
            };
            moved.timeline_start = clamped_start;

            if src_track_idx == dst_track_idx {
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
            track.insert_clip(dup).map_err(ApplyError::Overlap)?;

            Ok(EditCommand::RemoveClip {
                clip_id: new_clip_id,
            })
        }

        EditCommand::TrimClipStart {
            clip_id,
            new_start,
            new_source_start: _,
        } => {
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
            let mut recalculated_source_start = original.source_start.as_micros()
                + (delta_timeline_us as f64 * original.speed) as i64;
            if recalculated_source_start < 0 {
                recalculated_source_start = 0;
            }
            if recalculated_source_start >= original.source_end.as_micros() {
                recalculated_source_start = original.source_end.as_micros().saturating_sub(1);
            }

            let mut updated = original.clone();
            updated.timeline_start = clamped_start;
            updated.source_start = MediaDuration::from_micros(recalculated_source_start);
            updated.timeline_duration = timeline_duration_from_source_bounds(
                updated.speed,
                updated.source_start,
                updated.source_end,
            )?;
            ensure_positive_duration(updated.timeline_duration)?;
            ensure_clip_source_bounds(&updated)?;

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

        EditCommand::TrimClipEnd {
            clip_id,
            new_duration,
        } => {
            ensure_positive_duration(new_duration)?;

            let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
            let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();

            let max_source_end = original.source_total_duration;
            let max_duration = MediaDuration::from_micros(
                ((max_source_end.as_micros() as f64 - original.source_start.as_micros() as f64)
                    / original.speed) as i64,
            );
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
                original.source_start.as_micros()
                    + (clamped_duration.as_micros() as f64 * original.speed) as i64,
            );

            let mut updated = original.clone();
            updated.timeline_duration = clamped_duration;
            updated.source_end = new_source_end;

            if !matches!(original.kind, ClipKind::Text(_)) {
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

        EditCommand::SplitClip {
            clip_id,
            at,
            new_clip_id,
        } => {
            let (track_idx, clip_idx) = find_clip_location(state, clip_id)?;
            let original = state.project.timeline.tracks[track_idx].clips[clip_idx].clone();

            if at <= original.timeline_start || at >= original.timeline_end() {
                return Err(ApplyError::SplitOutOfRange { clip_id, at });
            }

            let left_dur = at - original.timeline_start;
            let right_dur = MediaDuration::from_micros(
                original.timeline_duration.as_micros() - left_dur.as_micros(),
            );

            ensure_positive_duration(left_dur)?;
            ensure_positive_duration(right_dur)?;

            let split_source_start = MediaDuration::from_micros(
                original.source_start.as_micros()
                    + (left_dur.as_micros() as f64 * original.speed) as i64,
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

            ensure_clip_source_bounds(&left)?;
            ensure_clip_source_bounds(&right)?;

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

        EditCommand::SetClipSpeed { clip_id, speed } => {
            if speed <= 0.0 {
                return Err(ApplyError::InvalidSpeed(speed));
            }
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.speed;
            clip.speed = speed;
            clip.timeline_duration = timeline_duration_from_source_bounds(
                clip.speed,
                clip.source_start,
                clip.source_end,
            )?;
            ensure_positive_duration(clip.timeline_duration)?;
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

        EditCommand::SetClipOpacity { clip_id, opacity } => {
            let clip = find_clip_mut(state, clip_id)?;
            let old = clip.opacity;
            clip.opacity = opacity.clamp(0.0, 1.0);
            Ok(EditCommand::SetClipOpacity {
                clip_id,
                opacity: old,
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
                ClipKind::Video(v) => {
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

        EditCommand::UpdateVideoFilter {
            clip_id,
            index,
            filter,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    if index >= v.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let old = std::mem::replace(&mut v.filters[index], filter);
                    Ok(EditCommand::UpdateVideoFilter {
                        clip_id,
                        index,
                        filter: old,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::RemoveVideoFilter { clip_id, index } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
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

        EditCommand::SetVideoFilterEnabled {
            clip_id,
            index,
            enabled,
        } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    if index >= v.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let old = v.filters[index].enabled;
                    v.filters[index].enabled = enabled;
                    Ok(EditCommand::SetVideoFilterEnabled {
                        clip_id,
                        index,
                        enabled: old,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::MoveVideoFilter { clip_id, from, to } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    if from >= v.filters.len() || to >= v.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    if from == to {
                        return Ok(EditCommand::MoveVideoFilter { clip_id, from, to });
                    }
                    let moved = v.filters.remove(from);
                    v.filters.insert(to, moved);
                    Ok(EditCommand::MoveVideoFilter {
                        clip_id,
                        from: to,
                        to: from,
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::AddAudioFilter { clip_id, filter } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    v.audio_filters.push(filter);
                    let idx = v.audio_filters.len() - 1;
                    Ok(EditCommand::RemoveAudioFilter {
                        clip_id,
                        index: idx,
                    })
                }
                ClipKind::Audio(a) => {
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
                ClipKind::Video(v) => {
                    if index >= v.audio_filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let removed = v.audio_filters.remove(index);
                    Ok(EditCommand::AddAudioFilter {
                        clip_id,
                        filter: removed,
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
                    })
                }
                _ => Err(ApplyError::WrongClipKind(clip_id)),
            }
        }

        EditCommand::UpdateAudioFilterDuration { clip_id, index, duration_us } => {
            let clip = find_clip_mut(state, clip_id)?;
            match &mut clip.kind {
                ClipKind::Video(v) => {
                    if index >= v.audio_filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let filter = &mut v.audio_filters[index];
                    match filter {
                        AudioFilter::FadeIn { duration_us: d } => *d = duration_us,
                        AudioFilter::FadeOut { duration_us: d } => *d = duration_us,
                        _ => return Err(ApplyError::InvalidCommand("Only fade filters have duration")),
                    }
                    Ok(EditCommand::Nop)
                }
                ClipKind::Audio(a) => {
                    if index >= a.filters.len() {
                        return Err(ApplyError::IndexOutOfBounds);
                    }
                    let filter = &mut a.filters[index];
                    match filter {
                        AudioFilter::FadeIn { duration_us: d } => *d = duration_us,
                        AudioFilter::FadeOut { duration_us: d } => *d = duration_us,
                        _ => return Err(ApplyError::InvalidCommand("Only fade filters have duration")),
                    }
                    Ok(EditCommand::Nop)
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

        EditCommand::SetExportProfile { profile } => {
            let old = std::mem::replace(&mut state.project.export_profile, profile);
            Ok(EditCommand::SetExportProfile { profile: old })
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

fn timeline_duration_from_source_bounds(
    speed: f64,
    source_start: MediaDuration,
    source_end: MediaDuration,
) -> Result<MediaDuration, ApplyError> {
    if speed <= 0.0 {
        return Err(ApplyError::InvalidSpeed(speed));
    }
    let source_us = source_end.as_micros() - source_start.as_micros();
    let duration = MediaDuration::from_micros((source_us as f64 / speed) as i64);
    ensure_positive_duration(duration)?;
    Ok(duration)
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

#[derive(Debug, thiserror::Error)]
pub enum ApplyError {
    #[error("Track not found: {0:?}")]
    TrackNotFound(miniter_domain::track::TrackId),

    #[error("Clip not found: {0:?}")]
    ClipNotFound(ClipId),

    #[error("Track is locked: {0:?}")]
    TrackLocked(miniter_domain::track::TrackId),

    #[error("Clip {clip_id:?} is incompatible with track {track_id:?}")]
    IncompatibleTrackKind { track_id: TrackId, clip_id: ClipId },

    #[error("Wrong clip kind for operation: {0:?}")]
    WrongClipKind(ClipId),

    #[error("Split point outside clip range: clip={clip_id:?} at={at:?}")]
    SplitOutOfRange { clip_id: ClipId, at: Timestamp },

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

#[cfg(test)]
mod tests {
    use super::*;
    use miniter_domain::clip::{ClipKind, VideoClip};
    use miniter_domain::project::Project;
    use miniter_domain::time::{MediaDuration, Timestamp};
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
            transition_in: None,
            transition_out: None,
            kind: ClipKind::Video(VideoClip {
                source_path: "sample.mp4".into(),
                width: 1920,
                height: 1080,
                fps: 30.0,
                filters: Vec::<VideoEffect>::new(),
                audio_filters: Vec::<AudioFilter>::new(),
            }),
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
}
