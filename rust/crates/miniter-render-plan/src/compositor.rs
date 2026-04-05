use crate::render_graph::{RenderPlan, plan_frame};
use miniter_domain::clip::ClipKind;
use miniter_domain::export::ExportProfile;
use miniter_domain::time::Timestamp;
use miniter_domain::timeline::Timeline;

pub struct FramePlanIterator<'a> {
    timeline: &'a Timeline,
    width: u32,
    height: u32,
    frame_duration_us: i64,
    current_frame: u64,
    total_frames: u64,
}

impl<'a> FramePlanIterator<'a> {
    pub fn with_render_settings(timeline: &'a Timeline, width: u32, height: u32, fps: f64) -> Self {
        let frame_dur = frame_duration_us(fps);
        let end = timeline.duration_end().as_micros().max(0);

        let total = if end == 0 {
            0
        } else {
            ((end + frame_dur - 1) / frame_dur) as u64
        };

        Self {
            timeline,
            width,
            height,
            frame_duration_us: frame_dur,
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
        Self::with_render_settings(timeline, resolved_w, resolved_h, profile.fps)
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
        let t = Timestamp::from_micros(self.current_frame as i64 * self.frame_duration_us);
        self.current_frame += 1;
        Some(plan_frame(self.timeline, t, self.width, self.height))
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

fn frame_duration_us(fps: f64) -> i64 {
    let safe_fps = if fps.is_finite() && fps > 0.0 {
        fps
    } else {
        30.0
    };
    (1_000_000.0 / safe_fps).round().max(1.0) as i64
}

fn first_video_dimensions(timeline: &Timeline) -> Option<(u32, u32)> {
    for track in &timeline.tracks {
        for clip in &track.clips {
            if let ClipKind::Video(video) = &clip.kind {
                if video.width > 0 && video.height > 0 {
                    return Some((video.width, video.height));
                }
            }
        }
    }
    None
}
