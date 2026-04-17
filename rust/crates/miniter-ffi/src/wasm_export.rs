use std::collections::HashMap;
use std::io::{BufReader, Cursor, Seek, SeekFrom, Write};
use std::path::Path;

use miniter_domain::clip::{ClipId, ClipKind, VideoClip};
use miniter_domain::export::ExportFormat;
use miniter_domain::filter::VideoFilter;
use miniter_domain::project::Project;
use miniter_domain::time::Timestamp;
use miniter_media_native::decoder::{DecodeError, VideoDecodeSession};
use miniter_media_native::encoder::{EncodedVideoOutput, VideoEncodeSession};
use miniter_media_native::encoder_av1::{Av1EncodeSession, Av1Packet};
use miniter_media_native::frame::RgbaFrame;
use miniter_media_native::mux::{extract_sps_pps, ContainerFormat, Mp4Muxer, VideoTrackCodecOut};
use miniter_render_plan::compositor::FramePlanIterator;
use miniter_render_plan::render_graph::{plan_frame, RenderNode, RenderPlan};
use miniter_render_plan::transition_blend::{ease_in_out, opacity_pair, slide_offset};

pub struct WasmExportArtifact {
    pub bytes: Vec<u8>,
    pub file_name: String,
    pub mime_type: String,
}

#[derive(Debug)]
struct RenderSettings {
    width: u32,
    height: u32,
    fps: f64,
}

enum DecodeSession {
    File(VideoDecodeSession<BufReader<std::fs::File>>),
    Memory(VideoDecodeSession<Cursor<Vec<u8>>>),
}

impl DecodeSession {
    fn next_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeError> {
        match self {
            Self::File(session) => session.next_frame(),
            Self::Memory(session) => session.next_frame(),
        }
    }

    fn reset(&mut self) -> Result<(), DecodeError> {
        match self {
            Self::File(session) => session.reset(),
            Self::Memory(session) => session.reset(),
        }
    }
}

struct ExportDecodeSession {
    session: DecodeSession,
    last_frame: Option<RgbaFrame>,
    pending_frame: Option<RgbaFrame>,
}

struct ExportDecodeCache<'a> {
    sessions: HashMap<ClipId, ExportDecodeSession>,
    registered_files: &'a HashMap<String, Vec<u8>>,
}

impl<'a> ExportDecodeCache<'a> {
    fn new(registered_files: &'a HashMap<String, Vec<u8>>) -> Self {
        Self {
            sessions: HashMap::new(),
            registered_files,
        }
    }

    fn extract_frame(
        &mut self,
        clip_id: ClipId,
        path: &str,
        target_us: i64,
    ) -> Result<RgbaFrame, String> {
        if !self.sessions.contains_key(&clip_id) {
            let session = if let Some(bytes) = self.registered_files.get(path) {
                let size = bytes.len() as u64;
                let reader = Cursor::new(bytes.clone());
                DecodeSession::Memory(
                    VideoDecodeSession::from_reader(reader, size)
                        .map_err(|e| format!("decode init failed for '{path}': {e}"))?,
                )
            } else {
                DecodeSession::File(
                    VideoDecodeSession::open(Path::new(path))
                        .map_err(|e| format!("decode init failed for '{path}': {e}"))?,
                )
            };

            let mut session = session;
            let first_frame = session
                .next_frame()
                .map_err(|e| format!("decode failed for '{path}': {e}"))?;

            self.sessions.insert(
                clip_id,
                ExportDecodeSession {
                    session,
                    last_frame: first_frame,
                    pending_frame: None,
                },
            );
        }

        let entry = self
            .sessions
            .get_mut(&clip_id)
            .ok_or_else(|| "decode cache internal error".to_string())?;

        if let Some(ref last) = entry.last_frame {
            if target_us < last.pts_us {
                entry
                    .session
                    .reset()
                    .map_err(|e| format!("decode reset failed: {e}"))?;
                entry.last_frame = None;
                entry.pending_frame = None;
            } else if let Some(ref pending) = entry.pending_frame {
                if target_us < pending.pts_us {
                    return Ok(last.clone());
                }
                entry.last_frame = Some(pending.clone());
                entry.pending_frame = None;
            }

            if let Some(ref current) = entry.last_frame {
                if current.pts_us == target_us {
                    return Ok(current.clone());
                }
            }
        }

        loop {
            match entry
                .session
                .next_frame()
                .map_err(|e| format!("decode frame failed: {e}"))?
            {
                Some(frame) => {
                    if frame.pts_us == target_us {
                        entry.last_frame = Some(frame.clone());
                        entry.pending_frame = None;
                        return Ok(frame);
                    }

                    if frame.pts_us > target_us {
                        if let Some(ref last) = entry.last_frame {
                            entry.pending_frame = Some(frame);
                            return Ok(last.clone());
                        }
                        entry.last_frame = Some(frame.clone());
                        return Ok(frame);
                    }

                    entry.last_frame = Some(frame);
                }
                None => {
                    if let Some(ref pending) = entry.pending_frame {
                        let p = pending.clone();
                        entry.last_frame = Some(p.clone());
                        entry.pending_frame = None;
                        return Ok(p);
                    }

                    return entry
                        .last_frame
                        .clone()
                        .ok_or_else(|| "No decodable video frame".to_string());
                }
            }
        }
    }
}

