use crate::decoder::{DecodeError, VideoDecodeSession};
use crate::frame::RgbaFrame;
#[cfg(not(target_arch = "wasm32"))]
use std::collections::HashMap;
use std::path::Path;
#[cfg(not(target_arch = "wasm32"))]
use std::path::PathBuf;
#[cfg(not(target_arch = "wasm32"))]
use std::sync::{LazyLock, Mutex};

#[cfg(not(target_arch = "wasm32"))]
struct SessionEntry {
    session: VideoDecodeSession<std::io::BufReader<std::fs::File>>,
    last_pts: i64,
    last_frame: Option<RgbaFrame>,
}

#[cfg(not(target_arch = "wasm32"))]
static SESSION_CACHE: LazyLock<Mutex<HashMap<PathBuf, SessionEntry>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));

#[cfg(not(target_arch = "wasm32"))]
pub fn extract_thumbnail(path: &Path, target_us: i64) -> Result<RgbaFrame, DecodeError> {
    let mut guard = SESSION_CACHE.lock().unwrap();
    if !guard.contains_key(path) {
        let mut session = VideoDecodeSession::open(path)?;
        let first_frame = session.next_frame()?;
        let entry = SessionEntry {
            session,
            last_pts: first_frame.as_ref().map_or(0, |f| f.pts_us),
            last_frame: first_frame,
        };
        guard.insert(path.to_path_buf(), entry);
    }
    let entry = guard.get_mut(path).unwrap();

    if target_us <= entry.last_pts {
        if let Some(ref f) = entry.last_frame {
            if f.pts_us == target_us {
                return Ok(f.clone());
            }
        }
        entry.session.reset()?;
        entry.last_pts = 0;
        entry.last_frame = None;
    }

    loop {
        match entry.session.next_frame()? {
            Some(frame) => {
                if frame.pts_us >= target_us {
                    entry.last_pts = frame.pts_us;
                    entry.last_frame = Some(frame.clone());
                    return Ok(frame);
                }
                entry.last_pts = frame.pts_us;
                entry.last_frame = Some(frame);
            }
            None => {
                return entry.last_frame.clone().ok_or(DecodeError::NoVideoStream);
            }
        }
    }
}

#[cfg(target_arch = "wasm32")]
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
            None => return last_frame.ok_or(DecodeError::NoVideoStream),
        }
    }
}

#[cfg(not(target_arch = "wasm32"))]
pub fn clear_session_cache() {
    let mut guard = SESSION_CACHE.lock().unwrap();
    guard.clear();
}

#[cfg(target_arch = "wasm32")]
pub fn clear_session_cache() {}

#[cfg(not(target_arch = "wasm32"))]
pub fn extract_thumbnails(
    path: &Path,
    count: usize,
    duration_us: i64,
) -> Result<Vec<RgbaFrame>, DecodeError> {
    if count == 0 || duration_us <= 0 {
        return Ok(Vec::new());
    }

    let interval_us = duration_us / count as i64;
    let targets: Vec<i64> = (0..count as i64).map(|i| i * interval_us).collect();
    let mut results = Vec::with_capacity(count);

    let mut guard = SESSION_CACHE.lock().unwrap();
    if !guard.contains_key(path) {
        let mut session = VideoDecodeSession::open(path)?;
        let first_frame = session.next_frame()?;
        let entry = SessionEntry {
            session,
            last_pts: first_frame.as_ref().map_or(0, |f| f.pts_us),
            last_frame: first_frame,
        };
        guard.insert(path.to_path_buf(), entry);
    }
    let entry = guard.get_mut(path).unwrap();
    entry.session.reset()?;
    entry.last_pts = 0;
    entry.last_frame = None;
    let mut target_idx = 0;

    loop {
        if target_idx >= targets.len() {
            break;
        }

        match entry.session.next_frame()? {
            Some(frame) => {
                while target_idx < targets.len() && frame.pts_us >= targets[target_idx] {
                    results.push(frame.clone());
                    target_idx += 1;
                }
                entry.last_pts = frame.pts_us;
                entry.last_frame = Some(frame);
            }
            None => {
                if let Some(ref f) = entry.last_frame {
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

#[cfg(target_arch = "wasm32")]
pub fn extract_thumbnails(
    path: &Path,
    count: usize,
    duration_us: i64,
) -> Result<Vec<RgbaFrame>, DecodeError> {
    if count == 0 || duration_us <= 0 {
        return Ok(Vec::new());
    }

    let interval_us = duration_us / count as i64;
    let targets: Vec<i64> = (0..count as i64).map(|i| i * interval_us).collect();
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
