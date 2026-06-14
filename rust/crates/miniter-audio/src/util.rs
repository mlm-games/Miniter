use std::fs::File;
use std::io::Cursor;
use std::path::Path;
use symphonia::core::formats::FormatOptions;
use symphonia::core::formats::probe::Hint;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;

pub fn open_mss_from_path(
    path: &Path,
) -> std::io::Result<(MediaSourceStream<'static>, Option<String>)> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(|s| s.to_lowercase());
    let file = File::open(path)?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());
    Ok((mss, ext))
}

pub fn open_mss_from_bytes(
    bytes: &[u8],
    extension_hint: Option<&str>,
) -> (MediaSourceStream<'static>, Option<String>) {
    let cursor = Cursor::new(bytes.to_vec());
    let mss = MediaSourceStream::new(Box::new(cursor), Default::default());
    (mss, extension_hint.map(|s| s.to_lowercase()))
}

pub fn probe<'a>(
    mss: MediaSourceStream<'a>,
    ext: Option<&str>,
) -> Result<Box<dyn symphonia::core::formats::FormatReader + 'a>, symphonia::core::errors::Error> {
    let mut hint = Hint::new();
    if let Some(ext) = ext {
        hint.with_extension(ext);
    }
    symphonia::default::get_probe().probe(
        &hint,
        mss,
        FormatOptions::default(),
        MetadataOptions::default(),
    )
}

pub fn is_image_extension(ext: Option<&str>) -> bool {
    match ext.map(|e| e.to_lowercase()).as_deref() {
        Some("png" | "jpg" | "jpeg" | "webp" | "gif" | "bmp" | "tiff" | "tif") => true,
        _ => false,
    }
}

pub fn is_image_file(path: &Path) -> bool {
    is_image_extension(path.extension().and_then(|e| e.to_str()))
}

pub fn is_video_only_extension(ext: Option<&str>) -> bool {
    matches!(ext.map(|e| e.to_lowercase()).as_deref(), Some("ivf"))
}

pub fn resample_linear_interleaved(
    input: &[f32],
    in_sample_rate: u32,
    out_sample_rate: u32,
    channels: u16,
) -> Vec<f32> {
    let channels = channels.max(1) as usize;

    if in_sample_rate == out_sample_rate || input.is_empty() {
        return input.to_vec();
    }

    let in_frames = input.len() / channels;
    if in_frames == 0 {
        return Vec::new();
    }

    let out_frames =
        ((in_frames as u64 * out_sample_rate as u64) / in_sample_rate as u64).max(1) as usize;

    let mut out = vec![0.0f32; out_frames * channels];

    for of in 0..out_frames {
        let src_pos = (of as f64) * (in_sample_rate as f64) / (out_sample_rate as f64);
        let i0 = src_pos.floor() as usize;
        let i1 = (i0 + 1).min(in_frames.saturating_sub(1));
        let t = (src_pos - i0 as f64) as f32;

        for ch in 0..channels {
            let a = input[i0 * channels + ch];
            let b = input[i1 * channels + ch];
            out[of * channels + ch] = a + (b - a) * t;
        }
    }

    out
}
