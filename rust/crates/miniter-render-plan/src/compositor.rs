use crate::render_graph::{plan_frame, RenderPlan};
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
    pub fn new(timeline: &'a Timeline, profile: &ExportProfile) -> Self {
        let (w, h) = profile.resolution.dimensions();
        let frame_dur = (1_000_000.0 / profile.fps) as i64;
        let end = timeline.duration_end().as_micros();

        let total = if end <= 0 || frame_dur <= 0 {
            0
        } else {
            ((end + frame_dur - 1) / frame_dur) as u64
        };

        Self {
            timeline,
            width: w,
            height: h,
            frame_duration_us: frame_dur,
            current_frame: 0,
            total_frames: total,
        }
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
