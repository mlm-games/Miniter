//! Energy-based onset (beat) detection over decoded audio.
//!
//! Decodes via symphonia, downmixes to mono, then runs a 20 ms RMS window
//! against a ~1 s moving average. A window is an onset when it is a local
//! energy maximum exceeding `max(min_energy, avg * multiplier)`.
//!
//! Defaults: multiplier 1.4, 16-bit-scale floor of 1000 (normalized to f32
//! sample units here), 200 ms debounce.

use std::path::Path;

use symphonia::core::codecs::audio::AudioDecoderOptions;
use symphonia::core::io::MediaSourceStream;

use crate::util;

/// Detector configuration. [`BeatDetectorConfig::default`] holds the
/// standard values documented in the module header.
#[derive(Debug, Clone, Copy)]
pub struct BeatDetectorConfig {
    /// Analysis window length in milliseconds (20 ms ≈ 160 samples @ 8 kHz).
    pub window_ms: u32,
    /// Half-width of the moving-average context in windows (±50 ≈ 1 s).
    pub context_windows: usize,
    /// Half-width of the local-maximum check in windows (±3).
    pub local_max_windows: usize,
    /// Onset must exceed `moving_avg * multiplier`.
    pub threshold_multiplier: f32,
    /// Absolute energy floor, in mean-square sample units. Equivalent of
    /// `1000.0` on 16-bit PCM squares; f32 samples are ~32768x smaller, so
    /// the floor is `1000 / 32768^2`.
    pub min_energy: f32,
    /// Minimum gap between reported onsets, in milliseconds.
    pub debounce_ms: u32,
    /// Analysis sample rate. Lower rates are cheaper and sufficient for
    /// energy envelopes; resampling keeps window math sample-rate agnostic.
    pub analysis_sample_rate: u32,
}

impl Default for BeatDetectorConfig {
    fn default() -> Self {
        Self {
            window_ms: 20,
            context_windows: 50,
            local_max_windows: 3,
            threshold_multiplier: 1.4,
            min_energy: 1000.0 / 1_073_741_824.0,
            debounce_ms: 200,
            analysis_sample_rate: 8_000,
        }
    }
}

#[derive(Debug, Clone)]
pub struct BeatTrack {
    pub sample_rate: u32,
    /// Onset positions in milliseconds, ascending, debounced.
    pub onsets_ms: Vec<u64>,
    /// Per-window RMS energy (mean-square), for UI visualization.
    pub window_energy: Vec<f32>,
    pub window_ms: u32,
}

impl BeatTrack {
    pub fn window_count(&self) -> usize {
        self.window_energy.len()
    }

    /// Snap a timestamp to the nearest detected onset within `tolerance_ms`.
    pub fn snap(&self, time_ms: u64, tolerance_ms: u64) -> Option<u64> {
        self.onsets_ms
            .iter()
            .copied()
            .map(|onset| (onset, onset.abs_diff(time_ms)))
            .filter(|(_, dist)| *dist <= tolerance_ms)
            .min_by_key(|(_, dist)| *dist)
            .map(|(onset, _)| onset)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum BeatDetectError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("No audio track found")]
    NoAudioTrack,
    #[error("Symphonia error: {0}")]
    Symphonia(#[from] symphonia::core::errors::Error),
}

pub fn detect_beats(path: &Path) -> Result<BeatTrack, BeatDetectError> {
    detect_beats_with_config(path, BeatDetectorConfig::default())
}

pub fn detect_beats_with_config(
    path: &Path,
    config: BeatDetectorConfig,
) -> Result<BeatTrack, BeatDetectError> {
    let (mss, ext) = util::open_mss_from_path(path)?;
    detect_beats_stream(mss, ext.as_deref(), config)
}

pub fn detect_beats_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> Result<BeatTrack, BeatDetectError> {
    detect_beats_bytes_with_config(bytes, extension_hint, BeatDetectorConfig::default())
}

pub fn detect_beats_bytes_with_config(
    bytes: &[u8],
    extension_hint: Option<&str>,
    config: BeatDetectorConfig,
) -> Result<BeatTrack, BeatDetectError> {
    let (mss, ext) = util::open_mss_from_bytes(bytes, extension_hint);
    detect_beats_stream(mss, ext.as_deref(), config)
}

fn detect_beats_stream(
    mss: MediaSourceStream,
    extension_hint: Option<&str>,
    config: BeatDetectorConfig,
) -> Result<BeatTrack, BeatDetectError> {
    let mut reader = util::probe(mss, extension_hint)?;

    let track = reader
        .tracks()
        .iter()
        .find(|t| t.codec_params.as_ref().and_then(|p| p.audio()).is_some())
        .ok_or(BeatDetectError::NoAudioTrack)?;

    let track_id = track.id;
    let audio_params = track
        .codec_params
        .as_ref()
        .and_then(|p| p.audio())
        .cloned()
        .ok_or(BeatDetectError::NoAudioTrack)?;
    let src_rate = audio_params.sample_rate.unwrap_or(44_100);
    let analysis_rate = config.analysis_sample_rate.max(1_000);
    let channels = audio_params
        .channels
        .as_ref()
        .map(|c| c.count())
        .unwrap_or(1)
        .max(1);

    let mut decoder = crate::codecs::get_codecs()
        .make_audio_decoder(&audio_params, &AudioDecoderOptions::default())?;

    let window_ms = config.window_ms.max(1);
    let window_frames = (analysis_rate as u64 * window_ms as u64)
        .div_ceil(1000)
        .max(1) as usize;

    let mut mono = Vec::new();
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
            if frame.is_empty() {
                continue;
            }
            let sum: f32 = frame.iter().sum();
            mono.push(sum / frame.len() as f32);
        }
    }

    let mono = if src_rate != analysis_rate && !mono.is_empty() {
        util::resample_linear_interleaved(&mono, src_rate, analysis_rate, 1)
    } else {
        mono
    };

    let mut window_energy = Vec::new();
    for window in mono.chunks(window_frames) {
        if window.is_empty() {
            break;
        }
        let sum_sq: f64 = window.iter().map(|s| (*s as f64) * (*s as f64)).sum();
        window_energy.push((sum_sq / window.len() as f64) as f32);
    }

    let onsets_ms = pick_onsets(&window_energy, window_ms, &config);

    Ok(BeatTrack {
        sample_rate: analysis_rate,
        onsets_ms,
        window_energy,
        window_ms,
    })
}

