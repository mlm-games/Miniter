use image::GenericImageView;
use miniter_audio::probe;
use std::fs::File;
use std::io::{BufReader, Cursor, Read, Seek};
use std::path::Path;
use symphonia::core::codecs::video::VideoCodecId;
use symphonia::core::io::MediaSourceStream;

use symphonia::core::codecs::video::well_known::{
    CODEC_ID_AV1, CODEC_ID_H264, CODEC_ID_HEVC, CODEC_ID_VP8, CODEC_ID_VP9,
};

/// Check whether a decoder is available for a given Symphonia codec at compile time.
fn decoder_supported(codec: VideoCodecId) -> bool {
    let hw_avail = cfg!(all(
        feature = "hw-decoder",
        any(
            target_arch = "wasm32",
            target_os = "linux",
            target_os = "android"
        )
    ));
    match codec {
        CODEC_ID_H264 => cfg!(feature = "videoson") || hw_avail,
        CODEC_ID_HEVC => cfg!(any(feature = "videoson", feature = "videoson-h265")) || hw_avail,
        CODEC_ID_VP8 => cfg!(any(feature = "videoson", feature = "videoson-vp8")) || hw_avail,
        CODEC_ID_VP9 => cfg!(any(feature = "videoson", feature = "videoson-vp9")) || hw_avail,
        CODEC_ID_AV1 => cfg!(feature = "av1") || hw_avail,
        _ => false,
    }
}

fn requires_hardware_acceleration(codec: VideoCodecId) -> bool {
    let hw_avail = cfg!(all(
        feature = "hw-decoder",
        any(
            target_arch = "wasm32",
            target_os = "linux",
            target_os = "android"
        )
    ));
    match codec {
        CODEC_ID_H264 => !cfg!(feature = "videoson") && hw_avail,
        CODEC_ID_HEVC => !cfg!(any(feature = "videoson", feature = "videoson-h265")),
        CODEC_ID_VP8 => !cfg!(any(feature = "videoson", feature = "videoson-vp8")),
        CODEC_ID_VP9 => !cfg!(any(feature = "videoson", feature = "videoson-vp9")),
        CODEC_ID_AV1 => !cfg!(feature = "av1") && hw_avail,
        _ => true,
    }
}

#[derive(Debug, Clone)]
pub struct MediaInfo {
    pub duration_us: Option<i64>,
    pub video_streams: Vec<VideoStreamInfo>,
    pub audio_streams: Vec<AudioStreamInfo>,
}

#[derive(Debug, Clone)]
pub struct VideoStreamInfo {
    pub track_id: u32,
    pub codec: String,
    pub width: u32,
    pub height: u32,
    pub frame_rate: f64,
    pub bitrate: u32,
    pub decoder_available: bool,
    pub hardware_acceleration_required: bool,
}

#[derive(Debug, Clone)]
pub struct AudioStreamInfo {
    pub track_id: u32,
    pub codec: String,
    pub sample_rate: u32,
    pub channels: u32,
    pub bitrate: u32,
}

#[derive(Debug, thiserror::Error)]
pub enum MediaProbeError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("IVF parse error: {0}")]
    Ivf(String),
    #[error("Audio parse error: {0}")]
    Audio(String),
    #[error("Symphonia probe error: {0}")]
    Symphonia(String),
    #[error("No video track found")]
    NoVideoTrack,
}

pub fn probe_media(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    match ext.as_str() {
        "ivf" => probe_ivf(path),
        "wav" => probe_wav(path),
        "mp3" | "ogg" | "m4a" | "aac" | "flac" => probe_audio_only(path),
        "png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "tif" => probe_image(path),
        "mp4" | "m4v" | "mov" | "3gp" | "mkv" | "webm" | "avi" | "ogv" => probe_symphonia(path),
        _ => probe_symphonia(path),
    }
}

pub fn probe_media_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<MediaInfo, MediaProbeError> {
    let ext = extension_hint.unwrap_or("").to_lowercase();

    match ext.as_str() {
        "ivf" => probe_ivf_bytes(bytes),
        "wav" => probe_wav_bytes(bytes),
        "mp3" | "ogg" | "m4a" | "aac" | "flac" => probe_audio_only_bytes(bytes, extension_hint),
        "png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "tif" => {
            probe_image_bytes(bytes, extension_hint)
        }
        "mp4" | "m4v" | "mov" | "3gp" | "mkv" | "webm" | "avi" | "ogv" => {
            probe_symphonia_bytes(bytes, extension_hint)
        }
        _ => probe_symphonia_bytes(bytes, extension_hint),
    }
}

