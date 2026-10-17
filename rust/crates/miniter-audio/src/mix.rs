use std::collections::HashMap;
use std::path::Path;

use miniter_domain::Project;
use miniter_domain::clip::{ClipKind, speed_ramp_source_offset};
use miniter_domain::filter::AudioFilter;
use miniter_domain::keyframe::{KeyframeCurve, param};
use miniter_domain::time::MediaDuration;

use crate::decode::{DecodeAudioError, DecodedAudio, decode_audio_f32, decode_audio_f32_bytes};
use crate::dsp;
use crate::util;

#[derive(Debug, Clone, Copy)]
pub struct MixConfig {
    pub sample_rate: u32,
    pub channels: u16,
}

impl Default for MixConfig {
    fn default() -> Self {
        Self {
            sample_rate: 48_000,
            channels: 2,
        }
    }
}

#[derive(Debug, Clone)]
pub struct MixedAudio {
    pub sample_rate: u32,
    pub channels: u16,
    pub samples: Vec<f32>,
}

#[derive(Debug, thiserror::Error)]
pub enum AudioMixError {
    #[error("Invalid output sample rate")]
    InvalidSampleRate,
    #[error("Invalid output channel count")]
    InvalidChannelCount,
    #[error("Audio decode error: {0}")]
    Decode(#[from] DecodeAudioError),
}

#[derive(Debug, Clone)]
struct ScheduledAudioClip {
    source_path: String,
    timeline_start_frame: usize,
    timeline_len_frames: usize,
    source_start_frame: usize,
    source_end_frame: Option<usize>,
    speed: f64,
    speed_keyframes: KeyframeCurve,
    gain: f32,
    /// Source channel to play, from a `Channel` filter; None plays all.
    source_channel: Option<usize>,
    audio_filters: Vec<AudioFilter>,
}

pub fn mix_project_audio(
    project: &Project,
    config: MixConfig,
) -> Result<MixedAudio, AudioMixError> {
    mix_project_audio_internal(project, config, None)
}

pub fn mix_project_audio_with_source_map(
    project: &Project,
    config: MixConfig,
    source_map: &HashMap<String, Vec<u8>>,
) -> Result<MixedAudio, AudioMixError> {
    mix_project_audio_internal(project, config, Some(source_map))
}

fn mix_project_audio_internal(
    project: &Project,
    config: MixConfig,
    source_map: Option<&HashMap<String, Vec<u8>>>,
) -> Result<MixedAudio, AudioMixError> {
    if config.sample_rate == 0 {
        return Err(AudioMixError::InvalidSampleRate);
    }
    if config.channels == 0 {
        return Err(AudioMixError::InvalidChannelCount);
    }

    let scheduled = collect_scheduled_audio(project, config);
    if scheduled.is_empty() {
        return Ok(MixedAudio {
            sample_rate: config.sample_rate,
            channels: config.channels,
            samples: Vec::new(),
        });
    }

    // Ducking reads its gain from whatever is already in the mix, so the clips
    // carrying it have to be mixed last. Mixing is otherwise a plain sum, so
    // reordering cannot change the result.
    let mut scheduled = scheduled;
    scheduled.sort_by_key(|clip| {
        clip.audio_filters
            .iter()
            .any(|f| matches!(f, AudioFilter::Ducking { .. }))
    });

    let has_normalize = scheduled.iter().any(|clip| {
        clip.audio_filters
            .iter()
            .any(|f| matches!(f, AudioFilter::Normalize))
    });

    let out_channels = config.channels as usize;
    let total_frames = scheduled
        .iter()
        .map(|clip| clip.timeline_start_frame + clip.timeline_len_frames)
        .max()
        .unwrap_or(0);

    let mut mixed = vec![0.0f32; total_frames * out_channels];
    let mut cache: HashMap<String, Option<DecodedAudio>> = HashMap::new();
    let mut mixed_any = false;

    for clip in scheduled {
        if !cache.contains_key(&clip.source_path) {
            let cached = match decode_source_audio(&clip.source_path, source_map) {
                Ok(decoded) => Some(adapt_audio(decoded, config)),
                Err(DecodeAudioError::NoAudioTrack) => None,
                Err(err) => return Err(AudioMixError::Decode(err)),
            };
            cache.insert(clip.source_path.clone(), cached);
        }

        let asset = match cache
            .get(&clip.source_path)
            .and_then(|asset| asset.as_ref())
        {
            Some(asset) => asset,
            None => continue,
        };

        let asset_frames = asset.frames();
        if asset_frames == 0 {
            continue;
        }

        let source_end_frame = clip
            .source_end_frame
            .unwrap_or(asset_frames)
            .min(asset_frames);

        if clip.source_start_frame >= source_end_frame {
            continue;
        }

        let dsp_samples = if dsp::has_clip_dsp(&clip.audio_filters) {
            let mut processed = asset.samples.clone();
            let start = clip
                .source_start_frame
                .saturating_mul(out_channels)
                .min(processed.len());
            let end = source_end_frame
                .saturating_mul(out_channels)
                .min(processed.len());
            if start < end {
                dsp::apply_clip_dsp(
                    &mut processed[start..end],
                    out_channels,
                    config.sample_rate,
                    &clip.audio_filters,
                );
            }
            Some(processed)
        } else {
            None
        };
        let asset_samples: &[f32] = dsp_samples.as_deref().unwrap_or(&asset.samples);

        let is_reversed = clip
            .audio_filters
            .iter()
            .any(|f| matches!(f, AudioFilter::Reverse));

        let ducking = clip.audio_filters.iter().find_map(|f| match f {
            AudioFilter::Ducking { amount, release_ms } => Some((*amount, *release_ms)),
            _ => None,
        });
        let release_frames = ducking
            .map(|(_, release_ms)| {
                micros_to_frames(release_ms.max(0) * 1_000, config.sample_rate).max(1)
            })
            .unwrap_or(1);
        let mut duck_envelope = 0.0f32;

        for dst_offset in 0..clip.timeline_len_frames {
            let dst_frame = clip.timeline_start_frame + dst_offset;
            // Speed 0 holds the source start frame for the whole clip (freeze).
            let is_hold = clip.speed == 0.0;
            let offset_frames = clip.source_offset_frames(dst_offset, config.sample_rate);
            let src_pos = if is_hold {
                (clip.source_start_frame as f64).clamp(0.0, asset_frames.saturating_sub(1) as f64)
            } else if is_reversed {
                source_end_frame as f64 - 1.0 - offset_frames
            } else {
                clip.source_start_frame as f64 + offset_frames
            };

            if !is_hold
                && (src_pos >= source_end_frame as f64 || src_pos < clip.source_start_frame as f64)
            {
                break;
            }

            let fade_gain = calculate_fade_gain(
                dst_offset,
                clip.timeline_len_frames,
                config.sample_rate,
                &clip.audio_filters,
            );
            let duck_gain = match ducking {
                Some((amount, _)) => {
                    let level = frame_peak(&mixed, dst_frame, out_channels);
                    let coeff = if level > duck_envelope {
                        DUCK_ATTACK_COEFF
                    } else {
                        (1.0 / release_frames as f32).min(1.0)
                    };
                    duck_envelope += (level - duck_envelope) * coeff;
                    (1.0 - amount.clamp(0.0, 1.0) * duck_envelope.clamp(0.0, 1.0)).clamp(0.0, 1.0)
                }
                None => 1.0,
            };
            let effective_gain = clip.gain * fade_gain * duck_gain;
            let (pan_left, pan_right) = pan_gains(&clip.audio_filters, out_channels);

            if effective_gain > 0.0 {
                mixed_any = true;
                mix_interpolated_frame(
                    &mut mixed,
                    dst_frame,
                    asset_samples,
                    asset_frames,
                    out_channels,
                    src_pos,
                    effective_gain,
                    pan_left,
                    pan_right,
                    clip.source_channel,
                );
            }
        }
    }

    if !mixed_any {
        return Ok(MixedAudio {
            sample_rate: config.sample_rate,
            channels: config.channels,
            samples: Vec::new(),
        });
    }

    for sample in &mut mixed {
        *sample = sample.clamp(-1.0, 1.0);
    }

    if has_normalize {
        apply_normalize(&mut mixed);
    }

    Ok(MixedAudio {
        sample_rate: config.sample_rate,
        channels: config.channels,
        samples: mixed,
    })
}

fn decode_source_audio(
    source_path: &str,
    source_map: Option<&HashMap<String, Vec<u8>>>,
) -> Result<DecodedAudio, DecodeAudioError> {
    if let Some(map) = source_map
        && let Some(bytes) = map.get(source_path)
    {
        return decode_audio_f32_bytes(bytes, extension_hint_from_path(source_path));
    }

    decode_audio_f32(Path::new(source_path))
}

fn extension_hint_from_path(path: &str) -> Option<&str> {
    Path::new(path).extension().and_then(|ext| ext.to_str())
}

fn collect_scheduled_audio(project: &Project, config: MixConfig) -> Vec<ScheduledAudioClip> {
    let mut scheduled = Vec::new();

    for track in &project.timeline.tracks {
        if track.muted {
            continue;
        }

        for clip in &track.clips {
            if clip.muted {
                continue;
            }

            if clip.volume.abs() <= f32::EPSILON {
                continue;
            }

            if clip.speed < 0.0 || !clip.speed.is_finite() {
                continue;
            }

            let (source_path, audio_filters) = match &clip.kind {
                ClipKind::Video(video) => (video.source_path.clone(), video.audio_filters.clone()),
                ClipKind::Audio(audio) => (audio.source_path.clone(), audio.filters.clone()),
                ClipKind::Text(_) => continue,
                ClipKind::Subtitle(_) => continue,
                _ => continue,
            };

            let timeline_len_us = clip.timeline_duration.as_micros();
            if timeline_len_us <= 0 {
                continue;
            }

            let source_start_us = clip.source_start.as_micros();
            let source_end_us = clip.source_end.as_micros();

            let source_end_frame = if source_end_us > source_start_us {
                Some(micros_to_frames(source_end_us, config.sample_rate))
            } else {
                None
            };

            scheduled.push(ScheduledAudioClip {
                source_path,
                timeline_start_frame: micros_to_frames(
                    clip.timeline_start.as_micros(),
                    config.sample_rate,
                ),
                timeline_len_frames: micros_to_frames(timeline_len_us, config.sample_rate),
                source_start_frame: micros_to_frames(source_start_us, config.sample_rate),
                source_end_frame,
                speed: clip.speed,
                speed_keyframes: clip.keyframes.clone(),
                gain: clip.volume,
                source_channel: audio_filters
                    .iter()
                    .filter_map(|f| match f {
                        AudioFilter::Channel { index } => Some(*index as usize),
                        _ => None,
                    })
                    .last(),
                audio_filters,
            });
        }
    }

    scheduled
}

const DUCK_ATTACK_COEFF: f32 = 0.5;

/// Peak magnitude of one interleaved frame, used as the ducking sidechain.
fn frame_peak(mixed: &[f32], frame: usize, channels: usize) -> f32 {
    let start = (frame * channels).min(mixed.len());
    let end = (start + channels).min(mixed.len());
    mixed[start..end]
        .iter()
        .fold(0.0f32, |peak, sample| peak.max(sample.abs()))
}

fn calculate_fade_gain(
    current_frame: usize,
    total_frames: usize,
    sample_rate: u32,
    filters: &[AudioFilter],
) -> f32 {
    let mut fade_in_gain = 1.0f32;
    let mut fade_out_gain = 1.0f32;

    for filter in filters {
        match filter {
            AudioFilter::FadeIn { duration_us } => {
                let fade_frames = micros_to_frames(*duration_us, sample_rate);
                if current_frame < fade_frames {
                    fade_in_gain *= current_frame as f32 / fade_frames as f32;
                }
            }
            AudioFilter::FadeOut { duration_us } => {
                let fade_frames = micros_to_frames(*duration_us, sample_rate);
                let remaining = total_frames.saturating_sub(current_frame);
                if remaining < fade_frames {
                    fade_out_gain *= remaining as f32 / fade_frames as f32;
                }
            }
            _ => {}
        }
    }

    fade_in_gain * fade_out_gain
}

fn apply_normalize(samples: &mut [f32]) {
    let peak = samples.iter().fold(0.0f32, |max, &s| max.max(s.abs()));
    if peak > 0.001 {
        let target_peak = 0.99f32;
        let gain = target_peak / peak;
        for sample in samples.iter_mut() {
            *sample = (*sample * gain).clamp(-1.0, 1.0);
        }
    }
}

fn adapt_audio(mut audio: DecodedAudio, config: MixConfig) -> DecodedAudio {
    let out_channels = config.channels.max(1);

    if audio.channels != out_channels {
        audio.samples =
            convert_channels_interleaved(&audio.samples, audio.channels.max(1), out_channels);
        audio.channels = out_channels;
    }

    if audio.sample_rate != config.sample_rate {
        audio.samples = util::resample_linear_interleaved(
            &audio.samples,
            audio.sample_rate,
            config.sample_rate,
            audio.channels.max(1),
        );
        audio.sample_rate = config.sample_rate;
    }

    audio
}

fn micros_to_frames(micros: i64, sample_rate: u32) -> usize {
    if micros <= 0 {
        return 0;
    }

    (((micros as i128) * (sample_rate as i128) + 500_000) / 1_000_000) as usize
}

fn frames_to_micros(frames: usize, sample_rate: u32) -> i64 {
    if sample_rate == 0 {
        return 0;
    }

    ((frames as i128 * 1_000_000 + (sample_rate as i128) / 2) / sample_rate as i128) as i64
}

impl ScheduledAudioClip {
    /// Source frames consumed after `dst_offset` output frames. A keyframed
    /// speed curve is integrated, otherwise the constant `speed` scales
    /// directly so unramped clips keep the exact frame-domain arithmetic.
    fn source_offset_frames(&self, dst_offset: usize, sample_rate: u32) -> f64 {
        if !self.speed_keyframes.has_param(param::SPEED) {
            return dst_offset as f64 * self.speed;
        }

        let local_us = frames_to_micros(dst_offset, sample_rate);
        let offset_us = speed_ramp_source_offset(
            &self.speed_keyframes,
            self.speed,
            MediaDuration::from_micros(local_us),
        );
        micros_to_frames(offset_us.max(0), sample_rate) as f64
    }
}

fn convert_channels_interleaved(input: &[f32], in_channels: u16, out_channels: u16) -> Vec<f32> {
    let in_channels = in_channels.max(1) as usize;
    let out_channels = out_channels.max(1) as usize;

    if in_channels == out_channels {
        return input.to_vec();
    }

    let frames = input.len() / in_channels;
    let mut out = Vec::with_capacity(frames * out_channels);

    for frame in input.chunks(in_channels) {
        match (in_channels, out_channels) {
            (1, 2) => {
                let s = frame[0];
                out.push(s);
                out.push(s);
            }
            (2, 1) => {
                out.push((frame[0] + frame[1]) * 0.5);
            }
            (_, 1) => {
                let sum = frame.iter().copied().sum::<f32>();
                out.push(sum / in_channels as f32);
            }
            (1, _) => {
                let s = frame[0];
                for _ in 0..out_channels {
                    out.push(s);
                }
            }
            (_, 2) => {
                out.push(frame[0]);
                out.push(frame[1.min(in_channels - 1)]);
            }
            _ => {
                for ch in 0..out_channels {
                    out.push(frame[ch.min(in_channels - 1)]);
                }
            }
        }
    }

    out
}

fn mix_interpolated_frame(
    dst: &mut [f32],
    dst_frame: usize,
    src: &[f32],
    src_frames: usize,
    channels: usize,
    src_pos: f64,
    gain: f32,
    pan_left: f32,
    pan_right: f32,
    source_channel: Option<usize>,
) {
    if src_frames == 0 {
        return;
    }

    let src_frame_0 = src_pos.floor() as usize;
    let src_frame_1 = (src_frame_0 + 1).min(src_frames.saturating_sub(1));
    let frac = (src_pos - src_frame_0 as f64) as f32;

    let dst_base = dst_frame * channels;
    let src_base_0 = src_frame_0 * channels;
    let src_base_1 = src_frame_1 * channels;

    for ch in 0..channels {
        // A selected source channel plays as mono on every output; an
        // out-of-range selection is silence.
        let sch = source_channel.unwrap_or(ch);
        let (a, b) = if sch < channels {
            (src[src_base_0 + sch], src[src_base_1 + sch])
        } else {
            (0.0, 0.0)
        };
        let pan = match ch {
            0 => pan_left,
            1 => pan_right,
            _ => 1.0,
        };
        dst[dst_base + ch] += (a + (b - a) * frac) * gain * pan;
    }
}

/// Constant-power stereo gains for the last `Pan` filter, or unity when the
/// clip carries none. Extra channels pass through unchanged.
fn pan_gains(filters: &[AudioFilter], out_channels: usize) -> (f32, f32) {
    if out_channels < 2 {
        return (1.0, 1.0);
    }
    let Some(value) = filters
        .iter()
        .filter_map(|f| match f {
            AudioFilter::Pan { value } => Some(*value),
            _ => None,
        })
        .last()
    else {
        return (1.0, 1.0);
    };
    let pan = value.clamp(-1.0, 1.0);
    let angle = (pan + 1.0) * std::f32::consts::FRAC_PI_4;
    (angle.cos(), angle.sin())
}

#[cfg(test)]
mod tests {
    use super::*;
    use miniter_domain::clip::{AudioClip, Clip, ClipId};
    use miniter_domain::time::{MediaDuration, Timestamp};
    use miniter_domain::track::{Track, TrackKind};

