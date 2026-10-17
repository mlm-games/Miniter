use crate::transition_blend::{
    opacity_pair, slide_exit_offset, slide_exit_offset_y, slide_offset, slide_offset_y,
    transition_scales,
};
use miniter_domain::clip::{Clip, ClipId, ClipKind};
use miniter_domain::ease_in_out;
use miniter_domain::export::SubtitleMode;
use miniter_domain::filter::{VideoEffect, VideoFilter};
use miniter_domain::keyframe::KeyframeCurve;
use miniter_domain::mask::{
    BlendMode, MaskComposition, MaskEffect, MaskOperation, MaskShape, MaskSource, MaskTransform,
};
use miniter_domain::param;
use miniter_domain::text_overlay::{TextOverlay, TextStyle};
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
    if let Some(feather) = curve.evaluate(param::MASK_FEATHER, local_time)
        && let MaskSource::Shape { feather: f, .. } = &mut mask.source
    {
        *f = feather.max(0.0);
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

fn parse_filter_param_key(param: &str) -> Option<(usize, &str)> {
    let rest = param.strip_prefix("filter.")?;
    let (index, suffix) = rest.split_once('.')?;
    Some((index.parse().ok()?, suffix))
}

fn filter_param_mut<'a>(filter: &'a mut VideoFilter, suffix: &str) -> Option<&'a mut f32> {
    match filter {
        VideoFilter::Transform {
            scale,
            translate_x,
            translate_y,
            rotate,
        } => match suffix {
            "scale" => Some(scale),
            "translate_x" => Some(translate_x),
            "translate_y" => Some(translate_y),
            "rotate" => Some(rotate),
            _ => None,
        },
        VideoFilter::Brightness { value } => (suffix == "brightness").then_some(value),
        VideoFilter::Contrast { value } => (suffix == "contrast").then_some(value),
        VideoFilter::Saturation { value } => (suffix == "saturation").then_some(value),
        VideoFilter::Hue { degrees } => (suffix == "hue_deg").then_some(degrees),
        VideoFilter::Rotate { degrees } => (suffix == "rotate_deg").then_some(degrees),
        VideoFilter::Sharpen { amount } => (suffix == "sharpen_amount").then_some(amount),
        VideoFilter::Blur { radius, angle, .. } => match suffix {
            "blur_radius" => Some(radius),
            "blur_angle" => Some(angle),
            _ => None,
        },
        VideoFilter::Mosaic { size, .. } => (suffix == "mosaic_size").then_some(size),
        VideoFilter::Crop {
            left,
            top,
            right,
            bottom,
        } => match suffix {
            "crop_left" => Some(left),
            "crop_top" => Some(top),
            "crop_right" => Some(right),
            "crop_bottom" => Some(bottom),
            _ => None,
        },
        VideoFilter::CanvasBackground { blur_radius, .. } => {
            (suffix == "canvas_blur").then_some(blur_radius)
        }
        VideoFilter::ColorGrade {
            brightness,
            contrast,
            saturation,
            vibrance,
            temperature,
            tint,
            exposure,
            gamma,
            lift,
            gain,
            highlights,
            shadows,
        } => match suffix {
            "grade_brightness" => Some(brightness),
            "grade_contrast" => Some(contrast),
            "grade_saturation" => Some(saturation),
            "grade_vibrance" => Some(vibrance),
            "grade_temperature" => Some(temperature),
            "grade_tint" => Some(tint),
            "grade_exposure" => Some(exposure),
            "grade_gamma" => Some(gamma),
            "grade_lift" => Some(lift),
            "grade_gain" => Some(gain),
            "grade_highlights" => Some(highlights),
            "grade_shadows" => Some(shadows),
            _ => None,
        },
        VideoFilter::ChromaKey {
            tolerance,
            softness,
            spill,
            ..
        } => match suffix {
            "chroma_tolerance" => Some(tolerance),
            "chroma_softness" => Some(softness),
            "chroma_spill" => Some(spill),
            _ => None,
        },
        VideoFilter::Lut { strength, .. } => (suffix == "lut_strength").then_some(strength),
        VideoFilter::Vignette {
            amount,
            radius,
            softness,
        } => match suffix {
            "vignette_amount" => Some(amount),
            "vignette_radius" => Some(radius),
            "vignette_softness" => Some(softness),
            _ => None,
        },
        VideoFilter::Posterize { levels } => (suffix == "posterize_levels").then_some(levels),
        VideoFilter::Grain { amount, size } => match suffix {
            "grain_amount" => Some(amount),
            "grain_size" => Some(size),
            _ => None,
        },
        _ => None,
    }
}

