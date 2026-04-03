use std::fs::File;
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

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
    decoder: Box<dyn symphonia::core::codecs::Decoder>,
    track_id: u32,
    sample_rate: u32,
    channels: u16,
}

impl AudioDecoder {
    pub fn new(path: &Path) -> Result<Self, AudioDecodeError> {
        let file = File::open(path)?;
        let mss = MediaSourceStream::new(Box::new(file), Default::default());

        let mut hint = Hint::new();
        if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
            hint.with_extension(ext);
        }

        let probed = symphonia::default::get_probe().format(
            &hint,
            mss,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )?;

        let track = probed
            .format
            .tracks()
            .iter()
            .find(|t| t.codec_params.codec != symphonia::core::codecs::CODEC_TYPE_NULL)
            .ok_or(AudioDecodeError::NoAudioTrack)?;

        let track_id = track.id;
        let codec_params = track.codec_params.clone();
        let sample_rate = codec_params.sample_rate.unwrap_or(44100);
        let channels = codec_params.channels.map(|c| c.count() as u16).unwrap_or(2);

        let decoder_opts = DecoderOptions::default();
        let decoder = symphonia::default::get_codecs()
            .make(&codec_params, &decoder_opts)
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
        let file = File::open(path)?;
        let mss = MediaSourceStream::new(Box::new(file), Default::default());

        let mut hint = Hint::new();
        if let Some(ext) = path.extension().and_then(|e| e.to_str()) {
            hint.with_extension(ext);
        }

        let probed = symphonia::default::get_probe().format(
            &hint,
            mss,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )?;

        let mut format = probed.format;
        let track = format
            .tracks()
            .iter()
            .find(|t| t.id == self.track_id)
            .ok_or(AudioDecodeError::NoAudioTrack)?;

        let track_id = track.id;
        let codec_params = track.codec_params.clone();

        let mut decoder_opts = DecoderOptions::default();
        let mut decoder = symphonia::default::get_codecs()
            .make(&codec_params, &decoder_opts)
            .map_err(|e| AudioDecodeError::UnsupportedCodec(e.to_string()))?;

        let mut output_buffer: Option<SampleBuffer<i16>> = None;
        let mut all_samples: Vec<i16> = Vec::new();

        loop {
            let packet = match format.next_packet() {
                Ok(p) => p,
                Err(symphonia::core::errors::Error::IoError(ref e))
                    if e.kind() == std::io::ErrorKind::UnexpectedEof =>
                {
                    break;
                }
                Err(e) => return Err(e.into()),
            };

            if packet.track_id() != track_id {
                continue;
            }

            let decoded = match decoder.decode(&packet) {
                Ok(d) => d,
                Err(_) => continue,
            };

            let spec = *decoded.spec();
            if output_buffer.is_none() {
                output_buffer = Some(SampleBuffer::new(decoded.capacity() as u64, spec));
            }

            if let Some(ref mut buf) = output_buffer {
                buf.copy_interleaved_ref(decoded);
                all_samples.extend_from_slice(buf.samples());
            }
        }

        Ok(all_samples)
    }
}
