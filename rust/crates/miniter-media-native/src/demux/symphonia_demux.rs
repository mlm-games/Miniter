//! MKV, WebM, MP4, AVI, OGG, and other containers via symphonia.

use std::io::Seek;

use rediakit_bitstream::sync::{
    av1_is_keyframe, h264_annexb_has_idr, hevc_annexb_has_keyframe, vp8_is_keyframe,
    vp9_is_keyframe,
};
use rediakit_bitstream::{
    avcc_to_annexb, nal_length_size_avcc, nal_length_size_hvcc, parse_avcc, parse_hvcc,
};
use symphonia::core::codecs::video::VideoCodecId;
use symphonia::core::formats::FormatOptions;
use symphonia::core::formats::probe::Hint;
use symphonia::core::io::MediaSource;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

use super::{DemuxError, DemuxResult, DemuxedSample, Demuxer, VideoContainer};

pub struct SymphoniaDemuxer {
    format: Box<dyn symphonia::core::formats::FormatReader>,
    track_id: u32,
    width: u32,
    height: u32,
    time_base_numer: u32,
    time_base_denom: u32,
    total_samples: u32,
    current_sample: u32,
    last_pts_us: i64,
    fourcc: u32,
    codec_name: String,
    container: VideoContainer,
    /// Number of bytes in the NAL unit length prefix (0 for codecs without NAL framing).
    nalu_length_size: u8,
    /// AnnexB-formatted codec configuration (VPS/SPS/PPS for HEVC, SPS/PPS for H264).
    /// Prepended to the first sample after each seek.
    codec_config: Vec<u8>,
    /// Retained copy to restore `codec_config` after seek/reset.
    initial_codec_config: Vec<u8>,
}

impl SymphoniaDemuxer {
    pub fn from_reader<R: MediaSource + 'static>(reader: R, _size: u64) -> DemuxResult<Self> {
        let mss = MediaSourceStream::new(Box::new(reader), Default::default());

        let format = symphonia::default::get_probe().probe(
            &Hint::new(),
            mss,
            FormatOptions::default(),
            MetadataOptions::default(),
        )?;

        use symphonia::core::codecs::video::well_known::{
            CODEC_ID_H264, CODEC_ID_HEVC,
            extra_data::{
                VIDEO_EXTRA_DATA_ID_AVC_DECODER_CONFIG, VIDEO_EXTRA_DATA_ID_HEVC_DECODER_CONFIG,
            },
        };

        let ext = format
            .tracks()
            .iter()
            .find_map(|t| {
                let video_params = t.codec_params.as_ref().and_then(|p| p.video())?;
                let codec = video_params.codec;
                let fourcc = codec_to_fourcc(codec);
                let codec_name = format_codec_name(codec);
                let tb = t
                    .time_base
                    .unwrap_or(symphonia::core::units::TimeBase::try_new(1, 1000).unwrap());
                let samples = t.duration.map(|d| d.get() as u32).unwrap_or_else(|| {
                    format
                        .media_info()
                        .duration
                        .map(|d| d.get() as u32)
                        .unwrap_or(0)
                });
                let w = video_params.width.unwrap_or(0) as u32;
                let h = video_params.height.unwrap_or(0) as u32;

                let (nalu_len_size, codec_config) = match codec {
                    CODEC_ID_H264 => {
                        let avcc = video_params
                            .extra_data
                            .iter()
                            .find(|d| d.id == VIDEO_EXTRA_DATA_ID_AVC_DECODER_CONFIG)
                            .map(|d| &*d.data);
                        let nls = avcc.map(nal_length_size_avcc).unwrap_or(4);
                        let annexb = avcc.map(parse_avcc).unwrap_or_default();
                        (nls, annexb)
                    }
                    CODEC_ID_HEVC => {
                        let hvcc = video_params
                            .extra_data
                            .iter()
                            .find(|d| d.id == VIDEO_EXTRA_DATA_ID_HEVC_DECODER_CONFIG)
                            .map(|d| &*d.data);
                        let nls = hvcc.map(nal_length_size_hvcc).unwrap_or(4);
                        let annexb = hvcc.map(parse_hvcc).unwrap_or_default();
                        (nls, annexb)
                    }
                    _ => (0, Vec::new()),
                };

                Some((
                    t.id,
                    fourcc,
                    codec_name,
                    tb.numer.get(),
                    tb.denom.get(),
                    samples,
                    w,
                    h,
                    nalu_len_size,
                    codec_config,
                ))
            })
            .ok_or(DemuxError::NoVideoTrack)?;

