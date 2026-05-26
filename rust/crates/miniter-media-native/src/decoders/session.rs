use std::io::{Read, Seek};
use std::path::Path;

use crate::decoders::{DecodeError, create_backend};
use crate::demux::open_demuxer;
use crate::frame::RgbaFrame;

/// A session that pulls demuxed samples from a [`Demuxer`] and decodes them
/// through a [`VideoDecoderBackend`].
pub struct VideoDecodeSession {
    demuxer: Box<dyn crate::demux::Demuxer>,
    backend: Box<dyn crate::demux::VideoDecoderBackend>,
}

impl VideoDecodeSession {
    /// Open a file by path, auto-detect container + codec, and initialise the
    /// appropriate demuxer + decoder backend.
    pub fn open(path: &Path, hardware_acceleration: bool) -> Result<Self, DecodeError> {
        let demuxer = open_demuxer(path)?;
        Self::from_demuxer(demuxer, hardware_acceleration)
    }

    /// Open from an in-memory byte buffer (WASM path, MP4  only).
    pub fn from_reader<R: Read + Seek + Send + 'static>(
        reader: R,
        size: u64,
        hardware_acceleration: bool,
    ) -> Result<Self, DecodeError> {
        let d = crate::demux::Mp4Demuxer::from_reader(reader, size)?;
        Self::from_demuxer(Box::new(d), hardware_acceleration)
    }

    fn from_demuxer(
        demuxer: Box<dyn crate::demux::Demuxer>,
        hardware_acceleration: bool,
    ) -> Result<Self, DecodeError> {
        let fourcc = demuxer.fourcc();
        let width = demuxer.width();
        let height = demuxer.height();
        let codec_name = demuxer.codec_name().to_string();

        let backend = create_backend(fourcc, width, height, hardware_acceleration, &codec_name)
            .ok_or_else(|| DecodeError::DecoderNotAvailable(codec_name.clone()))?;

        log::info!(
            "Session: container={}, codec={}, {}x{}, backend={}",
            demuxer.format_name(),
            codec_name,
            width,
            height,
            backend.name(),
        );

        Ok(Self { demuxer, backend })
    }

    /// Decode the next frame from the stream.
    pub fn next_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeError> {
        loop {
            match self.demuxer.next_sample()? {
                Some(sample) if sample.is_eos => break,
                Some(sample) => {
                    if let Some(frame) =
                        self.backend
                            .decode_frame(&sample.data, sample.pts_us, sample.is_sync)?
                    {
                        return Ok(Some(frame));
                    }
                }
                None => break,
            }
        }
        self.backend.finish().map_err(DecodeError::from)
    }

    /// Reset the stream to the beginning.
    pub fn reset(&mut self) -> Result<(), DecodeError> {
        self.demuxer.reset()?;
        self.backend.reset();
        Ok(())
    }

    pub fn width(&self) -> u32 {
        self.demuxer.width()
    }

    pub fn height(&self) -> u32 {
        self.demuxer.height()
    }

    pub fn timescale(&self) -> u32 {
        self.demuxer.timescale()
    }

    pub fn total_samples(&self) -> u32 {
        self.demuxer.total_samples()
    }

    pub fn codec_name(&self) -> &str {
        self.demuxer.codec_name()
    }

    pub fn container_name(&self) -> &'static str {
        self.demuxer.format_name()
    }

    /// Seek to a specific sample index (1-based).
    pub fn seek_to_sample(&mut self, sample_id: u32) -> Result<(), DecodeError> {
        self.demuxer.seek_to_sample(sample_id)?;
        self.backend.reset();
        Ok(())
    }
}
