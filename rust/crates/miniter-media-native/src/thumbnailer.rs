use std::path::Path;

pub fn extract_thumbnail(
    _path: &Path,
    _target_us: i64,
) -> Result<ac_ffmpeg::codec::video::VideoFrame, DecodeError> {
    todo!("ac-ffmpeg integration pending")
}

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("FFmpeg: {0}")]
    Ffmpeg(#[from] ac_ffmpeg::Error),
    #[error("No video stream")]
    NoVideoStream,
}
