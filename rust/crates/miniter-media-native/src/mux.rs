//! Mux H.264/AV1 video and Opus audio into MP4 or Matroska using muxfin.

use muxfin::api::{
    AudioCodec, Muxer as InnerMuxer, MuxerBuilder, MuxerError as InnerMuxError, SubtitleCodec,
    VideoCodec,
};
use muxfin::codec::ass::AssEvent;
use muxfin::time::SubtitleCue;
use std::io::Write;

#[derive(Debug, thiserror::Error)]
pub enum MuxError {
    #[error("muxfin: {0}")]
    Muxide(#[from] InnerMuxError),
    #[error("MOV container is not supported by muxfin")]
    MovUnsupported,
    #[error("ASS/SSA subtitles need CodecPrivate (missing styles section)")]
    MissingAssCodecPrivate,
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
    /// Encoder delay in 48kHz samples, signalled via dOps so players skip
    /// it. Must match the PTS basis in `EncodedOpusPacket` (raw, WITHOUT
    /// preskip subtracted).
    pub preskip_48k: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubtitleTrackCodecOut {
    MovText,
    /// Matroska `S_TEXT/SSA` (styling preserved via CodecPrivate).
    Ssa,
    /// Matroska `S_TEXT/ASS` (styling preserved via CodecPrivate).
    Ass,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubtitleTrackConfigOut {
    pub codec: SubtitleTrackCodecOut,
    pub language: Option<String>,
    /// ASS/SSA CodecPrivate (`[Script Info]` + `[V4(+?) Styles]`).
    /// Required for `Ssa`/`Ass`, ignored otherwise.
    pub ass_codec_private: Option<Vec<u8>>,
}

impl SubtitleTrackConfigOut {
    pub fn mov_text(language: Option<String>) -> Self {
        Self {
            codec: SubtitleTrackCodecOut::MovText,
            language,
            ass_codec_private: None,
        }
    }
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
        subtitle: Option<SubtitleTrackConfigOut>,
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
            builder = builder
                .audio(AudioCodec::Opus, audio.sample_rate, audio.channels)
                .with_opus_preskip(audio.preskip_48k);
        }

        if let Some(subtitle) = subtitle {
            let codec = match subtitle.codec {
                SubtitleTrackCodecOut::MovText => SubtitleCodec::MovText,
                SubtitleTrackCodecOut::Ssa | SubtitleTrackCodecOut::Ass => {
                    return Err(MuxError::MissingAssCodecPrivate);
                }
            };
            builder = builder.subtitle(codec, subtitle.language);
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

    /// Write a video sample with an explicit decode timestamp for streams
    /// with B-frames (PTS != DTS).
    ///
    /// Frames must be fed in decode order with strictly increasing DTS; PTS
    /// may go backwards relative to the previous sample. muxfin records the
    /// `pts - dts` offsets in the `ctts` box so players display in PTS
    /// order. Use this for AV1 (rav1e emits decode-order packets); H.264
    /// (P-frames only) keeps using `write_sample_at`.
    pub fn write_sample_with_dts_at(
        &mut self,
        pts_us: u64,
        dts_us: u64,
        data: &[u8],
        is_keyframe: bool,
    ) -> Result<(), MuxError> {
        let pts = pts_us as f64 / 1_000_000.0;
        let dts = dts_us as f64 / 1_000_000.0;
        self.writer
            .write_video_with_dts(pts, dts, data, is_keyframe)?;
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

    pub fn write_subtitle_sample_at(
        &mut self,
        start_time_us: u64,
        duration_us: u64,
        text: &str,
    ) -> Result<(), MuxError> {
        // Subtitle track timescale is 1_000 (builder default) ms ticks.
        let start_ms = start_time_us.div_ceil(1_000);
        let duration_ms = duration_us.div_ceil(1_000).max(1) as u32;
        let start = i64::try_from(start_ms).unwrap_or(i64::MAX);
        let cue = SubtitleCue::new(start, duration_ms, text)?;
        self.writer.write_subtitle_cue(cue)?;
        Ok(())
    }

    pub fn finish(&mut self) -> Result<(), MuxError> {
        self.writer.finish_in_place()?;
        Ok(())
    }
}

/// Matroska/WebM muxer: the parallel counterpart to [`Mp4Muxer`].
///
/// Produced via [`MkvMuxer::new`]; timestamp/error semantics mirror the
/// MP4 path. ASS/SSA subtitle tracks preserve styling through CodecPrivate
/// + per-event Blocks (muxfin 0.5+).
pub struct MkvMuxer<W: Write> {
    writer: muxfin::api::MkvMuxer<W>,
    subtitle_codec: Option<SubtitleTrackCodecOut>,
}

impl<W: Write> MkvMuxer<W> {
    /// `matroska` selects `.mkv`, `webm` selects `.webm`.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        output: W,
        width: u32,
        height: u32,
        fps: f64,
        matroska: bool,
        audio: Option<OpusTrackConfigOut>,
        subtitle: Option<SubtitleTrackConfigOut>,
        video_codec: VideoTrackCodecOut,
    ) -> Result<Self, MuxError> {
        use muxfin::api::ContainerFormat as MuxContainer;

        let video_codec = match video_codec {
            VideoTrackCodecOut::H264 => VideoCodec::H264,
            VideoTrackCodecOut::Av1 => VideoCodec::Av1,
        };

        let mut builder = MuxerBuilder::new(output)
            .video(video_codec, width, height, fps)
            .with_container(if matroska {
                MuxContainer::Matroska
            } else {
                MuxContainer::WebM
            });

        if let Some(audio) = audio {
            builder = builder
                .audio(AudioCodec::Opus, audio.sample_rate, audio.channels)
                .with_opus_preskip(audio.preskip_48k);
        }

        let subtitle_codec = subtitle.as_ref().map(|s| s.codec);
        if let Some(subtitle) = subtitle {
            let codec = match subtitle.codec {
                SubtitleTrackCodecOut::MovText => SubtitleCodec::MovText,
                SubtitleTrackCodecOut::Ssa => SubtitleCodec::Ssa,
                SubtitleTrackCodecOut::Ass => SubtitleCodec::Ass,
            };
            if let Some(private) = subtitle.ass_codec_private {
                builder = builder.with_ass_codec_private(private);
            }
            builder = builder.subtitle(codec, subtitle.language);
        }

        let writer = builder.build_mkv()?;
        Ok(Self {
            writer,
            subtitle_codec,
        })
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

    /// Decode-order feed with explicit DTS (same B-frame contract as
    /// [`Mp4Muxer::write_sample_with_dts_at`]).
    pub fn write_sample_with_dts_at(
        &mut self,
        pts_us: u64,
        dts_us: u64,
        data: &[u8],
        is_keyframe: bool,
    ) -> Result<(), MuxError> {
        let pts = pts_us as f64 / 1_000_000.0;
        let dts = dts_us as f64 / 1_000_000.0;
        self.writer
            .write_video_with_dts(pts, dts, data, is_keyframe)?;
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

    pub fn write_subtitle_sample_at(
        &mut self,
        start_time_us: u64,
        duration_us: u64,
        text: &str,
    ) -> Result<(), MuxError> {
        if matches!(
            self.subtitle_codec,
            Some(SubtitleTrackCodecOut::Ssa) | Some(SubtitleTrackCodecOut::Ass)
        ) {
            return Err(MuxError::MissingAssCodecPrivate);
        }
        let pts = start_time_us as f64 / 1_000_000.0;
        let duration = duration_us.max(1) as f64 / 1_000_000.0;
        self.writer.write_subtitle(pts, duration, text)?;
        Ok(())
    }

    /// Write one ASS/SSA Dialogue event (styling preserved).
    pub fn write_ass_event_at(
        &mut self,
        start_time_us: u64,
        duration_us: u64,
        read_order: u32,
        event: &AssEvent,
    ) -> Result<(), MuxError> {
        let pts = start_time_us as f64 / 1_000_000.0;
        let duration = duration_us.max(1) as f64 / 1_000_000.0;
        self.writer
            .write_ass_event(pts, duration, read_order, event)?;
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
            if annex_b[j] == 0
                && annex_b[j + 1] == 0
                && ((j + 3 <= len && annex_b[j + 2] == 1)
                    || (j + 4 <= len && annex_b[j + 2] == 0 && annex_b[j + 3] == 1))
            {
                nalu_end = j;
                break;
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
