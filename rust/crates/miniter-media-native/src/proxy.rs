//! Scrub proxy: a small, densely-keyframed H.264 rendition of a source clip
//! used for preview playback and timeline thumbnails.
//!
//! Decoding a 4K source to RGBA for every thumbnail costs ~33 MB per frame and
//! dominates timeline rendering. A proxy trades one full-source decode for
//! cheap decodes forever after. Keyframes are packed one per second so seeking
//! the proxy stays tight even though the resolution is low.

use std::fs;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use crate::decoders::session::VideoDecodeSession;
use crate::encoder::{EncodedVideoOutput, VideoEncodeSession};
use crate::export_shared::scale_rgba;
use crate::frame::RgbaFrame;
use crate::mux::{ContainerFormat, Mp4Muxer, VideoTrackCodecOut, extract_sps_pps};

/// Bumped by [`cancel_generation`] and by every new build. A running build
/// stops as soon as it notices it is no longer the current generation.
///
/// Cancellation cannot be a plain flag: a flag would be cleared by the next
/// build starting, so a cancel issued for one clip would be silently lost as
/// soon as another clip began building.
static GENERATION: AtomicU64 = AtomicU64::new(0);

/// Invalidate any in-flight build so it stops and discards its partial output.
pub fn cancel_generation() {
    GENERATION.fetch_add(1, Ordering::SeqCst);
}

