use ac_ffmpeg::codec::video::{VideoEncoder, VideoFrame};
use ac_ffmpeg::format::muxer::Muxer;
use miniter_domain::export::ExportProfile;
use std::fs::File;

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("FFmpeg: {0}")]
    Ffmpeg(#[from] ac_ffmpeg::Error),
}

pub fn create_encoder(
    _profile: &ExportProfile,
) -> Result<(Muxer<File>, VideoEncoder), EncodeError> {
    todo!("ac-ffmpeg integration pending")
}

pub fn encode_frame(
    _muxer: &mut Muxer<File>,
    _encoder: &mut VideoEncoder,
    _frame: VideoFrame,
) -> Result<(), EncodeError> {
    todo!("ac-ffmpeg integration pending")
}

pub fn finish(_muxer: &mut Muxer<File>, _encoder: &mut VideoEncoder) -> Result<(), EncodeError> {
    todo!("ac-ffmpeg integration pending")
}
