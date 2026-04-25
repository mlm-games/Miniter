//! raw AV1/VP8 bitstreams.

use super::{DemuxError, DemuxResult, DemuxedSample, Demuxer, VideoContainer};
use std::io::{BufReader, Read, Seek};

const AV1_FOURCC: u32 = 0x31495641;
const VP8_FOURCC: u32 = 0x30385056;

pub struct IvfDemuxer<R: Read> {
    reader: R,
    width: u32,
    height: u32,
    frame_count: u32,
    current_frame: u32,
    pts_us: i64,
    fps: f64,
    done: bool,
}

impl<R: Read> IvfDemuxer<R> {
    pub fn from_reader(mut reader: R, _size: u64) -> DemuxResult<Self> {
        let mut header = [0u8; 32];
        reader
            .read_exact(&mut header)
            .map_err(|e| DemuxError::Io(e))?;

        if &header[0..4] != b"DKIF" {
            return Err(DemuxError::Other("Not an IVF file".into()));
        }

        let version = u16::from_le_bytes([header[4], header[5]]);
        if version != 0 {
            return Err(DemuxError::Other(format!(
                "Unknown IVF version: {}",
                version
            )));
        }

        let fourcc = u32::from_le_bytes([header[8], header[9], header[10], header[11]]);
        let width = u16::from_le_bytes([header[12], header[13]]) as u32;
        let height = u16::from_le_bytes([header[14], header[15]]) as u32;
        let fps_numer = u32::from_le_bytes([header[16], header[17], header[18], header[19]]);
        let fps_denom = u32::from_le_bytes([header[20], header[21], header[22], header[23]]);
        let frame_count = u32::from_le_bytes([header[24], header[25], header[26], header[27]]);

        let fps = if fps_denom > 0 {
            fps_numer as f64 / fps_denom as f64
        } else {
            30.0
        };

        let fourcc_str = std::str::from_utf8(&header[8..12]).unwrap_or("????");
        log::info!(
            "IVF: fourcc={}, size={}x{}, fps={:.3}, frames={}",
            fourcc_str,
            width,
            height,
            fps,
            frame_count
        );

        Ok(Self {
            reader,
            width,
            height,
            frame_count,
            current_frame: 0,
            pts_us: 0,
            fps,
            done: false,
        })
    }

    fn fourcc(&self) -> u32 {
        AV1_FOURCC
    }
}

impl<R: Read + Send> Demuxer for IvfDemuxer<R> {
    fn container(&self) -> VideoContainer {
        VideoContainer::Ivf
    }

    fn width(&self) -> u32 {
        self.width
    }

    fn height(&self) -> u32 {
        self.height
    }

    fn timescale(&self) -> u32 {
        1_000_000
    }

    fn total_samples(&self) -> u32 {
        self.frame_count
    }

    fn codec_name(&self) -> &str {
        "AV1"
    }

    fn fourcc(&self) -> u32 {
        AV1_FOURCC
    }

    fn format_name(&self) -> &'static str {
        "IVF"
    }

    fn next_sample(&mut self) -> DemuxResult<Option<DemuxedSample>> {
        if self.done || self.current_frame >= self.frame_count {
            self.done = true;
            return Ok(Some(DemuxedSample::eos()));
        }

        let mut size_buf = [0u8; 4];
        match self.reader.read_exact(&mut size_buf) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                self.done = true;
                return Ok(Some(DemuxedSample::eos()));
            }
            Err(e) => return Err(DemuxError::Io(e)),
        }

        let size = u32::from_le_bytes(size_buf) as usize;
        if size == 0 {
            self.done = true;
            return Ok(Some(DemuxedSample::eos()));
        }

        let mut data = vec![0u8; size];
        self.reader.read_exact(&mut data).map_err(DemuxError::Io)?;

        self.current_frame += 1;
        self.pts_us = (self.current_frame as f64 * 1_000_000.0 / self.fps) as i64;

        let is_key = data[0] & 1 != 0;
        Ok(Some(DemuxedSample::new(data, self.pts_us, is_key)))
    }

    fn seek_to_sample(&mut self, sample_id: u32) -> DemuxResult<()> {
        if sample_id == 1 {
            return Ok(());
        }
        Err(DemuxError::Other("IVF seek not implemented".into()))
    }

    fn reset(&mut self) -> DemuxResult<()> {
        Err(DemuxError::Other("IVF reset not implemented".into()))
    }
}

impl IvfDemuxer<BufReader<std::fs::File>> {
    pub fn open(path: &std::path::Path) -> DemuxResult<Self> {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        Self::from_reader(BufReader::new(file), size)
    }
}
