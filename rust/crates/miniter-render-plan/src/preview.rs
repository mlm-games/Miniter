use crate::render_graph::RenderNode;
use miniter_domain::clip::ClipId;
use miniter_domain::filter::VideoFilter;

pub fn strip_crop(root: &mut RenderNode, clip_id: &str) {
    let Ok(target) = uuid::Uuid::parse_str(clip_id) else {
        return;
    };
    strip_crop_node(root, ClipId(target));
}

fn strip_crop_node(node: &mut RenderNode, clip_id: ClipId) {
    match node {
        RenderNode::VideoFrame { clip_id: id, filters, .. } if *id == clip_id => {
            filters.retain(|filter| !matches!(filter, VideoFilter::Crop { .. }));
        }
        RenderNode::Stack(children) => {
            for child in children {
                strip_crop_node(child, clip_id);
            }
        }
        RenderNode::TransitionBlend { bottom, top, .. } => {
            strip_crop_node(bottom, clip_id);
            strip_crop_node(top, clip_id);
        }
        RenderNode::Masked { source, .. } | RenderNode::Adjustment { source, .. } => {
            strip_crop_node(source, clip_id);
        }
        _ => {}
    }
}

pub fn rewrite_video_sources(root: &mut RenderNode, map: &mut impl FnMut(&str) -> Option<String>) {
    match root {
        RenderNode::VideoFrame { source_path, .. } => {
            if let Some(replacement) = map(source_path.as_str()) {
                *source_path = replacement;
            }
        }
        RenderNode::Stack(children) => {
            for child in children {
                rewrite_video_sources(child, map);
            }
        }
        RenderNode::TransitionBlend { bottom, top, .. } => {
            rewrite_video_sources(bottom, map);
            rewrite_video_sources(top, map);
        }
        RenderNode::Masked { source, .. } | RenderNode::Adjustment { source, .. } => {
            rewrite_video_sources(source, map);
        }
        _ => {}
    }
}

pub fn collect_source_paths(root: &RenderNode, out: &mut Vec<String>) {
    match root {
        RenderNode::VideoFrame { source_path, .. } | RenderNode::Subtitle { source_path, .. } => {
            if !out.iter().any(|path| path == source_path) {
                out.push(source_path.clone());
            }
        }
        RenderNode::Stack(children) => {
            for child in children {
                collect_source_paths(child, out);
            }
        }
        RenderNode::TransitionBlend { bottom, top, .. } => {
            collect_source_paths(bottom, out);
            collect_source_paths(top, out);
        }
        RenderNode::Masked { source, .. } | RenderNode::Adjustment { source, .. } => {
            collect_source_paths(source, out);
        }
        _ => {}
    }
}
