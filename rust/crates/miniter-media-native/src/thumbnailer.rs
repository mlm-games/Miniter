use crate::decoder::{DecodeError, VideoDecodeSession};
use crate::frame::RgbaFrame;
use miniter_audio::util;
use std::path::Path;

pub fn extract_thumbnail(path: &Path, target_us: i64, hardware_acceleration: bool) -> Result<RgbaFrame, DecodeError> {
    if util::is_image_file(path) {
        return load_image_as_frame(path);
    }
    extract_thumbnail_inner(path, target_us, hardware_acceleration)
        .or_else(|_| {
            if hardware_acceleration {
                extract_thumbnail_inner(path, target_us, false)
            } else {
                Err(DecodeError::NoVideoStream)
            }
        })
}

fn extract_thumbnail_inner(path: &Path, target_us: i64, hardware_acceleration: bool) -> Result<RgbaFrame, DecodeError> {
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

pub fn extract_thumbnails(
    path: &Path,
    count: usize,
    duration_us: i64,
    hardware_acceleration: bool,
) -> Result<Vec<RgbaFrame>, DecodeError> {
    if util::is_image_file(path) {
        let frame = load_image_as_frame(path)?;
        return Ok(vec![frame; count.min(1)]);
    }
    extract_thumbnails_inner(path, count, duration_us, hardware_acceleration)
        .or_else(|_| {
            if hardware_acceleration {
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

    let interval_us = duration_us / count as i64;
    let targets: Vec<i64> = (0..count as i64).map(|i| i * interval_us).collect();
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
