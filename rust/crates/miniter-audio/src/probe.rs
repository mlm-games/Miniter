use std::fs::File;
use std::io::Cursor;
use std::path::Path;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

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
    let file = File::open(path)?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    probe_audio_stream(mss, path.extension().and_then(|e| e.to_str()))
}

pub fn probe_audio_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<AudioMeta, ProbeError> {
    let cursor = Cursor::new(bytes.to_vec());
    let mss = MediaSourceStream::new(Box::new(cursor), Default::default());

    probe_audio_stream(mss, extension_hint)
}

fn probe_audio_stream(
    mss: MediaSourceStream,
    extension_hint: Option<&str>,
) -> Result<AudioMeta, ProbeError> {
    let mut hint = Hint::new();
    if let Some(ext) = extension_hint {
        hint.with_extension(ext);
    }

    let probed = symphonia::default::get_probe().format(
        &hint,
        mss,
        &FormatOptions::default(),
        &MetadataOptions::default(),
    )?;

    let reader = probed.format;
    let track = reader
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != symphonia::core::codecs::CODEC_TYPE_NULL)
        .ok_or(ProbeError::NoAudioTrack)?;

    let params = &track.codec_params;

    let duration_us = params.n_frames.and_then(|frames| {
        params
            .sample_rate
            .map(|sr| (frames as f64 / sr as f64 * 1_000_000.0) as i64)
    });

    Ok(AudioMeta {
        codec: format!("{:?}", params.codec),
        sample_rate: params.sample_rate.unwrap_or(0),
        channels: params.channels.map(|c| c.count() as u16).unwrap_or(0),
        duration_us,
    })
}
