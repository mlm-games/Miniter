//! MKV, WebM, MP4, AVI, OGG, and other containers via symphonia.

use std::io::Read;
use std::io::Seek;

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
    /// Prepended to the first sample.
    codec_config: Vec<u8>,
}

// H264 AVCDecoderConfigurationRecord: byte 4 has lengthSizeMinusOne in bottom 2 bits.
const AVCC_LENGTH_SIZE_BYTE: usize = 4;
// HEVC HEVCDecoderConfigurationRecord: byte 21 has lengthSizeMinusOne in bottom 2 bits.
const HVCC_LENGTH_SIZE_BYTE: usize = 21;

fn extract_nalu_length_size_h264(extra_data: &[u8]) -> u8 {
    if extra_data.len() > AVCC_LENGTH_SIZE_BYTE {
        (extra_data[AVCC_LENGTH_SIZE_BYTE] & 0x03) + 1
    } else {
        4
    }
}

fn extract_nalu_length_size_hevc(extra_data: &[u8]) -> u8 {
    if extra_data.len() > HVCC_LENGTH_SIZE_BYTE {
        (extra_data[HVCC_LENGTH_SIZE_BYTE] & 0x03) + 1
    } else {
        4
    }
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
            CODEC_ID_AV1, CODEC_ID_H264, CODEC_ID_HEVC,
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
                        let nls = avcc.map(extract_nalu_length_size_h264).unwrap_or(4);
                        let annexb = avcc.map(parse_avcc).unwrap_or_default();
                        (nls, annexb)
                    }
                    CODEC_ID_HEVC => {
                        let hvcc = video_params
                            .extra_data
                            .iter()
                            .find(|d| d.id == VIDEO_EXTRA_DATA_ID_HEVC_DECODER_CONFIG)
                            .map(|d| &*d.data);
                        let nls = hvcc.map(extract_nalu_length_size_hevc).unwrap_or(4);
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
            codec_config,
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
        let start_code: &[u8] = &[0x00, 0x00, 0x00, 0x01];
        let mut output = Vec::with_capacity(data.len() + 128);
        let len_size = self.nalu_length_size as usize;
        let mut offset = 0;

        while offset + len_size <= data.len() {
            let mut nalu_len = 0usize;
            for _ in 0..len_size {
                nalu_len = (nalu_len << 8) | data[offset] as usize;
                offset += 1;
            }
            if nalu_len == 0 || offset + nalu_len > data.len() {
                continue;
            }
            output.extend_from_slice(start_code);
            output.extend_from_slice(&data[offset..offset + nalu_len]);
            offset += nalu_len;
        }

        if output.is_empty() {
            data.to_vec()
        } else {
            output
        }
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
        self.time_base_denom / self.time_base_numer
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

            let pts = (packet.pts.get() as i64 * self.time_base_numer as i64 * 1_000_000)
                / self.time_base_denom as i64;

            let mut data = if self.needs_annex_b() {
                self.to_annex_b(&packet.data)
            } else {
                packet.data.to_vec()
            };

            if !self.codec_config.is_empty() {
                let mut prefixed = std::mem::take(&mut self.codec_config);
                prefixed.extend_from_slice(&data);
                data = prefixed;
            }

            let is_sync = detect_is_sync(&data, self.fourcc);

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

/// Parse an HEVC hvcC (HEVCDecoderConfigurationRecord) and return the
/// VPS/SPS/PPS NAL units as a single AnnexB byte buffer.
pub fn parse_hvcc(data: &[u8]) -> Vec<u8> {
    if data.len() < 23 || data[0] != 1 {
        return Vec::new();
    }
    let num_arrays = data[22] as usize;
    let mut out = Vec::new();
    let mut pos = 23usize;
    for _ in 0..num_arrays {
        if pos >= data.len() {
            break;
        }
        let _nal_type = data[pos] & 0x3F;
        pos += 1;
        if pos + 2 > data.len() {
            break;
        }
        let num_nalus = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
        pos += 2;
        for _ in 0..num_nalus {
            if pos + 2 > data.len() {
                break;
            }
            let len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
            pos += 2;
            if pos + len > data.len() {
                break;
            }
            out.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
            out.extend_from_slice(&data[pos..pos + len]);
            pos += len;
        }
    }
    out
}

fn parse_avcc(data: &[u8]) -> Vec<u8> {
    if data.len() < 6 || data[0] != 1 {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut pos = 6usize;
    let num_sps = (data[5] & 0x1F) as usize;
    for _ in 0..num_sps {
        if pos + 2 > data.len() {
            break;
        }
        let len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
        pos += 2;
        if pos + len > data.len() {
            break;
        }
        out.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
        out.extend_from_slice(&data[pos..pos + len]);
        pos += len;
    }
    if pos >= data.len() {
        return out;
    }
    let num_pps = data[pos] as usize;
    pos += 1;
    for _ in 0..num_pps {
        if pos + 2 > data.len() {
            break;
        }
        let len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
        pos += 2;
        if pos + len > data.len() {
            break;
        }
        out.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
        out.extend_from_slice(&data[pos..pos + len]);
        pos += len;
    }
    out
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
    let Some(&first) = data.first() else {
        return false;
    };
    match fourcc {
        0x30385056 => (first & 0x01) == 0, // VP8: bit 0 = keyframe flag
        0x30395056 => (first & 0xC0) == 0x80 && (first & 0x04) == 0, // VP9
        0x31305641 | 0x31495641 => {
            // AV1
            let obu_type = (first >> 3) & 0x0F;
            matches!(obu_type, 1) // SEQUENCE_HEADER OBU
        }
        0x31637661 => {
            // H264
            let nal = skip_annexb_start_code(data);
            nal.first().is_some_and(|&b| (b & 0x1F) == 5)
        }
        0x31766568 => {
            // HEVC
            let nal = skip_annexb_start_code(data);
            nal.first()
                .is_some_and(|&b| matches!((b >> 1) & 0x3F, 16..=21 | 32))
        }
        _ => false,
    }
}

fn skip_annexb_start_code(data: &[u8]) -> &[u8] {
    if data.len() >= 4 && data[..4] == [0x00, 0x00, 0x00, 0x01] {
        &data[4..]
    } else if data.len() >= 3 && data[..3] == [0x00, 0x00, 0x01] {
        &data[3..]
    } else {
        data
    }
}
