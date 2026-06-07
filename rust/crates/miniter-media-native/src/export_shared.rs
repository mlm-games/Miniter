use crate::filters;
use crate::mux::{Mp4Muxer, OpusTrackConfigOut, SubtitleTrackCodecOut, SubtitleTrackConfigOut};
use fast_image_resize::images::{Image, ImageRef};
use fast_image_resize::{PixelType, Resizer};
use font8x8::{BASIC_FONTS, UnicodeFonts};
use miniter_audio::util;
use miniter_domain::filter::VideoFilter;
use miniter_domain::text_overlay::{TextAlignment, TextOverlay};
use std::io::Write;

pub(crate) fn audio_track_config(audio: &EncodedOpus) -> OpusTrackConfigOut {
    OpusTrackConfigOut {
        sample_rate: 48_000,
        channels: audio.channels,
    }
}

pub(crate) fn subtitle_track_config() -> SubtitleTrackConfigOut {
    SubtitleTrackConfigOut {
        codec: SubtitleTrackCodecOut::MovText,
        language: Some("und".to_string()),
    }
}

/// Write audio packets to an MP4 muxer, adjusting PTS by start_anchor_us.
pub(crate) fn write_audio_packets<W: Write>(
    muxer: &mut Mp4Muxer<W>,
    audio: &EncodedOpus,
    start_anchor_us: u64,
) -> Result<(), crate::mux::MuxError> {
    for packet in &audio.packets {
        let pts = start_anchor_us.saturating_add(packet.pts_us);
        muxer.write_audio_sample_at(pts, &packet.bytes)?;
    }
    Ok(())
}

/// Write soft subtitle samples to an MP4 muxer.
pub(crate) fn write_soft_subtitle_samples<W: Write>(
    muxer: &mut Mp4Muxer<W>,
    samples: &[SoftSubtitleSample],
) -> Result<(), crate::mux::MuxError> {
    for sample in samples {
        muxer.write_subtitle_sample_at(
            sample.start_us.max(0) as u64,
            sample.duration_us.max(1) as u64,
            &sample.text,
        )?;
    }
    Ok(())
}

#[derive(Debug, Clone)]
pub(crate) struct SoftSubtitleSample {
    pub start_us: i64,
    pub duration_us: i64,
    pub text: String,
}

#[derive(Debug, Clone)]
pub(crate) struct EncodedOpusPacket {
    pub pts_us: u64,
    pub bytes: Vec<u8>,
}

#[derive(Debug, Clone)]
pub(crate) struct EncodedOpus {
    pub channels: u16,
    pub packets: Vec<EncodedOpusPacket>,
}

pub(crate) fn transparent_rgba(width: usize, height: usize) -> Vec<u8> {
    vec![0u8; width * height * 4]
}

pub(crate) fn alpha_over(dst: &mut [u8], src: &[u8]) {
    for (d, s) in dst.chunks_exact_mut(4).zip(src.chunks_exact(4)) {
        alpha_over_pixel(d, s);
    }
}

pub(crate) fn alpha_over_with_offset(
    dst: &mut [u8],
    src: &[u8],
    dst_w: usize,
    dst_h: usize,
    src_w: usize,
    src_h: usize,
    dx: i32,
    dy: i32,
) {
    debug_assert_eq!(dst.len(), dst_w * dst_h * 4);
    debug_assert_eq!(src.len(), src_w * src_h * 4);

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

            if si + 4 <= src.len() && di + 4 <= dst.len() {
                alpha_over_pixel(&mut dst[di..di + 4], &src[si..si + 4]);
            }
        }
    }
}

