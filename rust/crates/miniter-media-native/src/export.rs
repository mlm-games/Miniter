use crate::HARDWARE_FALLBACK_OCCURRED;

use crate::clear_session_cache;

use crate::decoder::{DecodeError, VideoDecodeSession};

use crate::filters;
use crate::encoder::{EncodeError, EncodedVideoOutput, VideoEncodeSession};
use crate::encoder_av1::{Av1EncodeError, Av1EncodeSession, Av1Packet};
use crate::encoder_hw::HwEncodeSession;
use crate::frame::RgbaFrame;
use crate::image_cache::ImageCache;
use crate::mux::{
    ContainerFormat, Mp4Muxer, MuxError, OpusTrackConfigOut, SubtitleTrackCodecOut,
    SubtitleTrackConfigOut, VideoTrackCodecOut, extract_sps_pps,
};
use crate::subtitle::SubtitleRenderer;

use crate::export_shared::*;
use miniter_audio::mix::{AudioMixError, MixConfig, MixedAudio, mix_project_audio};
use miniter_domain::Project;
use miniter_domain::clip::ClipId;
use miniter_domain::clip::ClipKind;
use miniter_domain::ease_in_out;
use miniter_domain::export::{ExportFormat, SubtitleMode};
use miniter_domain::time::Timestamp;
use miniter_domain::track::TrackKind;
use miniter_render_plan::compositor::{FramePlanIterator, first_video_dimensions};
use miniter_render_plan::render_graph::{RenderNode, RenderPlan, plan_frame};
use miniter_render_plan::transition_blend::{opacity_pair, slide_offset};
use std::collections::HashMap;
use std::fs::{File, create_dir_all};
use std::io::BufWriter;
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

pub(crate) static EXPORT_PREVIEW: OnceLock<Mutex<Option<(u32, u32, Vec<u8>)>>> = OnceLock::new();

fn preview_store() -> &'static Mutex<Option<(u32, u32, Vec<u8>)>> {
    EXPORT_PREVIEW.get_or_init(|| Mutex::new(None))
}

pub fn take_export_preview() -> Option<(u32, u32, Vec<u8>)> {
    preview_store().lock().ok()?.take()
}

pub fn clear_export_preview() {
    if let Ok(mut preview) = preview_store().lock() {
        *preview = None;
    }
}

