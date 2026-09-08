use crate::render_graph::{RenderPlan, plan_frame};
use miniter_domain::clip::ClipKind;
use miniter_domain::export::{ExportProfile, SubtitleMode};
use miniter_domain::time::Timestamp;
use miniter_domain::timeline::Timeline;

pub struct FramePlanIterator<'a> {
    timeline: &'a Timeline,
    width: u32,
    height: u32,
    subtitle_mode: SubtitleMode,
    /// Sanitized export rate. Frame timestamps derive from it directly
    /// (`round(i * 1e6 / fps)`) instead of accumulating a truncated step, so
    /// long exports don't drift (240 accumulated 80µs per 10s at 24fps).
    fps: f64,
    current_frame: u64,
    total_frames: u64,
}

impl<'a> FramePlanIterator<'a> {
    pub fn with_render_settings(
        timeline: &'a Timeline,
        width: u32,
        height: u32,
        fps: f64,
        subtitle_mode: SubtitleMode,
    ) -> Self {
        let fps = sanitize_fps(fps);
        let end = timeline.duration_end().as_micros().max(0);

        let total = if end == 0 {
            0
        } else {
            (end as f64 * fps / 1_000_000.0).ceil().max(0.0) as u64
        };

        Self {
            timeline,
            width,
            height,
            subtitle_mode,
            fps,
            current_frame: 0,
            total_frames: total,
        }
    }

    pub fn new(timeline: &'a Timeline, profile: &ExportProfile) -> Self {
        let (w, h) = profile.resolution.dimensions();
        let (resolved_w, resolved_h) = if w > 0 && h > 0 {
            (w, h)
        } else {
            first_video_dimensions(timeline).unwrap_or((1920, 1080))
        };
        Self::with_render_settings(
            timeline,
            resolved_w,
            resolved_h,
            profile.fps,
            profile.subtitle_mode,
        )
    }

    pub fn total_frames(&self) -> u64 {
        self.total_frames
    }
}

impl<'a> Iterator for FramePlanIterator<'a> {
    type Item = RenderPlan;

    fn next(&mut self) -> Option<Self::Item> {
        if self.current_frame >= self.total_frames {
            return None;
        }
        let t = Timestamp::from_micros(
            (self.current_frame as f64 * 1_000_000.0 / self.fps).round() as i64,
        );
        self.current_frame += 1;
        Some(plan_frame(
            self.timeline,
            t,
            self.width,
            self.height,
            self.subtitle_mode,
        ))
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        let remaining = (self.total_frames - self.current_frame) as usize;
        (remaining, Some(remaining))
    }
}

impl<'a> ExactSizeIterator for FramePlanIterator<'a> {}

pub fn compose_frame(timeline: &Timeline, profile: &ExportProfile) -> Vec<RenderPlan> {
    FramePlanIterator::new(timeline, profile).collect()
}

fn sanitize_fps(fps: f64) -> f64 {
    if fps.is_finite() && fps > 0.0 {
        fps
    } else {
        30.0
    }
}

pub fn first_video_dimensions(timeline: &Timeline) -> Option<(u32, u32)> {
    for track in &timeline.tracks {
        for clip in &track.clips {
            if let ClipKind::Video(video) = &clip.kind
                && video.width > 0
                && video.height > 0
            {
                return Some((video.width, video.height));
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use miniter_domain::clip::{Clip, ClipId, ClipKind, VideoClip};
    use miniter_domain::time::MediaDuration;
    use miniter_domain::track::{Track, TrackKind};
    use uuid::Uuid;

    fn video_clip(start_us: i64, dur_us: i64) -> Clip {
        Clip {
            id: ClipId(Uuid::new_v4()),
            timeline_start: Timestamp::from_micros(start_us),
            timeline_duration: MediaDuration::from_micros(dur_us),
            source_start: MediaDuration::ZERO,
            source_end: MediaDuration::from_micros(dur_us),
            source_total_duration: MediaDuration::from_micros(dur_us),
            speed: 1.0,
            volume: 1.0,
            opacity: 1.0,
            muted: false,
            transition_in: None,
            transition_out: None,
            kind: ClipKind::Video(VideoClip {
                source_path: "/tmp/a.mp4".into(),
                width: 1920,
                height: 1080,
                fps: 30.0,
                filters: vec![],
                audio_filters: vec![],
                masks: vec![],
            }),
            keyframes: Default::default(),
            blend_mode: Default::default(),
        }
    }

    fn timeline_ending_at(end_us: i64) -> Timeline {
        let mut track = Track::new(TrackKind::Video, "V1");
        track
            .insert_clip(video_clip(0, end_us))
            .expect("fixture clip inserts");
        Timeline {
            tracks: vec![track],
        }
    }

    #[test]
    fn frame_enumeration_is_exact() {
        for (fps, expected) in [(24.0, 240), (30.0, 300)] {
            let timeline = timeline_ending_at(10_000_000);
            let iter = FramePlanIterator::with_render_settings(
                &timeline,
                1920,
                1080,
                fps,
                SubtitleMode::Soft,
            );
            assert_eq!(iter.total_frames(), expected, "fps {fps}");
        }
        let timeline = timeline_ending_at(10_000_000);
        let mut iter = FramePlanIterator::with_render_settings(
            &timeline,
            1920,
            1080,
            24_000.0 / 1001.0,
            SubtitleMode::Soft,
        );
        assert_eq!(iter.total_frames(), 240);
        let _ = iter.next().unwrap();
        assert_eq!(iter.next().unwrap().timestamp.as_micros(), 41_708);
        assert_eq!(
            FramePlanIterator::with_render_settings(
                &Timeline::new(),
                1920,
                1080,
                24.0,
                SubtitleMode::Soft
            )
            .total_frames(),
            0
        );
    }

    #[test]
    fn long_exports_do_not_drift() {
        let timeline = timeline_ending_at(3_600_000_000);
        let mut iter = FramePlanIterator::with_render_settings(
            &timeline,
            1920,
            1080,
            24.0,
            SubtitleMode::Soft,
        );
        assert_eq!(iter.total_frames(), 86_400);
        let last = iter.nth(86_400 - 1).expect("last frame plans");
        assert_eq!(last.timestamp.as_micros(), 3_599_958_333);
    }

    #[test]
    fn plans_are_deterministic_across_runs() {
        let timeline = timeline_ending_at(5_000_000);
        let a = plan_frame(
            &timeline,
            Timestamp::from_micros(1_234_567),
            1920,
            1080,
            SubtitleMode::Soft,
        );
        let b = plan_frame(
            &timeline,
            Timestamp::from_micros(1_234_567),
            1920,
            1080,
            SubtitleMode::Soft,
        );
        assert_eq!(format!("{a:?}"), format!("{b:?}"));
        assert!(crate::validate::validate_frame_plan(&a).is_empty());
    }
}
