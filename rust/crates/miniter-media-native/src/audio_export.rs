use std::path::{Path, PathBuf};

use miniter_audio::mix::{mix_project_audio, AudioMixError, MixConfig};
use miniter_audio::wav::{write_pcm16_wav, WavWriteError};
use miniter_domain::clip::ClipKind;
use miniter_domain::Project;

#[derive(Debug, thiserror::Error)]
pub enum AudioSidecarExportError {
    #[error("Audio mix failed: {0}")]
    Mix(#[from] AudioMixError),
    #[error("WAV write failed: {0}")]
    Wav(#[from] WavWriteError),
    #[error("Invalid output path")]
    InvalidOutputPath,
}

pub fn write_audio_sidecar_if_present(
    project: &Project,
    output_path: &Path,
) -> Result<Option<PathBuf>, AudioSidecarExportError> {
    if !project_has_any_audio(project) {
        return Ok(None);
    }

    let mixed = mix_project_audio(project, MixConfig::default())?;
    if mixed.samples.is_empty() {
        return Ok(None);
    }

    let wav_path =
        sidecar_wav_path(output_path).ok_or(AudioSidecarExportError::InvalidOutputPath)?;
    write_pcm16_wav(&wav_path, mixed.sample_rate, mixed.channels, &mixed.samples)?;
    Ok(Some(wav_path))
}

fn project_has_any_audio(project: &Project) -> bool {
    project.timeline.tracks.iter().any(|track| {
        !track.muted
            && track.clips.iter().any(|clip| {
                !clip.muted
                    && clip.volume.abs() > f32::EPSILON
                    && matches!(clip.kind, ClipKind::Video(_) | ClipKind::Audio(_))
            })
    })
}

fn sidecar_wav_path(output_path: &Path) -> Option<PathBuf> {
    let stem = output_path.file_stem()?.to_str()?;
    let parent = output_path.parent().unwrap_or_else(|| Path::new("."));
    Some(parent.join(format!("{stem}.audio.wav")))
}
