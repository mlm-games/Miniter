//! MKV, WebM, AVI, OGG, and other containers via symphonia.

use std::io::Seek;

use symphonia::core::codecs::video::VideoCodecId;
use symphonia::core::formats::FormatOptions;
use symphonia::core::formats::probe::Hint;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

use super::{DemuxError, DemuxResult, DemuxedSample, Demuxer, VideoContainer};

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

        let format = symphonia::default::get_probe().probe(
            &Hint::new(),
            mss,
            FormatOptions::default(),
            MetadataOptions::default(),
        )?;

        let ext = format
            .tracks()
            .iter()
            .find_map(|t| {
                let video_params = t.codec_params.as_ref().and_then(|p| p.video())?;
                let codec = video_params.codec;
                let fourcc = codec_to_fourcc(codec);
                let codec_name = format_codec_name(codec);
                let ts = t.time_base.map(|_| 1000u32).unwrap_or(1000);
                let samples = t.num_frames.unwrap_or(0) as u32;
                Some((t.id, fourcc, codec_name, ts, samples))
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
                Ok(Some(p)) => p,
                Ok(None) => return Ok(Some(DemuxedSample::eos())),
                Err(e) => return Err(e.into()),
            };

            if packet.track_id != self.track_id {
                continue;
            }

            let pts = (packet.pts.get() / 1_000_000);
            let data: Vec<u8> = packet.data.to_vec();
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

fn codec_to_fourcc(codec: VideoCodecId) -> u32 {
    use symphonia::core::codecs::video::well_known::*;
    if codec == CODEC_ID_VP8 {
        0x31475661
    } else if codec == CODEC_ID_VP9 {
        0x31475639
    } else if codec == CODEC_ID_AV1 {
        0x31305641
    } else if codec == CODEC_ID_H264 {
        0x31637661
    } else if codec == CODEC_ID_HEVC {
        0x68657631
    } else if codec == CODEC_ID_THEORA {
        0x316854
    } else if codec == CODEC_ID_MPEG1 {
        0x31676d
    } else if codec == CODEC_ID_MPEG2 {
        0x32676d
    } else if codec == CODEC_ID_MPEG4 {
        0x33475034
    } else if codec == CODEC_ID_MJPEG {
        0x676d696d
    } else if codec == CODEC_ID_H263 {
        0x33363248
    } else {
        0
    }
}

fn format_codec_name(codec: VideoCodecId) -> String {
    use symphonia::core::codecs::video::well_known::*;
    if codec == CODEC_ID_VP8 {
        "VP8"
    } else if codec == CODEC_ID_VP9 {
        "VP9"
    } else if codec == CODEC_ID_AV1 {
        "AV1"
    } else if codec == CODEC_ID_H264 {
        "H.264"
    } else if codec == CODEC_ID_HEVC {
        "H.265"
    } else if codec == CODEC_ID_THEORA {
        "Theora"
    } else if codec == CODEC_ID_MPEG1 {
        "MPEG-1"
    } else if codec == CODEC_ID_MPEG2 {
        "MPEG-2"
    } else if codec == CODEC_ID_MPEG4 {
        "MPEG-4"
    } else if codec == CODEC_ID_MJPEG {
        "MJPEG"
    } else if codec == CODEC_ID_H263 {
        "H.263"
    } else {
        "Unknown"
    }
    .into()
}
