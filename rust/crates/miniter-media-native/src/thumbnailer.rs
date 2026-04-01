use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("No video stream")]
    NoVideoStream,
}

pub fn extract_thumbnail(_path: &Path, _target_us: i64) -> Result<(), DecodeError> {
    todo!("ac-ffmpeg integration pending")
}
