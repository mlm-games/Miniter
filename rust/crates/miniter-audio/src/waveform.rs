use std::path::Path;

use symphonia::core::codecs::audio::AudioDecoderOptions;
use symphonia::core::io::MediaSourceStream;

use crate::util;

#[derive(Debug, Clone)]
pub struct WaveformData {
    pub sample_rate: u32,
    pub channels: u16,
    pub samples_per_bucket: u64,
    pub peaks: Vec<(f32, f32)>,
}

#[derive(Debug, thiserror::Error)]
pub enum WaveformError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("No audio track found")]
    NoAudioTrack,
    #[error("Invalid bucket count: must be > 0")]
    InvalidBucketCount,
    #[error("Symphonia error: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
}

pub fn extract_waveform(path: &Path, target_buckets: usize) -> Result<WaveformData, WaveformError> {
    let (mss, ext) = util::open_mss_from_path(path)?;
    extract_waveform_stream(mss, target_buckets, ext.as_deref())
}

pub fn extract_waveform_bytes(
    bytes: &[u8],
    target_buckets: usize,
    extension_hint: Option<&str>,
) -> Result<WaveformData, WaveformError> {
    let (mss, ext) = util::open_mss_from_bytes(bytes, extension_hint);
    extract_waveform_stream(mss, target_buckets, ext.as_deref())
}

fn extract_waveform_stream(
    mss: MediaSourceStream,
    target_buckets: usize,
    extension_hint: Option<&str>,
) -> Result<WaveformData, WaveformError> {
    if target_buckets == 0 {
        return Err(WaveformError::InvalidBucketCount);
    }

    let mut reader = util::probe(mss, extension_hint)?;

    let track = reader
        .tracks()
        .iter()
        .find(|t| t.codec_params.as_ref().and_then(|p| p.audio()).is_some())
        .ok_or(WaveformError::NoAudioTrack)?;

    let track_id = track.id;
    let audio_params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .cloned()
        .ok_or(WaveformError::NoAudioTrack)?;
    let sample_rate = audio_params.sample_rate.unwrap_or(44_100);
    let channels = audio_params
        .channels
        .as_ref()
        .map(|c| c.count() as u16)
        .unwrap_or(1);

    let channels_usize = channels.max(1) as usize;
    let total_samples = track.num_frames.unwrap_or(sample_rate as u64 * 300);
    let samples_per_bucket = (total_samples / target_buckets as u64).max(1);

    let mut decoder = crate::codecs::get_codecs()
        .make_audio_decoder(&audio_params, &AudioDecoderOptions::default())?;

    let mut peaks: Vec<(f32, f32)> = Vec::with_capacity(target_buckets);
    let mut bucket_min: f32 = 0.0;
    let mut bucket_max: f32 = 0.0;
    let mut bucket_has_value = false;
    let mut bucket_count: u64 = 0;

    loop {
        let packet = match reader.next_packet() {
            Ok(Some(p)) => p,
            Ok(None) => break,
            Err(e) => return Err(e.into()),
        };

        if packet.track_id != track_id {
            continue;
        }

        let decoded = decoder.decode(&packet)?;

        let mut interleaved: Vec<f32> = Vec::new();
        decoded.copy_to_vec_interleaved(&mut interleaved);

        for frame in interleaved.chunks(channels_usize) {
            let mut mono = 0.0f32;
            let mut n = 0usize;
            for &sample in frame {
                mono += sample;
                n += 1;
            }
            if n == 0 {
                continue;
            }
            mono /= n as f32;

            if !bucket_has_value {
                bucket_min = mono;
                bucket_max = mono;
                bucket_has_value = true;
            } else {
                bucket_min = bucket_min.min(mono);
                bucket_max = bucket_max.max(mono);
            }

            bucket_count += 1;

            if bucket_count >= samples_per_bucket {
                peaks.push((bucket_min, bucket_max));
                bucket_has_value = false;
                bucket_count = 0;
            }
        }
    }

    if bucket_has_value {
        peaks.push((bucket_min, bucket_max));
    }

    Ok(WaveformData {
        sample_rate,
        channels,
        samples_per_bucket,
        peaks,
    })
}
