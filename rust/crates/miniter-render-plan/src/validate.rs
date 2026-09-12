//! Structural validation for frame render plans.

use crate::render_graph::{RenderNode, RenderPlan};
use miniter_domain::time::Timestamp;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PlanViolation {
    NegativeSourcePts { timestamp: Timestamp },
    NonFiniteOpacity { timestamp: Timestamp },
    OpacityOutOfRange { timestamp: Timestamp, opacity: f32 },
    NonFiniteProgress { timestamp: Timestamp },
    ProgressOutOfRange { timestamp: Timestamp, progress: f32 },
    EmptySourcePath { timestamp: Timestamp },
    ZeroExtent { timestamp: Timestamp },
    NonFiniteMaskParam { timestamp: Timestamp, param: String },
}

pub fn validate_frame_plan(plan: &RenderPlan) -> Vec<PlanViolation> {
    let mut out = Vec::new();
    if plan.width == 0 || plan.height == 0 {
        out.push(PlanViolation::ZeroExtent {
            timestamp: plan.timestamp,
        });
    }
    validate_node(&plan.root, plan.timestamp, &mut out);
    out
}

fn check_opacity(t: Timestamp, opacity: f32, out: &mut Vec<PlanViolation>) {
    if !opacity.is_finite() {
        out.push(PlanViolation::NonFiniteOpacity { timestamp: t });
    } else if !(0.0..=1.0).contains(&opacity) {
        out.push(PlanViolation::OpacityOutOfRange {
            timestamp: t,
            opacity,
        });
    }
}

fn validate_node(node: &RenderNode, t: Timestamp, out: &mut Vec<PlanViolation>) {
    match node {
        RenderNode::VideoFrame {
            source_path,
            source_pts,
            opacity,
            ..
        } => {
            if source_path.is_empty() {
                out.push(PlanViolation::EmptySourcePath { timestamp: t });
            }
            if source_pts.as_micros() < 0 {
                out.push(PlanViolation::NegativeSourcePts { timestamp: t });
            }
            check_opacity(t, *opacity, out);
        }
        RenderNode::TransitionBlend {
            bottom,
            top,
            progress,
            ..
        } => {
            if !progress.is_finite() {
                out.push(PlanViolation::NonFiniteProgress { timestamp: t });
            } else if !(0.0..=1.0).contains(progress) {
                out.push(PlanViolation::ProgressOutOfRange {
                    timestamp: t,
                    progress: *progress,
                });
            }
            validate_node(bottom, t, out);
            validate_node(top, t, out);
        }
        RenderNode::Text { opacity, .. } | RenderNode::Subtitle { opacity, .. } => {
            check_opacity(t, *opacity, out);
            if let RenderNode::Subtitle { source_pts, .. } = node {
                if source_pts.as_micros() < 0 {
                    out.push(PlanViolation::NegativeSourcePts { timestamp: t });
                }
            }
        }
        RenderNode::Stack(nodes) => {
            for child in nodes {
                validate_node(child, t, out);
            }
        }
        RenderNode::Masked {
            source,
            mask_source,
            transform,
            ..
        } => {
            validate_node(source, t, out);
            check_mask(t, mask_source, transform, out);
        }
    }
}

