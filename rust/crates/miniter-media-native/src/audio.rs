use std::path::Path;

use miniter_audio::codecs;
use miniter_audio::util;
use symphonia::core::codecs::audio::AudioDecoderOptions;

#[derive(Debug, thiserror::Error)]
pub enum AudioDecodeError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Symphonia error: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
    #[error("No audio track")]
    NoAudioTrack,
    #[error("Unsupported codec: {0}")]
    UnsupportedCodec(String),
}

pub struct AudioDecoder {
    decoder: Box<dyn symphonia::core::codecs::audio::AudioDecoder>,
    track_id: u32,
    sample_rate: u32,
    channels: u16,
}

impl AudioDecoder {
    pub fn new(path: &Path) -> Result<Self, AudioDecodeError> {
        let (mss, ext) = util::open_mss_from_path(path)?;
        let mut format = util::probe(mss, ext.as_deref())?;

        let track = format
            .tracks()
            .iter()
            .find(|t| t.codec_params.as_ref().and_then(|p| p.audio()).is_some())
            .ok_or(AudioDecodeError::NoAudioTrack)?;

        let track_id = track.id;
        let audio_params = track
            .codec_params
            .as_ref()
            .and_then(|p| p.audio())
            .cloned()
            .ok_or(AudioDecodeError::NoAudioTrack)?;
        let sample_rate = audio_params.sample_rate.unwrap_or(44100);
        let channels = audio_params
            .channels
            .as_ref()
            .map(|c| c.count() as u16)
            .unwrap_or(2);

        let decoder_opts = AudioDecoderOptions::default();
        let decoder = codecs::get_codecs()
            .make_audio_decoder(&audio_params, &decoder_opts)
            .map_err(|e| AudioDecodeError::UnsupportedCodec(e.to_string()))?;

        Ok(Self {
            decoder,
            track_id,
            sample_rate,
            channels,
        })
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn channels(&self) -> u16 {
        self.channels
    }

    pub fn decode_file(&mut self, path: &Path) -> Result<Vec<i16>, AudioDecodeError> {
        let (mss, ext) = util::open_mss_from_path(path)?;
        let mut format = util::probe(mss, ext.as_deref())?;

        let track = format
            .tracks()
            .iter()
            .find(|t| t.id == self.track_id)
            .ok_or(AudioDecodeError::NoAudioTrack)?;

        let track_id = track.id;
        let audio_params = track
            .codec_params
            .as_ref()
            .and_then(|p| p.audio())
            .cloned()
            .ok_or(AudioDecodeError::NoAudioTrack)?;

        let decoder_opts = AudioDecoderOptions::default();
        let mut decoder = codecs::get_codecs()
            .make_audio_decoder(&audio_params, &decoder_opts)
            .map_err(|e| AudioDecodeError::UnsupportedCodec(e.to_string()))?;

        let mut all_samples: Vec<i16> = Vec::new();

        loop {
            let packet = match format.next_packet() {
                Ok(Some(p)) => p,
                Ok(None) => break,
                Err(e) => return Err(e.into()),
            };

            if packet.track_id != track_id {
                continue;
            }

            let decoded = match decoder.decode(&packet) {
                Ok(d) => d,
                Err(_) => continue,
            };

            let mut packet_samples = Vec::new();
            decoded.copy_to_vec_interleaved(&mut packet_samples);
            all_samples.extend_from_slice(&packet_samples);
        }

        Ok(all_samples)
    }
}
