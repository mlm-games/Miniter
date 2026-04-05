use crate::transition_blend::{ease_in_out, opacity_pair};
use miniter_domain::clip::{Clip, ClipId, ClipKind};
use miniter_domain::filter::VideoFilter;
use miniter_domain::text_overlay::TextOverlay;
use miniter_domain::time::Timestamp;
use miniter_domain::timeline::Timeline;
use miniter_domain::transition::{Transition, TransitionKind};

#[derive(Debug, Clone)]
pub enum RenderNode {
    VideoFrame {
        clip_id: ClipId,
        source_path: String,
        source_pts: Timestamp,
        filters: Vec<VideoFilter>,
        opacity: f32,
    },
    TransitionBlend {
        bottom: Box<RenderNode>,
        top: Box<RenderNode>,
        kind: TransitionKind,
        progress: f32,
    },
    Text {
        overlay: TextOverlay,
        opacity: f32,
    },
    Stack(Vec<RenderNode>),
}

#[derive(Debug, Clone)]
pub struct RenderPlan {
    pub timestamp: Timestamp,
    pub width: u32,
    pub height: u32,
    pub root: RenderNode,
}

pub fn plan_frame(timeline: &Timeline, t: Timestamp, width: u32, height: u32) -> RenderPlan {
    let mut layers: Vec<RenderNode> = Vec::new();

    for track in &timeline.tracks {
        if track.muted {
            continue;
        }
        if let Some(clip) = track.clip_at(t) {
            if let Some(node) = node_for_clip(clip, t, track) {
                layers.push(node);
            }
        }
    }

    let root = if layers.len() == 1 {
        layers.remove(0)
    } else {
        RenderNode::Stack(layers)
    };

    RenderPlan {
        timestamp: t,
        width,
        height,
        root,
    }
}

fn node_for_clip(
    clip: &Clip,
    t: Timestamp,
    track: &miniter_domain::track::Track,
) -> Option<RenderNode> {
    if clip.muted {
        return None;
    }

    let local_offset = t - clip.timeline_start;
    let source_pts = Timestamp::from_micros(
        clip.source_start.as_micros() + (local_offset.as_micros() as f64 * clip.speed) as i64,
    );

    match &clip.kind {
        ClipKind::Video(v) => {
            let base = RenderNode::VideoFrame {
                clip_id: clip.id,
                source_path: v.source_path.clone(),
                source_pts,
                filters: v.filters.clone(),
                opacity: clip.opacity,
            };

            if let Some(ref trans) = clip.transition_in {
                if let Some(prev) = find_previous_clip(track, clip) {
                    let progress = transition_progress(clip, trans, t);
                    let prev_pts = Timestamp::from_micros(
                        prev.source_start.as_micros()
                            + ((t - prev.timeline_start).as_micros() as f64 * prev.speed) as i64,
                    );
                    if let ClipKind::Video(pv) = &prev.kind {
                        let prev_node = RenderNode::VideoFrame {
                            clip_id: prev.id,
                            source_path: pv.source_path.clone(),
                            source_pts: prev_pts,
                            filters: pv.filters.clone(),
                            opacity: prev.opacity,
                        };
                        return Some(RenderNode::TransitionBlend {
                            bottom: Box::new(prev_node),
                            top: Box::new(base),
                            kind: trans.kind,
                            progress,
                        });
                    }
                }
            }
            Some(base)
        }
        ClipKind::Audio(_) => None,
        ClipKind::Text(overlay) => {
            let mut opacity = clip.opacity;

            if let Some(ref trans) = clip.transition_in {
                let progress = transition_progress(clip, trans, t);
                if progress < 1.0 {
                    let eased = ease_in_out(progress);
                    let (_, text_a) = opacity_pair(trans.kind, eased);
                    opacity *= text_a;
                }
                if let Some(prev) = find_previous_clip(track, clip) {
                    if progress < 1.0 {
                        if let Some(prev_node) = node_for_clip(prev, t, track) {
                            return Some(RenderNode::TransitionBlend {
                                bottom: Box::new(prev_node),
                                top: Box::new(RenderNode::Text {
                                    overlay: overlay.clone(),
                                    opacity,
                                }),
                                kind: trans.kind,
                                progress,
                            });
                        }
                    }
                }
            }

            if let Some(ref trans) = clip.transition_out {
                let out_progress = transition_out_progress(clip, trans, t);
                if out_progress < 1.0 {
                    let eased = ease_in_out(out_progress);
                    let (fade_a, _) = opacity_pair(trans.kind, eased);
                    opacity *= fade_a;
                }
            }

            Some(RenderNode::Text {
                overlay: overlay.clone(),
                opacity,
            })
        }
    }
}

fn find_previous_clip<'a>(
    track: &'a miniter_domain::track::Track,
    clip: &Clip,
) -> Option<&'a Clip> {
    let idx = track.clip_index(clip.id)?;
    if idx > 0 {
        Some(&track.clips[idx - 1])
    } else {
        None
    }
}

fn transition_progress(clip: &Clip, trans: &Transition, t: Timestamp) -> f32 {
    let elapsed = (t - clip.timeline_start).as_micros() as f64;
    let total = trans.duration.as_micros() as f64;
    if total <= 0.0 {
        1.0
    } else {
        (elapsed / total).clamp(0.0, 1.0) as f32
    }
}

fn transition_out_progress(clip: &Clip, trans: &Transition, t: Timestamp) -> f32 {
    let clip_end = clip.timeline_end();
    let fade_start_us = clip_end.as_micros() - trans.duration.as_micros();
    let fade_start = Timestamp::from_micros(fade_start_us);
    let elapsed = (t - fade_start).as_micros() as f64;
    let total = trans.duration.as_micros() as f64;
    if total <= 0.0 {
        1.0
    } else {
        (elapsed / total).clamp(0.0, 1.0) as f32
    }
}