fn check_mask(
    t: Timestamp,
    source: &miniter_domain::mask::MaskSource,
    transform: &miniter_domain::mask::MaskTransform,
    out: &mut Vec<PlanViolation>,
) {
    use miniter_domain::mask::{MaskShape, MaskSource};
    let mut check = |name: &str, v: f32| {
        if !v.is_finite() {
            out.push(PlanViolation::NonFiniteMaskParam {
                timestamp: t,
                param: name.to_string(),
            });
        }
    };
    check("mask.scale", transform.scale);
    check("mask.translate_x", transform.translate_x);
    check("mask.translate_y", transform.translate_y);
    check("mask.rotate", transform.rotate);
    if let MaskSource::Shape {
        shape, feather, ..
    } = source
    {
        check("mask.feather", *feather);
        match shape {
            MaskShape::Rectangle {
                left,
                top,
                right,
                bottom,
            } => {
                check("mask.shape.left", *left);
                check("mask.shape.top", *top);
                check("mask.shape.right", *right);
                check("mask.shape.bottom", *bottom);
            }
            MaskShape::Ellipse {
                center_x,
                center_y,
                radius_x,
                radius_y,
            } => {
                check("mask.shape.center_x", *center_x);
                check("mask.shape.center_y", *center_y);
                check("mask.shape.radius_x", *radius_x);
                check("mask.shape.radius_y", *radius_y);
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use miniter_domain::clip::ClipId;
    use miniter_domain::mask::BlendMode;
    use uuid::Uuid;

    fn video_frame(pts_us: i64, opacity: f32) -> RenderNode {
        RenderNode::VideoFrame {
            clip_id: ClipId(Uuid::new_v4()),
            source_path: "/tmp/a.mp4".into(),
            source_pts: Timestamp::from_micros(pts_us),
            filters: vec![],
            opacity,
            blend_mode: BlendMode::Normal,
        }
    }

    fn plan_with(root: RenderNode) -> RenderPlan {
        RenderPlan {
            timestamp: Timestamp::ZERO,
            width: 1920,
            height: 1080,
            root,
        }
    }

    #[test]
    fn clean_plan_passes() {
        let plan = plan_with(RenderNode::Stack(vec![
            video_frame(1_000_000, 1.0),
            video_frame(2_000_000, 0.5),
        ]));
        assert!(validate_frame_plan(&plan).is_empty());
    }

    #[test]
    fn empty_stack_is_a_legal_black_frame() {
        let plan = plan_with(RenderNode::Stack(vec![]));
        assert!(validate_frame_plan(&plan).is_empty());
    }

    #[test]
    fn bad_source_pts_opacity_and_path_are_flagged() {
        let plan = plan_with(video_frame(-5, 1.5));
        let violations = validate_frame_plan(&plan);
        assert!(violations.contains(&PlanViolation::NegativeSourcePts {
            timestamp: Timestamp::ZERO
        }));
        assert!(violations.contains(&PlanViolation::OpacityOutOfRange {
            timestamp: Timestamp::ZERO,
            opacity: 1.5
        }));

        let mut bad_path = video_frame(0, 1.0);
        if let RenderNode::VideoFrame { source_path, .. } = &mut bad_path {
            source_path.clear();
        }
        let violations = validate_frame_plan(&plan_with(bad_path));
        assert!(violations.contains(&PlanViolation::EmptySourcePath {
            timestamp: Timestamp::ZERO
        }));
    }

    #[test]
    fn blend_progress_checked_recursively() {
        let plan = plan_with(RenderNode::TransitionBlend {
            bottom: Box::new(video_frame(0, 1.0)),
            top: Box::new(video_frame(-1, f32::NAN)),
            kind: miniter_domain::transition::TransitionKind::Dissolve,
            progress: 1.5,
        });
        let violations = validate_frame_plan(&plan);
        assert!(violations.contains(&PlanViolation::ProgressOutOfRange {
            timestamp: Timestamp::ZERO,
            progress: 1.5
        }));
        assert!(violations.contains(&PlanViolation::NegativeSourcePts {
            timestamp: Timestamp::ZERO
        }));
        assert!(
            violations
                .iter()
                .any(|v| matches!(v, PlanViolation::NonFiniteOpacity { .. }))
        );
    }

    #[test]
    fn zero_extent_plan_is_flagged() {
        let mut plan = plan_with(RenderNode::Stack(vec![]));
        plan.width = 0;
        assert!(
            validate_frame_plan(&plan).contains(&PlanViolation::ZeroExtent {
                timestamp: Timestamp::ZERO
            })
        );
    }
}