pub fn export_project_to_bytes(
    project: &Project,
    output_path: &str,
    registered_files: &HashMap<String, Vec<u8>>,
    is_cancelled: impl Fn() -> bool,
    on_progress: impl Fn(u32),
) -> Result<WasmExportArtifact, String> {
    let format = project.export_profile.format;
    let (extension, mime_type) = export_target_info(format)?;
    let file_name = resolve_output_file_name(project, output_path, extension);

    let bytes = match format {
        ExportFormat::Mp4 => {
            export_h264_mp4_bytes(project, registered_files, &is_cancelled, &on_progress)?
        }
        ExportFormat::Av1Mp4 => {
            export_av1_mp4_bytes(project, registered_files, &is_cancelled, &on_progress)?
        }
        ExportFormat::Av1Ivf => {
            export_av1_ivf_bytes(project, registered_files, &is_cancelled, &on_progress)?
        }
        ExportFormat::Mov => {
            return Err("MOV export is not supported on web yet".to_string());
        }
    };

    Ok(WasmExportArtifact {
        bytes,
        file_name,
        mime_type: mime_type.to_string(),
    })
}

fn export_target_info(format: ExportFormat) -> Result<(&'static str, &'static str), String> {
    match format {
        ExportFormat::Mp4 => Ok(("mp4", "video/mp4")),
        ExportFormat::Av1Mp4 => Ok(("mp4", "video/mp4")),
        ExportFormat::Av1Ivf => Ok(("ivf", "video/ivf")),
        ExportFormat::Mov => Err("MOV export is not supported on web yet".to_string()),
    }
}

fn resolve_output_file_name(project: &Project, output_path: &str, extension: &str) -> String {
    let raw = output_path
        .rsplit('/')
        .next()
        .and_then(|name| name.rsplit('\\').next())
        .unwrap_or("")
        .trim();

    let mut name = sanitize_file_name(raw);
    if name.is_empty() {
        name = sanitize_file_name(&project.meta.name);
    }
    if name.is_empty() {
        name = "export".to_string();
    }

    let expected_suffix = format!(".{extension}");
    if !name.to_ascii_lowercase().ends_with(&expected_suffix) {
        name.push_str(&expected_suffix);
    }

    name
}

fn sanitize_file_name(input: &str) -> String {
    input
        .chars()
        .map(|ch| match ch {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => ch,
        })
        .collect::<String>()
        .trim()
        .to_string()
}

