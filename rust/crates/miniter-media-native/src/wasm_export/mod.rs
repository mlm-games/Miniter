use std::collections::HashMap;
use std::io::{Cursor, Seek, SeekFrom, Write};
use std::path::Path;
use std::sync::{Mutex, OnceLock};

pub(crate) static WASM_EXPORT_PREVIEW: OnceLock<Mutex<Option<(u32, u32, Vec<u8>)>>> =
    OnceLock::new();

fn wasm_preview_store() -> &'static Mutex<Option<(u32, u32, Vec<u8>)>> {
    WASM_EXPORT_PREVIEW.get_or_init(|| Mutex::new(None))
}

pub fn take_wasm_export_preview() -> Option<(u32, u32, Vec<u8>)> {
    wasm_preview_store().lock().ok()?.take()
}

pub fn clear_wasm_export_preview() {
    if let Ok(mut preview) = wasm_preview_store().lock() {
        *preview = None;
    }
}

pub mod encoder;

use crate::decoder::{DecodeError, VideoDecodeSession};
use crate::encoder::{EncodeError, EncodedVideoOutput, VideoEncodeSession};
use crate::encoder_av1::{Av1EncodeSession, Av1Packet};
use crate::encoder_hw::HwEncodeSession;
use crate::export_shared::*;
use crate::filters;
use crate::frame::RgbaFrame;
use crate::image_cache::ImageCache;
use crate::mux::{
    ContainerFormat, Mp4Muxer, OpusTrackConfigOut, SubtitleTrackCodecOut, SubtitleTrackConfigOut,
    VideoTrackCodecOut, extract_sps_pps,
};
use crate::wasm_export::encoder::{EncodedPacket, EncoderBackend, create_encoder_backend};
use miniter_audio::mix::{MixConfig, mix_project_audio_with_source_map};
use miniter_domain::clip::{ClipId, ClipKind, VideoClip};
use miniter_domain::ease_in_out;
use miniter_domain::export::{ExportFormat, SubtitleMode};
use miniter_domain::mask::{BlendMode, MaskOperation, MaskSource};
use miniter_domain::project::Project;
use miniter_domain::text_overlay::{TextAlignment, TextOverlay, TextStyle};
use miniter_domain::time::Timestamp;
use miniter_domain::track::TrackKind;
use miniter_render_plan::compositor::FramePlanIterator;
use miniter_render_plan::render_graph::{RenderNode, RenderPlan, plan_frame};
use miniter_render_plan::transition_blend::{opacity_pair, slide_offset};

fn store_wasm_preview_frame(rgba: &[u8], width: u32, height: u32) {
    let preview = if let Some(down) = downscale_rgba_for_preview(rgba, width, height) {
        down
    } else {
        (width, height, rgba.to_vec())
    };
    if let Ok(mut store) = wasm_preview_store().lock() {
        *store = Some(preview);
    }
}

fn image_format_from_path(path: &str) -> image::ImageFormat {
    std::path::Path::new(path)
        .extension()
        .and_then(|e| e.to_str())
        .and_then(image::ImageFormat::from_extension)
        .unwrap_or(image::ImageFormat::Png)
}

fn load_image_from_bytes(bytes: &[u8], fmt: image::ImageFormat) -> Result<RgbaFrame, String> {
    let img = image::load_from_memory_with_format(bytes, fmt).map_err(|e| e.to_string())?;
    let rgba = img.to_rgba8();
    let (width, height) = rgba.dimensions();
    Ok(RgbaFrame {
        width,
        height,
        data: rgba.into_raw(),
        pts_us: 0,
        color_info: Default::default(),
    })
}

pub struct WasmExportArtifact {
    pub bytes: Vec<u8>,
    pub file_name: String,
    pub mime_type: String,
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

