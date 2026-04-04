//! Mux H.264 bitstream into an MP4/MOV container using the `mp4` crate writer.

use mp4::{
    AacConfig, AudioObjectType, AvcConfig, Bytes, ChannelConfig, MediaConfig, Mp4Config, Mp4Sample,
    Mp4Writer, SampleFreqIndex, TrackConfig,
};
use std::io::{Seek, Write};

#[derive(Debug, thiserror::Error)]
pub enum MuxError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("MP4: {0}")]
    Mp4(#[from] mp4::Error),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContainerFormat {
    Mp4,
    Mov,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AacTrackConfigOut {
    pub sample_rate: u32,
    pub bitrate: u32,
    pub profile: AudioObjectType,
    pub freq_index: SampleFreqIndex,
    pub chan_conf: ChannelConfig,
}

/// MP4/MOV muxer that writes H.264 video samples and, optionally, AAC audio samples.
pub struct Mp4Muxer<W: Write + Seek> {
    writer: Mp4Writer<W>,
    video_track_id: u32,
    audio_track_id: Option<u32>,
    video_timescale: u32,
    current_video_time: u64,
    frame_duration: u64,
}

impl<W: Write + Seek> Mp4Muxer<W> {
    pub fn new(
        output: W,
        width: u32,
        height: u32,
        fps: f64,
        sps: &[u8],
        pps: &[u8],
        container: ContainerFormat,
        audio: Option<AacTrackConfigOut>,
    ) -> Result<Self, MuxError> {
        let video_timescale = 90_000u32;
        let frame_duration = (video_timescale as f64 / fps) as u64;

        let mp4_config = match container {
            ContainerFormat::Mp4 => Mp4Config {
                major_brand: "isom".parse().unwrap(),
                minor_version: 512,
                compatible_brands: vec![
                    "isom".parse().unwrap(),
                    "iso2".parse().unwrap(),
                    "avc1".parse().unwrap(),
                    "mp41".parse().unwrap(),
                ],
                timescale: video_timescale,
            },
            ContainerFormat::Mov => Mp4Config {
                major_brand: "qt  ".parse().unwrap(),
                minor_version: 0,
                compatible_brands: vec!["qt  ".parse().unwrap()],
                timescale: video_timescale,
            },
        };

        let mut writer = Mp4Writer::write_start(output, &mp4_config)?;

        let video_track_config = TrackConfig {
            track_type: mp4::TrackType::Video,
            timescale: video_timescale,
            language: "und".to_string(),
            media_conf: MediaConfig::AvcConfig(AvcConfig {
                width: width as u16,
                height: height as u16,
                seq_param_set: sps.to_vec(),
                pic_param_set: pps.to_vec(),
            }),
        };

        writer.add_track(&video_track_config)?;
        let video_track_id = 1;

        let audio_track_id = if let Some(audio) = audio {
            let audio_track_config = TrackConfig {
                track_type: mp4::TrackType::Audio,
                timescale: audio.sample_rate,
                language: "und".to_string(),
                media_conf: MediaConfig::AacConfig(AacConfig {
                    bitrate: audio.bitrate,
                    profile: audio.profile,
                    freq_index: audio.freq_index,
                    chan_conf: audio.chan_conf,
                }),
            };
            writer.add_track(&audio_track_config)?;
            Some(2)
        } else {
            None
        };

        Ok(Self {
            writer,
            video_track_id,
            audio_track_id,
            video_timescale,
            current_video_time: 0,
            frame_duration,
        })
    }

    pub fn write_sample(&mut self, data: &[u8], is_keyframe: bool) -> Result<(), MuxError> {
        let avcc_data = annex_b_to_avcc(data);

        let sample = Mp4Sample {
            start_time: self.current_video_time,
            duration: self.frame_duration as u32,
            rendering_offset: 0,
            is_sync: is_keyframe,
            bytes: Bytes::copy_from_slice(&avcc_data),
        };

        self.writer.write_sample(self.video_track_id, &sample)?;
        self.current_video_time += self.frame_duration;

        Ok(())
    }

    pub fn write_audio_sample_at(
        &mut self,
        start_time: u64,
        duration: u32,
        data: &[u8],
    ) -> Result<(), MuxError> {
        let track_id = match self.audio_track_id {
            Some(id) => id,
            None => return Ok(()),
        };
        let sample = Mp4Sample {
            start_time,
            duration,
            rendering_offset: 0,
            is_sync: true,
            bytes: Bytes::copy_from_slice(data),
        };
        self.writer.write_sample(track_id, &sample)?;
        Ok(())
    }

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