fn export_h264_mp4_bytes(
    project: &Project,
    registered_files: &HashMap<String, Vec<u8>>,
    is_cancelled: &dyn Fn() -> bool,
    on_progress: &dyn Fn(u32),
) -> Result<Vec<u8>, String> {
    let settings = resolve_render_settings(project);
    let bitrate_kbps = project.export_profile.video_bitrate_kbps.max(500);

    let mut decode_cache = ExportDecodeCache::new(registered_files);
    on_progress(1);

    let mut encoder = VideoEncodeSession::new(
        settings.width,
        settings.height,
        bitrate_kbps * 1000,
        settings.fps as f32,
    )
    .map_err(|e| format!("H.264 encoder init failed: {e}"))?;

    let mut iter = FramePlanIterator::with_render_settings(
        &project.timeline,
        settings.width,
        settings.height,
        settings.fps,
        project.export_profile.subtitle_mode,
    );

    let total_frames = iter.total_frames().max(1) as u32;

    let first_plan = iter.next().unwrap_or_else(|| {
        plan_frame(
            &project.timeline,
            Timestamp::ZERO,
            settings.width,
            settings.height,
            project.export_profile.subtitle_mode,
        )
    });

    if is_cancelled() {
        return Err("Export cancelled".to_string());
    }

    let first_rgba = render_plan_to_rgba(&first_plan, &mut decode_cache)?;
    let first_frame = RgbaFrame {
        width: settings.width,
        height: settings.height,
        data: first_rgba,
        pts_us: first_plan.timestamp.as_micros(),
    };

    let first_output = encoder
        .encode_frame(&first_frame)
        .map_err(|e| format!("H.264 encode failed: {e}"))?;
    let (first_bytes, first_keyframe) = match first_output {
        EncodedVideoOutput::Sample { bytes, is_keyframe } => (bytes, is_keyframe),
        EncodedVideoOutput::Skipped => return Err("H.264 encoder skipped first frame".to_string()),
    };

    if first_bytes.is_empty() || !has_annexb_start_code(&first_bytes) {
        return Err("H.264 encoder produced invalid first frame".to_string());
    }

    let (sps, pps) = extract_sps_pps(&first_bytes)
        .ok_or_else(|| "Could not extract SPS/PPS from H.264 stream".to_string())?;

    let mut output = Vec::new();
    {
        let mut muxer = Mp4Muxer::new(
            &mut output,
            settings.width,
            settings.height,
            settings.fps,
            &sps,
            &pps,
            ContainerFormat::Mp4,
            None,
            None,
            VideoTrackCodecOut::H264,
        )
        .map_err(|e| format!("MP4 muxer init failed: {e}"))?;

        muxer
            .write_sample_at(
                first_frame.pts_us.max(0) as u64,
                &first_bytes,
                first_keyframe,
            )
            .map_err(|e| format!("MP4 write failed: {e}"))?;

        let mut frame_count: u32 = 1;

        for plan in iter {
            if is_cancelled() {
                return Err("Export cancelled".to_string());
            }

            let rgba = render_plan_to_rgba(&plan, &mut decode_cache)?;
            let frame = RgbaFrame {
                width: settings.width,
                height: settings.height,
                data: rgba,
                pts_us: plan.timestamp.as_micros(),
            };

            let encoded = encoder
                .encode_frame(&frame)
                .map_err(|e| format!("H.264 encode failed: {e}"))?;

            let (bytes, keyframe) = match encoded {
                EncodedVideoOutput::Sample { bytes, is_keyframe } => (bytes, is_keyframe),
                EncodedVideoOutput::Skipped => {
                    return Err("H.264 encoder skipped frame".to_string())
                }
            };

            if bytes.is_empty() || !has_annexb_start_code(&bytes) {
                return Err("H.264 encoder produced empty frame".to_string());
            }

            muxer
                .write_sample_at(frame.pts_us.max(0) as u64, &bytes, keyframe)
                .map_err(|e| format!("MP4 write failed: {e}"))?;

            frame_count = frame_count.saturating_add(1);
            let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
            on_progress(pct.min(100_000));
        }

        muxer
            .finish()
            .map_err(|e| format!("MP4 finalize failed: {e}"))?;
    }

    on_progress(100_000);
    Ok(output)
}