fn probe_symphonia(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let (mss, ext) = miniter_audio::util::open_mss_from_path(path)?;
    probe_media_info_from_mss(mss, ext.as_deref())
}

fn probe_symphonia_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<MediaInfo, MediaProbeError> {
    let (mss, _ext) = miniter_audio::util::open_mss_from_bytes(bytes, extension_hint);
    probe_media_info_from_mss(mss, extension_hint)
}

fn probe_media_info_from_mss(
    mss: MediaSourceStream<'_>,
    ext: Option<&str>,
) -> Result<MediaInfo, MediaProbeError> {
    let mut reader = miniter_audio::util::probe(mss, ext)
        .map_err(|e| MediaProbeError::Symphonia(e.to_string()))?;

    let mut duration_us = reader
        .tracks()
        .iter()
        .find_map(|t| {
            let tb = t.time_base?;
            let dur = t.duration?;
            let ts = symphonia::core::units::Timestamp::new(dur.get() as i64);
            let time = tb.calc_time(ts)?;
            let us = time.as_micros() as i64;
            (us > 0).then_some(us)
        })
        // Fallback to media-level duration for formats like MKV/WebM
        .or_else(|| {
            let mi = reader.media_info();
            let tb = mi.time_base?;
            let dur = mi.duration?;
            let ts = symphonia::core::units::Timestamp::new(dur.get() as i64);
            let time = tb.calc_time(ts)?;
            let us = time.as_micros() as i64;
            (us > 0).then_some(us)
        });

    // Last resort for containers whose header lacks Duration AND Cues (e.g.
    // streamed MediaRecorder output).
    let mut scanned_packet_counts: std::collections::HashMap<u32, u64> =
        std::collections::HashMap::new();
    if duration_us.is_none_or(|us| us <= 0) {
        let (scanned_us, counts) = estimate_duration_by_packet_scan(&mut reader);
        scanned_packet_counts = counts;
        if let Some(us) = scanned_us {
            if us > 0 {
                log::debug!(
                    "probe: header duration missing (ext={ext:?}), using packet-scan estimate {us}us"
                );
                duration_us = Some(us);
            }
        }
    }

    let mut video_streams = Vec::new();
    let mut audio_streams = Vec::new();

    for track in reader.tracks() {
        if let Some(v) = track.codec_params.as_ref().and_then(|p| p.video()) {
            let codec_name = crate::demux::symphonia_demux::format_codec_name(v.codec);
            let mut fps = track_frame_rate(track, duration_us);
            // Cueless WebM often lacks num_frames too; estimate fps from
            // scanned packet count when header-based estimation failed.
            if fps <= 0.0 {
                if let (Some(&count), Some(dus)) =
                    (scanned_packet_counts.get(&track.id), duration_us)
                {
                    if dus > 0 && count > 0 {
                        let est = count as f64 / (dus as f64 / 1_000_000.0);
                        if est.is_finite() && est > 0.0 && est < 240.0 {
                            fps = est;
                        }
                    }
                }
            }
            video_streams.push(VideoStreamInfo {
                track_id: track.id,
                codec: codec_name,
                width: v.width.unwrap_or(0) as u32,
                height: v.height.unwrap_or(0) as u32,
                frame_rate: fps,
                bitrate: 0,
                decoder_available: decoder_supported(v.codec),
                hardware_acceleration_required: requires_hardware_acceleration(v.codec),
            });
        } else if let Some(a) = track.codec_params.as_ref().and_then(|p| p.audio()) {
            audio_streams.push(AudioStreamInfo {
                track_id: track.id,
                codec: format!("{:?}", a.codec),
                sample_rate: a.sample_rate.unwrap_or(0),
                channels: a.channels.as_ref().map(|c| c.count() as u32).unwrap_or(0),
                bitrate: 0,
            });
        }
    }

    Ok(MediaInfo {
        duration_us,
        video_streams,
        audio_streams,
    })
}