/// Onset picker over per-window mean-square energies. Kept separate so
/// unit tests run without audio files.
pub fn pick_onsets(window_energy: &[f32], window_ms: u32, config: &BeatDetectorConfig) -> Vec<u64> {
    if window_energy.is_empty() || window_ms == 0 {
        return Vec::new();
    }
    let context = config.context_windows.max(1);
    let local = config.local_max_windows.max(1);
    let debounce_windows = (config.debounce_ms / window_ms.max(1)).max(1) as usize;

    let mut prefix = Vec::with_capacity(window_energy.len() + 1);
    prefix.push(0.0f64);
    for &e in window_energy {
        prefix.push(prefix[prefix.len() - 1] + e as f64);
    }
    let range_avg = |lo: usize, hi: usize| -> f64 {
        let hi = hi.min(window_energy.len());
        let lo = lo.min(hi);
        if hi <= lo {
            return 0.0;
        }
        (prefix[hi] - prefix[lo]) / (hi - lo) as f64
    };

    let mut onsets = Vec::new();
    let mut last_onset_idx: Option<usize> = None;

    for i in 0..window_energy.len() {
        let energy = window_energy[i];
        if energy < config.min_energy {
            continue;
        }
        let lo = i.saturating_sub(context);
        let hi = (i + context + 1).min(window_energy.len());
        let avg = range_avg(lo, hi);
        if energy as f64 <= avg * config.threshold_multiplier as f64 {
            continue;
        }
        let is_local_max = (i.saturating_sub(local)..=(i + local).min(window_energy.len() - 1))
            .all(|j| window_energy[j] <= energy);
        if !is_local_max {
            continue;
        }
        if let Some(last) = last_onset_idx
            && i - last < debounce_windows
        {
            continue;
        }
        last_onset_idx = Some(i);
        onsets.push(i as u64 * window_ms as u64);
    }

    onsets
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> BeatDetectorConfig {
        BeatDetectorConfig {
            min_energy: 0.0,
            ..BeatDetectorConfig::default()
        }
    }

    #[test]
    fn picks_isolated_peak_over_threshold() {
        let mut energy = vec![0.01f32; 120];
        energy[60] = 1.0;
        let onsets = pick_onsets(&energy, 20, &config());
        assert_eq!(onsets, vec![60 * 20]);
    }

    #[test]
    fn rejects_non_local_maxima() {
        let energy: Vec<f32> = (0..120).map(|i| 0.01 + i as f32 * 0.001).collect();
        let onsets = pick_onsets(&energy, 20, &config());
        assert!(
            onsets.len() <= 1,
            "ramp must not fire repeatedly: {onsets:?}"
        );
    }

    #[test]
    fn debounce_collapses_adjacent_peaks() {
        let mut energy = vec![0.01f32; 120];
        energy[60] = 1.0;
        energy[61] = 0.9;
        energy[62] = 1.0;
        let onsets = pick_onsets(&energy, 20, &config());
        assert_eq!(onsets.len(), 1, "debounce must collapse: {onsets:?}");
    }

    #[test]
    fn silence_yields_no_onsets() {
        let energy = vec![0.0f32; 200];
        assert!(pick_onsets(&energy, 20, &config()).is_empty());
    }

    #[test]
    fn snap_finds_nearest_onset_within_tolerance() {
        let track = BeatTrack {
            sample_rate: 8_000,
            onsets_ms: vec![1000, 2000, 3000],
            window_energy: Vec::new(),
            window_ms: 20,
        };
        assert_eq!(track.snap(1990, 50), Some(2000));
        assert_eq!(track.snap(1900, 50), None);
        assert_eq!(track.snap(500, 600), Some(1000));
    }

    #[test]
    fn min_energy_floor_suppresses_noise_floor() {
        let energy = vec![1e-9f32; 200];
        let mut cfg = config();
        cfg.min_energy = BeatDetectorConfig::default().min_energy;
        assert!(pick_onsets(&energy, 20, &cfg).is_empty());
    }
}
