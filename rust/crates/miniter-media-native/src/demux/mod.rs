//! formats: MP4, MKV, WebM, AVI, IVF, OGG (Theora), and raw bitstream.
//!
//! ```text
//! [Demuxer trait] ← backends: Mp4Demuxer, SymphoniaDemuxer, IvfDemuxer
//!        ↓
//! [VideoDecoderBackend trait] ← backends in crate::decoders
//!        ↓
//! [VideoDecodeSession] ← coordinates demux + decode, provides RGBA frames
//! ```

pub mod ivf_demux;
pub mod mp4_demux;
pub mod symphonia_demux;

use std::fs::File;
use std::io::{BufReader, Read, Seek};
use std::path::Path;
use symphonia::core::io::MediaSource;

pub use ivf_demux::IvfDemuxer;
pub use mp4_demux::Mp4Demuxer;
pub use symphonia_demux::SymphoniaDemuxer;

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
    fn codec_name(&self) -> &str;
    fn fourcc(&self) -> u32;
    fn next_sample(&mut self) -> DemuxResult<Option<DemuxedSample>>;
    fn seek_to_sample(&mut self, sample_id: u32) -> DemuxResult<()>;
    fn reset(&mut self) -> DemuxResult<()>;
    fn format_name(&self) -> &'static str;
}

pub trait VideoDecoderBackend {
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

pub fn open_demuxer(path: &Path) -> DemuxResult<Box<dyn Demuxer>> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_lowercase());

    if matches!(ext.as_deref(), Some("ivf")) {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        let reader = BufReader::new(file);
        return IvfDemuxer::from_reader(reader, size).map(|d| Box::new(d) as Box<dyn Demuxer>);
    }

    if matches!(ext.as_deref(), Some("mp4" | "m4v" | "mov" | "3gp")) {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        let reader = BufReader::new(file);
        if let Ok(d) = Mp4Demuxer::from_reader(reader, size) {
            return Ok(Box::new(d) as Box<dyn Demuxer>);
        }
    }

    let file = File::open(path)?;
    let size = file.metadata()?.len();

    SymphoniaDemuxer::from_file(file, size).map(|d| Box::new(d) as Box<dyn Demuxer>)
}

pub fn open_demuxer_with_fallback(path: &Path) -> DemuxResult<Box<dyn Demuxer>> {
    open_demuxer(path).or_else(|_| {
        let file = File::open(path)?;
        let size = file.metadata()?.len();
        SymphoniaDemuxer::from_file(file, size).map(|d| Box::new(d) as Box<dyn Demuxer>)
    })
}

/// Open a demuxer from a generic reader with content-based detection.
pub fn open_demuxer_from_reader<R: MediaSource + 'static>(
    mut reader: R,
    size: u64,
) -> DemuxResult<Box<dyn Demuxer>> {
    let mut magic = [0u8; 8];
    reader.read_exact(&mut magic).map_err(DemuxError::Io)?;
    reader
        .seek(std::io::SeekFrom::Start(0))
        .map_err(DemuxError::Io)?;

    // IVF files start with "DKIF"
    if &magic[0..4] == b"DKIF" {
        return IvfDemuxer::from_reader(reader, size).map(|d| Box::new(d) as Box<dyn Demuxer>);
    }

    // MP4/ MOV files start with an ftyp box
    if &magic[4..8] == b"ftyp" {
        if let Ok(d) = Mp4Demuxer::from_reader(reader, size) {
            return Ok(Box::new(d) as Box<dyn Demuxer>);
        }
        return Err(DemuxError::UnsupportedFormat("MP4 parse failed".into()));
    }

    // Symphonia handles MKV, WebM, AVI, OGG, and any other container
    SymphoniaDemuxer::from_reader(reader, size).map(|d| Box::new(d) as Box<dyn Demuxer>)
}
