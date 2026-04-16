use miniter_audio::probe;
use mp4::Mp4Reader;
use std::fs::File;
use std::io::BufReader;
use std::io::Cursor;
use std::path::Path;

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
    #[error("MP4 parse error: {0}")]
    Mp4(#[from] mp4::Error),
    #[error("IVF parse error: {0}")]
    Ivf(String),
    #[error("Audio parse error: {0}")]
    Audio(String),
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
        _ => probe_mp4(path),
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
        _ => probe_mp4_bytes(bytes),
    }
}

fn probe_mp4(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let file = File::open(path)?;
    let size = file.metadata()?.len();
    let reader = BufReader::new(file);
    let mp4 = match Mp4Reader::read_header(reader, size) {
        Ok(m) => m,
        Err(e) => {
            if e.to_string().contains("larger size than") || e.to_string().contains("invalid size")
            {
                return Ok(MediaInfo {
                    duration_us: None,
                    video_streams: Vec::new(),
                    audio_streams: Vec::new(),
                });
            }
            return Err(MediaProbeError::Mp4(e));
        }
    };

    let duration_us = {
        let dur = mp4.duration();
        Some((dur.as_secs_f64() * 1_000_000.0) as i64)
    };

    let mut video_streams = Vec::new();
    let mut audio_streams = Vec::new();

    for track in mp4.tracks().values() {
        let track_type = match track.track_type() {
            Ok(t) => t,
            Err(_) => continue,
        };

        match track_type {
            mp4::TrackType::Video => {
                let fps = track.frame_rate();
                video_streams.push(VideoStreamInfo {
                    track_id: track.track_id(),
                    codec: format!("{}", track.media_type().unwrap_or(mp4::MediaType::H264)),
                    width: track.width() as u32,
                    height: track.height() as u32,
                    frame_rate: fps as f64,
                    bitrate: track.bitrate(),
                });
            }
            mp4::TrackType::Audio => {
                audio_streams.push(AudioStreamInfo {
                    track_id: track.track_id(),
                    codec: format!("{}", track.media_type().unwrap_or(mp4::MediaType::AAC)),
                    sample_rate: track.sample_freq_index().map(|s| s.freq()).unwrap_or(44100),
                    channels: track.channel_config().map(|c| c as u32).unwrap_or(2),
                    bitrate: track.bitrate(),
                });
            }
            _ => {}
        }
    }

    Ok(MediaInfo {
        duration_us,
        video_streams,
        audio_streams,
    })
}

fn probe_mp4_bytes(bytes: &[u8]) -> Result<MediaInfo, MediaProbeError> {
    let size = bytes.len() as u64;
    let reader = BufReader::new(Cursor::new(bytes.to_vec()));
    let mp4 = match Mp4Reader::read_header(reader, size) {
        Ok(m) => m,
        Err(e) => {
            if e.to_string().contains("larger size than") || e.to_string().contains("invalid size")
            {
                return Ok(MediaInfo {
                    duration_us: None,
                    video_streams: Vec::new(),
                    audio_streams: Vec::new(),
                });
            }
            return Err(MediaProbeError::Mp4(e));
        }
    };

    let duration_us = {
        let dur = mp4.duration();
        Some((dur.as_secs_f64() * 1_000_000.0) as i64)
    };

    let mut video_streams = Vec::new();
    let mut audio_streams = Vec::new();

    for track in mp4.tracks().values() {
        let track_type = match track.track_type() {
            Ok(t) => t,
            Err(_) => continue,
        };

        match track_type {
            mp4::TrackType::Video => {
                let fps = track.frame_rate();
                video_streams.push(VideoStreamInfo {
                    track_id: track.track_id(),
                    codec: format!("{}", track.media_type().unwrap_or(mp4::MediaType::H264)),
                    width: track.width() as u32,
                    height: track.height() as u32,
                    frame_rate: fps as f64,
                    bitrate: track.bitrate(),
                });
            }
            mp4::TrackType::Audio => {
                audio_streams.push(AudioStreamInfo {
                    track_id: track.track_id(),
                    codec: format!("{}", track.media_type().unwrap_or(mp4::MediaType::AAC)),
                    sample_rate: track.sample_freq_index().map(|s| s.freq()).unwrap_or(44100),
                    channels: track.channel_config().map(|c| c as u32).unwrap_or(2),
                    bitrate: track.bitrate(),
                });
            }
            _ => {}
        }
    }

    Ok(MediaInfo {
        duration_us,
        video_streams,
        audio_streams,
    })
}

fn probe_ivf(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let file = File::open(path)?;
    let file_size = file.metadata()?.len();
    let mut reader = BufReader::new(file);

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
        }],
        audio_streams: Vec::new(),
    })
}

fn probe_ivf_bytes(bytes: &[u8]) -> Result<MediaInfo, MediaProbeError> {
    let file_size = bytes.len() as u64;
    let mut reader = BufReader::new(Cursor::new(bytes.to_vec()));

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
