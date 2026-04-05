//! Mux H.264 video and Opus audio into MP4 using muxide.

use muxide::api::{
    AudioCodec, Muxer as InnerMuxer, MuxerBuilder, MuxerError as InnerMuxError, VideoCodec,
};
use std::io::Write;

#[derive(Debug, thiserror::Error)]
pub enum MuxError {
    #[error("muxide: {0}")]
    Muxide(#[from] InnerMuxError),
    #[error("MOV container is not supported by muxide")]
    MovUnsupported,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ContainerFormat {
    Mp4,
    Mov,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoTrackCodecOut {
    H264,
    Av1,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OpusTrackConfigOut {
    pub sample_rate: u32,
    pub channels: u16,
}

pub struct Mp4Muxer<W: Write> {
    writer: InnerMuxer<W>,
}

impl<W: Write> Mp4Muxer<W> {
    pub fn new(
        output: W,
        width: u32,
        height: u32,
        fps: f64,
        _sps: &[u8],
        _pps: &[u8],
        container: ContainerFormat,
        audio: Option<OpusTrackConfigOut>,
        video_codec: VideoTrackCodecOut,
    ) -> Result<Self, MuxError> {
        if matches!(container, ContainerFormat::Mov) {
            return Err(MuxError::MovUnsupported);
        }

        let video_codec = match video_codec {
            VideoTrackCodecOut::H264 => VideoCodec::H264,
            VideoTrackCodecOut::Av1 => VideoCodec::Av1,
        };

        let mut builder = MuxerBuilder::new(output).video(video_codec, width, height, fps);

        if let Some(audio) = audio {
            builder = builder.audio(AudioCodec::Opus, audio.sample_rate, audio.channels);
        }

        let writer = builder.build()?;

        Ok(Self { writer })
    }

    pub fn write_sample_at(
        &mut self,
        pts_us: u64,
        data: &[u8],
        is_keyframe: bool,
    ) -> Result<(), MuxError> {
        let pts = pts_us as f64 / 1_000_000.0;
        self.writer.write_video(pts, data, is_keyframe)?;
        Ok(())
    }

    pub fn write_audio_sample_at(
        &mut self,
        start_time_us: u64,
        data: &[u8],
    ) -> Result<(), MuxError> {
        let pts = start_time_us as f64 / 1_000_000.0;
        self.writer.write_audio(pts, data)?;
        Ok(())
    }

    pub fn finish(&mut self) -> Result<(), MuxError> {
        self.writer.finish_in_place()?;
        Ok(())
    }
}

/// Extract SPS and PPS from an Annex-B bitstream (kept for API compatibility).
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
