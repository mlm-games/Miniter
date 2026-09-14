//! H.264 video encoder.
//!

//!
//! `rusty_h264` with mb-tree off emits exactly one access unit per `encode`
//! call (`Skipped` can never fire, but the variant is handled anyway so a
//! backend behavior change can never silently drop a frame).

use crate::export_shared::video_bitrate_bps;
use crate::frame::RgbaFrame;
use crate::yuv::rgba_to_yuv420;

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("H.264 backend: {0}")]
    Backend(String),
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
    #[error("Encoder produced no output for frame {frame_index}")]
    EmptyFrame { frame_index: u32 },
    /// The HW/platform encoder buffered a frame and returned nothing yet
    /// (async WebCodecs output). SW paths never emit this; the native and
    /// wasm-immediate H.264 loops treat it as "feed more frames" instead of
    /// failing the export.
    #[error("Encoder buffered frame {frame_index} (output pending)")]
    BufferedFrame { frame_index: u32 },
}

#[derive(Debug)]
pub enum EncodedVideoOutput {
    Sample {
        bytes: Vec<u8>,
        is_keyframe: bool,
        pts_us: i64,
    },
    Skipped,
}

pub struct VideoEncodeSession {
    encoder: rusty_h264_encoder::Encoder,
    width: u32,
    height: u32,
    frame_index: u32,
    /// PTS of frames submitted but not yet emitted. Always empty with the
    /// current no-lookahead config, but kept so the accounting survives a
    /// future config change (a `Skipped` output must map back to its input
    /// PTS instead of inheriting a later frame's).
    pending_pts: std::collections::VecDeque<i64>,
}

impl VideoEncodeSession {
    pub fn new(
        width: u32,
        height: u32,
        bitrate_kbps: u32,
        fps: f32,
        encode_effort: u8,
    ) -> Result<Self, EncodeError> {
        if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(EncodeError::InvalidDimensions);
        }
        let mut cfg = rusty_h264_encoder::EncoderConfig::new(width as usize, height as usize);
        let fps = if fps.is_finite() && fps > 0.0 {
            fps
        } else {
            30.0
        };
        cfg.gop_size = (2.0 * fps as f64).round().clamp(30.0, 240.0) as u32;
        cfg.scenecut = 0;
        cfg.mbtree = false;

        // Balanced (default), 8-10 Fast.
        cfg.preset = match encode_effort.min(10) {
            0..=3 => rusty_h264_encoder::Preset::Quality,
            4..=7 => rusty_h264_encoder::Preset::Balanced,
            _ => rusty_h264_encoder::Preset::Fast,
        };
        cfg.bitrate = video_bitrate_bps(bitrate_kbps);
        cfg.framerate = fps;
        cfg.qp = 26;
        let encoder = rusty_h264_encoder::Encoder::new(cfg)
            .map_err(|e| EncodeError::Backend(e.to_string()))?;
        Ok(Self {
            encoder,
            width,
            height,
            frame_index: 0,
            pending_pts: std::collections::VecDeque::new(),
        })
    }

    pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
        self.frame_index += 1;
        if frame.width != self.width || frame.height != self.height {
            return Err(EncodeError::InvalidDimensions);
        }

        let (y_plane, u_plane, v_plane) = self.to_planes(frame)?;
        let input = rusty_h264_common::YuvFrame {
            width: self.width as usize,
            height: self.height as usize,
            y: y_plane,
            u: u_plane,
            v: v_plane,
        };
        self.pending_pts.push_back(frame.pts_us);
        let bytes = self.encoder.encode(&input);
        if bytes.is_empty() {
            return Ok(EncodedVideoOutput::Skipped);
        }
        let pts = self.pending_pts.pop_front().unwrap_or(frame.pts_us);
        Ok(EncodedVideoOutput::Sample {
            is_keyframe: is_idr_access_unit(&bytes),
            bytes,
            pts_us: pts,
        })
    }

    /// Drain any lookahead tail in decode order. Empty with the current
    /// config; always safe to call.
    pub fn finish(&mut self) -> Vec<EncodedVideoOutput> {
        let mut out = Vec::new();
        for bytes in [self.encoder.flush()].into_iter().filter(|b| !b.is_empty()) {
            let pts = self.pending_pts.pop_front().unwrap_or(0);
            out.push(EncodedVideoOutput::Sample {
                is_keyframe: is_idr_access_unit(&bytes),
                bytes,
                pts_us: pts,
            });
        }
        out
    }

    pub fn width(&self) -> u32 {
        self.width
    }
    pub fn height(&self) -> u32 {
        self.height
    }

    fn to_planes(&self, frame: &RgbaFrame) -> Result<(Vec<u8>, Vec<u8>, Vec<u8>), EncodeError> {
        Ok(rgba_to_yuv420(
            &frame.data,
            self.width as usize,
            self.height as usize,
            frame.color_info.matrix,
        ))
    }
}

/// An access unit is a keyframe iff it carries an IDR slice (NAL type 5).
/// SPS (7) / PPS (8) prefix every IDR, so scan all NAL headers.
fn is_idr_access_unit(annex_b: &[u8]) -> bool {
    let mut i = 0;
    while i + 3 <= annex_b.len() {
        let header_at = if annex_b[i..].starts_with(&[0, 0, 0, 1]) {
            i + 4
        } else if annex_b[i..].starts_with(&[0, 0, 1]) {
            i + 3
        } else {
            i += 1;
            continue;
        };
        if header_at < annex_b.len() && annex_b[header_at] & 0x1F == 5 {
            return true;
        }
        i = header_at + 1;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::is_idr_access_unit;

    #[test]
    fn idr_detected_with_3_and_4_byte_start_codes() {
        let au = vec![
            0, 0, 0, 1, 0x67, 0xAA, // SPS (7)
            0, 0, 0, 1, 0x68, 0xBB, // PPS (8)
            0, 0, 1, 0x65, 0xCC, // IDR (5), 3-byte prefix
        ];
        assert!(is_idr_access_unit(&au));
    }

    #[test]
    fn p_frame_is_not_keyframe() {
        let au = vec![0, 0, 0, 1, 0x67, 0xAA, 0, 0, 1, 0x41, 0xBB];
        assert!(!is_idr_access_unit(&au));
    }

    #[test]
    fn trailing_idr_is_found() {
        let mut au = vec![0u8; 64];
        au.extend_from_slice(&[0, 0, 1, 0x65, 0xCC]);
        assert!(is_idr_access_unit(&au));
    }
}
