use std::collections::HashMap;
use std::io::{BufReader, Cursor, Seek, SeekFrom, Write};
use std::path::Path;

use font8x8::{UnicodeFonts, BASIC_FONTS};
use miniter_audio::mix::{mix_project_audio_with_source_map, MixConfig, MixedAudio};
use miniter_domain::clip::{ClipId, ClipKind, VideoClip};
use miniter_domain::export::{ExportFormat, SubtitleMode};
use miniter_domain::filter::VideoFilter;
use miniter_domain::project::Project;
use miniter_domain::text_overlay::{TextAlignment, TextOverlay, TextStyle};
use miniter_domain::time::Timestamp;
use miniter_domain::track::TrackKind;
use miniter_media_native::decoder::{DecodeError, VideoDecodeSession};
use miniter_media_native::encoder::{EncodedVideoOutput, VideoEncodeSession};
use miniter_media_native::encoder_av1::{Av1EncodeSession, Av1Packet};
use miniter_media_native::frame::RgbaFrame;
use miniter_media_native::mux::{
    extract_sps_pps, ContainerFormat, Mp4Muxer, OpusTrackConfigOut, SubtitleTrackCodecOut,
    SubtitleTrackConfigOut, VideoTrackCodecOut,
};
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

#[derive(Debug, Clone)]
struct SubtitleCue {
    start_us: i64,
    end_us: i64,
    text: String,
}

#[derive(Debug, Clone)]
struct SoftSubtitleSample {
    start_us: i64,
    duration_us: i64,
    text: String,
}

struct ExportDecodeCache<'a> {
    sessions: HashMap<ClipId, ExportDecodeSession>,
    subtitle_cues: HashMap<String, Vec<SubtitleCue>>,
    first_decoded_video_pts_us: Option<i64>,
    registered_files: &'a HashMap<String, Vec<u8>>,
}

impl<'a> ExportDecodeCache<'a> {
    fn new(registered_files: &'a HashMap<String, Vec<u8>>) -> Self {
        Self {
            sessions: HashMap::new(),
            subtitle_cues: HashMap::new(),
            first_decoded_video_pts_us: None,
            registered_files,
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

#[derive(Debug, Clone)]
struct EncodedOpusPacket {
    pts_us: u64,
    bytes: Vec<u8>,
}

#[derive(Debug, Clone)]
struct EncodedOpus {
    channels: u16,
    packets: Vec<EncodedOpusPacket>,
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

fn encode_opus(mixed: &MixedAudio, bitrate_bps: u32) -> Result<EncodedOpus, String> {
    use mousiki::{
        Application as MousikiApplication, Bitrate as MousikiBitrate, Channels as MousikiChannels,
        Encoder as MousikiEncoder, FrameDuration as MousikiFrameDuration,
    };

    let channels = mixed.channels;

    let mousiki_channels = match channels {
        1 => MousikiChannels::Mono,
        2 => MousikiChannels::Stereo,
        other => return Err(format!("Unsupported channel count: {other}")),
    };

    let sample_rate = 48_000u32;
    let pcm = if mixed.sample_rate == sample_rate {
        mixed.samples.clone()
    } else {
        if mixed.sample_rate == 0 {
            return Err("Unsupported sample rate: 0".to_string());
        }
        resample_interleaved_linear(
            &mixed.samples,
            mixed.sample_rate,
            sample_rate,
            channels as usize,
        )
    };

    let mut encoder =
        MousikiEncoder::builder(sample_rate, mousiki_channels, MousikiApplication::Audio)
            .bitrate(MousikiBitrate::Bits(bitrate_bps.min(i32::MAX as u32) as i32))
            .complexity(10)
            .vbr(false)
            .inband_fec(false)
            .packet_loss_perc(0)
            .frame_duration(MousikiFrameDuration::Ms20)
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

fn resample_interleaved_linear(
    input: &[f32],
    in_rate: u32,
    out_rate: u32,
    channels: usize,
) -> Vec<f32> {
    if in_rate == out_rate || input.is_empty() {
        return input.to_vec();
    }

    let in_frames = input.len() / channels;
    if in_frames == 0 {
        return Vec::new();
    }

    let out_frames = ((in_frames as u64 * out_rate as u64) / in_rate as u64) as usize;
    let mut out = vec![0.0f32; out_frames * channels];

    for of in 0..out_frames {
        let src_pos = (of as f64) * (in_rate as f64) / (out_rate as f64);
        let i0 = src_pos.floor() as usize;
        let i1 = (i0 + 1).min(in_frames.saturating_sub(1));
        let t = (src_pos - i0 as f64) as f32;

        for ch in 0..channels {
            let a = input[i0 * channels + ch];
            let b = input[i1 * channels + ch];
            out[of * channels + ch] = a + (b - a) * t;
        }
    }

    out
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

fn parse_srt_time_range(line: &str) -> Option<(i64, i64)> {
    let mut parts = line.split("-->");
    let start = parts.next()?.trim();
    let end = parts.next()?.trim();
    if parts.next().is_some() {
        return None;
    }
    Some((parse_srt_timestamp_us(start)?, parse_srt_timestamp_us(end)?))
}

fn parse_srt_timestamp_us(value: &str) -> Option<i64> {
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

fn normalize_ass_text(input: &str) -> String {
    let mut text = input
        .replace("\\N", "\n")
        .replace("\\n", "\n")
        .replace("\\h", " ");
    text = strip_ass_override_tags(&text);
    text
}

fn strip_ass_override_tags(text: &str) -> String {
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
