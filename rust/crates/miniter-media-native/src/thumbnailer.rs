use std::sync::atomic::Ordering;

use crate::decoder::{DecodeError, VideoDecodeSession};
use crate::frame::RgbaFrame;
use miniter_audio::util;
use std::path::Path;

pub fn extract_thumbnail(
    path: &Path,
    target_us: i64,
    hardware_acceleration: bool,
) -> Result<RgbaFrame, DecodeError> {
    if util::is_image_file(path) {
        return load_image_as_frame(path);
    }
    extract_thumbnail_inner(path, target_us, hardware_acceleration).or_else(|_| {
        if hardware_acceleration {
            crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            extract_thumbnail_inner(path, target_us, false)
        } else {
            Err(DecodeError::NoVideoStream)
        }
    })
}

fn extract_thumbnail_inner(
    path: &Path,
    target_us: i64,
    hardware_acceleration: bool,
) -> Result<RgbaFrame, DecodeError> {
    let mut session = VideoDecodeSession::open(path, hardware_acceleration)?;
    let mut last_frame: Option<RgbaFrame> = None;

    loop {
        match session.next_frame()? {
            Some(frame) => {
                if frame.pts_us >= target_us {
                    return Ok(frame);
                }
                last_frame = Some(frame);
            }
            None if session.is_eos() => {
                return last_frame.ok_or(DecodeError::NoVideoStream);
            }
            None => {
                // Decoder not ready yet; retry.
            }
        }
    }
}

pub fn clear_session_cache() {}

/// Shared target-timestamp computation for N thumbnails over `duration_us`.
///
/// Contract (all platforms must match):
/// - `count == 0` or `duration_us <= 0` → empty
/// - `count == 1` → `[duration_us / 2]` (middle frame, not first)
/// - otherwise → evenly spread inclusive of both ends:
///   `targets[i] = i * duration_us / (count - 1)`
pub fn thumbnail_targets(count: usize, duration_us: i64) -> Vec<i64> {
    if count == 0 || duration_us <= 0 {
        return Vec::new();
    }
    if count == 1 {
        return vec![duration_us / 2];
    }
    (0..count as i64)
        .map(|i| (i * duration_us) / (count as i64 - 1))
        .collect()
}

pub fn extract_thumbnails(
    path: &Path,
    count: usize,
    duration_us: i64,
    hardware_acceleration: bool,
) -> Result<Vec<RgbaFrame>, DecodeError> {
    if util::is_image_file(path) {
        let frame = load_image_as_frame(path)?;
        return Ok(vec![frame; count.max(1)]);
    }
    extract_thumbnails_inner(path, count, duration_us, hardware_acceleration).or_else(|_| {
        if hardware_acceleration {
            crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            extract_thumbnails_inner(path, count, duration_us, false)
        } else {
            Err(DecodeError::NoVideoStream)
        }
    })
}

fn extract_thumbnails_inner(
    path: &Path,
    count: usize,
    duration_us: i64,
    hardware_acceleration: bool,
) -> Result<Vec<RgbaFrame>, DecodeError> {
    if count == 0 || duration_us <= 0 {
        return Ok(Vec::new());
    }

    let targets: Vec<i64> = thumbnail_targets(count, duration_us);
    let mut results = Vec::with_capacity(count);

    let mut session = VideoDecodeSession::open(path, hardware_acceleration)?;
    let mut target_idx = 0;
    let mut last_frame: Option<RgbaFrame> = None;

    loop {
        if target_idx >= targets.len() {
            break;
        }

        match session.next_frame()? {
            Some(frame) => {
                while target_idx < targets.len() && frame.pts_us >= targets[target_idx] {
                    results.push(frame.clone());
                    target_idx += 1;
                }
                last_frame = Some(frame);
            }
            None if session.is_eos() => {
                if let Some(ref f) = last_frame {
                    while results.len() < count {
                        results.push(f.clone());
                    }
                }
                break;
            }
            None => {
                // Decoder not ready yet; retry.
            }
        }
    }

    Ok(results)
}

fn load_image_as_frame(path: &Path) -> Result<RgbaFrame, DecodeError> {
    let img = image::open(path).map_err(|_e| DecodeError::NoVideoStream)?;
    let rgba = img.to_rgba8();
    let (width, height) = rgba.dimensions();
    let data = rgba.into_raw();
    Ok(RgbaFrame {
        width,
        height,
        data,
        pts_us: 0,
        color_info: Default::default(),
    })
}

/// Decode an image from in-memory bytes (WASM registered-file path).
/// Shared so native and WASM never diverge on image thumbnails.
pub fn load_image_bytes_as_frame(bytes: &[u8]) -> Result<RgbaFrame, DecodeError> {
    let img = image::load_from_memory(bytes).map_err(|_e| DecodeError::NoVideoStream)?;
    let rgba = img.to_rgba8();
    let (width, height) = rgba.dimensions();
    Ok(RgbaFrame {
        width,
        height,
        data: rgba.into_raw(),
        pts_us: 0,
        color_info: Default::default(),
    })
}

#[cfg(test)]
mod tests {
    use super::thumbnail_targets;

    #[test]
    fn targets_empty_on_zero_inputs() {
        assert!(thumbnail_targets(0, 1_000_000).is_empty());
        assert!(thumbnail_targets(5, 0).is_empty());
        assert!(thumbnail_targets(5, -1).is_empty());
    }

    #[test]
    fn targets_single_is_middle() {
        assert_eq!(thumbnail_targets(1, 1_000_000), vec![500_000]);
        assert_eq!(thumbnail_targets(1, 7), vec![3]);
    }

    #[test]
    fn targets_span_full_range_inclusive() {
        let targets = thumbnail_targets(12, 11_000_000);
        assert_eq!(targets.len(), 12);
        assert_eq!(targets[0], 0);
        assert_eq!(targets[11], 11_000_000);
        for w in targets.windows(2) {
            assert!(w[1] >= w[0]);
        }
    }

    #[test]
    fn targets_two_endpoints() {
        assert_eq!(thumbnail_targets(2, 10_000_000), vec![0, 10_000_000]);
    }
}