/// Estimate frame rate from symphonia track params (num_frames + time base +
/// duration) instead of hardcoding 0.0.
fn track_frame_rate(track: &symphonia::core::formats::Track, duration_us: Option<i64>) -> f64 {
    if let (Some(frames), Some(tb), Some(dur)) = (track.num_frames, track.time_base, track.duration)
    {
        let ticks = dur.get() as f64;
        let secs_per_tick = tb.numer.get() as f64 / tb.denom.get().max(1) as f64;
        let secs = ticks * secs_per_tick;
        if secs > 0.0 && frames > 0 {
            let fps = frames as f64 / secs;
            if fps.is_finite() && fps > 0.0 && fps < 240.0 {
                return fps;
            }
        }
    }
    if let (Some(frames), Some(dus)) = (track.num_frames, duration_us) {
        if dus > 0 && frames > 0 {
            let fps = frames as f64 / (dus as f64 / 1_000_000.0);
            if fps.is_finite() && fps > 0.0 && fps < 240.0 {
                return fps;
            }
        }
    }
    0.0
}

/// Last-resort duration estimation for containers (notably streamed WebM/MKV)
/// whose header lacks both Duration and Cues. Scans demuxed packets and
/// returns the max (pts + dur) converted to microseconds, plus per-track
/// packet counts for fps estimation. Bounded by MAX_PACKETS; returns
/// (None, counts) when no usable timestamps exist.
fn estimate_duration_by_packet_scan(
    reader: &mut Box<dyn symphonia::core::formats::FormatReader + '_>,
) -> (Option<i64>, std::collections::HashMap<u32, u64>) {
    use std::collections::HashMap;
    use symphonia::core::units::Timestamp;

    let time_bases: HashMap<u32, symphonia::core::units::TimeBase> = reader
        .tracks()
        .iter()
        .filter_map(|t| t.time_base.map(|tb| (t.id, tb)))
        .collect();

    let mut counts: HashMap<u32, u64> = HashMap::new();
    let mut max_us: i64 = 0;
    let mut packets: u64 = 0;
    // Guard against pathological/corrupt files; 2M packets is far beyond any
    // realistic probe while still bounding scan time.
    const MAX_PACKETS: u64 = 2_000_000;

    loop {
        if packets >= MAX_PACKETS {
            log::warn!("probe: packet scan hit {MAX_PACKETS} packet cap, using partial estimate");
            break;
        }
        let pkt = match reader.next_packet() {
            Ok(Some(p)) => p,
            Ok(None) => break, // EOF
            Err(_) => break,   // Use partial max on mid-stream errors
        };
        packets += 1;
        *counts.entry(pkt.track_id).or_insert(0) += 1;

        let Some(tb) = time_bases.get(&pkt.track_id).copied() else {
            continue;
        };
        // End timestamp = pts + dur (dur may be 0 when the muxer omits
        // BlockDuration; pts alone still advances the max).
        let end_ticks = pkt.pts.get().saturating_add(pkt.dur.get() as i64);
        let end_ticks = end_ticks.max(pkt.pts.get());
        if end_ticks <= 0 {
            continue;
        }
        let Some(time) = tb.calc_time(Timestamp::new(end_ticks)) else {
            continue;
        };
        let us = time.as_micros().min(i64::MAX as i128) as i64;
        if us > max_us {
            max_us = us;
        }
    }

    if max_us > 0 {
        (Some(max_us), counts)
    } else {
        (None, counts)
    }
}

fn probe_ivf(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let file = File::open(path)?;
    let file_size = file.metadata()?.len();
    read_ivf_into_media_info(BufReader::new(file), file_size)
}

fn probe_ivf_bytes(bytes: &[u8]) -> Result<MediaInfo, MediaProbeError> {
    let file_size = bytes.len() as u64;
    read_ivf_into_media_info(BufReader::new(Cursor::new(bytes.to_vec())), file_size)
}

