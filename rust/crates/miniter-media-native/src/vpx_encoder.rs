//! VP9 encoding via vpx-encode crate (wrapping libvpx).

use crate::frame::RgbaFrame;

#[derive(Debug, thiserror::Error)]
pub enum Vp9EncodeError {
    #[error("vpx-encode: {0}")]
    Vpx(#[from] vpx_encode::Error),
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
    #[error("No encoded data returned for frame")]
    NoData,
}

pub struct Vp9EncodeSession {
    encoder: vpx_encode::Encoder,
    width: u32,
    height: u32,
    pts: i64,
}

impl Vp9EncodeSession {
    pub fn new(
        width: u32,
        height: u32,
        bitrate_kbps: u32,
        fps: f64,
    ) -> Result<Self, Vp9EncodeError> {
        if width == 0 || height == 0 || width % 2 != 0 || height % 2 != 0 {
            return Err(Vp9EncodeError::InvalidDimensions);
        }

        let config = vpx_encode::Config {
            width,
            height,
            timebase: [1, 1_000_000_000],
            bitrate: bitrate_kbps,
            codec: vpx_encode::VideoCodecId::VP9,
        };

        let encoder = config.new_encoder()?;

        Ok(Self {
            encoder,
            width,
            height,
            pts: 0,
        })
    }

    pub fn encode_frame(
        &mut self,
        frame: &RgbaFrame,
        frame_duration_ns: i64,
    ) -> Result<Vec<(Vec<u8>, bool)>, Vp9EncodeError> {
        let yuv = rgba_to_i420(&frame.data, self.width as usize, self.height as usize);

        let packets = self.encoder.encode(self.pts, &yuv)?;
        self.pts += frame_duration_ns;

        let result: Vec<(Vec<u8>, bool)> = packets
            .into_iter()
            .map(|pkt| (pkt.data.to_vec(), pkt.key))
            .collect();

        Ok(result)
    }

    pub fn flush(&mut self) -> Result<Vec<(Vec<u8>, bool)>, Vp9EncodeError> {
        let packets = self.encoder.finish()?;

        let result: Vec<(Vec<u8>, bool)> = packets
            .into_iter()
            .map(|pkt| (pkt.data.to_vec(), pkt.key))
            .collect();

        Ok(result)
    }

    pub fn width(&self) -> u32 {
        self.width
    }

    pub fn height(&self) -> u32 {
        self.height
    }
}

fn rgba_to_i420(rgba: &[u8], width: usize, height: usize) -> Vec<u8> {
    let y_size = width * height;
    let cw = width / 2;
    let ch = height / 2;
    let uv_size = cw * ch;

    let mut yuv = vec![0u8; y_size + uv_size * 2];
    let (y_plane, rest) = yuv.split_at_mut(y_size);
    let (u_plane, v_plane) = rest.split_at_mut(uv_size);

    for row in 0..height {
        for col in 0..width {
            let base = (row * width + col) * 4;
            let r = rgba[base] as f32;
            let g = rgba[base + 1] as f32;
            let b = rgba[base + 2] as f32;

            let yy = (0.299 * r + 0.587 * g + 0.114 * b).clamp(0.0, 255.0) as u8;
            y_plane[row * width + col] = yy;

            if row % 2 == 0 && col % 2 == 0 {
                let u = (-0.168736 * r - 0.331264 * g + 0.5 * b + 128.0).clamp(0.0, 255.0) as u8;
                let v = (0.5 * r - 0.418688 * g - 0.081312 * b + 128.0).clamp(0.0, 255.0) as u8;
                let ci = (row / 2) * cw + col / 2;
                u_plane[ci] = u;
                v_plane[ci] = v;
            }
        }
    }

    yuv
}
