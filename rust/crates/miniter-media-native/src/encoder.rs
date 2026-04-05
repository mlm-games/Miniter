//! Encode RGBA frames → H.264 NAL units via OpenH264.

use crate::frame::RgbaFrame;
use openh264::encoder::{Encoder, EncoderConfig, FrameType};
use openh264::formats::YUVBuffer;

#[derive(Debug, thiserror::Error)]
pub enum EncodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("OpenH264: {0}")]
    OpenH264(#[from] openh264::Error),
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
    #[error("Encoder skipped frame {frame_index}")]
    SkippedFrame { frame_index: u32 },
    #[error("Encoder produced empty output for frame {frame_index}")]
    EmptyFrame { frame_index: u32 },
}

pub enum EncodedVideoOutput {
    Sample { bytes: Vec<u8>, is_keyframe: bool },
    Skipped,
}

pub struct VideoEncodeSession {
    encoder: Encoder,
    width: u32,
    height: u32,
    frame_index: u32,
}

impl VideoEncodeSession {
    pub fn new(width: u32, height: u32, bitrate_bps: u32, fps: f32) -> Result<Self, EncodeError> {
        if width == 0 || height == 0 || width % 2 != 0 || height % 2 != 0 {
            return Err(EncodeError::InvalidDimensions);
        }

        let config = EncoderConfig::new()
            .bitrate(openh264::encoder::BitRate::from_bps(bitrate_bps))
            .max_frame_rate(openh264::encoder::FrameRate::from_hz(fps))
            .skip_frames(false);

        let encoder = Encoder::with_api_config(openh264::OpenH264API::from_source(), config)?;

        Ok(Self {
            encoder,
            width,
            height,
            frame_index: 0,
        })
    }

    pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
        let idx = self.frame_index;
        self.frame_index += 1;

        let rgb = rgba_to_rgb(&frame.data);

        let mut yuv_buf = YUVBuffer::new(self.width as usize, self.height as usize);
        yuv_buf.read_rgb8(RgbSlice {
            data: rgb,
            width: self.width as usize,
            height: self.height as usize,
        });

        let bitstream = self.encoder.encode(&yuv_buf)?;

        match bitstream.frame_type() {
            FrameType::Skip => Ok(EncodedVideoOutput::Skipped),
            FrameType::Invalid => Err(EncodeError::OpenH264(openh264::Error::msg(
                "encoder returned invalid frame type",
            ))),
            _ => {
                let bytes = bitstream.to_vec();
                if bytes.is_empty() {
                    return Err(EncodeError::EmptyFrame { frame_index: idx });
                }
                let is_keyframe = matches!(bitstream.frame_type(), FrameType::IDR | FrameType::I);
                Ok(EncodedVideoOutput::Sample { bytes, is_keyframe })
            }
        }
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