fn read_ivf_into_media_info<R: Read + Seek>(
    mut reader: BufReader<R>,
    file_size: u64,
) -> Result<MediaInfo, MediaProbeError> {
    let header = ivf::read_header(&mut reader).map_err(|e| MediaProbeError::Ivf(e.to_string()))?;

    let width = header.w as u32;
    let height = header.h as u32;
    let fps = if header.timebase_den > 0 {
        header.timebase_den as f64 / header.timebase_num as f64
    } else {
        30.0
    };

    let mut frame_count: u64 = 0;
    loop {
        match ivf::read_packet(&mut reader) {
            Ok(_) => frame_count += 1,
            Err(_) => break,
        }
    }

    let duration_us = if fps > 0.0 {
        Some(((frame_count as f64 / fps) * 1_000_000.0) as i64)
    } else {
        None
    };

    let bitrate = if let Some(dur_us) = duration_us {
        if dur_us > 0 {
            ((file_size * 8 * 1_000_000) / dur_us as u64) as u32
        } else {
            0
        }
    } else {
        0
    };

    Ok(MediaInfo {
        duration_us,
        video_streams: vec![VideoStreamInfo {
            track_id: 1,
            codec: "AV1".to_string(),
            width,
            height,
            frame_rate: fps,
            bitrate,
            decoder_available: cfg!(feature = "av1")
                || cfg!(all(
                    feature = "hw-decoder",
                    any(
                        target_arch = "wasm32",
                        target_os = "linux",
                        target_os = "android"
                    )
                )),
            hardware_acceleration_required: !cfg!(feature = "av1"),
        }],
        audio_streams: Vec::new(),
    })
}

fn probe_wav(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let meta = probe::probe_audio(path).map_err(|e| MediaProbeError::Audio(e.to_string()))?;

    Ok(MediaInfo {
        duration_us: meta.duration_us,
        video_streams: Vec::new(),
        audio_streams: vec![AudioStreamInfo {
            track_id: 1,
            codec: meta.codec,
            sample_rate: meta.sample_rate,
            channels: meta.channels as u32,
            bitrate: 0,
        }],
    })
}

fn probe_wav_bytes(bytes: &[u8]) -> Result<MediaInfo, MediaProbeError> {
    let meta = probe::probe_audio_bytes(bytes, Some("wav"))
        .map_err(|e| MediaProbeError::Audio(e.to_string()))?;

    Ok(MediaInfo {
        duration_us: meta.duration_us,
        video_streams: Vec::new(),
        audio_streams: vec![AudioStreamInfo {
            track_id: 1,
            codec: meta.codec,
            sample_rate: meta.sample_rate,
            channels: meta.channels as u32,
            bitrate: 0,
        }],
    })
}

fn probe_audio_only(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let meta = probe::probe_audio(path).map_err(|e| MediaProbeError::Audio(e.to_string()))?;

    Ok(MediaInfo {
        duration_us: meta.duration_us,
        video_streams: Vec::new(),
        audio_streams: vec![AudioStreamInfo {
            track_id: 1,
            codec: meta.codec,
            sample_rate: meta.sample_rate,
            channels: meta.channels as u32,
            bitrate: 0,
        }],
    })
}

fn probe_audio_only_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<MediaInfo, MediaProbeError> {
    let meta = probe::probe_audio_bytes(bytes, extension_hint)
        .map_err(|e| MediaProbeError::Audio(e.to_string()))?;

    Ok(MediaInfo {
        duration_us: meta.duration_us,
        video_streams: Vec::new(),
        audio_streams: vec![AudioStreamInfo {
            track_id: 1,
            codec: meta.codec,
            sample_rate: meta.sample_rate,
            channels: meta.channels as u32,
            bitrate: 0,
        }],
    })
}

fn probe_image(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let img =
        image::open(path).map_err(|e| MediaProbeError::Io(std::io::Error::other(e.to_string())))?;
    let (width, height) = img.dimensions();
    Ok(MediaInfo {
        duration_us: None,
        video_streams: vec![VideoStreamInfo {
            track_id: 1,
            codec: "Image".to_string(),
            width,
            height,
            frame_rate: 0.0,
            bitrate: 0,
            decoder_available: true,
            hardware_acceleration_required: false,
        }],
        audio_streams: Vec::new(),
    })
}

pub fn probe_image_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<MediaInfo, MediaProbeError> {
    use std::io::Cursor;
    let fmt = extension_hint
        .and_then(image::ImageFormat::from_extension)
        .unwrap_or(image::ImageFormat::Png);
    let img = image::load(Cursor::new(bytes), fmt)
        .map_err(|e| MediaProbeError::Io(std::io::Error::other(e.to_string())))?;
    let (width, height) = img.dimensions();
    Ok(MediaInfo {
        duration_us: None,
        video_streams: vec![VideoStreamInfo {
            track_id: 1,
            codec: "Image".to_string(),
            width,
            height,
            frame_rate: 0.0,
            bitrate: 0,
            decoder_available: true,
            hardware_acceleration_required: false,
        }],
        audio_streams: Vec::new(),
    })
}
