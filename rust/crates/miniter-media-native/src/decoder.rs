use ac_ffmpeg::codec::video::{VideoDecoder, VideoFrame};
use ac_ffmpeg::format::demuxer::Demuxer;
use std::fs::File;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("FFmpeg: {0}")]
    Ffmpeg(#[from] ac_ffmpeg::Error),
    #[error("No video stream")]
    NoVideoStream,
}

pub fn open_video(_path: &Path) -> Result<(Demuxer<File>, VideoDecoder, usize), DecodeError> {
    todo!("ac-ffmpeg integration pending")
}

pub fn next_frame(
    _demuxer: &mut Demuxer<File>,
    _decoder: &mut VideoDecoder,
    _stream_index: usize,
) -> Result<Option<VideoFrame>, DecodeError> {
    todo!("ac-ffmpeg integration pending")
}
