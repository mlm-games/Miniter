use mp4::Mp4Reader;
use std::fs::File;
use std::io::BufReader;
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
    #[error("No video track found")]
    NoVideoTrack,
}

pub fn probe_media(path: &Path) -> Result<MediaInfo, MediaProbeError> {
    let file = File::open(path)?;
    let size = file.metadata()?.len();
    let reader = BufReader::new(file);
    let mp4 = Mp4Reader::read_header(reader, size)?;

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
