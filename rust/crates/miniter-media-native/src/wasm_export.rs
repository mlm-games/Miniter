use std::collections::HashMap;
use std::io::{BufReader, Cursor, Seek, SeekFrom, Write};
use std::path::Path;

use crate::decoder::{DecodeError, VideoDecodeSession};
use crate::encoder::{EncodedVideoOutput, VideoEncodeSession};
use crate::encoder_av1::{Av1EncodeSession, Av1Packet};
use crate::export_shared::*;
use crate::filters;
use crate::frame::RgbaFrame;
use crate::image_cache::ImageCache;
use crate::mux::{
    extract_sps_pps, ContainerFormat, Mp4Muxer, OpusTrackConfigOut, SubtitleTrackCodecOut,
    SubtitleTrackConfigOut, VideoTrackCodecOut,
};
use miniter_audio::mix::{mix_project_audio_with_source_map, MixConfig, MixedAudio};
use miniter_domain::clip::{ClipId, ClipKind, VideoClip};
use miniter_domain::ease_in_out;
use miniter_domain::export::{ExportFormat, SubtitleMode};
use miniter_domain::filter::VideoFilter;
use miniter_domain::project::Project;
use miniter_domain::text_overlay::{TextAlignment, TextOverlay, TextStyle};
use miniter_domain::time::Timestamp;
use miniter_domain::track::TrackKind;
use miniter_render_plan::compositor::FramePlanIterator;
use miniter_render_plan::render_graph::{plan_frame, RenderNode, RenderPlan};
use miniter_render_plan::transition_blend::{opacity_pair, slide_offset};

fn is_image_path(path: &str) -> bool {
    matches!(
        std::path::Path::new(path).extension().and_then(|e| e.to_str()).map(|e| e.to_lowercase()).as_deref(),
        Some("png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "tif")
    )
}

fn image_format_from_path(path: &str) -> image::ImageFormat {
    std::path::Path::new(path)
        .extension()
        .and_then(|e| e.to_str())
        .and_then(image::ImageFormat::from_extension)
        .unwrap_or(image::ImageFormat::Png)
}

fn load_image_from_bytes(bytes: &[u8], fmt: image::ImageFormat) -> Result<RgbaFrame, String> {
    let img = image::load_from_memory_with_format(bytes, fmt)
        .map_err(|e| e.to_string())?;
    let rgba = img.to_rgba8();
    let (width, height) = rgba.dimensions();
    Ok(RgbaFrame {
        width,
        height,
        data: rgba.into_raw(),
        pts_us: 0,
    })
}

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
    File(VideoDecodeSession),
    Memory(VideoDecodeSession),
    Image(RgbaFrame),
}

impl DecodeSession {
    fn next_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeError> {
        match self {
            Self::File(session) => session.next_frame(),
            Self::Memory(session) => session.next_frame(),
            Self::Image(frame) => Ok(Some(frame.clone())),
        }
    }

    fn reset(&mut self) -> Result<(), DecodeError> {
        match self {
            Self::File(session) => session.reset(),
            Self::Memory(session) => session.reset(),
            Self::Image(_) => Ok(()),
        }
    }
}

struct ExportDecodeSession {
    session: DecodeSession,
    last_frame: Option<RgbaFrame>,
    pending_frame: Option<RgbaFrame>,
}

#[derive(Debug, Clone)]
struct SubtitleCue {
    start_us: i64,
    end_us: i64,
    text: String,
}

struct ExportDecodeCache<'a> {
    sessions: HashMap<ClipId, ExportDecodeSession>,
    subtitle_cues: HashMap<String, Vec<SubtitleCue>>,
    first_decoded_video_pts_us: Option<i64>,
    registered_files: &'a HashMap<String, Vec<u8>>,
    image_cache: ImageCache,
}

impl<'a> ExportDecodeCache<'a> {
    fn new(registered_files: &'a HashMap<String, Vec<u8>>) -> Self {
        Self {
            sessions: HashMap::new(),
            subtitle_cues: HashMap::new(),
            first_decoded_video_pts_us: None,
            registered_files,
            image_cache: ImageCache::new(),
        }
    }

    fn remember_first_decoded_video_pts(&mut self, pts_us: i64) {
        if self.first_decoded_video_pts_us.is_none() {
            self.first_decoded_video_pts_us = Some(pts_us);
        }
    }

