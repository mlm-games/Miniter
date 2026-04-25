//! formats: MP4, MKV, WebM, AVI, IVF, OGG (Theora), and raw bitstream.
//!
//! ```text
//! [Demuxer trait] ← backends: Mp4Demuxer, SymphoniaDemuxer, IvfDemuxer
//!        ↓
//! [VideoDecoderBackend trait] ← backends: VideosonDecoder, OxideAvDecoder, Av1Decoder
//!        ↓
//! [VideoDecodeSession] ← coordinates demux + decode, provides RGBA frames
//! ```

pub mod ivf_demux;
pub mod mp4_demux;
pub mod symphonia_demux;

use std::io::{BufReader, Read, Seek};
use std::path::Path;

pub use ivf_demux::IvfDemuxer;
pub use mp4_demux::Mp4Demuxer;
pub use symphonia_demux::SymphoniaDemuxer;

pub use symphonia_demux::{
    DecodeBackendError, DemuxError, DemuxResult, DemuxedSample, Demuxer, VideoContainer,
    VideoDecoderBackend,
};

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

    let file = std::fs::File::open(path)?;
    let size = file.metadata()?.len();

    SymphoniaDemuxer::from_file(file, size).map(|d| Box::new(d) as Box<dyn Demuxer>)
}

pub fn open_demuxer_with_fallback(path: &Path) -> DemuxResult<Box<dyn Demuxer>> {
    open_demuxer(path).or_else(|_| {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        SymphoniaDemuxer::from_file(file, size).map(|d| Box::new(d) as Box<dyn Demuxer>)
    })
}
