use std::collections::HashMap;
use std::path::Path;

use miniter_domain::clip::ClipKind;
use miniter_domain::Project;

use crate::decode::{decode_audio_f32, DecodeAudioError, DecodedAudio};

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
    gain: f32,
}

pub fn mix_project_audio(
    project: &Project,
    config: MixConfig,
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
            let path = Path::new(&clip.source_path);
            let cached = match decode_audio_f32(path) {
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

        for dst_offset in 0..clip.timeline_len_frames {
            let dst_frame = clip.timeline_start_frame + dst_offset;
            let src_pos = clip.source_start_frame as f64 + (dst_offset as f64 * clip.speed);

            if src_pos >= source_end_frame as f64 {
                break;
            }

            mixed_any = true;
            mix_interpolated_frame(
                &mut mixed,
                dst_frame,
                &asset.samples,
                asset_frames,
                out_channels,
                src_pos,
                clip.gain,
            );
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

    Ok(MixedAudio {
        sample_rate: config.sample_rate,
        channels: config.channels,
        samples: mixed,
    })
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

            if clip.speed <= 0.0 {
                continue;
            }

            let source_path = match &clip.kind {
                ClipKind::Video(video) => video.source_path.clone(),
                ClipKind::Audio(audio) => audio.source_path.clone(),
                ClipKind::Text(_) => continue,
                ClipKind::Subtitle(_) => continue,
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
                gain: clip.volume,
            });
        }
    }

    scheduled
}

fn adapt_audio(mut audio: DecodedAudio, config: MixConfig) -> DecodedAudio {
    let out_channels = config.channels.max(1);

    if audio.channels != out_channels {
        audio.samples =
            convert_channels_interleaved(&audio.samples, audio.channels.max(1), out_channels);
        audio.channels = out_channels;
    }

    if audio.sample_rate != config.sample_rate {
        audio.samples = resample_linear_interleaved(
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

fn resample_linear_interleaved(
    input: &[f32],
    in_sample_rate: u32,
    out_sample_rate: u32,
    channels: u16,
) -> Vec<f32> {
    let channels = channels.max(1) as usize;

    if in_sample_rate == out_sample_rate {
        return input.to_vec();
    }

    let in_frames = input.len() / channels;
    if in_frames == 0 {
        return Vec::new();
    }

    let out_frames = (((in_frames as u128) * (out_sample_rate as u128)
        + (in_sample_rate as u128 / 2))
        / (in_sample_rate as u128))
        .max(1) as usize;

    let mut out = vec![0.0f32; out_frames * channels];

    for out_frame in 0..out_frames {
        let mut src_pos = (out_frame as f64) * (in_sample_rate as f64) / (out_sample_rate as f64);
        if src_pos > (in_frames.saturating_sub(1)) as f64 {
            src_pos = (in_frames.saturating_sub(1)) as f64;
        }

        let src_frame_0 = src_pos.floor() as usize;
        let src_frame_1 = (src_frame_0 + 1).min(in_frames.saturating_sub(1));
        let frac = (src_pos - src_frame_0 as f64) as f32;

        for ch in 0..channels {
            let a = input[src_frame_0 * channels + ch];
            let b = input[src_frame_1 * channels + ch];
            out[out_frame * channels + ch] = a + (b - a) * frac;
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
        let a = src[src_base_0 + ch];
        let b = src[src_base_1 + ch];
        dst[dst_base + ch] += (a + (b - a) * frac) * gain;
    }
}
