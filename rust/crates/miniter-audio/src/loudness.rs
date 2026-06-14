use std::path::Path;

use symphonia::core::codecs::audio::AudioDecoderOptions;

use crate::util;

#[derive(Debug, Clone)]
pub struct LoudnessProfile {
    pub chunk_duration_us: i64,
    pub rms_db: Vec<f32>,
}

#[derive(Debug, thiserror::Error)]
pub enum LoudnessError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("No audio track")]
    NoAudioTrack,
    #[error("Invalid chunk duration: must be > 0")]
    InvalidChunkDuration,
    #[error("Symphonia: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
}

pub fn scan_loudness(
    path: &Path,
    chunk_duration_us: i64,
) -> Result<LoudnessProfile, LoudnessError> {
    if chunk_duration_us <= 0 {
        return Err(LoudnessError::InvalidChunkDuration);
    }
    let (mss, ext) = util::open_mss_from_path(path)?;
    let mut reader = util::probe(mss, ext.as_deref())?;

    let track = reader
        .tracks()
        .iter()
        .find(|t| t.codec_params.as_ref().and_then(|p| p.audio()).is_some())
        .ok_or(LoudnessError::NoAudioTrack)?;

    let track_id = track.id;
    let audio_params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .cloned()
        .ok_or(LoudnessError::NoAudioTrack)?;
    let sample_rate = audio_params.sample_rate.unwrap_or(44_100);
    let channels = audio_params
        .channels
        .as_ref()
        .map(|c| c.count())
        .unwrap_or(1)
        .max(1) as usize;

    let samples_per_chunk = (sample_rate as f64 * chunk_duration_us as f64 / 1_000_000.0)
        .round()
        .max(1.0) as u64;

    let mut decoder = crate::codecs::get_codecs()
        .make_audio_decoder(&audio_params, &AudioDecoderOptions::default())?;

    let mut rms_values: Vec<f32> = Vec::new();
    let mut sum_sq: f64 = 0.0;
    let mut count_frames: u64 = 0;

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

        for frame in interleaved.chunks(channels) {
            let mut frame_sq = 0.0f64;
            let mut n = 0usize;
            for &s in frame {
                frame_sq += (s as f64) * (s as f64);
                n += 1;
            }
            if n == 0 {
                continue;
            }

            sum_sq += frame_sq / n as f64;
            count_frames += 1;

            if count_frames >= samples_per_chunk {
                let rms = (sum_sq / count_frames as f64).sqrt();
                let db = if rms > 0.0 {
                    20.0 * (rms as f32).log10()
                } else {
                    -96.0
                };
                rms_values.push(db);
                sum_sq = 0.0;
                count_frames = 0;
            }
        }
    }

    if count_frames > 0 {
        let rms = (sum_sq / count_frames as f64).sqrt();
        let db = if rms > 0.0 {
            20.0 * (rms as f32).log10()
        } else {
            -96.0
        };
        rms_values.push(db);
    }

    Ok(LoudnessProfile {
        chunk_duration_us,
        rms_db: rms_values,
    })
}
