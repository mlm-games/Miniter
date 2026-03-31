use std::fs::File;
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

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
    if target_buckets == 0 {
        return Err(WaveformError::InvalidBucketCount);
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
        .ok_or(WaveformError::NoAudioTrack)?;

    let track_id = track.id;
    let sample_rate = track.codec_params.sample_rate.unwrap_or(44_100);
    let channels = track
        .codec_params
        .channels
        .map(|c| c.count() as u16)
        .unwrap_or(1);

    let total_samples = track
        .codec_params
        .n_frames
        .unwrap_or(sample_rate as u64 * 300);
    let samples_per_bucket = (total_samples / target_buckets as u64).max(1);

    let mut decoder =
        symphonia::default::get_codecs().make(&track.codec_params, &DecoderOptions::default())?;

    let mut peaks: Vec<(f32, f32)> = Vec::with_capacity(target_buckets);
    let mut bucket_min: f32 = 0.0;
    let mut bucket_max: f32 = 0.0;
    let mut bucket_count: u64 = 0;

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

        for &sample in sample_buf.samples() {
            bucket_min = bucket_min.min(sample);
            bucket_max = bucket_max.max(sample);
            bucket_count += 1;

            if bucket_count >= samples_per_bucket {
                peaks.push((bucket_min, bucket_max));
                bucket_min = 0.0;
                bucket_max = 0.0;
                bucket_count = 0;
            }
        }
    }

    if bucket_count > 0 {
        peaks.push((bucket_min, bucket_max));
    }

    Ok(WaveformData {
        sample_rate,
        channels,
        samples_per_bucket,
        peaks,
    })
}
