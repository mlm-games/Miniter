use miniter_domain::transition::TransitionKind;

pub fn opacity_pair(kind: TransitionKind, progress: f32) -> (f32, f32) {
    match kind {
        TransitionKind::CrossFade | TransitionKind::Dissolve => (1.0 - progress, progress),
        TransitionKind::SlideLeft | TransitionKind::SlideRight => (1.0, 1.0),
    }
}

pub fn slide_offset(kind: TransitionKind, progress: f32) -> f32 {
    match kind {
        TransitionKind::SlideLeft => -1.0 + progress,
        TransitionKind::SlideRight => 1.0 - progress,
        _ => 0.0,
    }
}

pub fn ease_in_out(t: f32) -> f32 {
    if t < 0.5 {
        4.0 * t * t * t
    } else {
        1.0 - (-2.0 * t + 2.0).powi(3) / 2.0
    }
}
