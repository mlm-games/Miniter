//! H.264 video encoder.
//!
//! Uses rusty_h264 (pure Rust, no C): P-frames, CABAC, adaptive quantization,
//! and average-bitrate rate control. Replaces the previous less-avc backend,
//! which was fixed all-intra with no rate-control API — the Quality slider
//! was a no-op on the software path and files were ~10x larger.
//!
//! `rusty_h264` may buffer input (mb-tree lookahead) and return empty bytes
//! for a call, so this session queues `(pts, bytes)` pairs and drains them
//! through [`EncodedVideoOutput`]. `finish()` flushes the tail; dropping the
//! session without finishing would panic inside the encoder, so callers must
//! drive every frame through `encode_frame` then `finish`.

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
    #[error("Encoder skipped frame {frame_index}")]
    SkippedFrame { frame_index: u32 },
    #[error("Encoder produced empty output for frame {frame_index}")]
    EmptyFrame { frame_index: u32 },
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
    /// Frames submitted but not yet emitted (lookahead buffering).
    pending_pts: std::collections::VecDeque<i64>,
}

impl VideoEncodeSession {
    pub fn new(
        width: u32,
        height: u32,
        bitrate_bps: u32,
        fps: f32,
        encode_effort: u8,
    ) -> Result<Self, EncodeError> {
        if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(EncodeError::InvalidDimensions);
        }
        let mut cfg = rusty_h264_encoder::EncoderConfig::new(width as usize, height as usize);
        cfg.gop_size = 60;
        cfg.scenecut = 0;
        cfg.mbtree = false;
        // Same 0..10 scale as the AV1 speed preset: 0-3 Quality, 4-7
        // Balanced (default), 8-10 Fast.
        cfg.preset = match encode_effort.min(10) {
            0..=3 => rusty_h264_encoder::Preset::Quality,
            4..=7 => rusty_h264_encoder::Preset::Balanced,
            _ => rusty_h264_encoder::Preset::Fast,
        };
        if bitrate_bps >= 500_000 {
            cfg.bitrate = bitrate_bps;
            cfg.framerate = if fps.is_finite() && fps > 0.0 {
                fps
            } else {
                30.0
            };
        } else {
            cfg.bitrate = 0;
            cfg.qp = 26;
        }
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
        let idx = self.frame_index;
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
        if bytes.is_empty() {
            return Err(EncodeError::EmptyFrame { frame_index: idx });
        }
        Ok(EncodedVideoOutput::Sample {
            is_keyframe: is_idr_access_unit(&bytes),
            bytes,
            pts_us: pts,
        })
    }

    /// Flush lookahead tail. Returns drained samples in decode order.
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
    while i + 4 < annex_b.len() {
        let (header_at, header_len) = if annex_b[i..].starts_with(&[0, 0, 0, 1]) {
            (i + 4, 4)
        } else if annex_b[i..].starts_with(&[0, 0, 1]) {
            (i + 3, 3)
        } else {
            i += 1;
            continue;
        };
        if header_at < annex_b.len() && annex_b[header_at] & 0x1F == 5 {
            return true;
        }
        let _ = header_len;
        i = header_at + 1;
    }
    false
}