    fn is_eos(&self) -> bool {
        match self {
            Self::File(session) => session.is_eos(),
            Self::Memory(session) => session.is_eos(),
            Self::Image(_) => true,
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
    last_pts: i64,
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
    hardware_acceleration: bool,
    default_width: u32,
    default_height: u32,
}

impl<'a> ExportDecodeCache<'a> {
    fn new(registered_files: &'a HashMap<String, Vec<u8>>, hardware_acceleration: bool) -> Self {
        Self {
            sessions: HashMap::new(),
            subtitle_cues: HashMap::new(),
            first_decoded_video_pts_us: None,
            registered_files,
            image_cache: ImageCache::new(),
            hardware_acceleration,
            default_width: 1920,
            default_height: 1080,
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
                    let img = self
                        .image_cache
                        .get_frame(Path::new(path))
                        .map_err(|e| format!("image load failed for '{path}': {e}"))?;
                    DecodeSession::Image(img)
                }
            } else if let Some(bytes) = self.registered_files.get(path) {
                let size = bytes.len() as u64;
                let reader = Cursor::new(bytes.clone());
                DecodeSession::Memory(
                    VideoDecodeSession::from_reader(reader, size, self.hardware_acceleration)
                        .map_err(|e| format!("decode init failed for '{path}': {e}"))?,
                )
            } else {
                DecodeSession::File(
                    VideoDecodeSession::open(Path::new(path), self.hardware_acceleration)
                        .map_err(|e| format!("decode init failed for '{path}': {e}"))?,
                )
            };

            let mut session = session;
            // Capture session dimensions for placeholder fallback
            if let DecodeSession::File(ref s) | DecodeSession::Memory(ref s) = session {
                if s.width() > 0 && s.height() > 0 {
                    self.default_width = s.width();
                    self.default_height = s.height();
                }
            }
            // Prime the decoder with one batch of packets. On WASM the first frame
            // may not be immediately available (WebCodecs callbacks need a JS event
            // loop yield). The chunked export mechanism (process_chunk) yields between
            // calls, so frames are guaranteed by the next chunk iteration.
            let first_frame: Option<RgbaFrame> = match session.next_frame() {
                Ok(Some(frame)) => Some(frame),
                Ok(None) => None,
                Err(e) => {
                    return Err(format!("decode failed for '{path}': {e}"));
                }
            };

            self.sessions.insert(
                clip_id,
                ExportDecodeSession {
                    session,
                    last_pts: first_frame.as_ref().map_or(0, |f| f.pts_us),
                    last_frame: first_frame,
                    pending_frame: None,
                },
            );
        }

        {
            let entry = self
                .sessions
                .get_mut(&clip_id)
                .ok_or_else(|| "decode cache internal error".to_string())?;
            if target_us < entry.last_pts {
                entry
                    .session
                    .reset()
                    .map_err(|e| format!("decode reset failed: {e}"))?;
                entry.last_pts = 0;
                entry.last_frame = None;
                entry.pending_frame = None;
            }
            if let Some(ref pending) = entry.pending_frame {
                if target_us < pending.pts_us {
                    return entry.last_frame.clone().ok_or_else(|| "pending without last_frame".to_string());
                }
                entry.last_pts = pending.pts_us;
                entry.last_frame = Some(pending.clone());
                entry.pending_frame = None;
            }

            if let Some(ref current) = entry.last_frame
                && current.pts_us == target_us {
                    return Ok(current.clone());
                }
        }

        loop {
            let frame = {
                let entry = self
                    .sessions
                    .get_mut(&clip_id)
                    .ok_or_else(|| "decode cache internal error".to_string())?;
                entry.session.next_frame()
            };
            if self.hardware_acceleration {
                if let Err(ref e) = frame {
                    if matches!(e, crate::decoders::DecodeError::Other(msg) if msg.starts_with("baaba try_frame_raw")) {
                        self.hardware_acceleration = false;
                        self.sessions.remove(&clip_id);
                        return self.extract_frame(clip_id, path, target_us);
                    }
                }
            }
            let frame = frame.map_err(|e| format!("decode frame failed: {e}"))?;
            let entry = self
                .sessions
                .get_mut(&clip_id)
                .ok_or_else(|| "decode cache internal error".to_string())?;
            match frame {
                Some(frame) => {
                    if frame.pts_us == target_us {
                        entry.last_pts = frame.pts_us;
                        entry.last_frame = Some(frame.clone());
                        entry.pending_frame = None;
                        return Ok(frame);
                    }

                    if frame.pts_us > target_us {
                        if let Some(ref last) = entry.last_frame {
                            entry.pending_frame = Some(frame);
                            return Ok(last.clone());
                        }
                        entry.last_pts = target_us;
                        entry.last_frame = Some(frame.clone());
                        return Ok(frame);
                    }

                    entry.last_pts = frame.pts_us;
                    entry.last_frame = Some(frame);
                }
                None => {
                    if let Some(ref pending) = entry.pending_frame {
                        let p = pending.clone();
                        entry.last_pts = pending.pts_us;
                        entry.last_frame = Some(p.clone());
                        entry.pending_frame = None;
                        return Ok(p);
                    }

                    if entry.session.is_eos() {
                        return entry
                            .last_frame
                            .clone()
                            .ok_or_else(|| "No decodable video frame".to_string());
                    }

                    // Decoder not ready yet (WASM: WebCodecs callbacks need a JS event
                    // loop yield). Return last frame if available, or a blank placeholder.
                    // The next chunk iteration will have frames after the yield.
                    if let Some(last) = entry.last_frame.clone() {
                        return Ok(last);
                    }
                    // Create a placeholder frame using default dimensions
                    let w = self.default_width.max(1) as usize;
                    let h = self.default_height.max(1) as usize;
                    let placeholder = RgbaFrame {
                        width: self.default_width,
                        height: self.default_height,
                        data: crate::export_shared::transparent_rgba(w, h),
                        pts_us: target_us,
                        color_info: Default::default(),
                    };
                    entry.last_frame = Some(placeholder.clone());
                    return Ok(placeholder);
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
    clear_wasm_export_preview();
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
        ExportFormat::Opus => {
            export_opus_ogg_bytes(project, registered_files, &is_cancelled, &on_progress)?
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
        ExportFormat::Opus => Ok(("ogg", "audio/ogg")),
        ExportFormat::Mov => Err("MOV export is not supported on web yet".to_string()),
        ExportFormat::Av1Mkv | ExportFormat::Av1WebM => {
            Err("MKV/WebM AV1 export is not supported on web yet".to_string())
        }
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

enum AnyH264Encoder {
    Sw(VideoEncodeSession),
    Hw(HwEncodeSession),
}

impl AnyH264Encoder {
    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
        match self {
            Self::Sw(e) => e.encode_frame(frame),
            Self::Hw(e) => e.encode_frame(frame),
        }
    }
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

    let hw_requested = project.export_profile.hardware_acceleration;
    let mut decode_cache = ExportDecodeCache::new(registered_files, hw_requested);
    decode_cache.default_width = settings.width;
    decode_cache.default_height = settings.height;
    on_progress(1);
    on_progress(5);
    let mut encoder: AnyH264Encoder = if hw_requested {
        match HwEncodeSession::new(
            settings.width,
            settings.height,
            bitrate_kbps * 1000,
            settings.fps as f32,
            "video/avc",
        ) {
            Ok(hw) => AnyH264Encoder::Hw(hw),
            Err(e) => {
                log::warn!("HW H.264 encoder init failed, falling back to SW: {e}");
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, std::sync::atomic::Ordering::SeqCst);
                AnyH264Encoder::Sw(
                    VideoEncodeSession::new(
                        settings.width,
                        settings.height,
                        bitrate_kbps * 1000,
                        settings.fps as f32,
                    )
                    .map_err(|e| format!("H.264 SW encoder init failed: {e}"))?,
                )
            }
        }
    } else {
        AnyH264Encoder::Sw(
            VideoEncodeSession::new(
                settings.width,
                settings.height,
                bitrate_kbps * 1000,
                settings.fps as f32,
            )
            .map_err(|e| format!("H.264 encoder init failed: {e}"))?,
        )
    };

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
    store_wasm_preview_frame(&first_rgba, settings.width, settings.height);
    let first_frame = RgbaFrame {
        width: settings.width,
        height: settings.height,
        data: first_rgba,
        pts_us: first_plan.timestamp.as_micros(),
        color_info: Default::default(),
    };

    let first_output = encoder
        .encode_frame(&first_frame)
        .map_err(|e| format!("H.264 encode failed: {e}"))?;
    let (first_bytes, first_keyframe) = match first_output {
        EncodedVideoOutput::Sample { bytes, is_keyframe, .. } => (bytes, is_keyframe),
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
            store_wasm_preview_frame(&rgba, settings.width, settings.height);
            let frame = RgbaFrame {
                width: settings.width,
                height: settings.height,
                data: rgba,
                pts_us: plan.timestamp.as_micros(),
                color_info: Default::default(),
            };

            let encoded = encoder
                .encode_frame(&frame)
                .map_err(|e| format!("H.264 encode failed: {e}"))?;

            let (bytes, keyframe) = match encoded {
                EncodedVideoOutput::Sample { bytes, is_keyframe, .. } => (bytes, is_keyframe),
                EncodedVideoOutput::Skipped => {
                    return Err("H.264 encoder skipped frame".to_string());
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
            write_audio_packets(&mut muxer, encoded_audio, start_anchor_us)
                .map_err(|e| format!("MP4 audio write failed: {e}"))?;
        }

        write_soft_subtitle_samples(&mut muxer, &subtitle_samples)
            .map_err(|e| format!("MP4 subtitle write failed: {e}"))?;

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

    let hw_requested = project.export_profile.hardware_acceleration;
    let mut decode_cache = ExportDecodeCache::new(registered_files, hw_requested);
    decode_cache.default_width = settings.width;
    decode_cache.default_height = settings.height;
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
        store_wasm_preview_frame(&rgba, settings.width, settings.height);
        let frame = RgbaFrame {
            width: settings.width,
            height: settings.height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
            color_info: Default::default(),
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
        write_audio_packets(&mut muxer, encoded_audio, start_anchor_us)
            .map_err(|e| format!("MP4 audio write failed: {e}"))?;
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

    let hw_requested = project.export_profile.hardware_acceleration;
    let mut decode_cache = ExportDecodeCache::new(registered_files, hw_requested);
    decode_cache.default_width = settings.width;
    decode_cache.default_height = settings.height;
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
        store_wasm_preview_frame(&rgba, settings.width, settings.height);
        let frame = RgbaFrame {
            width: settings.width,
            height: settings.height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
            color_info: Default::default(),
        };

        let packets = encoder
            .encode_frame(&frame)
            .map_err(|e| format!("AV1 encode failed: {e}"))?;

        for packet in packets {
            let pts_tbn = (packet.pts * fps_int as u64 + 500_000) / 1_000_000;
            ivf::write_ivf_frame(&mut cursor, pts_tbn, &packet.data);
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
        let pts_tbn = (packet.pts * fps_int as u64 + 500_000) / 1_000_000;
        ivf::write_ivf_frame(&mut cursor, pts_tbn, &packet.data);
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

fn export_opus_ogg_bytes(
    project: &Project,
    registered_files: &HashMap<String, Vec<u8>>,
    is_cancelled: &dyn Fn() -> bool,
    on_progress: &dyn Fn(u32),
) -> Result<Vec<u8>, String> {
    on_progress(1);
    let mixed = mix_project_audio_with_source_map(project, MixConfig::default(), registered_files)
        .map_err(|e| format!("Audio mix failed: {e}"))?;
    if mixed.samples.is_empty() {
        return Err("No audio to export".to_string());
    }
    on_progress(10);

    if is_cancelled() {
        return Err("Export cancelled".to_string());
    }

    let bitrate_bps = project
        .export_profile
        .audio_bitrate_kbps
        .max(32)
        .min(510)
        .saturating_mul(1000);
    let encoded = encode_opus(&mixed, bitrate_bps)?;
    on_progress(50);

    if is_cancelled() {
        return Err("Export cancelled".to_string());
    }

    let channels = (mixed.channels.max(1)) as u64;
    let src_samples_per_ch = mixed.samples.len() as u64 / channels;
    let total_samples_48k = if mixed.sample_rate == 48_000 {
        src_samples_per_ch
    } else {
        src_samples_per_ch * 48_000 / mixed.sample_rate.max(1) as u64
    };

    let mut output = Vec::new();
    let mut cursor = Cursor::new(&mut output);
    write_ogg_opus(
        &mut cursor,
        &encoded,
        mixed.sample_rate,
        total_samples_48k,
        is_cancelled,
    )?;

    on_progress(100_000);
    Ok(output)
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
                    .filter_map(|cue| {
                        map_subtitle_cue_to_timeline_sample(
                            clip,
                            cue.start_us,
                            cue.end_us,
                            &cue.text,
                        )
                    }),
            );
        }
    }

    samples.sort_by_key(|s| s.start_us);
    samples
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
            if let ClipKind::Video(VideoClip { width, height, .. }) = &clip.kind
                && *width > 0 && *height > 0 {
                    return (*width, *height);
                }
        }
    }

    (1920, 1080)
}

struct NoopBackend;

impl EncoderBackend for NoopBackend {
    fn name(&self) -> &'static str {
        "noop"
    }
    fn encode_frame(&mut self, _frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String> {
        Ok(Vec::new())
    }
    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String> {
        Ok(Vec::new())
    }
    fn check_error(&self) -> Option<String> {
        None
    }
}

/// An encoded video frame buffered in memory.
struct BufferedFrame {
    pts_us: u64,
    data: Vec<u8>,
    is_keyframe: bool,
}

/// A chunked export state machine that processes video frames one at a time.
/// Usage: `new()` -> loop `process_chunk(1)` until `is_done()` -> `finish()`
pub struct WasmExportChunker {
    // Drop order: decode_cache before _registered_files
    decode_cache: ExportDecodeCache<'static>,
    _registered_files: Box<HashMap<String, Vec<u8>>>,

    timeline: miniter_domain::timeline::Timeline,
    format: ExportFormat,
    settings: RenderSettings,
    output_path: String,
    subtitle_mode: SubtitleMode,
    frame_duration_us: i64,
    current_frame: u64,
    total_frames: u64,
    cancelled: bool,
    done: bool,
    progress: u32,
    encoder: Box<dyn EncoderBackend>,
    sps: Vec<u8>,
    pps: Vec<u8>,
    fps_int: u32,
    buffered_frames: Vec<BufferedFrame>,
    audio_encoded: Option<EncodedOpus>,
    subtitle_samples: Vec<SoftSubtitleSample>,
    ogg_sample_rate: u32,
    ogg_total_samples_48k: u64,
}

impl WasmExportChunker {
    const MAX_FRAMES_PER_CHUNK: u32 = 15;

    pub fn new(
        project: &Project,
        output_path: &str,
        registered_files: HashMap<String, Vec<u8>>,
    ) -> Result<Self, String> {
        clear_wasm_export_preview();
        let format = project.export_profile.format;
        let settings = resolve_render_settings(project);

        let files_box = Box::new(registered_files);
        // SAFETY: The reference points to data inside files_box (heap-allocated).
        // The Box and its heap allocation live as long as WasmExportChunker.
        // drop order ensures decode_cache is dropped before _registered_files.
        let files_ref: &'static HashMap<String, Vec<u8>> =
            unsafe { &*(&*files_box as *const HashMap<String, Vec<u8>>) };
        let hw_requested = project.export_profile.hardware_acceleration;
        let mut decode_cache = ExportDecodeCache::new(files_ref, hw_requested);
        decode_cache.default_width = settings.width;
        decode_cache.default_height = settings.height;

        let audio_encoded = prepare_audio_track(project, &files_box)
            .map_err(|e| format!("Audio prep failed: {e}"))?;
        let subtitle_samples = if project.export_profile.subtitle_mode == SubtitleMode::Soft {
            collect_soft_subtitle_samples(project, &files_box)
        } else {
            Vec::new()
        };

        let subtitle_mode = project.export_profile.subtitle_mode;

        let safe_fps = if settings.fps.is_finite() && settings.fps > 0.0 {
            settings.fps
        } else {
            30.0
        };
        let frame_duration_us = (1_000_000.0 / safe_fps).round().max(1.0) as i64;
        let end_us = project.timeline.duration_end().as_micros().max(0);
        let total_frames = if end_us == 0 {
            1
        } else {
            ((end_us + frame_duration_us - 1) / frame_duration_us) as u64
        };

        log::warn!(
            "EXPORT_INIT: fps={} frame_duration_us={} end_us={} total_frames={} tracks={} clips={}",
            safe_fps, frame_duration_us, end_us, total_frames,
            project.timeline.tracks.len(),
            project.timeline.tracks.iter().map(|t| t.clips.len()).sum::<usize>(),
        );
        for (ti, track) in project.timeline.tracks.iter().enumerate() {
            for (ci, clip) in track.clips.iter().enumerate() {
                log::warn!(
                    "  track[{}] clip[{}]: timeline_start={}us timeline_duration={}us source_start={}us source_end={}us speed={}",
                    ti, ci,
                    clip.timeline_start.as_micros(),
                    clip.timeline_duration.as_micros(),
                    clip.source_start.as_micros(),
                    clip.source_end.as_micros(),
                    clip.speed,
                );
            }
        }

        let timeline = project.timeline.clone();

        let hw_requested = project.export_profile.hardware_acceleration;
        let bitrate_kbps = project.export_profile.video_bitrate_kbps.max(500);
        let fps_int = settings.fps.round().max(1.0) as u32;

        if format == ExportFormat::Opus {
            let (ogg_sample_rate, ogg_total_samples_48k) = if let Some(ref audio) = audio_encoded {
                let samples_per_packet = 960u64;
                let total = audio.packets.len() as u64 * samples_per_packet;
                (48_000, total)
            } else {
                (48_000, 0)
            };

            return Ok(WasmExportChunker {
                decode_cache,
                _registered_files: files_box,
                timeline,
                format,
                settings,
                output_path: output_path.to_string(),
                subtitle_mode,
                frame_duration_us,
                current_frame: 0,
                total_frames: 0,
                cancelled: false,
                done: true,
                progress: 0,
                encoder: Box::new(NoopBackend),
                sps: Vec::new(),
                pps: Vec::new(),
                fps_int,
                buffered_frames: Vec::new(),
                audio_encoded,
                subtitle_samples,
                ogg_sample_rate,
                ogg_total_samples_48k,
            });
        }

        let (encoder, sps, pps) = {
            let enc = create_encoder_backend(
                format,
                settings.width,
                settings.height,
                bitrate_kbps,
                settings.fps,
                hw_requested,
            )?;
            let sps = Vec::new();
            let pps = Vec::new();
            (enc, sps, pps)
        };

        Ok(WasmExportChunker {
            decode_cache,
            _registered_files: files_box,
            timeline,
            format,
            settings,
            output_path: output_path.to_string(),
            subtitle_mode,
            frame_duration_us,
            current_frame: 0,
            total_frames,
            cancelled: false,
            done: false,
            progress: 0,
            encoder,
            sps,
            pps,
            fps_int,
            buffered_frames: Vec::new(),
            audio_encoded,
            subtitle_samples,
            ogg_sample_rate: 0,
            ogg_total_samples_48k: 0,
        })
    }

    /// Process up to `max_frames` frames (one at a time, computed on-demand).
    /// Returns `Ok(Some(progress_0_100000))` if more frames remain,
    /// `Ok(None)` if all frames are done, or `Err` on failure/cancellation.
    pub fn process_chunk(&mut self, max_frames: u32) -> Result<Option<u32>, String> {
        if self.done {
            return Ok(None);
        }
        if self.cancelled {
            return Err("Export cancelled".to_string());
        }

        let end = self
            .current_frame
            .saturating_add(max_frames.max(1) as u64)
            .min(self.total_frames);
        while self.current_frame < end {
            if self.cancelled {
                return Err("Export cancelled".to_string());
            }

            let t = Timestamp::from_micros(self.current_frame as i64 * self.frame_duration_us);
            let plan = plan_frame(
                &self.timeline,
                t,
                self.settings.width,
                self.settings.height,
                self.subtitle_mode,
            );

            let rgba = render_plan_to_rgba(&plan, &mut self.decode_cache)?;
            store_wasm_preview_frame(&rgba, self.settings.width, self.settings.height);
            let frame = RgbaFrame {
                width: self.settings.width,
                height: self.settings.height,
                data: rgba,
                pts_us: plan.timestamp.as_micros(),
                color_info: Default::default(),
            };

            self.encode_one_frame(&frame)?;
            self.current_frame += 1;
            self.progress =
                ((self.current_frame as f64 / self.total_frames as f64) * 100_000.0) as u32;
            log::warn!(
                "EXPORT_FRAME: current_frame={}/{} pts_us={} buffered={}",
                self.current_frame, self.total_frames,
                frame.pts_us, self.buffered_frames.len(),
            );
        }

        if self.current_frame >= self.total_frames {
            self.done = true;
            self.encoder.start_flush();
        }

        Ok(Some(self.progress.min(100_000)))
    }

    fn encode_one_frame(&mut self, frame: &RgbaFrame) -> Result<(), String> {
        let _pts = frame.pts_us.max(0) as u64;
        let packets = self.encoder.encode_frame(frame)?;

        for packet in packets {
            if packet.data.is_empty() {
                continue;
            }

            // Extract SPS/PPS from the first H.264 frame
            if self.format == ExportFormat::Mp4 && self.sps.is_empty() {
                let (sps, pps) = extract_sps_pps(&packet.data)
                    .ok_or_else(|| "Could not extract SPS/PPS from H.264 stream".to_string())?;
                self.sps = sps;
                self.pps = pps;
            }

            // Strip AV1 temporal delimiters
            let sample = if matches!(self.format, ExportFormat::Av1Mp4 | ExportFormat::Av1Ivf) {
                strip_leading_temporal_delimiters(&packet.data).to_vec()
            } else {
                packet.data
            };
            if sample.is_empty() {
                continue;
            }

            self.buffered_frames.push(BufferedFrame {
                pts_us: packet.pts_us,
                data: sample,
                is_keyframe: packet.is_keyframe,
            });
        }

        Ok(())
    }

    /// Check if the encoder's async error callback fired (e.g., WebCodec not supported).
    /// Take the flush completion promise from the underlying encoder,
    /// so the FFI can await it before calling `finish()`.
    #[cfg(target_arch = "wasm32")]
    pub fn take_flush_promise(&mut self) -> Option<js_sys::Promise> {
        self.encoder.take_flush_promise()
    }

    pub fn check_hw_encoder_error(&self) -> Result<(), String> {
        if let Some(err) = self.encoder.check_error() {
            Err(err)
        } else {
            Ok(())
        }
    }

    pub fn is_done(&self) -> bool {
        self.done
    }

    pub fn progress(&self) -> u32 {
        self.progress
    }

    pub fn cancel(&mut self) {
        self.cancelled = true;
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled
    }

    /// Complete the export and return the final artifact.
    pub fn finish(mut self) -> Result<WasmExportArtifact, String> {
        if self.cancelled {
            return Err("Export cancelled".to_string());
        }

        // Process any remaining frames
        while !self.done {
            self.process_chunk(Self::MAX_FRAMES_PER_CHUNK)?;
            if self.cancelled {
                return Err("Export cancelled".to_string());
            }
        }

        // Flush encoder trailing packets
        let trailing = self.encoder.finish()?;
        log::warn!(
            "EXPORT_FINISH: buffered_frames={} trailing={} total_frames={} current_frame={}",
            self.buffered_frames.len(), trailing.len(), self.total_frames, self.current_frame,
        );
        if !self.buffered_frames.is_empty() {
            let first = &self.buffered_frames[0];
            let last = &self.buffered_frames[self.buffered_frames.len() - 1];
            log::warn!(
                "  first: pts_us={} is_key={} data_len={}",
                first.pts_us, first.is_keyframe, first.data.len(),
            );
            log::warn!(
                "  last:  pts_us={} is_key={} data_len={}",
                last.pts_us, last.is_keyframe, last.data.len(),
            );
        }
        for p in trailing {
            if p.data.is_empty() {
                continue;
            }
            let sample = if matches!(self.format, ExportFormat::Av1Mp4 | ExportFormat::Av1Ivf) {
                strip_leading_temporal_delimiters(&p.data).to_vec()
            } else {
                p.data
            };
            if sample.is_empty() {
                continue;
            }
            self.buffered_frames.push(BufferedFrame {
                pts_us: p.pts_us,
                data: sample,
                is_keyframe: p.is_keyframe,
            });
        }

        let (extension, mime_type) = export_target_info(self.format)?;
        let file_name = chunker_file_name(&self.output_path, extension);

        match self.format {
            ExportFormat::Mp4 | ExportFormat::Av1Mp4 => {
                let (sps, pps) = (std::mem::take(&mut self.sps), std::mem::take(&mut self.pps));
                let video_codec = match self.format {
                    ExportFormat::Mp4 => VideoTrackCodecOut::H264,
                    _ => VideoTrackCodecOut::Av1,
                };
                let audio_track = self.audio_encoded.as_ref().map(|e| OpusTrackConfigOut {
                    sample_rate: 48_000,
                    channels: e.channels,
                });
                let subtitle_track = if self.subtitle_samples.is_empty() {
                    None
                } else {
                    Some(SubtitleTrackConfigOut {
                        codec: SubtitleTrackCodecOut::MovText,
                        language: Some("und".to_string()),
                    })
                };

                let mut output = Vec::new();
                let mut muxer = Mp4Muxer::new(
                    &mut output,
                    self.settings.width,
                    self.settings.height,
                    self.settings.fps,
                    &sps,
                    &pps,
                    ContainerFormat::Mp4,
                    audio_track,
                    subtitle_track,
                    video_codec,
                )
                .map_err(|e| format!("MP4 muxer init failed: {e}"))?;

                for f in &self.buffered_frames {
                    muxer
                        .write_sample_at(f.pts_us, &f.data, f.is_keyframe)
                        .map_err(|e| format!("MP4 write failed: {e}"))?;
                }

                if let Some(audio) = &self.audio_encoded {
                    let start_anchor_us = self
                        .decode_cache
                        .first_decoded_video_pts_us
                        .unwrap_or(0)
                        .max(0) as u64;
                    let audio_packets = &audio.packets;
                    for packet in audio_packets {
                        let pts = start_anchor_us.saturating_add(packet.pts_us);
                        muxer
                            .write_audio_sample_at(pts, &packet.bytes)
                            .map_err(|e| format!("MP4 audio write failed: {e}"))?;
                    }
                }

                for sample in &self.subtitle_samples {
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
                drop(muxer);

                Ok(WasmExportArtifact {
                    bytes: output,
                    file_name,
                    mime_type: mime_type.to_string(),
                })
            }
            ExportFormat::Av1Ivf => {
                let fps_int = self.fps_int;

                let mut cursor = std::io::Cursor::new(Vec::<u8>::new());
                ivf::write_ivf_header(
                    &mut cursor,
                    self.settings.width as usize,
                    self.settings.height as usize,
                    fps_int as usize,
                    1,
                );

                let mut packet_count: u32 = 0;
                for f in &self.buffered_frames {
                    let pts_tbn = (f.pts_us * fps_int as u64 + 500_000) / 1_000_000;
                    ivf::write_ivf_frame(&mut cursor, pts_tbn, &f.data);
                    packet_count = packet_count.saturating_add(1);
                }

                use std::io::{Seek, Write};
                cursor
                    .seek(std::io::SeekFrom::Start(24))
                    .map_err(|e| format!("IVF seek failed: {e}"))?;
                cursor
                    .write_all(&packet_count.to_le_bytes())
                    .map_err(|e| format!("IVF packet-count write failed: {e}"))?;

                Ok(WasmExportArtifact {
                    bytes: cursor.into_inner(),
                    file_name,
                    mime_type: mime_type.to_string(),
                })
            }
            ExportFormat::Opus => {
                let Some(audio) = &self.audio_encoded else {
                    return Err("No audio data to export".to_string());
                };
                let mut output = Vec::new();
                let mut cursor = std::io::Cursor::new(&mut output);
                write_ogg_opus(
                    &mut cursor,
                    audio,
                    self.ogg_sample_rate,
                    self.ogg_total_samples_48k,
                    &|| false,
                )?;
                Ok(WasmExportArtifact {
                    bytes: output,
                    file_name,
                    mime_type: mime_type.to_string(),
                })
            }
            _ => Err("Unsupported export format for finalization".to_string()),
        }
    }
}

fn chunker_file_name(output_path: &str, extension: &str) -> String {
    let raw = output_path
        .rsplit('/')
        .next()
        .and_then(|name| name.rsplit('\\').next())
        .unwrap_or("")
        .trim();
    let mut name = sanitize_file_name(raw);
    if name.is_empty() {
        name = "export".to_string();
    }
    let expected_suffix = format!(".{extension}");
    if !name.to_ascii_lowercase().ends_with(&expected_suffix) {
        name.push_str(&expected_suffix);
    }
    name
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
            blend_mode: _,
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
            filters::scale_alpha(&mut fitted, *opacity);
            Ok(fitted)
        }
        RenderNode::Text { overlay, opacity } => {
            let font_path = if !overlay.style.font_family.is_empty()
                && overlay.style.font_family != "sans-serif"
                && std::path::Path::new(&overlay.style.font_family).exists()
            {
                Some(overlay.style.font_family.as_str())
            } else {
                None
            };
            let mut img = render_text_overlay(overlay, width, height, font_path);
            filters::scale_alpha(&mut img, *opacity);
            Ok(img)
        }
        RenderNode::Subtitle {
            source_path,
            source_pts,
            opacity,
            font_path,
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

            let fp = font_path.as_deref();
            let mut img = render_text_overlay(&overlay, width, height, fp);
            filters::scale_alpha(&mut img, *opacity);
            Ok(img)
        }
        RenderNode::Stack(children) => {
            let mut canvas = transparent_rgba(width, height);
            for child in children {
                let layer = render_node(child, width, height, decode_cache)?;
                let bm = miniter_render_plan::render_graph::node_blend_mode(child);
                if bm == BlendMode::Normal {
                    alpha_over(&mut canvas, &layer);
                } else {
                    blend_over(&mut canvas, &layer, bm);
                }
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
                    filters::scale_alpha(&mut canvas, bottom_a);
                    let mut top_layer = top_img;
                    filters::scale_alpha(&mut top_layer, top_a);
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

        RenderNode::Masked {
            source,
            mask_source,
            operation,
            composition,
            transform,
        } => {
            let mut pixels = render_node(source, width, height, decode_cache)?;

            let mask_rgba = match mask_source {
                MaskSource::Shape {
                    shape,
                    feather,
                    invert,
                } => {
                    let mut mask = render_mask_shape(shape, transform, width, height);
                    if *feather > 0.0 {
                        feather_mask(&mut mask, width, height, *feather);
                    }
                    if *invert {
                        for px in mask.chunks_exact_mut(4) {
                            px[0] = 255 - px[0];
                            px[1] = 255 - px[1];
                            px[2] = 255 - px[2];
                            px[3] = 255 - px[3];
                        }
                    }
                    mask
                }
                _ => transparent_rgba(width, height),
            };

            apply_mask_to_alpha(&mut pixels, &mask_rgba, *operation, *composition);
            Ok(pixels)
        }
    }
}
