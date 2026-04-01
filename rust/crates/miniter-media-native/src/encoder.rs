use miniter_domain::export::ExportProfile;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
}

pub fn create_encoder(_profile: &ExportProfile) -> Result<(), EncodeError> {
    todo!("ac-ffmpeg integration pending")
}

pub fn encode_frame(_path: &Path) -> Result<(), EncodeError> {
    todo!("ac-ffmpeg integration pending")
}

pub fn finish(_path: &Path) -> Result<(), EncodeError> {
    todo!("ac-ffmpeg integration pending")
}
