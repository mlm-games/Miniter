use crate::decoder::{DecodeError, VideoDecodeSession};
use crate::frame::RgbaFrame;
use std::path::Path;

pub fn extract_thumbnail(path: &Path, target_us: i64) -> Result<RgbaFrame, DecodeError> {
    let mut session = VideoDecodeSession::open(path)?;
    let mut last_frame: Option<RgbaFrame> = None;

    loop {
        match session.next_frame()? {
            Some(frame) => {
                if frame.pts_us >= target_us {
                    return Ok(frame);
                }
                last_frame = Some(frame);
            }
            None => {
                return last_frame.ok_or(DecodeError::NoVideoStream);
            }
        }
    }
}

pub fn extract_thumbnails(
    path: &Path,
    count: usize,
    duration_us: i64,
) -> Result<Vec<RgbaFrame>, DecodeError> {
    if count == 0 || duration_us <= 0 {
        return Ok(Vec::new());
    }

    let interval_us = duration_us / count as i64;
    let mut targets: Vec<i64> = (0..count as i64).map(|i| i * interval_us).collect();
    let mut results = Vec::with_capacity(count);

    let mut session = VideoDecodeSession::open(path)?;
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
            None => {
                if let Some(ref f) = last_frame {
                    while results.len() < count {
                        results.push(f.clone());
                    }
                }
                break;
            }
        }
    }

    Ok(results)
}