    fn extract_frame(
        &mut self,
        clip_id: ClipId,
        path: &str,
        target_us: i64,
    ) -> Result<RgbaFrame, String> {
        if !self.sessions.contains_key(&clip_id) {
            let session = if is_image_path(path) {
                if let Some(bytes) = self.registered_files.get(path) {
                    let fmt = image_format_from_path(path);
                    let img = load_image_from_bytes(bytes, fmt)?;
                    DecodeSession::Image(img)
                } else {
                    let img = self.image_cache.get_frame(Path::new(path))
                        .map_err(|e| format!("image load failed for '{path}': {e}"))?;
                    DecodeSession::Image(img)
                }
            } else if let Some(bytes) = self.registered_files.get(path) {
                let size = bytes.len() as u64;
                let reader = Cursor::new(bytes.clone());
                DecodeSession::Memory(
                    VideoDecodeSession::from_reader(reader, size, false)
                        .map_err(|e| format!("decode init failed for '{path}': {e}"))?,
                )
            } else {
                DecodeSession::File(
                    VideoDecodeSession::open(Path::new(path), false)
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

    fn subtitle_text_at(
        &mut self,
        path: &str,
        source_pts_us: i64,
    ) -> Result<Option<String>, String> {
        if !self.subtitle_cues.contains_key(path) {
            let cues = load_subtitle_cues(path, self.registered_files);
            self.subtitle_cues.insert(path.to_string(), cues);
        }

        let cues = self
            .subtitle_cues
            .get(path)
            .ok_or_else(|| "subtitle cache internal error".to_string())?;

        let mut active = Vec::new();
        for cue in cues {
            if source_pts_us >= cue.start_us && source_pts_us < cue.end_us {
                active.push(cue.text.as_str());
            }
        }

        if active.is_empty() {
            Ok(None)
        } else {
            Ok(Some(active.join("\n")))
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
        ExportFormat::Av1Mkv | ExportFormat::Av1WebM => {
            return Err("MKV/WebM AV1 export is not supported on web yet".to_string());
        }
        _ => return Err("Unsupported export format on web".to_string()),
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
        ExportFormat::Av1Mkv | ExportFormat::Av1WebM => Err("MKV/WebM AV1 export is not supported on web yet".to_string()),
        _ => Err("Unsupported export format on web".to_string()),
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
    let audio_encoded = prepare_audio_track(project, registered_files)?;
    let audio_track = audio_encoded.as_ref().map(|encoded| OpusTrackConfigOut {
        sample_rate: 48_000,
        channels: encoded.channels,
    });
    let subtitle_samples = if project.export_profile.subtitle_mode == SubtitleMode::Soft {
        collect_soft_subtitle_samples(project, registered_files)
    } else {
        Vec::new()
    };
    let subtitle_track = if subtitle_samples.is_empty() {
        None
    } else {
        Some(SubtitleTrackConfigOut {
            codec: SubtitleTrackCodecOut::MovText,
            language: Some("und".to_string()),
        })
    };

    let mut decode_cache = ExportDecodeCache::new(registered_files);
    on_progress(1);
    on_progress(5);

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
            audio_track,
            subtitle_track,
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

        if let Some(encoded_audio) = &audio_encoded {
            let start_anchor_us =
                decode_cache.first_decoded_video_pts_us.unwrap_or(0).max(0) as u64;
            write_audio_packets(&mut muxer, encoded_audio, start_anchor_us)?;
        }

        for sample in &subtitle_samples {
            muxer
                .write_subtitle_sample_at(
                    sample.start_us.max(0) as u64,
                    sample.duration_us.max(1) as u64,
                    &sample.text,
                )
                .map_err(|e| format!("MP4 subtitle write failed: {e}"))?;
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
    let audio_encoded = prepare_audio_track(project, registered_files)?;
    let audio_track = audio_encoded.as_ref().map(|encoded| OpusTrackConfigOut {
        sample_rate: 48_000,
        channels: encoded.channels,
    });
    let subtitle_samples = if project.export_profile.subtitle_mode == SubtitleMode::Soft {
        collect_soft_subtitle_samples(project, registered_files)
    } else {
        Vec::new()
    };
    let subtitle_track = if subtitle_samples.is_empty() {
        None
    } else {
        Some(SubtitleTrackConfigOut {
            codec: SubtitleTrackCodecOut::MovText,
            language: Some("und".to_string()),
        })
    };

    let mut decode_cache = ExportDecodeCache::new(registered_files);
    on_progress(1);
    on_progress(5);

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
        audio_track,
        subtitle_track,
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

    if let Some(encoded_audio) = &audio_encoded {
        let start_anchor_us = decode_cache.first_decoded_video_pts_us.unwrap_or(0).max(0) as u64;
        write_audio_packets(&mut muxer, encoded_audio, start_anchor_us)?;
    }

    for sample in &subtitle_samples {
        muxer
            .write_subtitle_sample_at(
                sample.start_us.max(0) as u64,
                sample.duration_us.max(1) as u64,
                &sample.text,
            )
            .map_err(|e| format!("MP4 subtitle write failed: {e}"))?;
    }

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

fn prepare_audio_track(
    project: &Project,
    registered_files: &HashMap<String, Vec<u8>>,
) -> Result<Option<EncodedOpus>, String> {
    let mixed = mix_project_audio_with_source_map(project, MixConfig::default(), registered_files)
        .map_err(|e| format!("Audio mix failed: {e}"))?;
    if mixed.samples.is_empty() {
        return Ok(None);
    }

    let bitrate_bps = project
        .export_profile
        .audio_bitrate_kbps
        .max(32)
        .saturating_mul(1000);
    let encoded = encode_opus(&mixed, bitrate_bps)?;
    if encoded.packets.is_empty() {
        return Ok(None);
    }

    Ok(Some(encoded))
}

fn write_audio_packets<W: Write>(
    muxer: &mut Mp4Muxer<W>,
    encoded: &EncodedOpus,
    start_anchor_us: u64,
) -> Result<(), String> {
    for packet in &encoded.packets {
        let pts = start_anchor_us.saturating_add(packet.pts_us);
        muxer
            .write_audio_sample_at(pts, &packet.bytes)
            .map_err(|e| format!("MP4 audio write failed: {e}"))?;
    }
    Ok(())
}



fn load_subtitle_cues(path: &str, registered_files: &HashMap<String, Vec<u8>>) -> Vec<SubtitleCue> {
    let content = if let Some(bytes) = registered_files.get(path) {
        match std::str::from_utf8(bytes) {
            Ok(text) => text.to_string(),
            Err(_) => String::new(),
        }
    } else {
        std::fs::read_to_string(path).unwrap_or_default()
    };

    if content.trim().is_empty() {
        return Vec::new();
    }

    let ext = Path::new(path)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();

    match ext.as_str() {
        "srt" => parse_srt_cues(&content),
        "ass" | "ssa" => parse_ass_cues(&content),
        _ => parse_srt_cues(&content),
    }
}

fn collect_soft_subtitle_samples(
    project: &Project,
    registered_files: &HashMap<String, Vec<u8>>,
) -> Vec<SoftSubtitleSample> {
    let mut samples = Vec::new();

    for track in &project.timeline.tracks {
        if track.kind != TrackKind::Subtitle || track.muted {
            continue;
        }

        for clip in &track.clips {
            if clip.muted {
                continue;
            }

            let ClipKind::Subtitle(sub) = &clip.kind else {
                continue;
            };

            samples.extend(
                load_subtitle_cues(&sub.source_path, registered_files)
                    .into_iter()
                    .filter_map(|cue| map_cue_to_timeline_sample(clip, cue)),
            );
        }
    }

    samples.sort_by_key(|s| s.start_us);
    samples
}

fn map_cue_to_timeline_sample(
    clip: &miniter_domain::clip::Clip,
    cue: SubtitleCue,
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

    let visible_start = cue.start_us.max(clip_source_start);
    let visible_end = cue.end_us.min(clip_source_end);
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

    let text = cue.text.trim();
    if text.is_empty() {
        return None;
    }

    Some(SoftSubtitleSample {
        start_us: sample_start,
        duration_us: (sample_end - sample_start).max(1),
        text: text.to_string(),
    })
}

fn parse_srt_cues(content: &str) -> Vec<SubtitleCue> {
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");
    let mut cues = Vec::new();

    for block in normalized.split("\n\n") {
        let lines: Vec<&str> = block.lines().collect();
        if lines.is_empty() {
            continue;
        }

        let mut cursor = 0usize;
        if lines[cursor].trim().chars().all(|c| c.is_ascii_digit()) {
            cursor += 1;
        }
        if cursor >= lines.len() {
            continue;
        }

        let Some((start_us, end_us)) = parse_srt_time_range(lines[cursor]) else {
            continue;
        };
        cursor += 1;
        if cursor >= lines.len() || end_us <= start_us {
            continue;
        }

        let text = lines[cursor..].join("\n").trim().to_string();
        if text.is_empty() {
            continue;
        }

        cues.push(SubtitleCue {
            start_us,
            end_us,
            text,
        });
    }

    cues
}



fn parse_ass_cues(content: &str) -> Vec<SubtitleCue> {
    let normalized = content.replace("\r\n", "\n").replace('\r', "\n");
    let mut cues = Vec::new();

    for line in normalized.lines() {
        let trimmed = line.trim();
        if !trimmed.starts_with("Dialogue:") {
            continue;
        }

        let payload = trimmed.trim_start_matches("Dialogue:").trim();
        let fields: Vec<&str> = payload.splitn(10, ',').collect();
        if fields.len() < 10 {
            continue;
        }

        let Some(start_us) = parse_ass_timestamp_us(fields[1].trim()) else {
            continue;
        };
        let Some(end_us) = parse_ass_timestamp_us(fields[2].trim()) else {
            continue;
        };
        if end_us <= start_us {
            continue;
        }

        let text = normalize_ass_text(fields[9]).trim().to_string();
        if text.is_empty() {
            continue;
        }

        cues.push(SubtitleCue {
            start_us,
            end_us,
            text,
        });
    }

    cues
}

fn normalize_ass_text(input: &str) -> String {
    let mut text = input
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ");
    text = strip_ass_override_tags(&text);
    text
}

fn parse_ass_timestamp_us(value: &str) -> Option<i64> {
    let mut hms = value.trim().split(':');
    let h: i64 = hms.next()?.parse().ok()?;
    let m: i64 = hms.next()?.parse().ok()?;
    let sec_frac = hms.next()?;
    if hms.next().is_some() {
        return None;
    }

    let mut sf = sec_frac.split('.');
    let s: i64 = sf.next()?.parse().ok()?;
    let cs_str = sf.next()?;
    if sf.next().is_some() {
        return None;
    }
    let cs: i64 = match cs_str.len() {
        0 => return None,
        1 => cs_str.parse::<i64>().ok()?.saturating_mul(10),
        _ => cs_str.get(0..2)?.parse::<i64>().ok()?,
    };

    if !(0..60).contains(&m) || !(0..60).contains(&s) {
        return None;
    }

    Some(((h * 3600 + m * 60 + s) * 1_000_000) + (cs * 10_000))
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
            decode_cache.remember_first_decoded_video_pts(frame.pts_us);
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
        RenderNode::Text { overlay, opacity } => {
            let mut img = render_text_overlay(overlay, width, height);
            apply_global_alpha(&mut img, *opacity);
            Ok(img)
        }
        RenderNode::Subtitle {
            source_path,
            source_pts,
            opacity,
        } => {
            let Some(text) = decode_cache.subtitle_text_at(source_path, source_pts.as_micros())?
            else {
                return Ok(transparent_rgba(width, height));
            };

            let subtitle_style = TextStyle {
                font_size: ((height as f32) * 0.045).clamp(22.0, 56.0),
                position_x: 0.5,
                position_y: 0.90,
                alignment: TextAlignment::Center,
                color: "FFFFFFFF".to_string(),
                outline_color: Some("FF000000".to_string()),
                outline_width: 2.0,
                shadow: true,
                bold: false,
                italic: false,
                background_color: None,
                font_family: "sans-serif".to_string(),
            };
            let overlay = TextOverlay {
                text,
                style: subtitle_style,
            };

            let mut img = render_text_overlay(&overlay, width, height);
            apply_global_alpha(&mut img, *opacity);
            Ok(img)
        }
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
                _ => Ok(bottom_img),
            }
        }
    }
}






