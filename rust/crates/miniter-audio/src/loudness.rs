use std::path::Path;

use symphonia::core::codecs::audio::AudioDecoderOptions;

use crate::util;

#[derive(Debug, Clone)]
pub struct LoudnessProfile {
    pub chunk_duration_us: i64,
    pub rms_db: Vec<f32>,
}

/// Stable error codes for user-facing diagnostics and telemetry (ffmpeg-like).
/// Numbering:
/// - `LC-101` encode failure
/// - `LC-102` font load failure
/// - `LC-201` source file unreadable/missing
/// - `LC-202` gallery/output write failure
/// - `LC-301` out of memory during export
/// - `LC-500` unexpected crash
/// - `MT-401` no decodable stream (audio/video/subtitle)
/// - `MT-402` unsupported codec or container
/// - `MT-403` corrupt/truncated media
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ErrorCode {
    EncodeFailed,
    FontLoadFailed,
    SourceUnreadable,
    OutputWriteFailed,
    OutOfMemory,
    UnexpectedCrash,
    NoDecodableStream,
    UnsupportedCodec,
    CorruptMedia,
}

impl ErrorCode {
    pub fn code(self) -> &'static str {
        match self {
            Self::EncodeFailed => "LC-101",
            Self::FontLoadFailed => "LC-102",
            Self::SourceUnreadable => "LC-201",
            Self::OutputWriteFailed => "LC-202",
            Self::OutOfMemory => "LC-301",
            Self::UnexpectedCrash => "LC-500",
            Self::NoDecodableStream => "MT-401",
            Self::UnsupportedCodec => "MT-402",
            Self::CorruptMedia => "MT-403",
        }
    }

    pub fn description(self) -> &'static str {
        match self {
            Self::EncodeFailed => "Encoding failed",
            Self::FontLoadFailed => "Font could not be loaded",
            Self::SourceUnreadable => "Source file is missing or unreadable",
            Self::OutputWriteFailed => "Output could not be written",
            Self::OutOfMemory => "Out of memory during export",
            Self::UnexpectedCrash => "Unexpected error",
            Self::NoDecodableStream => "No decodable audio/video stream",
            Self::UnsupportedCodec => "Unsupported codec or container",
            Self::CorruptMedia => "Media file is corrupt or truncated",
        }
    }
}

impl std::fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code(), self.description())
    }
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
        .max(1);

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

const ABSOLUTE_GATE_LUFS: f64 = -70.0;
const MIN_CEILING_DB: f32 = -24.0;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ExportMastering {
    pub measured_lufs: Option<f32>,
    pub loudness_gain_db: f32,
    pub ceiling_gain_db: f32,
}

#[derive(Debug, Clone, Copy, Default)]
struct BiquadState {
    x1: f64,
    x2: f64,
    y1: f64,
    y2: f64,
}

fn k_weighting_filters(sample_rate: u32) -> ([f64; 5], [f64; 5]) {
    let rate = sample_rate.max(1) as f64;

    let f0 = 1681.974450955533;
    let gain_db = 3.999843853973347;
    let q = 0.7071752369554196;
    let k = (std::f64::consts::PI * f0 / rate).tan();
    let vh = 10f64.powf(gain_db / 20.0);
    let vb = vh.powf(0.4996667741545416);
    let a0 = 1.0 + k / q + k * k;
    let shelf = [
        (vh + vb * k / q + k * k) / a0,
        2.0 * (k * k - vh) / a0,
        (vh - vb * k / q + k * k) / a0,
        2.0 * (k * k - 1.0) / a0,
        (1.0 - k / q + k * k) / a0,
    ];

    let f0 = 38.13547087602444;
    let q = 0.5003270373238773;
    let k = (std::f64::consts::PI * f0 / rate).tan();
    let a0 = 1.0 + k / q + k * k;
    let high_pass = [
        1.0,
        -2.0,
        1.0,
        2.0 * (k * k - 1.0) / a0,
        (1.0 - k / q + k * k) / a0,
    ];

    (shelf, high_pass)
}

fn biquad_process(state: &mut BiquadState, coeffs: &[f64; 5], x: f64) -> f64 {
    let y = coeffs[0] * x + coeffs[1] * state.x1 + coeffs[2] * state.x2
        - coeffs[3] * state.y1
        - coeffs[4] * state.y2;
    state.x2 = state.x1;
    state.x1 = x;
    state.y2 = state.y1;
    state.y1 = y;
    y
}