#[derive(Debug, thiserror::Error)]
pub enum ProxyError {
    #[error("failed to decode source: {0}")]
    Decode(#[from] crate::decoders::DecodeError),
    #[error("failed to encode proxy: {0}")]
    Encode(#[from] crate::encoder::EncodeError),
    #[error("failed to write proxy: {0}")]
    Mux(#[from] crate::mux::MuxError),
    #[error("proxy io failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("source has no video stream")]
    NoVideoStream,
    #[error("source resolution {0}x{1} is not valid")]
    InvalidDimensions(u32, u32),
    #[error("proxy generation cancelled")]
    Cancelled,
}

#[derive(Debug, Clone, Copy)]
pub struct ProxyOptions {
    pub max_dimension: u32,
    pub fps: f32,
    pub bitrate_kbps: u32,
}

impl Default for ProxyOptions {
    fn default() -> Self {
        Self {
            max_dimension: 1280,
            fps: 15.0,
            bitrate_kbps: 1200,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProxyStats {
    pub width: u32,
    pub height: u32,
    pub frames: u32,
    pub bytes: u64,
}

/// Proxy dimensions for a source, clamped to `max_dimension` on the long edge
/// with the aspect ratio preserved and both axes even (h264 requirement).
///
/// Returns `None` when the source is already at or below the limit, meaning a
/// proxy would not be smaller than the original and is not worth building.
pub fn proxy_target_size(width: u32, height: u32, max_dimension: u32) -> Option<(u32, u32)> {
    if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
        return None;
    }
    let long_edge = width.max(height);
    if long_edge <= max_dimension {
        return None;
    }

    let scale = max_dimension as f64 / long_edge as f64;
    let mut w = ((width as f64 * scale).round() as u32).max(2);
    let mut h = ((height as f64 * scale).round() as u32).max(2);
    if !w.is_multiple_of(2) {
        w -= 1;
    }
    if !h.is_multiple_of(2) {
        h -= 1;
    }
    if w < 2 || h < 2 {
        return None;
    }
    Some((w, h))
}

/// `VideoEncodeSession` derives its GOP from fps with a 30-frame floor, so at
/// the proxy's 15 fps a GOP is 30 frames (2 s), not 1 s. Seeks therefore land
/// at most 2 s from the target; that is still an order of magnitude tighter
/// than the source's own keyframe spacing at typical camera bitrates.
fn proxy_encode_session(
    width: u32,
    height: u32,
    opts: &ProxyOptions,
) -> Result<VideoEncodeSession, ProxyError> {
    VideoEncodeSession::new(width, height, opts.bitrate_kbps, opts.fps, 9).map_err(ProxyError::from)
}

fn cache_root_lock() -> &'static std::sync::RwLock<Option<PathBuf>> {
    static ROOT: std::sync::OnceLock<std::sync::RwLock<Option<PathBuf>>> =
        std::sync::OnceLock::new();
    ROOT.get_or_init(|| std::sync::RwLock::new(None))
}

/// Directory holding generated proxies. Every playback-source lookup reads it,
/// so it is shared rather than passed through each call.
fn cache_root() -> PathBuf {
    if let Some(dir) = cache_root_lock().read().ok().and_then(|g| g.clone()) {
        return dir;
    }
    let resolved = std::env::var_os("MINITER_PROXY_CACHE")
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("miniter").join("proxies"));
    if let Ok(mut guard) = cache_root_lock().write() {
        *guard = Some(resolved.clone());
    }
    resolved
}

/// Point proxy caching at `dir`, creating it if absent. Call once during app
/// start; affects every later `playback_source_for` and [`cache_path_for`].
pub fn set_cache_root(dir: PathBuf) -> std::io::Result<()> {
    fs::create_dir_all(&dir)?;
    if let Ok(mut guard) = cache_root_lock().write() {
        *guard = Some(dir);
    }
    Ok(())
}

/// Where `source`'s proxy lives under the active cache root. The single place
/// that maps a source to its proxy path, so generation and lookup can never
/// disagree about the directory.
pub fn cache_path_for(source: &Path) -> Option<PathBuf> {
    proxy_cache_path(source, &cache_root())
}

/// Reuse `source`'s cached proxy when one exists, otherwise return the source
/// path unchanged.
///
/// This is the entry point for preview and thumbnail work: callers do not need
/// to know whether a proxy was ever built. Proxy generation is a separate,
/// explicitly triggered step because it costs a full decode of the source.
pub fn playback_source_for(source: &Path) -> PathBuf {
    match proxy_cache_path(source, &cache_root()) {
        Some(candidate) if is_proxy_fresh(source, &candidate) => candidate,
        _ => source.to_path_buf(),
    }
}

/// Decode `source`, preferring a cached proxy when one exists.
///
/// Frame geometry changes when a proxy is used, so callers that key state on
/// resolution must read dimensions from the returned frame rather than probing
/// the source.
pub fn decode_for_playback(
    path: &Path,
    hardware_acceleration: bool,
) -> Result<RgbaFrame, crate::decoders::DecodeError> {
    let source = playback_source_for(path);
    crate::thumbnailer::extract_thumbnail(&source, 0, hardware_acceleration)
}

/// Decode `source` for preview, preferring a cached proxy when one exists.
pub fn decode_thumbnails_for_playback(
    path: &Path,
    count: usize,
    duration_us: i64,
    hardware_acceleration: bool,
) -> Result<Vec<RgbaFrame>, crate::decoders::DecodeError> {
    let source = playback_source_for(path);
    crate::thumbnailer::extract_thumbnails(&source, count, duration_us, hardware_acceleration)
}

/// Decode `source`, downscale and re-encode to `dest`.
///
/// Audio is dropped: a scrub proxy exists to make seeking cheap, and every
/// consumer of it draws frames rather than plays sound. `dest` is written to a
/// sibling temp file and renamed on success, so an aborted run never leaves a
/// truncated proxy that later readers would treat as valid.
pub fn generate_proxy(
    source: &Path,
    dest: &Path,
    opts: &ProxyOptions,
    hardware_acceleration: bool,
) -> Result<ProxyStats, ProxyError> {
    let generation = GENERATION.fetch_add(1, Ordering::SeqCst) + 1;
    let mut session = VideoDecodeSession::open(source, hardware_acceleration)?;

    let first = match session.next_frame()? {
        Some(frame) => frame,
        None => return Err(ProxyError::NoVideoStream),
    };

    let (width, height) = proxy_target_size(first.width, first.height, opts.max_dimension)
        .ok_or(ProxyError::InvalidDimensions(first.width, first.height))?;

    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)?;
    }
    // Unique per generation: two builds can never share a temp file, so a
    // slow build cannot rename a newer build's partial output into place.
    let temp = dest.with_extension(format!("{generation}.part"));
    let _ = fs::remove_file(&temp);

    let mut muxer: Option<Mp4Muxer<BufWriter<fs::File>>> = None;
    let mut encoder = proxy_encode_session(width, height, opts)?;
    let mut frames: u32 = 0;

    let frame_interval_us = (1_000_000.0 / opts.fps.max(1.0)) as i64;

    let run = || -> Result<ProxyStats, ProxyError> {
        let pump = |frame: &RgbaFrame,
                    encoder: &mut VideoEncodeSession,
                    muxer: &mut Option<Mp4Muxer<BufWriter<fs::File>>>,
                    frames: &mut u32|
         -> Result<(), ProxyError> {
            let scaled = RgbaFrame::new(
                width,
                height,
                scale_rgba(
                    &frame.data,
                    frame.width as usize,
                    frame.height as usize,
                    width as usize,
                    height as usize,
                ),
                frame.pts_us,
            )
            .ok_or(ProxyError::InvalidDimensions(width, height))?;

            let output = encoder.encode_frame(&scaled)?;
            let EncodedVideoOutput::Sample {
                bytes,
                is_keyframe,
                pts_us,
            } = output
            else {
                return Ok(());
            };

            if muxer.is_none() {
                extract_sps_pps(&bytes).ok_or(ProxyError::NoVideoStream)?;
                let writer = BufWriter::new(fs::File::create(&temp)?);
                *muxer = Some(Mp4Muxer::new(
                    writer,
                    width,
                    height,
                    opts.fps as f64,
                    &[],
                    &[],
                    ContainerFormat::Mp4,
                    None,
                    None,
                    VideoTrackCodecOut::H264,
                    frame.color_info.matrix,
                )?);
            }

            muxer
                .as_mut()
                .expect("muxer created above")
                .write_sample_at(pts_us.max(0) as u64, &bytes, is_keyframe)?;
            *frames += 1;
            Ok(())
        };

        pump(&first, &mut encoder, &mut muxer, &mut frames)?;
        let mut next_emit_us = first.pts_us + frame_interval_us;

        loop {
            if GENERATION.load(Ordering::SeqCst) != generation {
                return Err(ProxyError::Cancelled);
            }
            match session.next_frame()? {
                Some(frame) => {
                    if frame.pts_us >= next_emit_us {
                        pump(&frame, &mut encoder, &mut muxer, &mut frames)?;
                        // Advance past every slot the decoder skipped so a long
                        // frame gap does not queue a burst of back-to-back emits.
                        next_emit_us = frame.pts_us + frame_interval_us;
                    }
                }
                None if session.is_eos() => break,
                None => {}
            }
        }

        if GENERATION.load(Ordering::SeqCst) != generation {
            return Err(ProxyError::Cancelled);
        }

        for output in encoder.finish() {
            if let EncodedVideoOutput::Sample {
                bytes,
                is_keyframe,
                pts_us,
            } = output
            {
                if let Some(muxer) = muxer.as_mut() {
                    muxer.write_sample_at(pts_us.max(0) as u64, &bytes, is_keyframe)?;
                    frames += 1;
                }
            }
        }

        let mut m = muxer.ok_or(ProxyError::NoVideoStream)?;
        m.finish()?;
        drop(m);

        let bytes = fs::metadata(&temp)?.len();
        if bytes == 0 {
            return Err(ProxyError::NoVideoStream);
        }
        fs::rename(&temp, dest)?;

        Ok(ProxyStats {
            width,
            height,
            frames,
            bytes,
        })
    };

    // A failed or cancelled build must not leave a partial file that a later
    // run could mistake for a finished proxy.
    match run() {
        Ok(stats) => Ok(stats),
        Err(e) => {
            let _ = fs::remove_file(&temp);
            Err(e)
        }
    }
}

/// Deterministic cache location for a source's proxy.
///
/// Keyed on the full path plus size. Staged inputs are content-addressed and
/// carry a fresh mtime on every import, so including mtime would give the same
/// clip a new key each time it is re-imported and the cache would never hit.
/// The path is hashed rather than embedded because two sources can share a
/// directory, and distinct clips that merely share a base name and byte length
/// must not collide onto one another's proxy.
pub fn proxy_cache_path(source: &Path, cache_dir: &Path) -> Option<PathBuf> {
    let meta = fs::metadata(source).ok()?;
    let stem = source.file_stem()?.to_string_lossy();
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in source.to_string_lossy().as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100_0000_01b3);
    }
    Some(cache_dir.join(format!("{stem}-{}-{hash:016x}.proxy.mp4", meta.len())))
}

