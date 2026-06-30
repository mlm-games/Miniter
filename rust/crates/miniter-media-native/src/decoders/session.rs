use std::path::Path;

use crate::decoders::{DecodeError, create_backend};
use crate::demux::{open_demuxer, open_demuxer_from_reader};
use crate::frame::RgbaFrame;
use symphonia::core::io::MediaSource;

/// A session that pulls demuxed samples from a [`Demuxer`] and decodes them
/// through a [`VideoDecoderBackend`].
pub struct VideoDecodeSession {
    demuxer: Box<dyn crate::demux::Demuxer>,
    backend: Box<dyn crate::demux::VideoDecoderBackend>,
    finished: bool,
}

impl VideoDecodeSession {
    /// Open a file by path, auto-detect container + codec, and initialise the
    /// appropriate demuxer + decoder backend.
    pub fn open(path: &Path, hardware_acceleration: bool) -> Result<Self, DecodeError> {
        let demuxer = open_demuxer(path)?;
        Self::from_demuxer(demuxer, hardware_acceleration)
    }

    /// Open from an in-memory byte buffer (WASM path).
    /// Auto-detects container via content sniffing: MP4, MKV, WebM, IVF, etc.
    pub fn from_reader<R: MediaSource + 'static>(
        reader: R,
        size: u64,
        hardware_acceleration: bool,
    ) -> Result<Self, DecodeError> {
        let d = open_demuxer_from_reader(reader, size)?;
        Self::from_demuxer(d, hardware_acceleration)
    }

    fn from_demuxer(
        demuxer: Box<dyn crate::demux::Demuxer>,
        hardware_acceleration: bool,
    ) -> Result<Self, DecodeError> {
        let fourcc = demuxer.fourcc();
        let width = demuxer.width();
        let height = demuxer.height();
        let codec_name = demuxer.codec_name().to_string();

        let backend = create_backend(
            fourcc,
            width,
            height,
            hardware_acceleration,
            &codec_name,
            demuxer.codec_description(),
        )
        .ok_or_else(|| DecodeError::DecoderNotAvailable(codec_name.clone()))?;

        log::info!(
            "Session: container={}, codec={}, {}x{}, backend={}",
            demuxer.format_name(),
            codec_name,
            width,
            height,
            backend.name(),
        );

        Ok(Self { demuxer, backend, finished: false })
    }

    /// Decode the next frame from the stream.
    ///
    /// On native backends this sends all remaining demuxed packets to the decoder
    /// and returns the first available decoded frame. On WASM (async WebCodecs)
    /// the loop is bounded: after processing a batch of packets the method returns
    /// either a frame or `None` to give the caller a chance to yield to the JS
    /// event loop so that async decode callbacks can fire.
    pub fn next_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeError> {
        if self.finished {
            return Ok(None);
        }

        #[cfg(target_arch = "wasm32")]
        const MAX_PACKETS: u32 = 16;
        #[cfg(not(target_arch = "wasm32"))]
        const MAX_PACKETS: u32 = u32::MAX;

        let mut packets_sent = 0u32;
        loop {
            match self.demuxer.next_sample()? {
                Some(sample) if sample.is_eos => {
                    self.finished = true;
                    return self.backend.finish().map_err(DecodeError::from);
                }
                Some(sample) => {
                    if let Some(frame) =
                        self.backend
                            .decode_frame(&sample.data, sample.pts_us, sample.is_sync)?
                    {
                        return Ok(Some(frame));
                    }
                    packets_sent += 1;
                    if packets_sent >= MAX_PACKETS {
                        return Ok(None);
                    }
                }
                None => {
                    self.finished = true;
                    return self.backend.finish().map_err(DecodeError::from);
                }
            }
        }
    }

    /// Returns `true` once the stream has been fully consumed.
    pub fn is_eos(&self) -> bool {
        self.finished
    }

    /// Reset the stream to the beginning.
    pub fn reset(&mut self) -> Result<(), DecodeError> {
        self.finished = false;
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
        self.finished = false;
        self.backend.reset();
        Ok(())
    }
}