pub(crate) fn alpha_over_pixel(dst: &mut [u8], src: &[u8]) {
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

pub(crate) fn flatten_on_black(img: &mut [u8]) {
    for px in img.chunks_exact_mut(4) {
        let a = px[3] as f32 / 255.0;
        px[0] = ((px[0] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[1] = ((px[1] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[2] = ((px[2] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[3] = 255;
    }
}

pub(crate) fn flip_rgba(
    src: &[u8],
    width: usize,
    height: usize,
    horizontal: bool,
    vertical: bool,
) -> Vec<u8> {
    if !horizontal && !vertical {
        return src.to_vec();
    }

    let mut out = src.to_vec();
    let stride = width * 4;

    for y in 0..height {
        let src_y = if vertical { height - 1 - y } else { y };
        for x in 0..width {
            let src_x = if horizontal { width - 1 - x } else { x };
            let di = (y * stride) + x * 4;
            let si = (src_y * stride) + src_x * 4;
            out[di..di + 4].copy_from_slice(&src[si..si + 4]);
        }
    }

    out
}

pub(crate) fn rotate_rgba(src: &[u8], width: usize, height: usize, degrees: f32) -> Vec<u8> {
    if width == 0 || height == 0 {
        return src.to_vec();
    }
    let rad = degrees.to_radians();
    let (sin_r, cos_r) = rad.sin_cos();
    let mut dst = vec![0u8; width * height * 4];
    let wf = width as f32;
    let hf = height as f32;
    let cx = wf / 2.0;
    let cy = hf / 2.0;

    for y in 0..height {
        for x in 0..width {
            let px = (x as f32 - cx) * cos_r - (y as f32 - cy) * sin_r;
            let py = (x as f32 - cx) * sin_r + (y as f32 - cy) * cos_r;
            let sx = (px + cx).round().clamp(0.0, wf - 1.0) as usize;
            let sy = (py + cy).round().clamp(0.0, hf - 1.0) as usize;
            let si = sy * width * 4 + sx * 4;
            let di = y * width * 4 + x * 4;
            dst[di..di + 4].copy_from_slice(&src[si..si + 4]);
        }
    }
    dst
}

pub(crate) fn crop_rgba(
    src: &[u8],
    width: usize,
    height: usize,
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
) -> Vec<u8> {
    let l = (left.clamp(0.0, 1.0) * width as f32).round() as usize;
    let t = (top.clamp(0.0, 1.0) * height as f32).round() as usize;
    let r = (right.clamp(0.0, 1.0) * width as f32).round() as usize;
    let b = (bottom.clamp(0.0, 1.0) * height as f32).round() as usize;

    let l = l.min(width);
    let r = r.max(l).min(width);
    let t = t.min(height);
    let b = b.max(t).min(height);

    let new_w = r - l;
    let new_h = b - t;
    if new_w == 0 || new_h == 0 {
        return src.to_vec();
    }

    let mut out = vec![0u8; new_w * new_h * 4];
    for y in t..b {
        for x in l..r {
            let si = (y * width + x) * 4;
            let di = ((y - t) * new_w + (x - l)) * 4;
            out[di..di + 4].copy_from_slice(&src[si..si + 4]);
        }
    }

    out
}

pub(crate) fn transform_rgba(
    src: &[u8],
    width: usize,
    height: usize,
    scale: f32,
    tx: f32,
    ty: f32,
    rotate: f32,
) -> Vec<u8> {
    let zoom = scale.clamp(0.05, 50.0);
    let rad = rotate.to_radians();
    let (sin_r, cos_r) = rad.sin_cos();
    let mut dst = vec![0u8; width * height * 4];
    let wf = width as f32;
    let hf = height as f32;
    let cx = 0.5;
    let cy = 0.5;

    for yd in 0..height {
        for xd in 0..width {
            let mut u = (xd as f32 + 0.5) / wf;
            let mut v = (yd as f32 + 0.5) / hf;

            let px = (u - cx) * wf;
            let py = (v - cy) * hf;
            let rx = px * cos_r + py * sin_r;
            let ry = -px * sin_r + py * cos_r;
            u = rx / wf + cx;
            v = ry / hf + cy;

            u = u - 0.5 - tx;
            v = v - 0.5 - ty;
            u = u / zoom;
            v = v / zoom;

            u += 0.5;
            v += 0.5;
            let sx = (u * wf - 0.5).round().clamp(0.0, wf - 1.0) as usize;
            let sy = (v * hf - 0.5).round().clamp(0.0, hf - 1.0) as usize;
            let si = sy * width * 4 + sx * 4;
            let di = yd * width * 4 + xd * 4;
            dst[di..di + 4].copy_from_slice(&src[si..si + 4]);
        }
    }
    dst
}

pub(crate) fn scale_rgba(
    src: &[u8],
    src_w: usize,
    src_h: usize,
    dst_w: usize,
    dst_h: usize,
) -> Vec<u8> {
    let src_ref = match ImageRef::new(src_w as u32, src_h as u32, src, PixelType::U8x4) {
        Ok(r) => r,
        Err(_) => return vec![0u8; dst_w * dst_h * 4],
    };
    let mut dst_image = Image::new(dst_w as u32, dst_h as u32, PixelType::U8x4);
    let mut resizer = Resizer::new();
    if resizer.resize(&src_ref, &mut dst_image, None).is_ok() {
        dst_image.into_vec()
    } else {
        vec![0u8; dst_w * dst_h * 4]
    }
}

pub(crate) fn fit_rgba_into_canvas(
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
    alpha_over_with_offset(
        &mut canvas,
        &scaled,
        dst_w,
        dst_h,
        scaled_w,
        scaled_h,
        off_x,
        off_y,
    );

    canvas
}

pub(crate) fn apply_video_filters(
    pixels: &mut Vec<u8>,
    width: usize,
    height: usize,
    filter_list: &[VideoFilter],
) {
    let w = width;
    let h = height;
    for filter in filter_list {
        match filter {
            VideoFilter::Brightness { value } => filters::adjust_brightness(pixels, *value),
            VideoFilter::Contrast { value } => filters::adjust_contrast(pixels, *value),
            VideoFilter::Saturation { value } => filters::adjust_saturation(pixels, *value),
            VideoFilter::Grayscale => filters::apply_grayscale(pixels),
            VideoFilter::Sepia => filters::apply_sepia(pixels),
            VideoFilter::Blur { radius } => filters::blur_rgba(pixels, w, h, *radius),
            VideoFilter::Sharpen { amount } => filters::sharpen_rgba(pixels, w, h, *amount),
            VideoFilter::Opacity { value } => filters::scale_alpha(pixels, *value),
            VideoFilter::Hue { degrees } => filters::adjust_hue(pixels, *degrees),
            VideoFilter::Flip {
                horizontal,
                vertical,
            } => {
                *pixels = flip_rgba(pixels, w, h, *horizontal, *vertical);
            }
            VideoFilter::Rotate { degrees } => {
                *pixels = rotate_rgba(pixels, w, h, *degrees);
            }
            VideoFilter::Crop {
                left,
                top,
                right,
                bottom,
            } => {
                *pixels = crop_rgba(pixels, w, h, *left, *top, *right, *bottom);
            }
            VideoFilter::Transform {
                scale,
                translate_x,
                translate_y,
                rotate,
            } => {
                *pixels = transform_rgba(pixels, w, h, *scale, *translate_x, *translate_y, *rotate);
            }
            VideoFilter::Speed { .. } => {}
            _ => {}
        }
    }
}

pub(crate) fn has_annexb_start_code(data: &[u8]) -> bool {
    data.windows(4).any(|w| w == [0, 0, 0, 1]) || data.windows(3).any(|w| w == [0, 0, 1])
}

pub(crate) fn strip_leading_temporal_delimiters(data: &[u8]) -> &[u8] {
    let mut pos = 0usize;
    while pos + 2 <= data.len() {
        if data[pos] != 0x12 || data[pos + 1] != 0x00 {
            break;
        }
        pos += 2;
    }
    &data[pos..]
}

pub(crate) fn normalize_even_dimension(value: u32, fallback: u32) -> u32 {
    let mut dim = if value > 0 { value } else { fallback };
    if dim % 2 != 0 {
        dim = dim.saturating_sub(1);
    }
    dim.max(2)
}

pub(crate) fn parse_srt_time_range(line: &str) -> Option<(i64, i64)> {
    let mut parts = line.split("-->");
    let start = parts.next()?.trim();
    let end = parts.next()?.trim();
    if parts.next().is_some() {
        return None;
    }
    Some((parse_srt_timestamp_us(start)?, parse_srt_timestamp_us(end)?))
}

pub(crate) fn parse_srt_timestamp_us(value: &str) -> Option<i64> {
    let trimmed = value.trim();
    let (time_part, frac_part) = if let Some((time, frac)) = trimmed.split_once(',') {
        (time, frac)
    } else if let Some((time, frac)) = trimmed.split_once('.') {
        (time, frac)
    } else {
        return None;
    };

    let mut hms = time_part.split(':');
    let h: i64 = hms.next()?.parse().ok()?;
    let m: i64 = hms.next()?.parse().ok()?;
    let s: i64 = hms.next()?.parse().ok()?;
    if hms.next().is_some() || !(0..60).contains(&m) || !(0..60).contains(&s) {
        return None;
    }

    let ms = match frac_part.len() {
        0 => return None,
        1 => frac_part.parse::<i64>().ok()?.saturating_mul(100),
        2 => frac_part.parse::<i64>().ok()?.saturating_mul(10),
        _ => frac_part.get(0..3)?.parse::<i64>().ok()?,
    };

    Some(((h * 3600 + m * 60 + s) * 1_000 + ms) * 1_000)
}

pub(crate) fn strip_ass_override_tags(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut in_tag = false;

    for ch in text.chars() {
        match ch {
            '{' if !in_tag => in_tag = true,
            '}' if in_tag => in_tag = false,
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }

    out
}

pub(crate) fn render_text_overlay(overlay: &TextOverlay, width: usize, height: usize) -> Vec<u8> {
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
            _ => anchor_x,
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
            _ => anchor_x,
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

pub(crate) fn encode_opus(
    mixed: &miniter_audio::mix::MixedAudio,
    bitrate_bps: u32,
) -> Result<EncodedOpus, String> {
    use oporus::{
        Application as OporusApplication, Bitrate as OporusBitrate, Channels as OporusChannels,
        Encoder as OporusEncoder, FrameDuration as OporusFrameDuration,
    };

    let channels = mixed.channels;

    let oporus_channels = match channels {
        1 => OporusChannels::Mono,
        2 => OporusChannels::Stereo,
        other => return Err(format!("Unsupported channel count: {other}")),
    };

    let sample_rate = 48_000u32;
    let pcm = if mixed.sample_rate == sample_rate {
        mixed.samples.clone()
    } else {
        if mixed.sample_rate == 0 {
            return Err("Unsupported sample rate: 0".to_string());
        }
        util::resample_linear_interleaved(
            &mixed.samples,
            mixed.sample_rate,
            sample_rate,
            channels as u16,
        )
    };

    let mut encoder =
        OporusEncoder::builder(sample_rate, oporus_channels, OporusApplication::Audio)
            .bitrate(OporusBitrate::Bits(bitrate_bps.min(i32::MAX as u32) as i32))
            .complexity(10)
            .vbr(false)
            .inband_fec(false)
            .packet_loss_perc(0)
            .frame_duration(OporusFrameDuration::Ms20)
            .build()
            .map_err(|e| format!("Opus encoder init failed: {e:?}"))?;

    let frame_size = (sample_rate / 50) as usize;
    let samples_per_packet = frame_size * channels as usize;

    let mut packets = Vec::new();
    let mut out_buf = vec![0u8; 1275];
    let mut offset = 0usize;
    let mut pts_samples = 0u64;

    while offset < pcm.len() {
        let remaining = pcm.len() - offset;
        let take = remaining.min(samples_per_packet);

        let mut frame = vec![0.0f32; samples_per_packet];
        frame[..take].copy_from_slice(&pcm[offset..offset + take]);

        let written = encoder
            .encode_float(&frame, &mut out_buf)
            .map_err(|e| format!("Opus encode failed: {e:?}"))?;

        packets.push(EncodedOpusPacket {
            pts_us: (pts_samples * 1_000_000) / sample_rate as u64,
            bytes: out_buf[..written].to_vec(),
        });

        offset += take;
        pts_samples += frame_size as u64;
    }

    Ok(EncodedOpus { channels, packets })
}

pub(crate) fn downscale_rgba_for_preview(
    data: &[u8],
    width: u32,
    height: u32,
) -> Option<(u32, u32, Vec<u8>)> {
    const MAX_DIM: u32 = 160;
    let (pw, ph) = if width > height {
        (MAX_DIM, (height * MAX_DIM / width).max(1))
    } else {
        ((width * MAX_DIM / height).max(1), MAX_DIM)
    };
    if pw >= width || ph >= height {
        return None;
    }
    let mut out = Vec::with_capacity((pw * ph * 4) as usize);
    for y in 0..ph {
        let src_y = (y * height / ph) as usize;
        for x in 0..pw {
            let src_x = (x * width / pw) as usize;
            let si = (src_y * width as usize + src_x) * 4;
            out.extend_from_slice(&data[si..si + 4]);
        }
    }
    Some((pw, ph, out))
}

/// Render settings resolved from a project's export profile.
#[derive(Debug)]
pub(crate) struct RenderSettings {
    pub width: u32,
    pub height: u32,
    pub fps: f64,
}

pub(crate) fn is_image_path(path: &str) -> bool {
    matches!(
        std::path::Path::new(path)
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| e.to_lowercase())
            .as_deref(),
        Some("png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "tif")
    )
}

/// Map a subtitle cue to a timeline sample (pure transform, no I/O).
pub(crate) fn map_subtitle_cue_to_timeline_sample(
    clip: &miniter_domain::clip::Clip,
    start_us: i64,
    end_us: i64,
    text: &str,
) -> Option<SoftSubtitleSample> {
    let speed = if clip.speed.is_finite() && clip.speed > 0.0 {
        clip.speed
    } else {
        1.0
    };

    let clip_source_start = clip.source_start.as_micros();
    let inferred_source_end = clip_source_start
        + (clip.timeline_duration.as_micros() as f64 * speed)
            .round()
            .max(1.0) as i64;
    let clip_source_end = if clip.source_end.as_micros() > clip_source_start {
        clip.source_end.as_micros()
    } else {
        inferred_source_end
    };

    let visible_start = start_us.max(clip_source_start);
    let visible_end = end_us.min(clip_source_end);
    if visible_end <= visible_start {
        return None;
    }

    let local_start = ((visible_start - clip_source_start) as f64 / speed).round() as i64;
    let local_end = ((visible_end - clip_source_start) as f64 / speed).round() as i64;

    let clip_timeline_start = clip.timeline_start.as_micros();
    let clip_timeline_end = clip.timeline_end().as_micros();

    let sample_start = (clip_timeline_start + local_start).clamp(0, clip_timeline_end);
    let sample_end = (clip_timeline_start + local_end).clamp(0, clip_timeline_end);
    if sample_end <= sample_start {
        return None;
    }

    let text = text.trim();
    if text.is_empty() {
        return None;
    }

    Some(SoftSubtitleSample {
        start_us: sample_start,
        duration_us: (sample_end - sample_start).max(1),
        text: text.to_string(),
    })
}