        let (
            track_id,
            fourcc,
            codec_name,
            time_base_numer,
            time_base_denom,
            total_samples,
            width,
            height,
            nalu_length_size,
            codec_config,
        ) = ext;
        let container = VideoContainer::Unknown;

        Ok(Self {
            format,
            track_id,
            width,
            height,
            time_base_numer,
            time_base_denom,
            total_samples,
            current_sample: 0,
            last_pts_us: -1,
            fourcc,
            codec_name,
            container,
            nalu_length_size,
            codec_config: codec_config.clone(),
            initial_codec_config: codec_config,
        })
    }

    pub fn from_file(file: std::fs::File, size: u64) -> DemuxResult<Self> {
        Self::from_reader(file, size)
    }

    fn needs_annex_b(&self) -> bool {
        self.nalu_length_size > 0
    }

    /// Convert length-prefixed NAL units to AnnexB (start-code prefixed).
    fn to_annex_b(&self, data: &[u8]) -> Vec<u8> {
        avcc_to_annexb(data, self.nalu_length_size as usize)
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
        if self.time_base_numer == 0 {
            return 0;
        }
        ((self.time_base_denom as f64) / (self.time_base_numer as f64)).round() as u32
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

    fn codec_description(&self) -> &[u8] {
        &self.initial_codec_config
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

            let pts = ((packet.pts.get() * self.time_base_numer as i64 * 1_000_000)
                + (self.time_base_denom as i64 / 2))
                / self.time_base_denom as i64;

            let mut data = if self.needs_annex_b() {
                self.to_annex_b(&packet.data)
            } else {
                packet.data.to_vec()
            };

            let mut is_sync = detect_is_sync(&data, self.fourcc);

            if !self.codec_config.is_empty() {
                let mut prefixed = std::mem::take(&mut self.codec_config);
                prefixed.extend_from_slice(&data);
                data = prefixed;
                // First sample after configure/reset must be marked as key for WebCodecs.
                // The codec_config (VPS/SPS/PPS) is only prepended once after open/seek/reset.
                // Even if detect_is_sync missed the IRAP NAL, force is_sync=true for the
                // very first sample carrying config NALs.
                if !is_sync {
                    log::warn!(
                        "First sample after configure had no detectable key NAL; forcing is_sync=true for WebCodecs compliance"
                    );
                }
                is_sync = true;
            }

            self.last_pts_us = pts;
            self.current_sample += 1;

            return Ok(Some(DemuxedSample::new(data, pts, is_sync)));
        }
    }

    fn seek_to_sample(&mut self, sample_id: u32) -> DemuxResult<()> {
        use symphonia::core::formats::{SeekMode, SeekTo};
        use symphonia::core::units::Time;
        let seconds = if self.time_base_numer > 0 && self.time_base_denom > 0 {
            f64::from(sample_id) * f64::from(self.time_base_numer) / f64::from(self.time_base_denom)
        } else {
            f64::from(sample_id) / 30.0
        };
        let seconds = seconds.max(0.0);
        let time = Time::try_from_secs_f64(seconds).unwrap_or_default();
        self.format
            .seek(
                SeekMode::Accurate,
                SeekTo::Time {
                    time,
                    track_id: Some(self.track_id),
                },
            )
            .map_err(|e| DemuxError::Other(e.to_string()))?;
        self.current_sample = sample_id;
        self.last_pts_us = -1;
        self.codec_config = self.initial_codec_config.clone();
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
        self.codec_config = self.initial_codec_config.clone();
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
        0x30385056
    } else if codec == CODEC_ID_VP9 {
        0x30395056
    } else if codec == CODEC_ID_AV1 {
        0x31305641
    } else if codec == CODEC_ID_H264 {
        0x31637661
    } else if codec == CODEC_ID_HEVC {
        0x31766568
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

pub fn format_codec_name(codec: VideoCodecId) -> String {
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

fn detect_is_sync(data: &[u8], fourcc: u32) -> bool {
    match fourcc {
        0x30385056 => vp8_is_keyframe(data),
        0x30395056 => vp9_is_keyframe(data),
        0x31305641 | 0x31495641 => av1_is_keyframe(data),
        0x31637661 => h264_annexb_has_idr(data),
        0x31766568 => hevc_annexb_has_keyframe(data),
        _ => false,
    }
}