fn export_av1_mp4_bytes(
    project: &Project,
    registered_files: &HashMap<String, Vec<u8>>,
    is_cancelled: &dyn Fn() -> bool,
    on_progress: &dyn Fn(u32),
) -> Result<Vec<u8>, String> {
    let settings = resolve_render_settings(project);
    let bitrate_kbps = project.export_profile.video_bitrate_kbps.max(500);

    let mut decode_cache = ExportDecodeCache::new(registered_files);
    on_progress(1);

    let mut encoder =
        Av1EncodeSession::new(settings.width, settings.height, settings.fps, bitrate_kbps)
            .map_err(|e| format!("AV1 encoder init failed: {e}"))?;

    let mut output = Vec::new();
    let mut muxer = Mp4Muxer::new(
        &mut output,
        settings.width,
        settings.height,
        settings.fps,
        &[],
        &[],
        ContainerFormat::Mp4,
        None,
        None,
        VideoTrackCodecOut::Av1,
    )
    .map_err(|e| format!("MP4 muxer init failed: {e}"))?;

    let mut iter = FramePlanIterator::with_render_settings(
        &project.timeline,
        settings.width,
        settings.height,
        settings.fps,
        project.export_profile.subtitle_mode,
    );
    let total_frames = iter.total_frames().max(1) as u32;
    let first_plan = iter.next().unwrap_or_else(|| {
        plan_frame(
            &project.timeline,
            Timestamp::ZERO,
            settings.width,
            settings.height,
            project.export_profile.subtitle_mode,
        )
    });

    let mut frame_count: u32 = 0;

    for plan in std::iter::once(first_plan).chain(iter) {
        if is_cancelled() {
            return Err("Export cancelled".to_string());
        }

        let rgba = render_plan_to_rgba(&plan, &mut decode_cache)?;
        let frame = RgbaFrame {
            width: settings.width,
            height: settings.height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
        };

        let packets = encoder
            .encode_frame(&frame)
            .map_err(|e| format!("AV1 encode failed: {e}"))?;

        write_av1_packets_to_mux(&mut muxer, &packets)?;

        frame_count = frame_count.saturating_add(1);
        let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
        on_progress(pct.min(100_000));
    }

    let finish_packets = encoder
        .finish()
        .map_err(|e| format!("AV1 finalize failed: {e}"))?;
    write_av1_packets_to_mux(&mut muxer, &finish_packets)?;

    muxer
        .finish()
        .map_err(|e| format!("MP4 finalize failed: {e}"))?;

    on_progress(100_000);
    drop(muxer);
    Ok(output)
}

fn export_av1_ivf_bytes(
    project: &Project,
    registered_files: &HashMap<String, Vec<u8>>,
    is_cancelled: &dyn Fn() -> bool,
    on_progress: &dyn Fn(u32),
) -> Result<Vec<u8>, String> {
    let settings = resolve_render_settings(project);
    let bitrate_kbps = project.export_profile.video_bitrate_kbps.max(500);
    let fps_int = settings.fps.round().max(1.0) as u32;

    let mut decode_cache = ExportDecodeCache::new(registered_files);
    on_progress(1);

    let mut encoder =
        Av1EncodeSession::new(settings.width, settings.height, settings.fps, bitrate_kbps)
            .map_err(|e| format!("AV1 encoder init failed: {e}"))?;

    let mut cursor = Cursor::new(Vec::<u8>::new());
    ivf::write_ivf_header(
        &mut cursor,
        settings.width as usize,
        settings.height as usize,
        fps_int as usize,
        1,
    );

    let mut iter = FramePlanIterator::with_render_settings(
        &project.timeline,
        settings.width,
        settings.height,
        settings.fps,
        project.export_profile.subtitle_mode,
    );
    let total_frames = iter.total_frames().max(1) as u32;
    let first_plan = iter.next().unwrap_or_else(|| {
        plan_frame(
            &project.timeline,
            Timestamp::ZERO,
            settings.width,
            settings.height,
            project.export_profile.subtitle_mode,
        )
    });

    let mut frame_count: u32 = 0;
    let mut packet_count: u32 = 0;

    for plan in std::iter::once(first_plan).chain(iter) {
        if is_cancelled() {
            return Err("Export cancelled".to_string());
        }

        let rgba = render_plan_to_rgba(&plan, &mut decode_cache)?;
        let frame = RgbaFrame {
            width: settings.width,
            height: settings.height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
        };

        let packets = encoder
            .encode_frame(&frame)
            .map_err(|e| format!("AV1 encode failed: {e}"))?;

        for packet in packets {
            ivf::write_ivf_frame(&mut cursor, packet.pts, &packet.data);
            packet_count = packet_count.saturating_add(1);
        }

        frame_count = frame_count.saturating_add(1);
        let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
        on_progress(pct.min(100_000));
    }

    let finish_packets = encoder
        .finish()
        .map_err(|e| format!("AV1 finalize failed: {e}"))?;
    for packet in finish_packets {
        ivf::write_ivf_frame(&mut cursor, packet.pts, &packet.data);
        packet_count = packet_count.saturating_add(1);
    }

    cursor
        .seek(SeekFrom::Start(24))
        .map_err(|e| format!("IVF seek failed: {e}"))?;
    cursor
        .write_all(&packet_count.to_le_bytes())
        .map_err(|e| format!("IVF packet-count write failed: {e}"))?;
    cursor
        .seek(SeekFrom::End(0))
        .map_err(|e| format!("IVF seek failed: {e}"))?;

    on_progress(100_000);
    Ok(cursor.into_inner())
}