    fn write_tone(path: &std::path::Path, freq: f32, seconds: u32) {
        let sample_rate = 48_000u32;
        let frames = (sample_rate * seconds) as usize;
        let mut bytes = Vec::with_capacity(frames * 2);
        for i in 0..frames {
            let value = (6000.0
                * (2.0 * std::f64::consts::PI as f32 * freq * i as f32 / sample_rate as f32).sin())
                as i16;
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        let mut wav = Vec::new();
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&((36 + bytes.len()) as u32).to_le_bytes());
        wav.extend_from_slice(b"WAVEfmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&sample_rate.to_le_bytes());
        wav.extend_from_slice(&(sample_rate * 2).to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
        wav.extend_from_slice(&bytes);
        std::fs::write(path, wav).unwrap();
    }

    fn audio_clip(path: &str, start_us: i64, duration_us: i64) -> Clip {
        Clip {
            id: ClipId::new(),
            timeline_start: Timestamp::from_micros(start_us),
            timeline_duration: MediaDuration::from_micros(duration_us),
            source_start: MediaDuration::ZERO,
            source_end: MediaDuration::from_micros(duration_us),
            source_total_duration: MediaDuration::from_micros(duration_us),
            speed: 1.0,
            volume: 1.0,
            opacity: 1.0,
            muted: false,
            transition_in: None,
            transition_out: None,
            kind: ClipKind::Audio(AudioClip {
                source_path: path.to_string(),
                sample_rate: 48_000,
                channels: 1,
                filters: Vec::new(),
            }),
            keyframes: Default::default(),
            blend_mode: Default::default(),
        }
    }

    fn project_with(clips: Vec<Clip>) -> Project {
        let mut project = Project::new("test");
        let mut track = Track::new(TrackKind::Audio, "A1");
        for clip in clips {
            track.insert_clip(clip).unwrap();
        }
        project.timeline.tracks.push(track);
        project
    }

    fn channel_energy(samples: &[f32], channels: usize, channel: usize) -> f64 {
        samples
            .iter()
            .skip(channel)
            .step_by(channels)
            .map(|s| (*s as f64) * (*s as f64))
            .sum()
    }

    #[test]
    fn pan_full_left_silences_the_right_channel() {
        let dir = std::env::temp_dir().join("miniter-pan-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("tone-left.wav");
        write_tone(&path, 440.0, 2);
        let mut clip = audio_clip(&path.display().to_string(), 0, 2_000_000);
        if let ClipKind::Audio(audio) = &mut clip.kind {
            audio.filters.push(AudioFilter::Pan { value: -1.0 });
        }
        let mixed = mix_project_audio(&project_with(vec![clip]), MixConfig::default()).unwrap();
        assert_eq!(mixed.channels, 2);
        let left = channel_energy(&mixed.samples, 2, 0);
        let right = channel_energy(&mixed.samples, 2, 1);
        assert!(left > 0.0, "the left channel carries the tone");
        assert!(
            right / left.max(1.0) < 1e-9,
            "full left pan silences the right channel"
        );
    }

    #[test]
    fn pan_full_right_silences_the_left_channel() {
        let dir = std::env::temp_dir().join("miniter-pan-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("tone-right.wav");
        write_tone(&path, 440.0, 2);
        let mut clip = audio_clip(&path.display().to_string(), 0, 2_000_000);
        if let ClipKind::Audio(audio) = &mut clip.kind {
            audio.filters.push(AudioFilter::Pan { value: 1.0 });
        }
        let mixed = mix_project_audio(&project_with(vec![clip]), MixConfig::default()).unwrap();
        let left = channel_energy(&mixed.samples, 2, 0);
        let right = channel_energy(&mixed.samples, 2, 1);
        assert!(
            left / right.max(1.0) < 1e-9,
            "full right pan silences the left channel"
        );
        assert!(right > 0.0, "the right channel carries the tone");
    }

    #[test]
    fn pan_centre_keeps_both_channels_equal() {
        let dir = std::env::temp_dir().join("miniter-pan-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("tone-centre.wav");
        write_tone(&path, 440.0, 2);
        let clip = audio_clip(&path.display().to_string(), 0, 2_000_000);
        let mixed = mix_project_audio(&project_with(vec![clip]), MixConfig::default()).unwrap();
        let left = channel_energy(&mixed.samples, 2, 0);
        let right = channel_energy(&mixed.samples, 2, 1);
        assert!(left > 0.0 && right > 0.0);
        assert!(
            (left - right).abs() / left.max(1.0) < 0.01,
            "a centred mono source splits equally: {left} vs {right}"
        );
    }

    #[test]
    fn freeze_holds_one_frame_for_the_whole_clip() {
        let dir = std::env::temp_dir().join("miniter-pan-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("tone-freeze.wav");
        write_tone(&path, 440.0, 4);
        let mut clip = audio_clip(&path.display().to_string(), 0, 2_000_000);
        clip.speed = 0.0;
        clip.source_start = MediaDuration::from_micros(1_000_000);
        clip.source_end = MediaDuration::from_micros(1_000_000);
        let mixed = mix_project_audio(&project_with(vec![clip]), MixConfig::default()).unwrap();
        assert_eq!(mixed.samples.len(), 2_000_000 / 1_000_000 * 48_000 * 2);
        let first = mixed.samples[0];
        assert!(
            mixed
                .samples
                .iter()
                .step_by(4800)
                .all(|s| (s - first).abs() < 1e-4),
            "every frame repeats the hold point"
        );
    }

    #[test]
    fn negative_speed_stays_silent() {
        let dir = std::env::temp_dir().join("miniter-pan-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("tone-negative.wav");
        write_tone(&path, 440.0, 2);
        let mut clip = audio_clip(&path.display().to_string(), 0, 2_000_000);
        clip.speed = -1.0;
        let mixed = mix_project_audio(&project_with(vec![clip]), MixConfig::default()).unwrap();
        assert!(mixed.samples.is_empty(), "a negative speed mixes nothing");
    }
}