fn apply_filter_param_keyframes(
    filters: &mut [VideoFilter],
    source: &[VideoEffect],
    curve: &KeyframeCurve,
    local_time: MediaDuration,
) {
    if filters.is_empty() || curve.keyframes.is_empty() {
        return;
    }

    let mut applied: Vec<(usize, String)> = Vec::new();
    for keyframe in &curve.keyframes {
        let Some((index, suffix)) = parse_filter_param_key(&keyframe.param) else {
            continue;
        };
        // Indices in `filter.<index>.<suffix>` address the clip's full filter
        // list, matching the UI, while `filters` holds only the enabled ones.
        let Some(position) = active_position(source, index) else {
            continue;
        };
        if applied
            .iter()
            .any(|(seen_index, seen_suffix)| *seen_index == index && seen_suffix.as_str() == suffix)
        {
            continue;
        }
        let Some(value) = curve.evaluate(&keyframe.param, local_time) else {
            continue;
        };
        if let Some(slot) = filter_param_mut(&mut filters[position], suffix) {
            *slot = value;
        }
        applied.push((index, suffix.to_string()));
    }
}

fn active_position(source: &[VideoEffect], index: usize) -> Option<usize> {
    let effect = source.get(index)?;
    if !effect.enabled {
        return None;
    }
    Some(source.iter().take(index).filter(|fx| fx.enabled).count())
}