fn write_av1_packets_to_mux<W: Write>(
    muxer: &mut Mp4Muxer<W>,
    packets: &[Av1Packet],
) -> Result<(), String> {
    for packet in packets {
        let sample = strip_leading_temporal_delimiters(&packet.data);
        if sample.is_empty() {
            continue;
        }

        muxer
            .write_sample_at(packet.pts, sample, packet.is_keyframe)
            .map_err(|e| format!("MP4 write failed: {e}"))?;
    }

    Ok(())
}

fn strip_leading_temporal_delimiters(data: &[u8]) -> &[u8] {
    let mut pos = 0usize;
    while pos + 2 <= data.len() {
        if data[pos] != 0x12 || data[pos + 1] != 0x00 {
            break;
        }
        pos += 2;
    }
    &data[pos..]
}

fn resolve_render_settings(project: &Project) -> RenderSettings {
    let (profile_w, profile_h) = project.export_profile.resolution.dimensions();
    let (source_w, source_h) = first_video_dimensions(project);

    let width = normalize_even_dimension(if profile_w > 0 { profile_w } else { source_w }, 1920);
    let height = normalize_even_dimension(if profile_h > 0 { profile_h } else { source_h }, 1080);
    let fps = if project.export_profile.fps.is_finite() && project.export_profile.fps > 0.0 {
        project.export_profile.fps
    } else {
        30.0
    };

    RenderSettings { width, height, fps }
}

fn first_video_dimensions(project: &Project) -> (u32, u32) {
    for track in &project.timeline.tracks {
        for clip in &track.clips {
            if let ClipKind::Video(VideoClip { width, height, .. }) = &clip.kind {
                if *width > 0 && *height > 0 {
                    return (*width, *height);
                }
            }
        }
    }

    (1920, 1080)
}

fn normalize_even_dimension(value: u32, fallback: u32) -> u32 {
    let mut dim = if value > 0 { value } else { fallback };
    if dim % 2 != 0 {
        dim = dim.saturating_sub(1);
    }
    dim.max(2)
}

fn render_plan_to_rgba(
    plan: &RenderPlan,
    decode_cache: &mut ExportDecodeCache,
) -> Result<Vec<u8>, String> {
    let mut rgba = render_node(
        &plan.root,
        plan.width as usize,
        plan.height as usize,
        decode_cache,
    )?;
    flatten_on_black(&mut rgba);
    Ok(rgba)
}

fn render_node(
    node: &RenderNode,
    width: usize,
    height: usize,
    decode_cache: &mut ExportDecodeCache,
) -> Result<Vec<u8>, String> {
    match node {
        RenderNode::VideoFrame {
            clip_id,
            source_path,
            source_pts,
            filters,
            opacity,
        } => {
            let frame =
                decode_cache.extract_frame(*clip_id, source_path, source_pts.as_micros().max(0))?;
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
        RenderNode::Text { .. } => Ok(transparent_rgba(width, height)),
        RenderNode::Subtitle { .. } => Ok(transparent_rgba(width, height)),
        RenderNode::Stack(children) => {
            let mut canvas = transparent_rgba(width, height);
            for child in children {
                let layer = render_node(child, width, height, decode_cache)?;
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
            let bottom_img = render_node(bottom, width, height, decode_cache)?;
            let top_img = render_node(top, width, height, decode_cache)?;

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
                    alpha_over_with_offset(
                        &mut canvas,
                        &top_img,
                        width,
                        height,
                        width,
                        height,
                        dx,
                        0,
                    );
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
    src_w: usize,
    src_h: usize,
    dx: i32,
    dy: i32,
) {
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

fn has_annexb_start_code(data: &[u8]) -> bool {
    data.windows(4).any(|w| w == [0, 0, 0, 1]) || data.windows(3).any(|w| w == [0, 0, 1])
}
