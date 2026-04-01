use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("No video stream")]
    NoVideoStream,
}

pub fn open_video(_path: &Path) -> Result<(), DecodeError> {
    todo!("ac-ffmpeg integration pending")
}

pub fn next_frame(_path: &Path) -> Result<(), DecodeError> {
    todo!("ac-ffmpeg integration pending")
}
