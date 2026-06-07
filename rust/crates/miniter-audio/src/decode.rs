use std::path::Path;

use symphonia::core::codecs::audio::AudioDecoderOptions;
use symphonia::core::io::MediaSourceStream;

use crate::util;

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
    let (mss, ext) = util::open_mss_from_path(path)?;
    if util::is_image_extension(ext.as_deref()) || util::is_video_only_extension(ext.as_deref()) {
        return Ok(false);
    }

    let reader = match util::probe(mss, ext.as_deref()) {
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
    let (mss, ext) = util::open_mss_from_path(path)?;
    if util::is_image_extension(ext.as_deref()) || util::is_video_only_extension(ext.as_deref()) {
        return Err(DecodeAudioError::NoAudioTrack);
    }
    decode_audio_stream(mss, ext.as_deref())
}

pub fn decode_audio_f32_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<DecodedAudio, DecodeAudioError> {
    if util::is_image_extension(extension_hint) || util::is_video_only_extension(extension_hint) {
        return Err(DecodeAudioError::NoAudioTrack);
    }
    let (mss, ext) = util::open_mss_from_bytes(bytes, extension_hint);
    decode_audio_stream(mss, ext.as_deref())
}

fn decode_audio_stream(
    mss: MediaSourceStream,
    extension_hint: Option<&str>,
) -> Result<DecodedAudio, DecodeAudioError> {
    let mut reader = match util::probe(mss, extension_hint) {
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
