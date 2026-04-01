use std::path::Path;

#[derive(Debug, Clone)]
pub struct MediaInfo {
    pub duration_us: Option<i64>,
    pub video_streams: Vec<VideoStreamInfo>,
    pub audio_streams: Vec<AudioStreamInfo>,
}

#[derive(Debug, Clone)]
pub struct VideoStreamInfo {
    pub index: usize,
    pub codec: String,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone)]
pub struct AudioStreamInfo {
    pub index: usize,
    pub codec: String,
    pub sample_rate: u32,
    pub channels: u32,
}

#[derive(Debug, thiserror::Error)]
pub enum MediaProbeError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

pub fn probe_media(_path: &Path) -> Result<MediaInfo, MediaProbeError> {
    todo!("ac-ffmpeg integration pending")
}