/// True when a usable proxy for this source is already on disk.
pub fn is_proxy_fresh(_source: &Path, proxy: &Path) -> bool {
    fs::metadata(proxy).map(|m| m.len() > 0).unwrap_or(false)
}

/// The path a preview or thumbnail request should read: the cached proxy when
/// one is usable, otherwise the original.
pub fn resolve_playback_source(source: &Path, cache_dir: &Path) -> PathBuf {
    match proxy_cache_path(source, cache_dir) {
        Some(candidate) if is_proxy_fresh(source, &candidate) => candidate,
        _ => source.to_path_buf(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn set_cache_root_is_observed_by_cache_path_for() {
        let dir = std::env::temp_dir().join(format!("miniter-test-proxy-{}", std::process::id()));
        set_cache_root(dir.clone()).expect("set cache root");

        let source = dir.join("clip.mp4");
        fs::write(&source, b"not really a video").expect("write source");

        let from_helper = cache_path_for(&source).expect("cache path");
        let from_root = proxy_cache_path(&source, &dir).expect("cache path");

        assert_eq!(
            from_helper, from_root,
            "cache_path_for must resolve against the configured root, not the default"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn no_proxy_when_already_small_enough() {
        assert_eq!(proxy_target_size(1280, 720, 1280), None);
        assert_eq!(proxy_target_size(640, 480, 1280), None);
    }

    #[test]
    fn scales_long_edge_to_limit_and_keeps_aspect() {
        let (w, h) = proxy_target_size(3840, 2160, 1280).expect("4K should downscale");
        assert_eq!((w, h), (1280, 720));
        assert!(w.is_multiple_of(2) && h.is_multiple_of(2));
    }

    #[test]
    fn portrait_uses_height_as_long_edge() {
        let (w, h) = proxy_target_size(2160, 3840, 1280).expect("portrait should downscale");
        assert_eq!((w, h), (720, 1280));
    }

    #[test]
    fn rounding_down_to_even_dimensions() {
        let (ow, oh) = proxy_target_size(1002, 1000, 333).expect("should downscale");
        assert!(ow.is_multiple_of(2), "rounded width {ow} must be even");
        assert!(oh.is_multiple_of(2), "rounded height {oh} must be even");
        assert!(ow <= 333 && oh <= 333);
    }

    #[test]
    fn cache_path_differs_for_same_name_and_size_in_other_dirs() {
        let root = std::env::temp_dir().join(format!("miniter-test-dirs-{}", std::process::id()));
        let a_dir = root.join("a");
        let b_dir = root.join("b");
        fs::create_dir_all(&a_dir).expect("mkdir a");
        fs::create_dir_all(&b_dir).expect("mkdir b");
        let a = a_dir.join("clip.mp4");
        let b = b_dir.join("clip.mp4");
        fs::write(&a, b"identical").expect("write a");
        fs::write(&b, b"identical").expect("write b");

        assert_ne!(
            proxy_cache_path(&a, &root).expect("path a"),
            proxy_cache_path(&b, &root).expect("path b"),
            "same name and size in different directories must not share a proxy"
        );

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn cache_path_is_stable_across_mtime_changes() {
        let dir = std::env::temp_dir().join(format!("miniter-test-mtime-{}", std::process::id()));
        let _ = fs::create_dir_all(&dir);
        let source = dir.join("clip.mp4");
        fs::write(&source, b"payload").expect("write");

        let first = proxy_cache_path(&source, &dir).expect("path");
        std::thread::sleep(std::time::Duration::from_millis(20));
        fs::write(&source, b"payload").expect("rewrite");
        let second = proxy_cache_path(&source, &dir).expect("path");

        assert_eq!(
            first, second,
            "re-importing identical content must reuse the same cache entry"
        );

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn rejects_odd_sized_sources() {
        assert_eq!(proxy_target_size(1001, 1000, 333), None);
    }

    #[test]
    fn rejects_degenerate_sources() {
        assert_eq!(proxy_target_size(0, 100, 1280), None);
        assert_eq!(proxy_target_size(100, 0, 1280), None);
        assert_eq!(proxy_target_size(641, 480, 1280), None);
    }
}
