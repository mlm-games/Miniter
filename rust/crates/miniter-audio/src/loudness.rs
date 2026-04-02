use std::fs::File;
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

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

    let mut reader = probed.format;

    let track = reader
        .tracks()
        .iter()
        .find(|t| t.codec_params.codec != symphonia::core::codecs::CODEC_TYPE_NULL)
        .ok_or(LoudnessError::NoAudioTrack)?;

    let track_id = track.id;
    let sample_rate = track.codec_params.sample_rate.unwrap_or(44_100);
    let channels = track
        .codec_params
        .channels
        .map(|c| c.count())
        .unwrap_or(1)
        .max(1) as usize;

    let samples_per_chunk = (sample_rate as f64 * chunk_duration_us as f64 / 1_000_000.0)
        .round()
        .max(1.0) as u64;

    let mut decoder =
        symphonia::default::get_codecs().make(&track.codec_params, &DecoderOptions::default())?;

    let mut rms_values: Vec<f32> = Vec::new();
    let mut sum_sq: f64 = 0.0;
    let mut count_frames: u64 = 0;

    loop {
        let packet = match reader.next_packet() {
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

        let decoded = decoder.decode(&packet)?;
        let spec = *decoded.spec();
        let num_frames = decoded.frames();

        let mut sample_buf = SampleBuffer::<f32>::new(num_frames as u64, spec);
        sample_buf.copy_interleaved_ref(decoded);

        for frame in sample_buf.samples().chunks(channels) {
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
