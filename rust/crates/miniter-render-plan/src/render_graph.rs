use crate::transition_blend::opacity_pair;
use miniter_domain::clip::{Clip, ClipId, ClipKind};
use miniter_domain::ease_in_out;
use miniter_domain::export::SubtitleMode;
use miniter_domain::filter::{VideoEffect, VideoFilter};
use miniter_domain::keyframe::KeyframeCurve;
use miniter_domain::mask::{
    BlendMode, MaskComposition, MaskEffect, MaskOperation, MaskShape, MaskSource, MaskTransform,
};
use miniter_domain::param;
use miniter_domain::text_overlay::TextOverlay;
use miniter_domain::time::{MediaDuration, Timestamp};
use miniter_domain::timeline::Timeline;
use miniter_domain::transition::{Transition, TransitionKind};

#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "type")]
pub enum RenderNode {
    VideoFrame {
        clip_id: ClipId,
        source_path: String,
        source_pts: Timestamp,
        filters: Vec<VideoFilter>,
        opacity: f32,
        #[serde(default)]
        blend_mode: BlendMode,
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
    Subtitle {
        source_path: String,
        source_pts: Timestamp,
        opacity: f32,
        font_path: Option<String>,
    },
    Stack(Vec<RenderNode>),
    Masked {
        source: Box<RenderNode>,
        mask_source: MaskSource,
        operation: MaskOperation,
        composition: MaskComposition,
        transform: MaskTransform,
    },
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct RenderPlan {
    pub timestamp: Timestamp,
    pub width: u32,
    pub height: u32,
    pub root: RenderNode,
}

pub fn plan_frame(
    timeline: &Timeline,
    t: Timestamp,
    width: u32,
    height: u32,
    subtitle_mode: SubtitleMode,
) -> RenderPlan {
    let mut layers: Vec<RenderNode> = Vec::new();

    for track in &timeline.tracks {
        if track.muted {
            continue;
        }
        if let Some(clip) = track.clip_at(t)
            && let Some(node) = node_for_clip(clip, t, track, subtitle_mode, timeline)
        {
            layers.push(node);
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

pub fn node_blend_mode(node: &RenderNode) -> BlendMode {
    match node {
        RenderNode::VideoFrame { blend_mode, .. } => *blend_mode,
        _ => BlendMode::Normal,
    }
}

fn apply_mask_transform_keyframes(
    mask: &mut MaskEffect,
    curve: &KeyframeCurve,
    local_time: MediaDuration,
) {
    if let Some(feather) = curve.evaluate(param::MASK_FEATHER, local_time) {
        if let MaskSource::Shape { feather: f, .. } = &mut mask.source {
            *f = feather.max(0.0);
        }
    }
    let scale = curve.evaluate(param::MASK_SCALE, local_time);
    let tx = curve.evaluate(param::MASK_TRANSLATE_X, local_time);
    let ty = curve.evaluate(param::MASK_TRANSLATE_Y, local_time);
    let rotate = curve.evaluate(param::MASK_ROTATE, local_time);

    if let Some(v) = scale {
        mask.transform.scale = v;
    }
    if let Some(v) = tx {
        mask.transform.translate_x = v;
    }
    if let Some(v) = ty {
        mask.transform.translate_y = v;
    }
    if let Some(v) = rotate {
        mask.transform.rotate = v;
    }

    if let MaskSource::Shape { shape, .. } = &mut mask.source {
        let left = curve.evaluate(param::MASK_SHAPE_LEFT, local_time);
        let top = curve.evaluate(param::MASK_SHAPE_TOP, local_time);
        let right = curve.evaluate(param::MASK_SHAPE_RIGHT, local_time);
        let bottom = curve.evaluate(param::MASK_SHAPE_BOTTOM, local_time);
        let cx = curve.evaluate(param::MASK_SHAPE_CENTER_X, local_time);
        let cy = curve.evaluate(param::MASK_SHAPE_CENTER_Y, local_time);
        let rx = curve.evaluate(param::MASK_SHAPE_RADIUS_X, local_time);
        let ry = curve.evaluate(param::MASK_SHAPE_RADIUS_Y, local_time);

        match shape {
            MaskShape::Rectangle {
                left: l,
                top: t,
                right: r,
                bottom: b,
            } => {
                if let Some(v) = left {
                    *l = v;
                }
                if let Some(v) = top {
                    *t = v;
                }
                if let Some(v) = right {
                    *r = v;
                }
                if let Some(v) = bottom {
                    *b = v;
                }
            }
            MaskShape::Ellipse {
                center_x,
                center_y,
                radius_x,
                radius_y,
            } => {
                if let Some(v) = cx {
                    *center_x = v;
                }
                if let Some(v) = cy {
                    *center_y = v;
                }
                if let Some(v) = rx {
                    *radius_x = v;
                }
                if let Some(v) = ry {
                    *radius_y = v;
                }
            }
            _ => {}
        }
    }
}

fn clip_opacity_at(clip: &Clip, local_time: MediaDuration) -> f32 {
    clip.keyframes
        .evaluate(param::OPACITY, local_time)
        .unwrap_or(clip.opacity)
}

fn apply_transform_keyframes(
    filters: &mut Vec<VideoFilter>,
    curve: &KeyframeCurve,
    local_time: MediaDuration,
) {
    let scale = curve.evaluate(param::TRANSFORM_SCALE, local_time);
    let tx = curve.evaluate(param::TRANSFORM_TRANSLATE_X, local_time);
    let ty = curve.evaluate(param::TRANSFORM_TRANSLATE_Y, local_time);
    let rotate = curve.evaluate(param::TRANSFORM_ROTATE, local_time);

    if scale.is_none() && tx.is_none() && ty.is_none() && rotate.is_none() {
        return;
    }

    let scale = scale.unwrap_or(1.0);
    let tx = tx.unwrap_or(0.0);
    let ty = ty.unwrap_or(0.0);
    let rotate = rotate.unwrap_or(0.0);

    for filter in filters.iter_mut() {
        if let VideoFilter::Transform {
            scale: s,
            translate_x: t_x,
            translate_y: t_y,
            rotate: r,
        } = filter
        {
            *s = scale;
            *t_x = tx;
            *t_y = ty;
            *r = rotate;
            return;
        }
    }
    filters.push(VideoFilter::Transform {
        scale,
        translate_x: tx,
        translate_y: ty,
        rotate,
    });
}

fn node_for_clip(
    clip: &Clip,
    t: Timestamp,
    track: &miniter_domain::track::Track,
    subtitle_mode: SubtitleMode,
    _timeline: &Timeline,
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
            let opacity = clip_opacity_at(clip, local_offset);
            let mut filters = active_filters(&v.filters);
            apply_transform_keyframes(&mut filters, &clip.keyframes, local_offset);
            let mut base_node = RenderNode::VideoFrame {
                clip_id: clip.id,
                source_path: v.source_path.clone(),
                source_pts,
                filters,
                opacity,
                blend_mode: clip.blend_mode,
            };

            let active_masks: Vec<&MaskEffect> = v.masks.iter().filter(|m| m.enabled).collect();
            if !active_masks.is_empty() {
                for mask_effect in active_masks {
                    let mut mask = mask_effect.clone();
                    apply_mask_transform_keyframes(&mut mask, &clip.keyframes, local_offset);

                    base_node = RenderNode::Masked {
                        source: Box::new(base_node),
                        mask_source: mask.source,
                        operation: mask.operation,
                        composition: mask.composition,
                        transform: mask.transform,
                    };
                }
            }

            if let Some(ref trans) = clip.transition_in
                && let Some(prev) = find_previous_clip(track, clip)
            {
                let progress = transition_progress(clip, trans, t);
                let prev_pts = Timestamp::from_micros(
                    prev.source_start.as_micros()
                        + ((t - prev.timeline_start).as_micros() as f64 * prev.speed) as i64,
                );
                if let ClipKind::Video(pv) = &prev.kind {
                    let prev_opacity = clip_opacity_at(prev, t - prev.timeline_start);
                    let prev_node = RenderNode::VideoFrame {
                        clip_id: prev.id,
                        source_path: pv.source_path.clone(),
                        source_pts: prev_pts,
                        filters: active_filters(&pv.filters),
                        opacity: prev_opacity,
                        blend_mode: prev.blend_mode,
                    };
                    return Some(RenderNode::TransitionBlend {
                        bottom: Box::new(prev_node),
                        top: Box::new(base_node),
                        kind: trans.kind,
                        progress,
                    });
                }
            }

            if let Some(ref trans) = clip.transition_out {
                let out_progress = transition_out_progress(clip, trans, t);
                if out_progress < 1.0
                    && let Some(next) = find_next_clip(track, clip)
                        && let ClipKind::Video(nv) = &next.kind
                {
                    let clip_end = clip.timeline_end();
                    let fade_start = Timestamp::from_micros(
                        clip_end.as_micros() - trans.duration.as_micros(),
                    );
                    let offset = (t - fade_start).as_micros();
                    let next_t =
                        Timestamp::from_micros(next.timeline_start.as_micros() + offset);

                    let next_pts = Timestamp::from_micros(
                        next.source_start.as_micros()
                            + ((next_t - next.timeline_start).as_micros() as f64 * next.speed)
                                as i64,
                    );
                    let next_opacity = clip_opacity_at(next, next_t - next.timeline_start);
                    let next_node = RenderNode::VideoFrame {
                        clip_id: next.id,
                        source_path: nv.source_path.clone(),
                        source_pts: next_pts,
                        filters: active_filters(&nv.filters),
                        opacity: next_opacity,
                        blend_mode: next.blend_mode,
                    };
                    return Some(RenderNode::TransitionBlend {
                        bottom: Box::new(base_node),
                        top: Box::new(next_node),
                        kind: trans.kind,
                        progress: out_progress,
                    });
                }
            }
            Some(base_node)
        }
        ClipKind::Audio(_) => None,
        ClipKind::Text(overlay) => {
            let mut opacity = clip_opacity_at(clip, local_offset);
            let mut modified = overlay.clone();
            if let Some(x) = clip
                .keyframes
                .evaluate(param::TEXT_POSITION_X, local_offset)
            {
                modified.style.position_x = x;
            }
            if let Some(y) = clip
                .keyframes
                .evaluate(param::TEXT_POSITION_Y, local_offset)
            {
                modified.style.position_y = y;
            }
            if let Some(fs) = clip.keyframes.evaluate(param::TEXT_FONT_SIZE, local_offset) {
                modified.style.font_size = fs;
            }
            apply_opacity_from_clip_transitions(clip, t, &mut opacity);
            Some(RenderNode::Text {
                overlay: modified,
                opacity,
            })
        }
        ClipKind::Subtitle(sub) => {
            let mut opacity = clip_opacity_at(clip, local_offset);
            apply_opacity_from_clip_transitions(clip, t, &mut opacity);
            match subtitle_mode {
                SubtitleMode::Hard => Some(RenderNode::Subtitle {
                    source_path: sub.source_path.clone(),
                    source_pts,
                    opacity,
                    font_path: sub.font_path.clone(),
                }),
                SubtitleMode::Soft => None,
                _ => None,
            }
        }
        _ => None,
    }
}

fn apply_opacity_from_clip_transitions(clip: &Clip, t: Timestamp, opacity: &mut f32) {
    if let Some(ref trans) = clip.transition_in {
        let progress = transition_progress(clip, trans, t);
        if progress < 1.0 {
            let eased = ease_in_out(progress);
            let (_, text_a) = opacity_pair(trans.kind, eased);
            *opacity *= text_a;
        }
    }

    if let Some(ref trans) = clip.transition_out {
        let out_progress = transition_out_progress(clip, trans, t);
        if out_progress < 1.0 {
            let eased = ease_in_out(out_progress);
            let (fade_a, _) = opacity_pair(trans.kind, eased);
            *opacity *= fade_a;
        }
    }
}

fn active_filters(filters: &[VideoEffect]) -> Vec<VideoFilter> {
    filters
        .iter()
        .filter(|fx| fx.enabled)
        .map(|fx| fx.filter.clone())
        .collect()
}

fn find_next_clip<'a>(track: &'a miniter_domain::track::Track, clip: &Clip) -> Option<&'a Clip> {
    let idx = track.clip_index(clip.id)?;
    if idx + 1 < track.clips.len() {
        Some(&track.clips[idx + 1])
    } else {
        None
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
    let dur = trans.duration.as_micros().min(clip_end.as_micros());
    let fade_start_us = clip_end.as_micros() - dur;
    let fade_start = Timestamp::from_micros(fade_start_us);
    let elapsed = (t - fade_start).as_micros() as f64;
    let total = dur as f64;
    if total <= 0.0 {
        1.0
    } else {
        (elapsed / total).clamp(0.0, 1.0) as f32
    }
}
