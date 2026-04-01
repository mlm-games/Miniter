//! Mux H.264 bitstream into an MP4 container using the `mp4` crate writer.

use mp4::{AvcConfig, MediaConfig, Mp4Config, Mp4Writer, TrackConfig};
use std::io::{Seek, Write};

#[derive(Debug, thiserror::Error)]
pub enum MuxError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("MP4: {0}")]
    Mp4(#[from] mp4::Error),
}

/// A simple MP4 muxer that writes H.264 video samples.
pub struct Mp4Muxer<W: Write + Seek> {
    writer: Mp4Writer<W>,
    track_id: u32,
    timescale: u32,
    current_time: u64,
    frame_duration: u64,
}

impl<W: Write + Seek> Mp4Muxer<W> {
    /// Create a new MP4 muxer writing to `output`.
    ///
    /// `width`/`height` — video dimensions (must be even).
    /// `fps` — frames per second.
    /// `sps`/`pps` — Sequence / Picture Parameter Sets extracted from
    /// the first keyframe (required for the avcC box).
    pub fn new(
        output: W,
        width: u32,
        height: u32,
        fps: f64,
        sps: &[u8],
        pps: &[u8],
    ) -> Result<Self, MuxError> {
        let timescale = 90_000u32;
        let frame_duration = (timescale as f64 / fps) as u64;

        let mp4_config = Mp4Config {
            major_brand: "isom".parse().unwrap(),
            minor_version: 512,
            compatible_brands: vec![
                "isom".parse().unwrap(),
                "iso2".parse().unwrap(),
                "avc1".parse().unwrap(),
                "mp41".parse().unwrap(),
            ],
            timescale,
        };

        let mut writer = Mp4Writer::write_start(output, &mp4_config)?;

        let track_config = TrackConfig {
            track_type: mp4::TrackType::Video,
            timescale,
            language: "und".to_string(),
            media_conf: MediaConfig::AvcConfig(AvcConfig {
                width: width as u16,
                height: height as u16,
                seq_param_set: sps.to_vec(),
                pic_param_set: pps.to_vec(),
            }),
        };

        writer.add_track(&track_config)?;
        let track_id = 1;

        Ok(Self {
            writer,
            track_id,
            timescale,
            current_time: 0,
            frame_duration,
        })
    }

    /// Write one encoded H.264 sample (the bitstream from the encoder).
    pub fn write_sample(&mut self, data: &[u8], is_keyframe: bool) -> Result<(), MuxError> {
        let avcc_data = annex_b_to_avcc(data);

        let sample = mp4::Mp4Sample {
            start_time: self.current_time,
            duration: self.frame_duration as u32,
            rendering_offset: 0,
            is_sync: is_keyframe,
            bytes: mp4::Bytes::copy_from_slice(&avcc_data),
        };

        self.writer.write_sample(self.track_id, &sample)?;
        self.current_time += self.frame_duration;

        Ok(())
    }

    /// Finalize the MP4 file (writes moov atom).
    pub fn finish(&mut self) -> Result<(), MuxError> {
        self.writer.write_end()?;
        Ok(())
    }
}

/// Convert Annex-B (start-code prefixed) to AVCC (length-prefixed) NALUs.
fn annex_b_to_avcc(data: &[u8]) -> Vec<u8> {
    let mut output = Vec::with_capacity(data.len());
    let mut i = 0;
    let len = data.len();

    while i < len {
        let (start_code_len, nalu_start) = if i + 4 <= len
            && data[i] == 0
            && data[i + 1] == 0
            && data[i + 2] == 0
            && data[i + 3] == 1
        {
            (4, i + 4)
        } else if i + 3 <= len && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 {
            (3, i + 3)
        } else {
            i += 1;
            continue;
        };

        i = nalu_start;

        let mut nalu_end = len;
        let mut j = i;
        while j + 3 <= len {
            if data[j] == 0 && data[j + 1] == 0 {
                if j + 3 <= len && data[j + 2] == 1 {
                    nalu_end = j;
                    break;
                }
                if j + 4 <= len && data[j + 2] == 0 && data[j + 3] == 1 {
                    nalu_end = j;
                    break;
                }
            }
            j += 1;
        }

        let nalu = &data[i..nalu_end];
        let nalu_len = nalu.len() as u32;
        output.extend_from_slice(&nalu_len.to_be_bytes());
        output.extend_from_slice(nalu);

        i = nalu_end;
    }

    if output.is_empty() {
        return data.to_vec();
    }

    output
}

/// Extract SPS and PPS from an Annex-B bitstream.
/// Returns `(sps, pps)` or `None` if not found.
pub fn extract_sps_pps(annex_b: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    let mut sps = None;
    let mut pps = None;

    let mut i = 0;
    let len = annex_b.len();

    while i < len {
        let nalu_start = if i + 4 <= len
            && annex_b[i] == 0
            && annex_b[i + 1] == 0
            && annex_b[i + 2] == 0
            && annex_b[i + 3] == 1
        {
            i + 4
        } else if i + 3 <= len && annex_b[i] == 0 && annex_b[i + 1] == 0 && annex_b[i + 2] == 1 {
            i + 3
        } else {
            i += 1;
            continue;
        };

        i = nalu_start;

        let mut nalu_end = len;
        let mut j = i;
        while j + 3 <= len {
            if annex_b[j] == 0 && annex_b[j + 1] == 0 {
                if (j + 3 <= len && annex_b[j + 2] == 1)
                    || (j + 4 <= len && annex_b[j + 2] == 0 && annex_b[j + 3] == 1)
                {
                    nalu_end = j;
                    break;
                }
            }
            j += 1;
        }

        if i < nalu_end {
            let nalu_type = annex_b[i] & 0x1F;
            let nalu_data = annex_b[i..nalu_end].to_vec();
            match nalu_type {
                7 => sps = Some(nalu_data),
                8 => pps = Some(nalu_data),
                _ => {}
            }
        }

        i = nalu_end;
    }

    match (sps, pps) {
        (Some(s), Some(p)) => Some((s, p)),
        _ => None,
    }
}
