use crate::clear_session_cache;
use crate::decoder::DecodeError;
use crate::encoder::{EncodeError, VideoEncodeSession};
use crate::encoder_av1::{Av1EncodeError, Av1EncodeSession};
use crate::frame::RgbaFrame;
use crate::mux::{extract_sps_pps, ContainerFormat, Mp4Muxer, MuxError};
use crate::thumbnailer;
use font8x8::{UnicodeFonts, BASIC_FONTS};
use miniter_domain::clip::ClipKind;
use miniter_domain::export::ExportFormat;
use miniter_domain::filter::VideoFilter;
use miniter_domain::text_overlay::{TextAlignment, TextOverlay};
use miniter_domain::time::Timestamp;
use miniter_domain::Project;
use miniter_render_plan::compositor::FramePlanIterator;
use miniter_render_plan::render_graph::{plan_frame, RenderNode, RenderPlan};
use miniter_render_plan::transition_blend::{ease_in_out, opacity_pair, slide_offset};
use std::fs::{create_dir_all, File};
use std::io::BufWriter;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum ExportError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("Decode: {0}")]
    Decode(#[from] DecodeError),
    #[error("H.264 encode: {0}")]
    H264Encode(#[from] EncodeError),
    #[error("AV1 encode: {0}")]
    Av1Encode(#[from] Av1EncodeError),
    #[error("MP4 mux: {0}")]
    Mp4Mux(#[from] MuxError),
    #[error("Could not extract SPS/PPS from H.264 stream")]
    MissingAvcConfig,
    #[error("Export cancelled")]
    Cancelled,
}

pub fn export_project<F>(
    project: &Project,
    output_path: &Path,
    is_cancelled: F,
    on_progress: impl Fn(u32),
) -> Result<(), ExportError>
where
    F: Fn() -> bool,
{
    clear_session_cache();
    let (width, height) = project.export_profile.resolution.dimensions();
    let fps = if project.export_profile.fps > 0.0 {
        project.export_profile.fps
    } else {
        30.0
    };
    let bitrate_kbps = project.export_profile.video_bitrate_kbps.max(500);

    if let Some(parent) = output_path.parent() {
        if !parent.as_os_str().is_empty() {
            create_dir_all(parent)?;
        }
    }

    let result = match project.export_profile.format {
        ExportFormat::Mp4 => export_h264(
            project,
            output_path,
            width,
            height,
            fps,
            bitrate_kbps,
            ContainerFormat::Mp4,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Mov => export_h264(
            project,
            output_path,
            width,
            height,
            fps,
            bitrate_kbps,
            ContainerFormat::Mov,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Av1Ivf => export_av1(
            project,
            output_path,
            width,
            height,
            fps,
            bitrate_kbps,
            &is_cancelled,
            &on_progress,
        ),
    };
    clear_session_cache();
    result
}

fn export_h264<F>(
    project: &Project,
    output_path: &Path,
    width: u32,
    height: u32,
    fps: f64,
    bitrate_kbps: u32,
    container: ContainerFormat,
    is_cancelled: &F,
    on_progress: &dyn Fn(u32),
) -> Result<(), ExportError>
where
    F: Fn() -> bool,
{
    let mut encoder = VideoEncodeSession::new(width, height, bitrate_kbps * 1000, fps as f32)?;

    let mut iter = FramePlanIterator::new(&project.timeline, &project.export_profile);
    let total_frames = iter.total_frames() as u32;
    let first_plan = iter
        .next()
        .unwrap_or_else(|| plan_frame(&project.timeline, Timestamp::ZERO, width, height));

    if is_cancelled() {
        return Err(ExportError::Cancelled);
    }

    let first_rgba = render_plan_to_rgba(&first_plan)?;
    let first_frame = RgbaFrame {
        width,
        height,
        data: first_rgba,
        pts_us: first_plan.timestamp.as_micros(),
    };
    let first_bitstream = encoder.encode_frame(&first_frame)?;
    let (sps, pps) = extract_sps_pps(&first_bitstream).ok_or(ExportError::MissingAvcConfig)?;

    let file = File::create(output_path)?;
    let writer = BufWriter::new(file);
    let mut muxer = Mp4Muxer::new(writer, width, height, fps, &sps, &pps, container)?;
    muxer.write_sample(&first_bitstream, contains_idr(&first_bitstream))?;

    let mut frame_count: u32 = 1;
    for plan in iter {
        if is_cancelled() {
            return Err(ExportError::Cancelled);
        }

        let rgba = render_plan_to_rgba(&plan)?;
        let frame = RgbaFrame {
            width,
            height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
        };
        let bitstream = encoder.encode_frame(&frame)?;
        muxer.write_sample(&bitstream, contains_idr(&bitstream))?;

        frame_count += 1;
        if total_frames > 0 {
            let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
            on_progress(pct);
        }
    }

    on_progress(100_000);
    muxer.finish()?;
    Ok(())
}

fn render_plan_to_rgba(plan: &RenderPlan) -> Result<Vec<u8>, ExportError> {
    let mut rgba = render_node(&plan.root, plan.width as usize, plan.height as usize)?;
    flatten_on_black(&mut rgba);
    Ok(rgba)
}

fn render_node(node: &RenderNode, width: usize, height: usize) -> Result<Vec<u8>, ExportError> {
    match node {
        RenderNode::VideoFrame {
            source_path,
            source_pts,
            filters,
            opacity,
        } => {
            let frame = thumbnailer::extract_thumbnail(
                Path::new(source_path),
                source_pts.as_micros().max(0),
            )?;
            let mut fitted = fit_rgba_into_canvas(
                &frame.data,
                frame.width as usize,
                frame.height as usize,
                width,
                height,
            );
            apply_video_filters(&mut fitted, width, height, filters);
            apply_global_alpha(&mut fitted, *opacity);
            Ok(fitted)
        }

        RenderNode::Text(overlay) => Ok(render_text_overlay(overlay, width, height)),

        RenderNode::Stack(children) => {
            let mut canvas = transparent_rgba(width, height);
            for child in children {
                let layer = render_node(child, width, height)?;
                alpha_over(&mut canvas, &layer);
            }
            Ok(canvas)
        }

        RenderNode::TransitionBlend {
            bottom,
            top,
            kind,
            progress,
        } => {
            let eased = ease_in_out(*progress);
            let bottom_img = render_node(bottom, width, height)?;
            let top_img = render_node(top, width, height)?;

            match kind {
                miniter_domain::transition::TransitionKind::CrossFade
                | miniter_domain::transition::TransitionKind::Dissolve => {
                    let (bottom_a, top_a) = opacity_pair(*kind, eased);
                    let mut canvas = bottom_img;
                    apply_global_alpha(&mut canvas, bottom_a);
                    let mut top_layer = top_img;
                    apply_global_alpha(&mut top_layer, top_a);
                    alpha_over(&mut canvas, &top_layer);
                    Ok(canvas)
                }
                miniter_domain::transition::TransitionKind::SlideLeft
                | miniter_domain::transition::TransitionKind::SlideRight => {
                    let mut canvas = bottom_img;
                    let dx = (slide_offset(*kind, eased) * width as f32).round() as i32;
                    alpha_over_with_offset(&mut canvas, &top_img, width, height, dx, 0);
                    Ok(canvas)
                }
            }
        }
    }
}

fn transparent_rgba(width: usize, height: usize) -> Vec<u8> {
    vec![0u8; width * height * 4]
}

fn fit_rgba_into_canvas(
    src: &[u8],
    src_w: usize,
    src_h: usize,
    dst_w: usize,
    dst_h: usize,
) -> Vec<u8> {
    if src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return transparent_rgba(dst_w, dst_h);
    }

    let scale = f64::min(dst_w as f64 / src_w as f64, dst_h as f64 / src_h as f64);
    let scaled_w = ((src_w as f64 * scale).round() as usize).max(1);
    let scaled_h = ((src_h as f64 * scale).round() as usize).max(1);

    let scaled = scale_rgba(src, src_w, src_h, scaled_w, scaled_h);
    let mut canvas = transparent_rgba(dst_w, dst_h);

    let off_x = ((dst_w - scaled_w) / 2) as i32;
    let off_y = ((dst_h - scaled_h) / 2) as i32;
    alpha_over_with_offset(&mut canvas, &scaled, dst_w, dst_h, off_x, off_y);
    canvas
}

fn scale_rgba(src: &[u8], src_w: usize, src_h: usize, dst_w: usize, dst_h: usize) -> Vec<u8> {
    let mut out = vec![0u8; dst_w * dst_h * 4];
    for y in 0..dst_h {
        let sy = y * src_h / dst_h;
        for x in 0..dst_w {
            let sx = x * src_w / dst_w;
            let si = (sy * src_w + sx) * 4;
            let di = (y * dst_w + x) * 4;
            out[di..di + 4].copy_from_slice(&src[si..si + 4]);
        }
    }
    out
}

fn alpha_over(dst: &mut [u8], src: &[u8]) {
    for (d, s) in dst.chunks_exact_mut(4).zip(src.chunks_exact(4)) {
        alpha_over_pixel(d, s);
    }
}

fn alpha_over_with_offset(
    dst: &mut [u8],
    src: &[u8],
    dst_w: usize,
    dst_h: usize,
    dx: i32,
    dy: i32,
) {
    let src_w = dst_w;
    let src_h = dst_h;

    for y in 0..src_h as i32 {
        let ty = y + dy;
        if ty < 0 || ty >= dst_h as i32 {
            continue;
        }
        for x in 0..src_w as i32 {
            let tx = x + dx;
            if tx < 0 || tx >= dst_w as i32 {
                continue;
            }

            let si = ((y as usize * src_w) + x as usize) * 4;
            let di = ((ty as usize * dst_w) + tx as usize) * 4;
            alpha_over_pixel(&mut dst[di..di + 4], &src[si..si + 4]);
        }
    }
}

fn alpha_over_pixel(dst: &mut [u8], src: &[u8]) {
    let sa = src[3] as f32 / 255.0;
    let da = dst[3] as f32 / 255.0;
    let out_a = sa + da * (1.0 - sa);

    if out_a <= 0.0 {
        dst.copy_from_slice(&[0, 0, 0, 0]);
        return;
    }

    for c in 0..3 {
        let sv = src[c] as f32 / 255.0;
        let dv = dst[c] as f32 / 255.0;
        let out = (sv * sa + dv * da * (1.0 - sa)) / out_a;
        dst[c] = (out * 255.0).round().clamp(0.0, 255.0) as u8;
    }

    dst[3] = (out_a * 255.0).round().clamp(0.0, 255.0) as u8;
}

fn apply_global_alpha(img: &mut [u8], alpha: f32) {
    let a = alpha.clamp(0.0, 1.0);
    for px in img.chunks_exact_mut(4) {
        px[3] = ((px[3] as f32) * a).round().clamp(0.0, 255.0) as u8;
    }
}

fn flatten_on_black(img: &mut [u8]) {
    for px in img.chunks_exact_mut(4) {
        let a = px[3] as f32 / 255.0;
        px[0] = ((px[0] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[1] = ((px[1] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[2] = ((px[2] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[3] = 255;
    }
}

fn apply_video_filters(pixels: &mut Vec<u8>, width: usize, height: usize, filters: &[VideoFilter]) {
    for filter in filters {
        match filter {
            VideoFilter::Brightness { value } => {
                let offset = (*value / 100.0 * 255.0) as i32;
                for px in pixels.chunks_exact_mut(4) {
                    px[0] = (px[0] as i32 + offset).clamp(0, 255) as u8;
                    px[1] = (px[1] as i32 + offset).clamp(0, 255) as u8;
                    px[2] = (px[2] as i32 + offset).clamp(0, 255) as u8;
                }
            }
            VideoFilter::Contrast { value } => {
                let factor = *value;
                for px in pixels.chunks_exact_mut(4) {
                    px[0] = (((px[0] as f32 - 128.0) * factor) + 128.0)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                    px[1] = (((px[1] as f32 - 128.0) * factor) + 128.0)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                    px[2] = (((px[2] as f32 - 128.0) * factor) + 128.0)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                }
            }
            VideoFilter::Saturation { value } => {
                let factor = *value;
                for px in pixels.chunks_exact_mut(4) {
                    let r = px[0] as f32;
                    let g = px[1] as f32;
                    let b = px[2] as f32;
                    let gray = 0.299 * r + 0.587 * g + 0.114 * b;
                    px[0] = (gray + (r - gray) * factor).round().clamp(0.0, 255.0) as u8;
                    px[1] = (gray + (g - gray) * factor).round().clamp(0.0, 255.0) as u8;
                    px[2] = (gray + (b - gray) * factor).round().clamp(0.0, 255.0) as u8;
                }
            }
            VideoFilter::Grayscale => {
                for px in pixels.chunks_exact_mut(4) {
                    let gray = (0.299 * px[0] as f32 + 0.587 * px[1] as f32 + 0.114 * px[2] as f32)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                    px[0] = gray;
                    px[1] = gray;
                    px[2] = gray;
                }
            }
            VideoFilter::Sepia => {
                for px in pixels.chunks_exact_mut(4) {
                    let r = px[0] as f32;
                    let g = px[1] as f32;
                    let b = px[2] as f32;
                    px[0] = (0.393 * r + 0.769 * g + 0.189 * b)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                    px[1] = (0.349 * r + 0.686 * g + 0.168 * b)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                    px[2] = (0.272 * r + 0.534 * g + 0.131 * b)
                        .round()
                        .clamp(0.0, 255.0) as u8;
                }
            }
            VideoFilter::Blur { radius } => {
                *pixels = box_blur_rgba(pixels, width, height, (*radius).round().max(1.0) as usize);
            }
            VideoFilter::Sharpen { amount } => {
                *pixels = sharpen_rgba(pixels, width, height, *amount);
            }
            VideoFilter::Opacity { value } => apply_global_alpha(pixels, *value),
            _ => {}
        }
    }
}

fn box_blur_rgba(src: &[u8], width: usize, height: usize, radius: usize) -> Vec<u8> {
    let mut out = src.to_vec();
    let r = radius.max(1) as i32;

    for y in 0..height as i32 {
        for x in 0..width as i32 {
            let mut sums = [0u32; 4];
            let mut count = 0u32;

            for ky in -r..=r {
                let sy = (y + ky).clamp(0, height as i32 - 1) as usize;
                for kx in -r..=r {
                    let sx = (x + kx).clamp(0, width as i32 - 1) as usize;
                    let i = (sy * width + sx) * 4;
                    sums[0] += src[i] as u32;
                    sums[1] += src[i + 1] as u32;
                    sums[2] += src[i + 2] as u32;
                    sums[3] += src[i + 3] as u32;
                    count += 1;
                }
            }

            let o = ((y as usize) * width + x as usize) * 4;
            out[o] = (sums[0] / count) as u8;
            out[o + 1] = (sums[1] / count) as u8;
            out[o + 2] = (sums[2] / count) as u8;
            out[o + 3] = (sums[3] / count) as u8;
        }
    }

    out
}

fn sharpen_rgba(src: &[u8], width: usize, height: usize, amount: f32) -> Vec<u8> {
    let blurred = box_blur_rgba(src, width, height, 1);
    let mut out = src.to_vec();

    for i in (0..src.len()).step_by(4) {
        for c in 0..3 {
            let orig = src[i + c] as f32;
            let blur = blurred[i + c] as f32;
            let val = orig + (orig - blur) * amount.max(0.0);
            out[i + c] = val.round().clamp(0.0, 255.0) as u8;
        }
        out[i + 3] = src[i + 3];
    }

    out
}

fn render_text_overlay(overlay: &TextOverlay, width: usize, height: usize) -> Vec<u8> {
    let mut canvas = transparent_rgba(width, height);
    let lines: Vec<&str> = overlay.text.lines().collect();
    if lines.is_empty() {
        return canvas;
    }

    let scale = (overlay.style.font_size / 8.0).round().max(1.0) as i32;
    let line_h = 8 * scale + scale;
    let block_h = line_h * lines.len() as i32;
    let anchor_x = (overlay.style.position_x.clamp(0.0, 1.0) * width as f32) as i32;
    let anchor_y = (overlay.style.position_y.clamp(0.0, 1.0) * height as f32) as i32;
    let start_y = anchor_y - block_h / 2;

    let fg = parse_argb_hex(&overlay.style.color, [255, 255, 255, 255]);
    let bg = overlay
        .style
        .background_color
        .as_ref()
        .map(|c| parse_argb_hex(c, [0, 0, 0, 0]));
    let outline = overlay
        .style
        .outline_color
        .as_ref()
        .map(|c| parse_argb_hex(c, [0, 0, 0, 255]));

    let max_w = lines
        .iter()
        .map(|line| line.chars().count() as i32 * 8 * scale)
        .max()
        .unwrap_or(0);

    if let Some(bg) = bg {
        let x = match overlay.style.alignment {
            TextAlignment::Left => anchor_x,
            TextAlignment::Center => anchor_x - max_w / 2,
            TextAlignment::Right => anchor_x - max_w,
        };
        draw_rect(
            &mut canvas,
            width,
            height,
            x - scale,
            start_y - scale,
            max_w + scale * 2,
            block_h + scale * 2,
            bg,
        );
    }

    for (line_idx, line) in lines.iter().enumerate() {
        let line_w = line.chars().count() as i32 * 8 * scale;
        let x = match overlay.style.alignment {
            TextAlignment::Left => anchor_x,
            TextAlignment::Center => anchor_x - line_w / 2,
            TextAlignment::Right => anchor_x - line_w,
        };
        let y = start_y + (line_idx as i32 * line_h);

        let mut pen_x = x;
        for ch in line.chars() {
            if overlay.style.shadow {
                draw_char(
                    &mut canvas,
                    width,
                    height,
                    ch,
                    pen_x + scale / 2,
                    y + scale / 2,
                    scale,
                    [0, 0, 0, 128],
                    None,
                    false,
                );
            }

            draw_char(
                &mut canvas,
                width,
                height,
                ch,
                pen_x,
                y,
                scale,
                fg,
                outline,
                overlay.style.bold,
            );

            pen_x += 8 * scale;
        }
    }

    canvas
}

fn draw_char(
    canvas: &mut [u8],
    width: usize,
    height: usize,
    ch: char,
    x: i32,
    y: i32,
    scale: i32,
    color: [u8; 4],
    outline: Option<[u8; 4]>,
    bold: bool,
) {
    let glyph = BASIC_FONTS.get(ch).or_else(|| BASIC_FONTS.get('?'));
    let Some(glyph) = glyph else { return };

    if let Some(outline_color) = outline {
        for oy in -1..=1 {
            for ox in -1..=1 {
                if ox == 0 && oy == 0 {
                    continue;
                }
                rasterize_glyph(
                    canvas,
                    width,
                    height,
                    &glyph,
                    x + ox * scale,
                    y + oy * scale,
                    scale,
                    outline_color,
                );
            }
        }
    }

    rasterize_glyph(canvas, width, height, &glyph, x, y, scale, color);
    if bold {
        rasterize_glyph(canvas, width, height, &glyph, x + 1, y, scale, color);
    }
}

fn rasterize_glyph(
    canvas: &mut [u8],
    width: usize,
    height: usize,
    glyph: &[u8; 8],
    x: i32,
    y: i32,
    scale: i32,
    color: [u8; 4],
) {
    for (row, bits) in glyph.iter().enumerate() {
        for col in 0..8 {
            if (bits & (1 << col)) == 0 {
                continue;
            }

            for sy in 0..scale {
                for sx in 0..scale {
                    let px = x + col * scale + sx;
                    let py = y + row as i32 * scale + sy;
                    if px < 0 || py < 0 || px >= width as i32 || py >= height as i32 {
                        continue;
                    }

                    let i = ((py as usize * width) + px as usize) * 4;
                    alpha_over_pixel(&mut canvas[i..i + 4], &color);
                }
            }
        }
    }
}

fn draw_rect(
    canvas: &mut [u8],
    width: usize,
    height: usize,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    color: [u8; 4],
) {
    for py in y.max(0)..(y + h).min(height as i32) {
        for px in x.max(0)..(x + w).min(width as i32) {
            let i = ((py as usize * width) + px as usize) * 4;
            alpha_over_pixel(&mut canvas[i..i + 4], &color);
        }
    }
}

fn parse_argb_hex(input: &str, default: [u8; 4]) -> [u8; 4] {
    let s = input.trim().trim_start_matches('#');
    match s.len() {
        8 => {
            let a = u8::from_str_radix(&s[0..2], 16).ok();
            let r = u8::from_str_radix(&s[2..4], 16).ok();
            let g = u8::from_str_radix(&s[4..6], 16).ok();
            let b = u8::from_str_radix(&s[6..8], 16).ok();
            match (r, g, b, a) {
                (Some(r), Some(g), Some(b), Some(a)) => [r, g, b, a],
                _ => default,
            }
        }
        6 => {
            let r = u8::from_str_radix(&s[0..2], 16).ok();
            let g = u8::from_str_radix(&s[2..4], 16).ok();
            let b = u8::from_str_radix(&s[4..6], 16).ok();
            match (r, g, b) {
                (Some(r), Some(g), Some(b)) => [r, g, b, 255],
                _ => default,
            }
        }
        _ => default,
    }
}

fn contains_idr(annex_b: &[u8]) -> bool {
    let mut i = 0;
    while i + 4 <= annex_b.len() {
        let start = if i + 4 <= annex_b.len()
            && annex_b[i] == 0
            && annex_b[i + 1] == 0
            && annex_b[i + 2] == 0
            && annex_b[i + 3] == 1
        {
            i + 4
        } else if i + 3 <= annex_b.len()
            && annex_b[i] == 0
            && annex_b[i + 1] == 0
            && annex_b[i + 2] == 1
        {
            i + 3
        } else {
            i += 1;
            continue;
        };

        if start < annex_b.len() {
            let nal_type = annex_b[start] & 0x1F;
            if nal_type == 5 {
                return true;
            }
        }

        i = start;
    }

    false
}

fn export_av1<F>(
    project: &Project,
    output_path: &Path,
    width: u32,
    height: u32,
    fps: f64,
    bitrate_kbps: u32,
    is_cancelled: &F,
    on_progress: &dyn Fn(u32),
) -> Result<(), ExportError>
where
    F: Fn() -> bool,
{
    let fps_int = fps.round().max(1.0) as u32;
    let mut encoder = Av1EncodeSession::new(width, height, fps_int, bitrate_kbps)?;

    let mut file = File::create(output_path)?;
    ivf::write_ivf_header(
        &mut file,
        width as usize,
        height as usize,
        fps_int as usize,
        1,
    );

    let mut iter = FramePlanIterator::new(&project.timeline, &project.export_profile);
    let total_frames = iter.total_frames() as u32;
    let first_plan = iter
        .next()
        .unwrap_or_else(|| plan_frame(&project.timeline, Timestamp::ZERO, width, height));

    if is_cancelled() {
        return Err(ExportError::Cancelled);
    }

    let first_rgba = render_plan_to_rgba(&first_plan)?;
    let first_frame = RgbaFrame {
        width,
        height,
        data: first_rgba,
        pts_us: first_plan.timestamp.as_micros(),
    };
    let packets = encoder.encode_frame(&first_frame)?;
    for packet in packets {
        ivf::write_ivf_frame(&mut file, packet.pts, &packet.data);
    }

    let mut frame_count: u32 = 1;
    for plan in iter {
        if is_cancelled() {
            return Err(ExportError::Cancelled);
        }

        let rgba = render_plan_to_rgba(&plan)?;
        let frame = RgbaFrame {
            width,
            height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
        };
        let packets = encoder.encode_frame(&frame)?;
        for packet in packets {
            ivf::write_ivf_frame(&mut file, packet.pts, &packet.data);
        }

        frame_count += 1;
        if total_frames > 0 {
            let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
            on_progress(pct);
        }
    }

    let finish_packets = encoder.finish()?;
    for packet in finish_packets {
        ivf::write_ivf_frame(&mut file, packet.pts, &packet.data);
        frame_count += 1;
    }

    use std::io::{Seek, SeekFrom, Write};
    file.seek(SeekFrom::Start(24))?;
    file.write_all(&frame_count.to_le_bytes())?;
    file.seek(SeekFrom::End(0))?;

    on_progress(100_000);
    Ok(())
}