pub fn integrated_lufs(samples: &[f32], sample_rate: u32, channels: u16) -> Option<f32> {
    let channels = channels.max(1) as usize;
    if sample_rate == 0 || samples.len() < channels {
        return None;
    }

    let hop_frames = (sample_rate as f64 * 0.1).round().max(1.0) as usize;
    let frames = samples.len() / channels;
    if frames < hop_frames * 4 {
        return None;
    }

    let (shelf, high_pass) = k_weighting_filters(sample_rate);
    let mut shelf_state = vec![BiquadState::default(); channels];
    let mut high_pass_state = vec![BiquadState::default(); channels];
    let mut hop_powers: Vec<f64> = Vec::with_capacity(frames / hop_frames + 1);
    let mut hop_sum = 0.0f64;
    let mut hop_fill = 0usize;

    for frame in samples.chunks_exact(channels) {
        let mut frame_power = 0.0f64;
        for (channel, &sample) in frame.iter().enumerate() {
            let weighted = biquad_process(&mut shelf_state[channel], &shelf, sample as f64);
            let filtered = biquad_process(&mut high_pass_state[channel], &high_pass, weighted);
            frame_power += filtered * filtered;
        }
        hop_sum += frame_power;
        hop_fill += 1;
        if hop_fill == hop_frames {
            hop_powers.push(hop_sum);
            hop_sum = 0.0;
            hop_fill = 0;
        }
    }

    if hop_powers.len() < 4 {
        return None;
    }

    let block_frames = (hop_frames * 4) as f64;
    let blocks: Vec<(f64, f64)> = hop_powers
        .windows(4)
        .map(|window| {
            let power = (window[0] + window[1] + window[2] + window[3]) / block_frames;
            let loudness = if power > 0.0 {
                -0.691 + 10.0 * power.log10()
            } else {
                f64::NEG_INFINITY
            };
            (power, loudness)
        })
        .collect();

    let mut absolute_sum = 0.0f64;
    let mut absolute_count = 0usize;
    for &(power, loudness) in &blocks {
        if loudness > ABSOLUTE_GATE_LUFS {
            absolute_sum += power;
            absolute_count += 1;
        }
    }
    if absolute_count == 0 {
        return None;
    }

    let absolute_mean = absolute_sum / absolute_count as f64;
    let relative_gate = (-0.691 + 10.0 * absolute_mean.log10() - 10.0).max(ABSOLUTE_GATE_LUFS);

    let mut gated_sum = 0.0f64;
    let mut gated_count = 0usize;
    for &(power, loudness) in &blocks {
        if loudness > relative_gate {
            gated_sum += power;
            gated_count += 1;
        }
    }
    if gated_count == 0 {
        return None;
    }

    let gated_mean = gated_sum / gated_count as f64;
    if !(gated_mean > 0.0) {
        return None;
    }

    Some((-0.691 + 10.0 * gated_mean.log10()) as f32)
}

fn apply_ceiling(samples: &mut [f32], ceiling_db: f32) -> f32 {
    let ceiling_db = ceiling_db.clamp(MIN_CEILING_DB, 0.0);
    let ceiling = 10f32.powf(ceiling_db / 20.0);
    let peak = samples.iter().fold(0.0f32, |peak, &s| peak.max(s.abs()));
    if peak <= ceiling || !(peak > 0.0) {
        return 0.0;
    }

    let gain = ceiling / peak;
    for sample in samples.iter_mut() {
        *sample *= gain;
    }
    20.0 * gain.log10()
}

pub fn apply_export_mastering(
    samples: &mut [f32],
    sample_rate: u32,
    channels: u16,
    target_lufs: f32,
    ceiling_db: f32,
) -> ExportMastering {
    let measured_lufs = integrated_lufs(samples, sample_rate, channels);
    let loudness_gain_db = match measured_lufs {
        Some(lufs) if lufs.is_finite() && target_lufs.is_finite() => {
            let gain_db = target_lufs - lufs;
            let gain = 10f32.powf(gain_db / 20.0);
            for sample in samples.iter_mut() {
                *sample *= gain;
            }
            gain_db
        }
        _ => 0.0,
    };
    let ceiling_gain_db = apply_ceiling(samples, ceiling_db);

    ExportMastering {
        measured_lufs,
        loudness_gain_db,
        ceiling_gain_db,
    }
}
