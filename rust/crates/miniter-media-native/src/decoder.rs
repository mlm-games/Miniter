//! Demux MP4 → extract H.264 NAL units → decode to RGBA frames via OpenH264.

use crate::frame::RgbaFrame;
use mp4::{Mp4Reader, TrackType};
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
use std::fs::File;
use std::io::{BufReader, Seek};
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("MP4: {0}")]
    Mp4(#[from] mp4::Error),
    #[error("OpenH264: {0}")]
    OpenH264(#[from] openh264::Error),
    #[error("No video stream")]
    NoVideoStream,
    #[error("Unsupported codec — only H.264 is supported")]
    UnsupportedCodec,
}

pub struct VideoDecodeSession<R: std::io::Read + Seek> {
    mp4: Mp4Reader<R>,
    h264_decoder: Decoder,
    video_track_id: u32,
    current_sample: u32,
    total_samples: u32,
    width: u32,
    height: u32,
    timescale: u32,
    nalu_length_size: u8,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
}

impl VideoDecodeSession<BufReader<File>> {
    pub fn open(path: &Path) -> Result<Self, DecodeError> {
        let file = File::open(path)?;
        let size = file.metadata()?.len();
        let reader = BufReader::new(file);
        Self::from_reader(reader, size)
    }
}

impl<R: std::io::Read + Seek> VideoDecodeSession<R> {
    pub fn from_reader(reader: R, size: u64) -> Result<Self, DecodeError> {
        let mp4 = Mp4Reader::read_header(reader, size)?;

        let video_track = mp4
            .tracks()
            .values()
            .find(|t| matches!(t.track_type(), Ok(TrackType::Video)))
            .ok_or(DecodeError::NoVideoStream)?;

        match video_track.media_type() {
            Ok(mp4::MediaType::H264) => {}
            _ => return Err(DecodeError::UnsupportedCodec),
        }

        let video_track_id = video_track.track_id();
        let total_samples = video_track.sample_count();
        let width = video_track.width() as u32;
        let height = video_track.height() as u32;
        let timescale = video_track.timescale();

        let (nalu_length_size, sps, pps) =
            if let Some(ref avc1) = video_track.trak.mdia.minf.stbl.stsd.avc1 {
                (
                    (avc1.avcc.length_size_minus_one & 0x03) + 1,
                    avc1.avcc
                        .sequence_parameter_sets
                        .first()
                        .map(|nal| nal.bytes.clone()),
                    avc1.avcc
                        .picture_parameter_sets
                        .first()
                        .map(|nal| nal.bytes.clone()),
                )
            } else {
                (4, None, None)
            };

        let h264_decoder = Decoder::new()?;

        Ok(Self {
            mp4,
            h264_decoder,
            video_track_id,
            current_sample: 1,
            total_samples,
            width,
            height,
            timescale,
            nalu_length_size,
            sps,
            pps,
        })
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }

    pub fn total_samples(&self) -> u32 {
        self.total_samples
    }

    pub fn timescale(&self) -> u32 {
        self.timescale
    }

    pub fn next_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeError> {
        loop {
            if self.current_sample > self.total_samples {
                return Ok(None);
            }

            let sample = self
                .mp4
                .read_sample(self.video_track_id, self.current_sample)?;

            let sample = match sample {
                Some(s) => s,
                None => return Ok(None),
            };

            let pts_us = if self.timescale > 0 {
                (sample.start_time as f64 / self.timescale as f64 * 1_000_000.0) as i64
            } else {
                0
            };

            self.current_sample += 1;

            let annex_b = mp4_nalu_to_annex_b(
                &sample.bytes,
                self.nalu_length_size,
                self.sps.as_deref(),
                self.pps.as_deref(),
            );

            let maybe_yuv = self.h264_decoder.decode(&annex_b);
            match maybe_yuv {
                Ok(Some(yuv)) => {
                    let (yw, yh) = yuv.dimensions();
                    let mut rgba = vec![0u8; yw * yh * 4];
                    yuv.write_rgba8(&mut rgba);

                    return Ok(Some(RgbaFrame {
                        width: yw as u32,
                        height: yh as u32,
                        data: rgba,
                        pts_us,
                    }));
                }
                Ok(None) => {
                    continue;
                }
                Err(e) => {
                    log::warn!("OpenH264 decode error on sample, skipping: {e}");
                    continue;
                }
            }
        }
    }

    pub fn reset(&mut self) -> Result<(), DecodeError> {
        self.current_sample = 1;
        self.h264_decoder = Decoder::new()?;
        Ok(())
    }

    pub fn seek_to_sample(&mut self, sample_id: u32) -> Result<(), DecodeError> {
        self.current_sample = sample_id.max(1);
        self.h264_decoder = Decoder::new()?;
        Ok(())
    }
}

fn mp4_nalu_to_annex_b(
    data: &[u8],
    nalu_length_size: u8,
    sps: Option<&[u8]>,
    pps: Option<&[u8]>,
) -> Vec<u8> {
    let start_code: &[u8] = &[0x00, 0x00, 0x00, 0x01];
    let mut output = Vec::with_capacity(data.len() + 128);
    let mut saw_sps = false;
    let mut saw_pps = false;
    let mut offset = 0;
    let len_size = nalu_length_size.clamp(1, 4) as usize;

    while offset + len_size <= data.len() {
        let mut nalu_len = 0usize;
        for _ in 0..len_size {
            nalu_len = (nalu_len << 8) | data[offset] as usize;
            offset += 1;
        }

        if nalu_len == 0 {
            continue;
        }

        if offset + nalu_len > data.len() {
            break;
        }

        let nalu = &data[offset..offset + nalu_len];
        if let Some(&header) = nalu.first() {
            match header & 0x1F {
                7 => saw_sps = true,
                8 => saw_pps = true,
                _ => {}
            }
        }

        output.extend_from_slice(start_code);
        output.extend_from_slice(nalu);
        offset += nalu_len;
    }

    if output.is_empty() {
        return data.to_vec();
    }

    if !saw_sps || !saw_pps {
        let mut prefixed = Vec::with_capacity(output.len() + 128);
        if !saw_sps {
            if let Some(sps_nal) = sps {
                prefixed.extend_from_slice(start_code);
                prefixed.extend_from_slice(sps_nal);
            }
        }
        if !saw_pps {
            if let Some(pps_nal) = pps {
                prefixed.extend_from_slice(start_code);
                prefixed.extend_from_slice(pps_nal);
            }
        }
        prefixed.extend_from_slice(&output);
        return prefixed;
    }

    output
}
