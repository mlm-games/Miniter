//! Unified demuxer trait for most video container formats.

use std::io::{Read, Seek};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoContainer {
    Mp4,
    Mkv,
    WebM,
    Avi,
    Ivf,
    Ogg,
    Unknown,
}

impl VideoContainer {
    pub fn from_extension(ext: Option<&str>) -> Self {
        match ext.map(|e| e.to_lowercase()).as_deref() {
            Some("mp4" | "m4v" | "mov" | "3gp") => VideoContainer::Mp4,
            Some("mkv") => VideoContainer::Mkv,
            Some("webm") => VideoContainer::WebM,
            Some("avi") => VideoContainer::Avi,
            Some("ivf") => VideoContainer::Ivf,
            Some("ogv" | "ogg") => VideoContainer::Ogg,
            _ => VideoContainer::Unknown,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            VideoContainer::Mp4 => "MP4",
            VideoContainer::Mkv => "Matroska",
            VideoContainer::WebM => "WebM",
            VideoContainer::Avi => "AVI",
            VideoContainer::Ivf => "IVF",
            VideoContainer::Ogg => "Ogg",
            VideoContainer::Unknown => "Unknown",
        }
    }
}

#[derive(Debug, Clone)]
pub struct DemuxedSample {
    pub data: Vec<u8>,
    pub pts_us: i64,
    pub is_sync: bool,
    pub is_eos: bool,
}

impl DemuxedSample {
    pub fn new(data: Vec<u8>, pts_us: i64, is_sync: bool) -> Self {
        Self {
            data,
            pts_us,
            is_sync,
            is_eos: false,
        }
    }
    pub fn eos() -> Self {
        Self {
            data: Vec::new(),
            pts_us: 0,
            is_sync: false,
            is_eos: true,
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DemuxError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("MP4: {0}")]
    Mp4(#[from] mp4::Error),
    #[error("Symphonia: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
    #[error("No video track")]
    NoVideoTrack,
    #[error("Unsupported format: {0}")]
    UnsupportedFormat(String),
    #[error("{0}")]
    Other(String),
}

pub type DemuxResult<T> = Result<T, DemuxError>;

pub trait Demuxer: Send {
    fn container(&self) -> VideoContainer;
    fn width(&self) -> u32;
    fn height(&self) -> u32;
    fn timescale(&self) -> u32;
    fn total_samples(&self) -> u32;
    fn codec_name(&self) -> &'static str;
    fn fourcc(&self) -> u32;
    fn next_sample(&mut self) -> DemuxResult<Option<DemuxedSample>>;
    fn seek_to_sample(&mut self, sample_id: u32) -> DemuxResult<()>;
    fn reset(&mut self) -> DemuxResult<()>;
    fn format_name(&self) -> &'static str;
}

pub trait VideoDecoderBackend: Send {
    fn name(&self) -> &'static str;
    fn is_supported(&self, fourcc: u32) -> bool;
    fn decode_frame(
        &mut self,
        data: &[u8],
        pts_us: i64,
        is_sync: bool,
    ) -> Result<Option<crate::RgbaFrame>, DecodeBackendError>;
    fn finish(&mut self) -> Result<Option<crate::RgbaFrame>, DecodeBackendError>;
    fn reset(&mut self);
}

#[derive(Debug, thiserror::Error)]
pub enum DecodeBackendError {
    #[error("{0}")]
    Other(String),
}
