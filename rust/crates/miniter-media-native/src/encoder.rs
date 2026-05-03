//! H.264 video encoder.
//!
//! Uses less-avc only for now ( since it works on all platforms including wasm, but the output size is questionable).

use crate::frame::RgbaFrame;
use crate::yuv::rgba_to_yuv420;
use less_avc::ycbcr_image::{DataPlane, Planes, YCbCrImage};
use less_avc::{BitDepth, LessEncoder};

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("less-avc: {0}")]
    LessAvc(String),
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
    #[error("Encoder skipped frame {frame_index}")]
    SkippedFrame { frame_index: u32 },
    #[error("Encoder produced empty output for frame {frame_index}")]
    EmptyFrame { frame_index: u32 },
}

#[derive(Debug)]
pub enum EncodedVideoOutput {
    Sample { bytes: Vec<u8>, is_keyframe: bool },
    Skipped,
}

pub struct VideoEncodeSession {
    encoder: Option<LessEncoder>,
    width: u32,
    height: u32,
    y_stride: usize,
    uv_stride: usize,
    y_rows: usize,
    uv_rows: usize,
    frame_index: u32,
}

fn next_multiple(value: usize, base: usize) -> usize {
    value.div_ceil(base) * base
}

impl VideoEncodeSession {
    pub fn new(width: u32, height: u32, _bitrate_bps: u32, _fps: f32) -> Result<Self, EncodeError> {
        if width == 0 || height == 0 || width % 2 != 0 || height % 2 != 0 {
            return Err(EncodeError::InvalidDimensions);
        }
        let y_stride = next_multiple(width as usize, 16);
        let uv_stride = next_multiple((width as usize) / 2, 8);
        let y_rows = next_multiple(height as usize, 16);
        let uv_rows = next_multiple((height as usize) / 2, 8);
        Ok(Self {
            encoder: None,
            width,
            height,
            y_stride,
            uv_stride,
            y_rows,
            uv_rows,
            frame_index: 0,
        })
    }

    pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
        let idx = self.frame_index;
        self.frame_index += 1;
        if frame.width != self.width || frame.height != self.height {
            return Err(EncodeError::InvalidDimensions);
        }

        let (y_plane, u_plane, v_plane) = self.to_planes(frame)?;
        let image = YCbCrImage {
            planes: Planes::YCbCr((
                DataPlane {
                    data: &y_plane,
                    stride: self.y_stride,
                    bit_depth: BitDepth::Depth8,
                },
                DataPlane {
                    data: &u_plane,
                    stride: self.uv_stride,
                    bit_depth: BitDepth::Depth8,
                },
                DataPlane {
                    data: &v_plane,
                    stride: self.uv_stride,
                    bit_depth: BitDepth::Depth8,
                },
            )),
            width: self.width,
            height: self.height,
        };

        let bytes = if let Some(enc) = &mut self.encoder {
            enc.encode(&image)
                .map_err(|e| EncodeError::LessAvc(e.to_string()))?
                .to_annex_b_data()
        } else {
            let (init, enc) =
                LessEncoder::new(&image).map_err(|e| EncodeError::LessAvc(e.to_string()))?;
            self.encoder = Some(enc);
            init.into_iter()
                .flat_map(|nal| nal.to_annex_b_data())
                .collect()
        };

        if bytes.is_empty() {
            return Err(EncodeError::EmptyFrame { frame_index: idx });
        }
        Ok(EncodedVideoOutput::Sample {
            bytes,
            is_keyframe: true,
        })
    }

    pub fn width(&self) -> u32 {
        self.width
    }
    pub fn height(&self) -> u32 {
        self.height
    }

    fn to_planes(&self, frame: &RgbaFrame) -> Result<(Vec<u8>, Vec<u8>, Vec<u8>), EncodeError> {
        let (y_src, u_src, v_src) =
            rgba_to_yuv420(&frame.data, self.width as usize, self.height as usize);
        let y_sz = self
            .y_stride
            .checked_mul(self.y_rows)
            .ok_or(EncodeError::InvalidDimensions)?;
        let uv_sz = self
            .uv_stride
            .checked_mul(self.uv_rows)
            .ok_or(EncodeError::InvalidDimensions)?;
        let mut y = vec![0u8; y_sz];
        let mut u = vec![128u8; uv_sz];
        let mut v = vec![128u8; uv_sz];
        let w = self.width as usize;
        let h = self.height as usize;
        let cw = w / 2;
        let ch = h / 2;
        for row in 0..h {
            y[row * self.y_stride..row * self.y_stride + w]
                .copy_from_slice(&y_src[row * w..(row + 1) * w]);
        }
        for row in 0..ch {
            u[row * self.uv_stride..row * self.uv_stride + cw]
                .copy_from_slice(&u_src[row * cw..(row + 1) * cw]);
            v[row * self.uv_stride..row * self.uv_stride + cw]
                .copy_from_slice(&v_src[row * cw..(row + 1) * cw]);
        }
        Ok((y, u, v))
    }
}