fn store_preview_frame(rgba: &[u8], width: u32, height: u32) {
    let preview = if let Some(down) = downscale_rgba_for_preview(rgba, width, height) {
        down
    } else {
        (width, height, rgba.to_vec())
    };
    if let Ok(mut store) = preview_store().lock() {
        *store = Some(preview);
    }
}

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
    #[error("Audio mix: {0}")]
    AudioMix(#[from] AudioMixError),
    #[error("Opus encode: {0}")]
    OpusEncode(#[from] OpusEncodeError),
    #[error("Could not extract SPS/PPS from H.264 stream")]
    MissingAvcConfig,
    #[error("Export cancelled")]
    Cancelled,
    #[error("{format} export is not yet available")]
    MkvNotAvailable { format: String },
    #[error("Unsupported export format")]
    UnsupportedFormat,
}

pub fn was_hardware_fallback() -> bool {
    HARDWARE_FALLBACK_OCCURRED.load(Ordering::SeqCst)
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
    clear_export_preview();
    HARDWARE_FALLBACK_OCCURRED.store(false, Ordering::SeqCst);
    let settings = resolve_render_settings(project);
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
            settings.width,
            settings.height,
            settings.fps,
            bitrate_kbps,
            ContainerFormat::Mp4,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Mov => export_h264(
            project,
            output_path,
            settings.width,
            settings.height,
            settings.fps,
            bitrate_kbps,
            ContainerFormat::Mov,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Av1Ivf => export_av1(
            project,
            output_path,
            settings.width,
            settings.height,
            settings.fps,
            bitrate_kbps,
            Av1Container::Ivf,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Av1Mp4 => export_av1(
            project,
            output_path,
            settings.width,
            settings.height,
            settings.fps,
            bitrate_kbps,
            Av1Container::Mp4,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Av1Mkv => export_av1(
            project,
            output_path,
            settings.width,
            settings.height,
            settings.fps,
            bitrate_kbps,
            Av1Container::Mkv,
            &is_cancelled,
            &on_progress,
        ),
        ExportFormat::Av1WebM => export_av1(
            project,
            output_path,
            settings.width,
            settings.height,
            settings.fps,
            bitrate_kbps,
            Av1Container::WebM,
            &is_cancelled,
            &on_progress,
        ),
        _ => return Err(ExportError::UnsupportedFormat),
    };
    clear_session_cache();
    result
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Av1Container {
    Ivf,
    Mp4,
    Mkv,
    WebM,
}

fn resolve_render_settings(project: &Project) -> RenderSettings {
    let (profile_w, profile_h) = project.export_profile.resolution.dimensions();
    let (source_w, source_h) = first_video_dimensions(&project.timeline).unwrap_or((1920, 1080));

    if profile_w > 0 && profile_h > 0 {
        log::info!(
            "Export resolution requested: {}x{} (source {}x{})",
            profile_w,
            profile_h,
            source_w,
            source_h
        );
    } else {
        log::info!(
            "Export resolution requested: source ({}x{})",
            source_w,
            source_h
        );
    }

    let width = normalize_even_dimension(if profile_w > 0 { profile_w } else { source_w }, 1920);
    let height = normalize_even_dimension(if profile_h > 0 { profile_h } else { source_h }, 1080);
    let fps = if project.export_profile.fps.is_finite() && project.export_profile.fps > 0.0 {
        project.export_profile.fps
    } else {
        30.0
    };

    log::info!(
        "Export render settings resolved: {}x{} @ {:.3}fps",
        width,
        height,
        fps
    );

    RenderSettings { width, height, fps }
}

#[derive(Debug, Clone)]
struct SourceSubtitleCue {
    start_us: i64,
    end_us: i64,
    text: String,
}

fn collect_soft_subtitle_samples(project: &Project, output_path: &Path) -> Vec<SoftSubtitleSample> {
    let preserve_styles = output_path
        .extension()
        .and_then(|ext| ext.to_str())
        .map(|ext| ext.eq_ignore_ascii_case("mkv"))
        .unwrap_or(false);

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

            let path = Path::new(&sub.source_path);
            let ext = path
                .extension()
                .and_then(|e| e.to_str())
                .map(|e| e.to_ascii_lowercase());

            let cues = match ext.as_deref() {
                Some("srt") => parse_srt_cues(path),
                Some("ass") | Some("ssa") => {
                    if !preserve_styles {
                        log::warn!(
                            "Soft subtitle export to MP4 converts ASS styling to plain text: {}",
                            path.display()
                        );
                    }
                    parse_ass_cues(path, preserve_styles)
                }
                _ => {
                    log::warn!(
                        "Unsupported subtitle extension for soft export: {}",
                        path.display()
                    );
                    continue;
                }
            };

            let cues = match cues {
                Ok(c) => c,
                Err(err) => {
                    log::warn!("Failed to parse subtitle '{}': {}", path.display(), err);
                    continue;
                }
            };

            samples.extend(
                cues.into_iter()
                    .filter_map(|cue| map_cue_to_timeline_sample(clip, cue)),
            );
        }
    }

    samples.sort_by_key(|s| s.start_us);
    samples
}

fn map_cue_to_timeline_sample(
    clip: &miniter_domain::clip::Clip,
    cue: SourceSubtitleCue,
) -> Option<SoftSubtitleSample> {
    map_subtitle_cue_to_timeline_sample(clip, cue.start_us, cue.end_us, &cue.text)
}

fn parse_srt_cues(path: &Path) -> Result<Vec<SourceSubtitleCue>, String> {
    let content = std::fs::read_to_string(path)
        .map_err(|e| format!("failed to read SRT '{}': {}", path.display(), e))?;

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

        cues.push(SourceSubtitleCue {
            start_us,
            end_us,
            text,
        });
    }

    Ok(cues)
}

fn parse_ass_cues(path: &Path, preserve_styles: bool) -> Result<Vec<SourceSubtitleCue>, String> {
    let content = std::fs::read_to_string(path)
        .map_err(|e| format!("failed to read ASS/SSA '{}': {}", path.display(), e))?;
    let script = ass_core::Script::parse(&content)
        .map_err(|e| format!("failed to parse ASS/SSA '{}': {}", path.display(), e))?;

    let mut cues = Vec::new();

    for section in script.sections() {
        let ass_core::Section::Events(events) = section else {
            continue;
        };

        for event in events {
            if !event.is_dialogue() {
                continue;
            }

            let start_cs = event
                .start_time_cs()
                .map_err(|e| format!("invalid ASS start time in '{}': {}", path.display(), e))?;
            let end_cs = event
                .end_time_cs()
                .map_err(|e| format!("invalid ASS end time in '{}': {}", path.display(), e))?;
            if end_cs <= start_cs {
                continue;
            }

            let mut text = event
                .text
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ");

            if !preserve_styles {
                text = strip_ass_override_tags(&text);
            }

            let text = text.trim().to_string();
            if text.is_empty() {
                continue;
            }

            cues.push(SourceSubtitleCue {
                start_us: start_cs as i64 * 10_000,
                end_us: end_cs as i64 * 10_000,
                text,
            });
        }
    }

    Ok(cues)
}

struct ExportDecodeSession {
    session: VideoDecodeSession,
    last_pts: i64,
    last_frame: Option<RgbaFrame>,
    pending_frame: Option<RgbaFrame>,
}

struct ImageSession {
    frame: RgbaFrame,
}

enum ExportSession {
    Video(ExportDecodeSession),
    Image(ImageSession),
}

struct ExportDecodeCache {
    sessions: HashMap<ClipId, ExportSession>,
    subtitle_renderers: HashMap<String, SubtitleRenderer>,
    image_cache: ImageCache,
    hardware_acceleration: bool,
}

impl ExportDecodeCache {
    fn new(hardware_acceleration: bool) -> Self {
        Self {
            sessions: HashMap::new(),
            subtitle_renderers: HashMap::new(),
            image_cache: ImageCache::new(),
            hardware_acceleration,
        }
    }

    fn extract_frame(
        &mut self,
        clip_id: ClipId,
        path: &Path,
        target_us: i64,
    ) -> Result<RgbaFrame, DecodeError> {
        if !self.sessions.contains_key(&clip_id) {
            if miniter_audio::util::is_image_file(path) {
                let frame = self
                    .image_cache
                    .get_frame(path)
                    .map_err(|e| DecodeError::NoVideoStream)?;
                let entry = ImageSession { frame };
                self.sessions.insert(clip_id, ExportSession::Image(entry));
            } else {
                let mut session = VideoDecodeSession::open(path, self.hardware_acceleration)?;
                let first_frame = session.next_frame()?;
                let entry = ExportDecodeSession {
                    session,
                    last_pts: first_frame.as_ref().map_or(0, |f| f.pts_us),
                    last_frame: first_frame,
                    pending_frame: None,
                };
                self.sessions.insert(clip_id, ExportSession::Video(entry));
            }
        }

        match self.sessions.get_mut(&clip_id).ok_or(DecodeError::Other(
            "clip_id missing from decode cache".into(),
        ))? {
            ExportSession::Image(entry) => Ok(entry.frame.clone()),
            ExportSession::Video(entry) => {
                if let Some(ref last) = entry.last_frame {
                    if target_us < last.pts_us {
                        entry.session.reset()?;
                        entry.last_pts = 0;
                        entry.last_frame = None;
                        entry.pending_frame = None;
                    } else if let Some(ref pending) = entry.pending_frame {
                        if target_us < pending.pts_us {
                            return Ok(last.clone());
                        } else {
                            entry.last_frame = Some(pending.clone());
                            entry.last_pts = pending.pts_us;
                            entry.pending_frame = None;
                        }
                    }
                    if let Some(ref last) = entry.last_frame {
                        if last.pts_us == target_us {
                            return Ok(last.clone());
                        }
                    }
                }

                loop {
                    match entry.session.next_frame()? {
                        Some(frame) => {
                            if frame.pts_us == target_us {
                                entry.last_pts = frame.pts_us;
                                entry.last_frame = Some(frame.clone());
                                entry.pending_frame = None;
                                return Ok(frame);
                            }
                            if frame.pts_us > target_us {
                                if let Some(ref last) = entry.last_frame {
                                    entry.pending_frame = Some(frame.clone());
                                    return Ok(last.clone());
                                }
                                entry.last_pts = frame.pts_us;
                                entry.last_frame = Some(frame.clone());
                                return Ok(frame);
                            }
                            entry.last_pts = frame.pts_us;
                            entry.last_frame = Some(frame);
                        }
                        None => {
                            if let Some(ref pending) = entry.pending_frame {
                                let p = pending.clone();
                                entry.last_frame = Some(p.clone());
                                entry.last_pts = pending.pts_us;
                                entry.pending_frame = None;
                                return Ok(p);
                            }
                            return entry.last_frame.clone().ok_or(DecodeError::NoVideoStream);
                        }
                    }
                }
            }
        }
    }

    fn get_subtitle_renderer(
        &mut self,
        path: &str,
        width: u32,
        height: u32,
    ) -> Result<&mut crate::subtitle::SubtitleRenderer, crate::subtitle::SubtitleError> {
        if !self.subtitle_renderers.contains_key(path) {
            let mut renderer = crate::subtitle::SubtitleRenderer::new(width, height)?;
            renderer.load_script(Path::new(path))?;
            self.subtitle_renderers.insert(path.to_string(), renderer);
        }

        Ok(self
            .subtitle_renderers
            .get_mut(path)
            .expect("subtitle renderer must exist"))
    }
}

enum AnyEncoder {
    Sw(VideoEncodeSession),
    Hw(HwEncodeSession),
}

impl AnyEncoder {
    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
        match self {
            AnyEncoder::Sw(e) => e.encode_frame(frame),
            AnyEncoder::Hw(e) => e.encode_frame(frame),
        }
    }

    fn width(&self) -> u32 {
        match self {
            AnyEncoder::Sw(e) => e.width(),
            AnyEncoder::Hw(e) => e.width(),
        }
    }

    fn height(&self) -> u32 {
        match self {
            AnyEncoder::Sw(e) => e.height(),
            AnyEncoder::Hw(e) => e.height(),
        }
    }
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
    let subtitle_samples = if project.export_profile.subtitle_mode == SubtitleMode::Soft {
        collect_soft_subtitle_samples(project, output_path)
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

    let mut decode_cache = ExportDecodeCache::new(project.export_profile.hardware_acceleration);
    let first_decoded_video_pts_us = AtomicI64::new(-1);
    on_progress(1);
    let config = MixConfig::default();
    let mixed = mix_project_audio(project, config)?;
    let audio_encoded = if !mixed.samples.is_empty() {
        let bitrate_bps = project
            .export_profile
            .audio_bitrate_kbps
            .max(32)
            .saturating_mul(1000);
        Some(encode_opus(&mixed, bitrate_bps)?)
    } else {
        None
    };
    on_progress(5);

    let audio_track = audio_encoded.as_ref().map(|oe| OpusTrackConfigOut {
        sample_rate: 48_000,
        channels: oe.channels,
    });

    let mut encoder = if project.export_profile.hardware_acceleration {
        match HwEncodeSession::new(width, height, bitrate_kbps * 1000, fps as f32, "video/avc") {
            Ok(hw) => AnyEncoder::Hw(hw),
            Err(e) => {
                log::warn!("HW encoder failed, falling back to software: {e}");
                HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
                AnyEncoder::Sw(VideoEncodeSession::new(
                    width,
                    height,
                    bitrate_kbps * 1000,
                    fps as f32,
                )?)
            }
        }
    } else {
        AnyEncoder::Sw(VideoEncodeSession::new(
            width,
            height,
            bitrate_kbps * 1000,
            fps as f32,
        )?)
    };

    let mut iter = FramePlanIterator::with_render_settings(
        &project.timeline,
        width,
        height,
        fps,
        project.export_profile.subtitle_mode,
    );
    let total_frames = iter.total_frames() as u32;
    let first_plan = iter.next().unwrap_or_else(|| {
        plan_frame(
            &project.timeline,
            Timestamp::ZERO,
            width,
            height,
            project.export_profile.subtitle_mode,
        )
    });

    if is_cancelled() {
        return Err(ExportError::Cancelled);
    }

    let first_rgba =
        render_plan_to_rgba(&first_plan, &mut decode_cache, &first_decoded_video_pts_us)?;
    store_preview_frame(&first_rgba, width, height);
    let first_frame = RgbaFrame {
        width,
        height,
        data: first_rgba,
        pts_us: first_plan.timestamp.as_micros(),
    };
    let first_output = encoder.encode_frame(&first_frame)?;
    let (bytes, is_keyframe) = match first_output {
        EncodedVideoOutput::Sample { bytes, is_keyframe } => (bytes, is_keyframe),
        EncodedVideoOutput::Skipped => {
            return Err(EncodeError::SkippedFrame { frame_index: 0 }.into());
        }
    };
    if bytes.is_empty() || !has_annexb_start_code(&bytes) {
        return Err(EncodeError::EmptyFrame { frame_index: 0 }.into());
    }
    let (sps, pps) = extract_sps_pps(&bytes).ok_or(ExportError::MissingAvcConfig)?;

    let file = File::create(output_path)?;
    let writer = BufWriter::new(file);
    let mut muxer = Mp4Muxer::new(
        writer,
        width,
        height,
        fps,
        &sps,
        &pps,
        container,
        audio_track,
        subtitle_track,
        VideoTrackCodecOut::H264,
    )?;
    muxer.write_sample_at(first_frame.pts_us as u64, &bytes, is_keyframe)?;

    let mut frame_count: u32 = 1;
    for plan in iter {
        if is_cancelled() {
            return Err(ExportError::Cancelled);
        }

        let rgba = render_plan_to_rgba(&plan, &mut decode_cache, &first_decoded_video_pts_us)?;
        store_preview_frame(&rgba, width, height);
        let frame = RgbaFrame {
            width,
            height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
        };
        let output = encoder.encode_frame(&frame)?;
        let (bytes, is_keyframe) = match output {
            EncodedVideoOutput::Sample { bytes, is_keyframe } => (bytes, is_keyframe),
            EncodedVideoOutput::Skipped => {
                return Err(EncodeError::SkippedFrame {
                    frame_index: frame_count,
                }
                .into());
            }
        };
        if bytes.is_empty() || !has_annexb_start_code(&bytes) {
            return Err(EncodeError::EmptyFrame {
                frame_index: frame_count,
            }
            .into());
        }
        muxer.write_sample_at(frame.pts_us as u64, &bytes, is_keyframe)?;

        frame_count += 1;
        if total_frames > 0 {
            let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
            on_progress(pct);
        }
    }

    on_progress(100_000);

    if let Some(oe) = audio_encoded {
        let start_anchor_us = first_decoded_video_pts_us.load(Ordering::Relaxed).max(0) as u64;
        write_audio_packets(&mut muxer, &oe, start_anchor_us)?;
    }

    write_soft_subtitle_samples(&mut muxer, &subtitle_samples)?;

    muxer.finish()?;

    Ok(())
}

fn render_plan_to_rgba(
    plan: &RenderPlan,
    decode_cache: &mut ExportDecodeCache,
    first_decoded_video_pts_us: &AtomicI64,
) -> Result<Vec<u8>, ExportError> {
    let mut rgba = render_node(
        &plan.root,
        plan.width as usize,
        plan.height as usize,
        decode_cache,
        first_decoded_video_pts_us,
    )?;
    flatten_on_black(&mut rgba);
    Ok(rgba)
}

fn render_node(
    node: &RenderNode,
    width: usize,
    height: usize,
    decode_cache: &mut ExportDecodeCache,
    first_decoded_video_pts_us: &AtomicI64,
) -> Result<Vec<u8>, ExportError> {
    match node {
        RenderNode::VideoFrame {
            clip_id,
            source_path,
            source_pts,
            filters,
            opacity,
        } => {
            let frame = decode_cache.extract_frame(
                *clip_id,
                Path::new(source_path),
                source_pts.as_micros().max(0),
            )?;
            if first_decoded_video_pts_us.load(Ordering::Relaxed) < 0 {
                let _ = first_decoded_video_pts_us.compare_exchange(
                    -1,
                    frame.pts_us,
                    Ordering::Relaxed,
                    Ordering::Relaxed,
                );
            }
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
            let mut img = render_text_overlay(overlay, width, height);
            filters::scale_alpha(&mut img, *opacity);
            Ok(img)
        }

        RenderNode::Subtitle {
            source_path,
            source_pts: _,
            opacity,
        } => {
            if !Path::new(source_path).exists() {
                return Ok(transparent_rgba(width, height));
            }

            let time_cs = (first_decoded_video_pts_us.load(Ordering::Relaxed) / 10_000) as i64;

            match decode_cache.get_subtitle_renderer(source_path, width as u32, height as u32) {
                Ok(renderer) => match renderer.render_frame(time_cs) {
                    Ok(frame) => {
                        let mut img = frame.into_buffer();
                        if img.len() == width * height * 4 {
                            filters::scale_alpha(&mut img, *opacity);
                            return Ok(img);
                        }
                    }
                    Err(e) => {
                        log::warn!("Subtitle render error: {}", e);
                    }
                },
                Err(e) => {
                    log::warn!("Subtitle renderer init error: {}", e);
                }
            }

            Ok(transparent_rgba(width, height))
        }

        RenderNode::Stack(children) => {
            let mut canvas = transparent_rgba(width, height);
            for child in children {
                let layer = render_node(
                    child,
                    width,
                    height,
                    decode_cache,
                    first_decoded_video_pts_us,
                )?;
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
            let bottom_img = render_node(
                bottom,
                width,
                height,
                decode_cache,
                first_decoded_video_pts_us,
            )?;
            let top_img =
                render_node(top, width, height, decode_cache, first_decoded_video_pts_us)?;

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
    }
}

fn export_av1<F>(
    project: &Project,
    output_path: &Path,
    width: u32,
    height: u32,
    fps: f64,
    bitrate_kbps: u32,
    container: Av1Container,
    is_cancelled: &F,
    on_progress: &dyn Fn(u32),
) -> Result<(), ExportError>
where
    F: Fn() -> bool,
{
    let subtitle_samples = if container == Av1Container::Mp4
        && project.export_profile.subtitle_mode == SubtitleMode::Soft
    {
        collect_soft_subtitle_samples(project, output_path)
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

    let mut decode_cache = ExportDecodeCache::new(project.export_profile.hardware_acceleration);
    let first_decoded_video_pts_us = AtomicI64::new(-1);
    on_progress(1);

    let (audio_track, audio_encoded) = if container == Av1Container::Mp4 {
        let config = MixConfig::default();
        let mixed = mix_project_audio(project, config)?;
        let audio_encoded = if !mixed.samples.is_empty() {
            let bitrate_bps = project
                .export_profile
                .audio_bitrate_kbps
                .max(32)
                .saturating_mul(1000);
            Some(encode_opus(&mixed, bitrate_bps)?)
        } else {
            None
        };

        let audio_track = audio_encoded.as_ref().map(|oe| OpusTrackConfigOut {
            sample_rate: 48_000,
            channels: oe.channels,
        });

        (audio_track, audio_encoded)
    } else {
        (None, None)
    };

    if project.export_profile.hardware_acceleration {
        if HwEncodeSession::new(
            width,
            height,
            bitrate_kbps * 1000,
            fps as f32,
            "video/av01",
        )
        .is_err()
        {
            log::warn!("HW AV1 encoder not available, falling back to software");
            HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
        }
    }

    on_progress(5);

    let fps_int = fps.round().max(1.0) as u32;
    let mut encoder = Av1EncodeSession::new(width, height, fps, bitrate_kbps)?;

    let mut ivf_file: Option<File> = None;
    let mut mp4_muxer: Option<Mp4Muxer<BufWriter<File>>> = None;

    match container {
        Av1Container::Ivf => {
            let mut file = File::create(output_path)?;
            ivf::write_ivf_header(
                &mut file,
                width as usize,
                height as usize,
                fps_int as usize,
                1,
            );
            ivf_file = Some(file);
        }
        Av1Container::Mp4 => {
            let file = File::create(output_path)?;
            let writer = BufWriter::new(file);
            mp4_muxer = Some(Mp4Muxer::new(
                writer,
                width,
                height,
                fps,
                &[],
                &[],
                ContainerFormat::Mp4,
                audio_track,
                subtitle_track.clone(),
                VideoTrackCodecOut::Av1,
            )?);
        }
        Av1Container::Mkv | Av1Container::WebM => {
            return Err(ExportError::MkvNotAvailable {
                format: if container == Av1Container::WebM {
                    "WebM".to_string()
                } else {
                    "MKV".to_string()
                },
            });
        }
    }

    let mut iter = FramePlanIterator::with_render_settings(
        &project.timeline,
        width,
        height,
        fps,
        project.export_profile.subtitle_mode,
    );
    let total_frames = iter.total_frames() as u32;
    let first_plan = iter.next().unwrap_or_else(|| {
        plan_frame(
            &project.timeline,
            Timestamp::ZERO,
            width,
            height,
            project.export_profile.subtitle_mode,
        )
    });

    let mut frame_count: u32 = 0;
    let mut ivf_packet_count: u32 = 0;
    for plan in std::iter::once(first_plan).chain(iter) {
        if is_cancelled() {
            return Err(ExportError::Cancelled);
        }

        let rgba = render_plan_to_rgba(&plan, &mut decode_cache, &first_decoded_video_pts_us)?;
        store_preview_frame(&rgba, width, height);
        let frame = RgbaFrame {
            width,
            height,
            data: rgba,
            pts_us: plan.timestamp.as_micros(),
        };

        let packets = encoder.encode_frame(&frame)?;

        match container {
            Av1Container::Ivf => {
                let file = ivf_file
                    .as_mut()
                    .expect("IVF file must exist for AV1 IVF export");
                for packet in packets {
                    // NOTE: previously, At 30fps, PTS=33333 (1/30s in μs) was interpreted by players as 33333 × 1/30 = 1111s.
                    let pts_tbn = (packet.pts * fps_int as u64 + 500_000) / 1_000_000;
                    ivf::write_ivf_frame(file, pts_tbn, &packet.data);
                    ivf_packet_count = ivf_packet_count.saturating_add(1);
                }
            }
            Av1Container::Mp4 => {
                let muxer = mp4_muxer
                    .as_mut()
                    .expect("MP4 muxer must exist for AV1 MP4 export");
                write_av1_packets_to_mux(muxer, &packets)?;
            }
            Av1Container::Mkv | Av1Container::WebM => {
                unreachable!();
            }
        }

        frame_count = frame_count.saturating_add(1);
        if total_frames > 0 {
            let pct = ((frame_count as f64 / total_frames as f64) * 100_000.0) as u32;
            on_progress(pct);
        }
    }

    let finish_packets = encoder.finish()?;

    match container {
        Av1Container::Ivf => {
            let file = ivf_file
                .as_mut()
                .expect("IVF file must exist for AV1 IVF export");

            for packet in finish_packets {
                let pts_tbn = (packet.pts * fps_int as u64 + 500_000) / 1_000_000;
                ivf::write_ivf_frame(file, pts_tbn, &packet.data);
                ivf_packet_count = ivf_packet_count.saturating_add(1);
            }

            use std::io::{Seek, SeekFrom, Write};
            file.seek(SeekFrom::Start(24))?;
            file.write_all(&ivf_packet_count.to_le_bytes())?;
            file.seek(SeekFrom::End(0))?;
        }
        Av1Container::Mp4 => {
            let muxer = mp4_muxer
                .as_mut()
                .expect("MP4 muxer must exist for AV1 MP4 export");
            write_av1_packets_to_mux(muxer, &finish_packets)?;

            if let Some(oe) = audio_encoded {
                let start_anchor_us =
                    first_decoded_video_pts_us.load(Ordering::Relaxed).max(0) as u64;
                write_audio_packets(muxer, &oe, start_anchor_us)?;
            }

            write_soft_subtitle_samples(muxer, &subtitle_samples)?;

            muxer.finish()?;
        }
        Av1Container::Mkv | Av1Container::WebM => {
            unreachable!();
        }
    }

    on_progress(100_000);
    Ok(())
}

fn write_av1_packets_to_mux<W: std::io::Write>(
    muxer: &mut Mp4Muxer<W>,
    packets: &[Av1Packet],
) -> Result<usize, ExportError> {
    let mut written = 0usize;
    for packet in packets {
        let pts_us = packet.pts;

        let sample_data = strip_leading_temporal_delimiters(&packet.data);
        if sample_data.is_empty() {
            continue;
        }
        muxer.write_sample_at(pts_us, sample_data, packet.is_keyframe)?;
        written += 1;
    }
    Ok(written)
}

fn encode_opus(
    mixed: &MixedAudio,
    bitrate_bps: u32,
) -> Result<crate::export_shared::EncodedOpus, OpusEncodeError> {
    crate::export_shared::encode_opus(mixed, bitrate_bps).map_err(|e| OpusEncodeError::Encoder(e))
}

#[derive(Debug, thiserror::Error)]
pub enum OpusEncodeError {
    #[error("Unsupported sample rate: {0}")]
    UnsupportedSampleRate(u32),
    #[error("Unsupported channel count: {0}")]
    UnsupportedChannels(u16),
    #[error("Opus encoder error: {0}")]
    Encoder(String),
}
