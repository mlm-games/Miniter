//! MKV, WebM, AVI, OGG, and other containers.

use std::io::{BufReader, Read, Seek};

use symphonia::core::codecs::CODEC_TYPE_NULL;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

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

pub struct SymphoniaDemuxer {
    format: Box<dyn symphonia::core::formats::FormatReader>,
    track_id: u32,
    width: u32,
    height: u32,
    timescale: u32,
    total_samples: u32,
    current_sample: u32,
    last_pts_us: i64,
    fourcc: u32,
    codec_name: String,
    container: VideoContainer,
}

impl SymphoniaDemuxer {
    pub fn from_file(file: std::fs::File, _size: u64) -> DemuxResult<Self> {
        let mss = MediaSourceStream::new(Box::new(file), Default::default());

        let probed = symphonia::default::get_probe().format(
            &Hint::new(),
            mss,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )?;

        let mut format = probed.format;
        let ext = format
            .tracks()
            .iter()
            .find_map(|t| {
                let params = &t.codec_params;
                if params.codec != CODEC_TYPE_NULL {
                    let fourcc_str = format!("{}", params.codec);
                    let fourcc =
                        u32::from_str_radix(fourcc_str.trim_start_matches("0x"), 16).unwrap_or(0);
                    let codec_name = format_codec_name(params.codec);
                    let ts = params.time_base.map(|_| 1000u32).unwrap_or(1000);
                    let samples = params.n_frames.unwrap_or(0) as u32;
                    Some((t.id, fourcc, codec_name, ts, samples))
                } else {
                    None
                }
            })
            .ok_or(DemuxError::NoVideoTrack)?;

        let (track_id, fourcc, codec_name, timescale, total_samples) = ext;

        let container = VideoContainer::Unknown;

        Ok(Self {
            format,
            track_id,
            width: 0,
            height: 0,
            timescale,
            total_samples,
            current_sample: 0,
            last_pts_us: -1,
            fourcc,
            codec_name,
            container,
        })
    }

    pub fn set_dimensions(&mut self, w: u32, h: u32) {
        self.width = w;
        self.height = h;
    }
}

impl Demuxer for SymphoniaDemuxer {
    fn container(&self) -> VideoContainer {
        self.container
    }

    fn width(&self) -> u32 {
        self.width
    }

    fn height(&self) -> u32 {
        self.height
    }

    fn timescale(&self) -> u32 {
        self.timescale
    }

    fn total_samples(&self) -> u32 {
        self.total_samples
    }

    fn codec_name(&self) -> &str {
        &self.codec_name
    }

    fn fourcc(&self) -> u32 {
        self.fourcc
    }

    fn format_name(&self) -> &'static str {
        self.container.name()
    }

    fn next_sample(&mut self) -> DemuxResult<Option<DemuxedSample>> {
        loop {
            let packet = match self.format.next_packet() {
                Ok(p) => p,
                Err(symphonia::core::errors::Error::IoError(ref e))
                    if e.kind() == std::io::ErrorKind::UnexpectedEof =>
                {
                    return Ok(Some(DemuxedSample::eos()));
                }
                Err(e) => return Err(e.into()),
            };

            if packet.track_id() != self.track_id {
                continue;
            }

            let pts = (packet.ts() / 1_000_000) as i64;
            let data: Vec<u8> = packet.buf().to_vec();
            let is_sync = false;

            self.last_pts_us = pts;
            self.current_sample += 1;

            return Ok(Some(DemuxedSample::new(data, pts, is_sync)));
        }
    }

    fn seek_to_sample(&mut self, sample_id: u32) -> DemuxResult<()> {
        use symphonia::core::formats::{SeekMode, SeekTo};
        use symphonia::core::units::Time;
        let _ = sample_id;
        self.format
            .seek(
                SeekMode::Accurate,
                SeekTo::Time {
                    time: Time::default(),
                    track_id: Some(self.track_id),
                },
            )
            .map_err(|e| DemuxError::Other(e.to_string()))?;
        self.current_sample = sample_id;
        self.last_pts_us = -1;
        Ok(())
    }

    fn reset(&mut self) -> DemuxResult<()> {
        use symphonia::core::formats::{SeekMode, SeekTo};
        use symphonia::core::units::Time;
        self.format
            .seek(
                SeekMode::Accurate,
                SeekTo::Time {
                    time: Time::default(),
                    track_id: Some(self.track_id),
                },
            )
            .map_err(|e| DemuxError::Other(e.to_string()))?;
        self.current_sample = 0;
        self.last_pts_us = -1;
        Ok(())
    }
}

impl SymphoniaDemuxer {
    pub fn open(path: &std::path::Path) -> DemuxResult<Self> {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        Self::from_file(file, size)
    }
}

fn format_codec_name(codec: symphonia::core::codecs::CodecType) -> String {
    let id_str = format!("{}", codec);
    let id = u32::from_str_radix(id_str.trim_start_matches("0x"), 16).unwrap_or(0);
    if id == 0x31475661 {
        "VP8".into()
    } else if id == 0x31475639 {
        "VP9".into()
    } else if id == 0x31305641 {
        "AV1".into()
    } else if id == 0x31637661 {
        "H.264".into()
    } else if id == 0x68657631 {
        "H.265".into()
    } else if id == 0x316854 {
        "Theora".into()
    } else if id == 0x31676d {
        "MPEG-1".into()
    } else if id == 0x32676d {
        "MPEG-2".into()
    } else if id == 0x33475034 {
        "MPEG-4".into()
    } else if id == 0x676d696d {
        "MJPEG".into()
    } else if id == 0x33363248 {
        "H.263".into()
    } else {
        format!("Unknown({})", id_str)
    }
}
