use miniter_domain::transition::TransitionKind;

pub fn opacity_pair(kind: TransitionKind, progress: f32) -> (f32, f32) {
    match kind {
        TransitionKind::CrossFade | TransitionKind::Dissolve => (1.0 - progress, progress),
        TransitionKind::ZoomIn | TransitionKind::ZoomOut => (1.0 - progress, progress),
        TransitionKind::FadeThroughBlack => {
            if progress < 0.5 {
                ((1.0 - 2.0 * progress).clamp(0.0, 1.0), 0.0)
            } else {
                (0.0, (2.0 * progress - 1.0).clamp(0.0, 1.0))
            }
        }
        TransitionKind::SlideLeft | TransitionKind::SlideRight => (1.0, 1.0),
        TransitionKind::SlideUp | TransitionKind::SlideDown => (1.0, 1.0),
        _ => (1.0, 1.0),
    }
}

/// Displacement of the incoming layer: fully off-frame on the side the name
/// implies at progress 0, in place at progress 1.
pub fn slide_offset(kind: TransitionKind, progress: f32) -> f32 {
    match kind {
        TransitionKind::SlideLeft => 1.0 - progress,
        TransitionKind::SlideRight => -1.0 + progress,
        _ => 0.0,
    }
}

pub fn slide_offset_y(kind: TransitionKind, progress: f32) -> f32 {
    match kind {
        TransitionKind::SlideUp => 1.0 - progress,
        TransitionKind::SlideDown => -1.0 + progress,
        _ => 0.0,
    }
}

/// Displacement of the outgoing layer: in place at progress 0, fully off-frame
/// in the direction the slide travels at progress 1.
pub fn slide_exit_offset(kind: TransitionKind, progress: f32) -> f32 {
    match kind {
        TransitionKind::SlideLeft => -progress,
        TransitionKind::SlideRight => progress,
        _ => 0.0,
    }
}

pub fn slide_exit_offset_y(kind: TransitionKind, progress: f32) -> f32 {
    match kind {
        TransitionKind::SlideUp => -progress,
        TransitionKind::SlideDown => progress,
        _ => 0.0,
    }
}

pub fn transition_scales(kind: TransitionKind, progress: f32) -> (f32, f32) {
    match kind {
        TransitionKind::ZoomIn => (1.0, 1.6 - 0.6 * progress),
        TransitionKind::ZoomOut => (1.0 - 0.4 * progress, 1.0),
        _ => (1.0, 1.0),
    }
}

const WIPE_FEATHER: f32 = 1.5;

/// Position of a wipe's edge after `progress`, moving from fully outside the
/// frame on one side to fully outside on the other. The feather is half a
/// feather beyond each end so coverage reaches exactly 0 and 1 at the extremes
/// instead of leaving a half-covered seam of terminal pixels.
fn wipe_edge(progress: f32, extent: f32) -> f32 {
    progress * (extent + WIPE_FEATHER * 2.0) - WIPE_FEATHER
}

pub fn wipe_coverage(
    kind: TransitionKind,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    progress: f32,
) -> f32 {
    let p = progress.clamp(0.0, 1.0);
    match kind {
        TransitionKind::WipeFromLeft => {
            ((wipe_edge(p, width) - x) / WIPE_FEATHER + 0.5).clamp(0.0, 1.0)
        }
        TransitionKind::WipeFromRight => {
            ((x - (width - wipe_edge(p, width))) / WIPE_FEATHER + 0.5).clamp(0.0, 1.0)
        }
        TransitionKind::WipeFromTop => {
            ((wipe_edge(p, height) - y) / WIPE_FEATHER + 0.5).clamp(0.0, 1.0)
        }
        TransitionKind::WipeFromBottom => {
            ((y - (height - wipe_edge(p, height))) / WIPE_FEATHER + 0.5).clamp(0.0, 1.0)
        }
        TransitionKind::IrisOpen => {
            let cx = width * 0.5;
            let cy = height * 0.5;
            let max_radius = (cx * cx + cy * cy).sqrt();
            let dx = x + 0.5 - cx;
            let dy = y + 0.5 - cy;
            let distance = (dx * dx + dy * dy).sqrt();
            let radius = wipe_edge(p, max_radius * 2.0) - max_radius;
            ((radius - distance) / (WIPE_FEATHER * 2.0) + 0.5).clamp(0.0, 1.0)
        }
        _ => 1.0,
    }
}
