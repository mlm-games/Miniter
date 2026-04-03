use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum WavWriteError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Invalid sample rate")]
    InvalidSampleRate,
    #[error("Invalid channel count")]
    InvalidChannelCount,
    #[error("WAV file too large")]
    FileTooLarge,
}

pub fn write_pcm16_wav(
    path: &Path,
    sample_rate: u32,
    channels: u16,
    samples: &[f32],
) -> Result<(), WavWriteError> {
    if sample_rate == 0 {
        return Err(WavWriteError::InvalidSampleRate);
    }
    if channels == 0 {
        return Err(WavWriteError::InvalidChannelCount);
    }

    let bits_per_sample: u16 = 16;
    let bytes_per_sample: u16 = bits_per_sample / 8;
    let block_align = channels
        .checked_mul(bytes_per_sample)
        .ok_or(WavWriteError::FileTooLarge)?;
    let byte_rate = sample_rate
        .checked_mul(block_align as u32)
        .ok_or(WavWriteError::FileTooLarge)?;

    let data_chunk_size = (samples.len() as u64)
        .checked_mul(bytes_per_sample as u64)
        .ok_or(WavWriteError::FileTooLarge)?;
    let riff_chunk_size = 36u64
        .checked_add(data_chunk_size)
        .ok_or(WavWriteError::FileTooLarge)?;

    if riff_chunk_size > u32::MAX as u64 || data_chunk_size > u32::MAX as u64 {
        return Err(WavWriteError::FileTooLarge);
    }

    let file = File::create(path)?;
    let mut writer = BufWriter::new(file);

    writer.write_all(b"RIFF")?;
    writer.write_all(&(riff_chunk_size as u32).to_le_bytes())?;
    writer.write_all(b"WAVE")?;

    writer.write_all(b"fmt ")?;
    writer.write_all(&(16u32).to_le_bytes())?;
    writer.write_all(&(1u16).to_le_bytes())?;
    writer.write_all(&channels.to_le_bytes())?;
    writer.write_all(&sample_rate.to_le_bytes())?;
    writer.write_all(&byte_rate.to_le_bytes())?;
    writer.write_all(&block_align.to_le_bytes())?;
    writer.write_all(&bits_per_sample.to_le_bytes())?;

    writer.write_all(b"data")?;
    writer.write_all(&(data_chunk_size as u32).to_le_bytes())?;

    for &sample in samples {
        let s = (sample.clamp(-1.0, 1.0) * 32767.0).round() as i16;
        writer.write_all(&s.to_le_bytes())?;
    }

    writer.flush()?;
    Ok(())
}
