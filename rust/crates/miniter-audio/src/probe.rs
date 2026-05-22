use std::fs::File;
use std::io::Cursor;
use std::path::Path;
use symphonia::core::formats::FormatOptions;
use symphonia::core::formats::probe::Hint;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

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
    if is_image_file(path) {
        return Err(ProbeError::NoAudioTrack);
    }
    let file = File::open(path)?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    probe_audio_stream(mss, path.extension().and_then(|e| e.to_str()))
}

pub fn probe_audio_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<AudioMeta, ProbeError> {
    if is_image_extension(extension_hint) {
        return Err(ProbeError::NoAudioTrack);
    }
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

    let reader = symphonia::default::get_probe().probe(
        &hint,
        mss,
        FormatOptions::default(),
        MetadataOptions::default(),
    )?;

    let track = reader
        .tracks()
        .iter()
        .find(|t| {
            t.codec_params
                .as_ref()
                .and_then(|p| p.audio())
                .is_some()
        })
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

fn is_image_file(path: &Path) -> bool {
    is_image_extension(path.extension().and_then(|e| e.to_str()))
}

fn is_image_extension(ext: Option<&str>) -> bool {
    match ext.map(|e| e.to_lowercase()).as_deref() {
        Some("png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "tif") => true,
        _ => false,
    }
}