fn node_for_clip(
    clip: &Clip,
    t: Timestamp,
    track: &miniter_domain::track::Track,
    subtitle_mode: SubtitleMode,
    _timeline: &Timeline,
) -> Option<RenderNode> {
    // `muted` silences a clip's audio. Returning early here dropped a muted
    // *video* clip from the picture too, leaving a hole in the render. Only
    // clips with no visual content are skipped.
    if clip.muted && !matches!(clip.kind, ClipKind::Video(_)) {
        return None;
    }

    let local_offset = t - clip.timeline_start;
    let source_pts =
        Timestamp::from_micros(clip.source_start.as_micros() + clip.source_offset_at(local_offset));

    match &clip.kind {
        ClipKind::Video(v) => {
            let opacity = clip_opacity_at(clip, local_offset);
            let mut filters = active_filters(&v.filters);
            apply_transform_keyframes(&mut filters, &clip.keyframes, local_offset);
            apply_filter_param_keyframes(&mut filters, &v.filters, &clip.keyframes, local_offset);
            let source_pts = if filters.iter().any(|f| matches!(f, VideoFilter::Reverse)) {
                mirror_source_pts(clip, source_pts)
            } else {
                source_pts
            };
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
                const ADJACENCY_EPSILON_US: i64 = 2_000;
                let adjacent = (prev.timeline_end().as_micros() - clip.timeline_start.as_micros())
                    .abs()
                    <= ADJACENCY_EPSILON_US;
                let progress = transition_progress(clip, trans, t);
                if !adjacent {
                    let eased = ease_in_out(progress.clamp(0.0, 1.0));
                    let (_, top_a) = opacity_pair(trans.kind, eased);
                    let mut faded = base_node.clone();
                    scale_node_opacity(&mut faded, top_a);
                    return Some(faded);
                }
                let prev_pts = Timestamp::from_micros(
                    prev.source_start.as_micros() + prev.source_offset_at(t - prev.timeline_start),
                );
                if let ClipKind::Video(pv) = &prev.kind {
                    let prev_opacity = clip_opacity_at(prev, t - prev.timeline_start);
                    let prev_filters = active_filters(&pv.filters);
                    let prev_pts = if is_reversed(&prev_filters) {
                        mirror_source_pts(prev, prev_pts)
                    } else {
                        prev_pts
                    };
                    let prev_node = RenderNode::VideoFrame {
                        clip_id: prev.id,
                        source_path: pv.source_path.clone(),
                        source_pts: prev_pts,
                        filters: prev_filters,
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
                let clip_end = clip.timeline_end();
                let fade_start_us = clip_end.as_micros()
                    - trans.duration.as_micros().min(clip_end.as_micros()).max(0);
                let in_window = t.as_micros() >= fade_start_us;
                if in_window
                    && out_progress < 1.0
                    && let Some(next) = find_next_clip(track, clip)
                    && let ClipKind::Video(nv) = &next.kind
                {
                    // Without this the fade samples the following clip at
                    // timestamps that fall in a gap between the two, or past
                    // its own end, reading frames the source does not have.
                    const ADJACENCY_EPSILON_US: i64 = 2_000;
                    let adjacent =
                        (clip.timeline_end().as_micros() - next.timeline_start.as_micros()).abs()
                            <= ADJACENCY_EPSILON_US;
                    let fade_start = Timestamp::from_micros(fade_start_us);
                    let offset = (t - fade_start).as_micros();
                    let next_t = Timestamp::from_micros(next.timeline_start.as_micros() + offset);
                    // `continue` is unavailable here, so the guard folds into
                    // the branch condition instead.
                    if adjacent && next_t < next.timeline_end() {
                        let next_pts = Timestamp::from_micros(
                            next.source_start.as_micros()
                                + next.source_offset_at(next_t - next.timeline_start),
                        );
                        let next_opacity = clip_opacity_at(next, next_t - next.timeline_start);
                        let next_filters = active_filters(&nv.filters);
                        let next_pts = if is_reversed(&next_filters) {
                            mirror_source_pts(next, next_pts)
                        } else {
                            next_pts
                        };
                        let next_node = RenderNode::VideoFrame {
                            clip_id: next.id,
                            source_path: nv.source_path.clone(),
                            source_pts: next_pts,
                            filters: next_filters,
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
            apply_motion_from_clip_transitions(clip, t, &mut modified.style);
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

fn apply_motion_from_clip_transitions(clip: &Clip, t: Timestamp, style: &mut TextStyle) {
    if let Some(ref trans) = clip.transition_in {
        let progress = transition_progress(clip, trans, t);
        if progress < 1.0 {
            let eased = ease_in_out(progress);
            let (_, scale) = transition_scales(trans.kind, eased);
            apply_text_motion(
                style,
                slide_offset(trans.kind, eased),
                slide_offset_y(trans.kind, eased),
                scale,
            );
        }
    }

    if let Some(ref trans) = clip.transition_out {
        let out_progress = transition_out_progress(clip, trans, t);
        if out_progress < 1.0 {
            let eased = ease_in_out(out_progress);
            let (scale, _) = transition_scales(trans.kind, eased);
            apply_text_motion(
                style,
                slide_exit_offset(trans.kind, eased),
                slide_exit_offset_y(trans.kind, eased),
                scale,
            );
        }
    }
}

fn apply_text_motion(style: &mut TextStyle, dx: f32, dy: f32, scale: f32) {
    if dx != 0.0 || dy != 0.0 {
        style.position_x += dx;
        style.position_y += dy;
    }
    if scale != 1.0 && scale > 0.0 {
        style.font_size = (style.font_size * scale).max(1.0);
    }
}

fn active_filters(filters: &[VideoEffect]) -> Vec<VideoFilter> {
    filters
        .iter()
        .filter(|fx| fx.enabled)
        .map(|fx| fx.filter.clone())
        .collect()
}

/// Mirror a source timestamp for reversed playback: the first timeline
/// microsecond maps to the last source microsecond of the clip range.
fn mirror_source_pts(clip: &Clip, forward_pts: Timestamp) -> Timestamp {
    let start = clip.source_start.as_micros();
    let end = clip.source_end.as_micros().max(start + 1);
    Timestamp::from_micros((start + (end - forward_pts.as_micros())).max(start))
}

fn is_reversed(filters: &[VideoFilter]) -> bool {
    filters.iter().any(|f| matches!(f, VideoFilter::Reverse))
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
fn scale_node_opacity(node: &mut RenderNode, factor: f32) {
    match node {
        RenderNode::VideoFrame { opacity, .. }
        | RenderNode::Text { opacity, .. }
        | RenderNode::Subtitle { opacity, .. } => {
            *opacity = (*opacity * factor).clamp(0.0, 1.0);
        }
        RenderNode::TransitionBlend { bottom, top, .. } => {
            scale_node_opacity(bottom, factor);
            scale_node_opacity(top, factor);
        }
        RenderNode::Stack(children) => {
            for child in children {
                scale_node_opacity(child, factor);
            }
        }
        RenderNode::Masked { source, .. } => {
            scale_node_opacity(source, factor);
        }
    }
}

#[cfg(test)]
mod reverse_plan_tests {
    use super::*;
    use miniter_domain::clip::{Clip, ClipId, ClipKind, VideoClip};
    use miniter_domain::filter::VideoEffect;
    use miniter_domain::time::MediaDuration;
    use miniter_domain::track::{Track, TrackKind};
    use uuid::Uuid;

    fn reversed_clip() -> (Timeline, ClipId) {
        let mut track = Track::new(TrackKind::Video, "V1");
        let clip = Clip {
            id: ClipId(Uuid::new_v4()),
            timeline_start: Timestamp::from_micros(0),
            timeline_duration: MediaDuration::from_micros(4_000_000),
            source_start: MediaDuration::from_micros(1_000_000),
            source_end: MediaDuration::from_micros(5_000_000),
            source_total_duration: MediaDuration::from_micros(10_000_000),
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
                filters: vec![VideoEffect::new(VideoFilter::Reverse)],
                audio_filters: vec![],
                masks: vec![],
            }),
            keyframes: Default::default(),
            blend_mode: Default::default(),
        };
        let id = clip.id;
        track.insert_clip(clip).unwrap();
        (
            Timeline {
                tracks: vec![track],
            },
            id,
        )
    }

    #[test]
    fn reversed_clip_mirrors_source_pts() {
        use miniter_domain::export::SubtitleMode;
        let (timeline, _) = reversed_clip();
        for (t_us, expect_us) in [
            (0, 5_000_000),
            (2_000_000, 3_000_000),
            (3_999_999, 1_000_001),
        ] {
            let plan = plan_frame(
                &timeline,
                Timestamp::from_micros(t_us),
                1920,
                1080,
                SubtitleMode::Soft,
            );
            match plan.root {
                RenderNode::VideoFrame { source_pts, .. } => {
                    assert_eq!(
                        source_pts.as_micros(),
                        expect_us,
                        "t={t_us}: reversed pts must mirror"
                    );
                }
                other => panic!("expected VideoFrame, got {other:?}"),
            }
        }
    }

    #[test]
    fn mirror_clamps_degenerate_ranges() {
        let (timeline, _) = reversed_clip();
        let clip = &timeline.tracks[0].clips[0];
        let pts = mirror_source_pts(clip, Timestamp::from_micros(99_000_000));
        assert!(pts.as_micros() >= 1_000_000);
    }
}

#[cfg(test)]
mod transition_and_mute_tests {
    use super::*;
    use miniter_domain::clip::{Clip, ClipId, ClipKind, VideoClip};
    use miniter_domain::export::SubtitleMode;
    use miniter_domain::time::MediaDuration;
    use miniter_domain::track::{Track, TrackKind};
    use miniter_domain::transition::{Transition, TransitionKind};
    use uuid::Uuid;

    fn video_clip(start_us: i64, dur_us: i64, out: Option<Transition>, muted: bool) -> Clip {
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
            muted,
            transition_in: None,
            transition_out: out,
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

    fn timeline_of(clips: Vec<Clip>) -> Timeline {
        let mut track = Track::new(TrackKind::Video, "V1");
        for c in clips {
            track.insert_clip(c).unwrap();
        }
        Timeline {
            tracks: vec![track],
        }
    }

    fn fade() -> Transition {
        Transition {
            kind: TransitionKind::Dissolve,
            duration: MediaDuration::from_micros(500_000),
        }
    }

    /// A transition-out must not sample the following clip across a gap: doing
    /// so produced a timeline position outside that clip and a source position
    /// past its end, reading frames the media file does not contain.
    #[test]
    fn transition_out_across_a_gap_does_not_sample_the_next_clip() {
        let mut first = video_clip(0, 2_000_000, Some(fade()), false);
        let second_start = 5_000_000;
        let timeline = timeline_of(vec![
            first.clone(),
            video_clip(second_start, 2_000_000, None, false),
        ]);
        first.id = ClipId(Uuid::new_v4());

        // Inside the fade window of the first clip.
        let plan = plan_frame(
            &timeline,
            Timestamp::from_micros(1_900_000),
            1920,
            1080,
            SubtitleMode::Soft,
        );

        match plan.root {
            RenderNode::TransitionBlend { top, .. } => {
                if let RenderNode::VideoFrame { source_pts, .. } = *top {
                    let clip_end = MediaDuration::from_micros(2_000_000);
                    assert!(
                        source_pts.as_micros() <= clip_end.as_micros(),
                        "transition must not read past the source end, got {}",
                        source_pts.as_micros()
                    );
                }
                panic!("a gap must not produce a blend");
            }
            _ => {}
        }
    }

    /// With the clips adjacent the transition must still work, otherwise the
    /// guard above would silently disable every transition.
    #[test]
    fn transition_out_between_adjacent_clips_still_blends() {
        let timeline = timeline_of(vec![
            video_clip(0, 2_000_000, Some(fade()), false),
            video_clip(2_000_000, 2_000_000, None, false),
        ]);

        let plan = plan_frame(
            &timeline,
            Timestamp::from_micros(1_900_000),
            1920,
            1080,
            SubtitleMode::Soft,
        );
        assert!(
            matches!(plan.root, RenderNode::TransitionBlend { .. }),
            "adjacent clips must still blend"
        );
    }

    /// `muted` means silence, not invisibility. Skipping muted clips wholesale
    /// left a hole in the render for any muted video clip.
    #[test]
    fn muted_video_clip_still_renders_its_picture() {
        let timeline = timeline_of(vec![video_clip(0, 2_000_000, None, true)]);
        let plan = plan_frame(
            &timeline,
            Timestamp::from_micros(500_000),
            1920,
            1080,
            SubtitleMode::Soft,
        );
        assert!(
            matches!(plan.root, RenderNode::VideoFrame { .. }),
            "a muted video clip must still contribute its image"
        );
    }
}
