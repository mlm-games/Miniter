//! Encode RGBA frames → H.264 NAL units via OpenH264.

use crate::frame::RgbaFrame;
use openh264::encoder::{Encoder, EncoderConfig};
use openh264::formats::YUVBuffer;

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("OpenH264: {0}")]
    OpenH264(#[from] openh264::Error),
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
}

pub struct VideoEncodeSession {
    encoder: Encoder,
    width: u32,
    height: u32,
}

impl VideoEncodeSession {
    pub fn new(width: u32, height: u32, bitrate_bps: u32, fps: f32) -> Result<Self, EncodeError> {
        if width == 0 || height == 0 || width % 2 != 0 || height % 2 != 0 {
            return Err(EncodeError::InvalidDimensions);
        }

        let config = EncoderConfig::new()
            .set_bitrate_bps(bitrate_bps)
            .max_frame_rate(fps);

        let encoder = Encoder::with_api_config(openh264::OpenH264API::from_source(), config)?;

        Ok(Self {
            encoder,
            width,
            height,
        })
    }

    pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<u8>, EncodeError> {
        let rgb = rgba_to_rgb(&frame.data);

        let mut yuv_buf = YUVBuffer::new(self.width as usize, self.height as usize);
        yuv_buf.read_rgb8(RgbSlice {
            data: rgb,
            width: self.width as usize,
            height: self.height as usize,
        });

        let bitstream = self.encoder.encode(&yuv_buf)?;

        let mut output = Vec::new();
        for layer_idx in 0..bitstream.num_layers() {
            if let Some(layer) = bitstream.layer(layer_idx) {
                for nal_idx in 0..layer.nal_count() {
                    if let Some(nal) = layer.nal_unit(nal_idx) {
                        output.extend_from_slice(nal);
                    }
                }
            }
        }

        Ok(output)
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }
}

struct RgbSlice {
    data: Vec<u8>,
    width: usize,
    height: usize,
}

impl openh264::formats::RGBSource for RgbSlice {
    fn dimensions(&self) -> (usize, usize) {
        (self.width, self.height)
    }

    fn pixel_f32(&self, x: usize, y: usize) -> (f32, f32, f32) {
        let idx = (y * self.width + x) * 3;
        (
            self.data[idx] as f32,
            self.data[idx + 1] as f32,
            self.data[idx + 2] as f32,
        )
    }
}

impl openh264::formats::RGB8Source for RgbSlice {
    fn dimensions_padded(&self) -> (usize, usize) {
        (self.width, self.height)
    }

    fn rgb8_data(&self) -> &[u8] {
        &self.data
    }
}

fn rgba_to_rgb(rgba: &[u8]) -> Vec<u8> {
    let mut rgb = Vec::with_capacity((rgba.len() / 4) * 3);
    for px in rgba.chunks_exact(4) {
        rgb.push(px[0]);
        rgb.push(px[1]);
        rgb.push(px[2]);
    }
    rgb
}
