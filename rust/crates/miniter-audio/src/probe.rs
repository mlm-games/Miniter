use std::path::Path;

use symphonia::core::io::MediaSourceStream;

use crate::util;

#[derive(Debug, Clone)]
pub struct AudioMeta {
    pub codec: String,
    pub sample_rate: u32,
    pub channels: u16,
    pub duration_us: Option<i64>,
}

#[derive(Debug, thiserror::Error)]
pub enum ProbeError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("No audio track found")]
    NoAudioTrack,
    #[error("Symphonia error: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
}

pub fn probe_audio(path: &Path) -> Result<AudioMeta, ProbeError> {
    let (mss, ext) = util::open_mss_from_path(path)?;
    if util::is_image_extension(ext.as_deref()) || util::is_video_only_extension(ext.as_deref()) {
        return Err(ProbeError::NoAudioTrack);
    }
    probe_audio_stream(mss, ext.as_deref())
}

pub fn probe_audio_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<AudioMeta, ProbeError> {
    if util::is_image_extension(extension_hint) || util::is_video_only_extension(extension_hint) {
        return Err(ProbeError::NoAudioTrack);
    }
    let (mss, ext) = util::open_mss_from_bytes(bytes, extension_hint);
    probe_audio_stream(mss, ext.as_deref())
}

fn probe_audio_stream(
    mss: MediaSourceStream,
    extension_hint: Option<&str>,
) -> Result<AudioMeta, ProbeError> {
    let reader = match util::probe(mss, extension_hint) {
        Ok(r) => r,
        Err(e) => {
            return match &e {
                symphonia::core::errors::Error::Unsupported(_) => Err(ProbeError::NoAudioTrack),
                _ => Err(ProbeError::Symphonia(e)),
            };
        }
    };

    let track = reader
        .tracks()
        .iter()
        .find(|t| t.codec_params.as_ref().and_then(|p| p.audio()).is_some())
        .ok_or(ProbeError::NoAudioTrack)?;

    let audio_params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .ok_or(ProbeError::NoAudioTrack)?;

    let duration_us = track.num_frames.and_then(|frames| {
        audio_params
            .sample_rate
            .map(|sr| (frames as f64 / sr as f64 * 1_000_000.0) as i64)
    });

    Ok(AudioMeta {
        codec: format!("{:?}", audio_params.codec),
        sample_rate: audio_params.sample_rate.unwrap_or(0),
        channels: audio_params
            .channels
            .as_ref()
            .map(|c| c.count() as u16)
            .unwrap_or(0),
        duration_us,
    })
}
