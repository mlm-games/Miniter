use std::fs::File;
use std::io::Cursor;
use std::path::Path;

use symphonia::core::codecs::audio::AudioDecoderOptions;
use symphonia::core::formats::FormatOptions;
use symphonia::core::formats::probe::Hint;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

#[derive(Debug, Clone)]
pub struct DecodedAudio {
    pub sample_rate: u32,
    pub channels: u16,
    pub samples: Vec<f32>,
}

impl DecodedAudio {
    pub fn frames(&self) -> usize {
        let channels = self.channels.max(1) as usize;
        self.samples.len() / channels
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DecodeAudioError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("No audio track found")]
    NoAudioTrack,
    #[error("Symphonia error: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
}

pub fn probe_has_audio_track(path: &Path) -> Result<bool, DecodeAudioError> {
    let ext = path.extension().and_then(|e| e.to_str());
    if is_image_extension(ext) || is_video_only_extension(ext) {
        return Ok(false);
    }
    let file = File::open(path)?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    let mut hint = Hint::new();
    if let Some(ext) = ext {
        hint.with_extension(ext);
    }

    let reader = match symphonia::default::get_probe().probe(
        &hint,
        mss,
        FormatOptions::default(),
        MetadataOptions::default(),
    ) {
        Ok(r) => r,
        Err(symphonia::core::errors::Error::Unsupported(_)) => return Ok(false),
        Err(e) => return Err(e.into()),
    };

    Ok(reader
        .tracks()
        .iter()
        .any(|t| is_likely_audio_params(&t.codec_params)))
}

pub fn decode_audio_f32(path: &Path) -> Result<DecodedAudio, DecodeAudioError> {
    let ext = path.extension().and_then(|e| e.to_str());
    if is_image_extension(ext) || is_video_only_extension(ext) {
        return Err(DecodeAudioError::NoAudioTrack);
    }
    let file = File::open(path)?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    decode_audio_stream(mss, ext)
}

pub fn decode_audio_f32_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<DecodedAudio, DecodeAudioError> {
    if is_image_extension(extension_hint) || is_video_only_extension(extension_hint) {
        return Err(DecodeAudioError::NoAudioTrack);
    }
    let cursor = Cursor::new(bytes.to_vec());
    let mss = MediaSourceStream::new(Box::new(cursor), Default::default());

    decode_audio_stream(mss, extension_hint)
}

fn decode_audio_stream(
    mss: MediaSourceStream,
    extension_hint: Option<&str>,
) -> Result<DecodedAudio, DecodeAudioError> {
    let mut hint = Hint::new();
    if let Some(ext) = extension_hint {
        hint.with_extension(ext);
    }

    let mut reader = match symphonia::default::get_probe().probe(
        &hint,
        mss,
        FormatOptions::default(),
        MetadataOptions::default(),
    ) {
        Ok(r) => r,
        Err(symphonia::core::errors::Error::Unsupported(_)) => {
            return Err(DecodeAudioError::NoAudioTrack);
        }
        Err(e) => return Err(e.into()),
    };

    let track = reader
        .tracks()
        .iter()
        .find(|t| is_likely_audio_params(&t.codec_params))
        .or_else(|| {
            reader
                .tracks()
                .iter()
                .find(|t| is_any_codec(&t.codec_params))
        })
        .ok_or(DecodeAudioError::NoAudioTrack)?;

    let track_id = track.id;
    let audio_params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .cloned()
        .ok_or(DecodeAudioError::NoAudioTrack)?;

    let mut decoder = crate::codecs::get_codecs()
        .make_audio_decoder(&audio_params, &AudioDecoderOptions::default())?;

    // Decoder may correct unset codec params (for eg. Opus-in-MP4 where symphonia's demuxer leaves sample_rate/channels as None).
    let decoder_params = decoder.codec_params();
    let sample_rate = decoder_params
        .sample_rate
        .or(audio_params.sample_rate)
        .unwrap_or(44_100);
    let channels = decoder_params
        .channels
        .as_ref()
        .or(audio_params.channels.as_ref())
        .map(|c| c.count() as u16)
        .unwrap_or(1)
        .max(1);

    let mut samples = Vec::<f32>::new();

    loop {
        let packet = match reader.next_packet() {
            Ok(Some(packet)) => packet,
            Ok(None) => break,
            Err(e) => return Err(e.into()),
        };

        if packet.track_id != track_id {
            continue;
        }

        let decoded = decoder.decode(&packet)?;
        let mut packet_samples = Vec::new();
        decoded.copy_to_vec_interleaved(&mut packet_samples);
        samples.extend_from_slice(&packet_samples);
    }

    Ok(DecodedAudio {
        sample_rate,
        channels,
        samples,
    })
}

fn is_likely_audio_params(params: &Option<symphonia::core::codecs::CodecParameters>) -> bool {
    match params {
        Some(symphonia::core::codecs::CodecParameters::Audio(a)) => {
            a.channels.is_some() || a.bits_per_sample.is_some() || a.sample_rate.is_some()
        }
        _ => false,
    }
}

fn is_any_codec(params: &Option<symphonia::core::codecs::CodecParameters>) -> bool {
    params.is_some()
}

fn is_video_only_extension(ext: Option<&str>) -> bool {
    matches!(ext.map(|e| e.to_lowercase()).as_deref(), Some("ivf"))
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
