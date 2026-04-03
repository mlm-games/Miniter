//! Encode RGBA frames → AV1 bitstream via rav1e.

use crate::frame::RgbaFrame;
use crate::yuv::rgba_to_yuv420;
use rav1e::prelude::*;

#[derive(Debug, thiserror::Error)]
pub enum Av1EncodeError {
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
    #[error("rav1e config error")]
    InvalidConfig,
    #[error("rav1e encoder error: {0:?}")]
    Encoder(EncoderStatus),
}

pub struct Av1Packet {
    pub data: Vec<u8>,
    pub is_keyframe: bool,
    pub pts: u64,
}

pub struct Av1EncodeSession {
    ctx: Context<u8>,
    width: usize,
    height: usize,
    seq_header: Vec<u8>,
    flushed: bool,
}

impl Av1EncodeSession {
    pub fn new(
        width: u32,
        height: u32,
        fps: u32,
        bitrate_kbps: u32,
    ) -> Result<Self, Av1EncodeError> {
        if width == 0 || height == 0 || width % 2 != 0 || height % 2 != 0 {
            return Err(Av1EncodeError::InvalidDimensions);
        }

        let mut enc = EncoderConfig::with_speed_preset(6);
        enc.width = width as usize;
        enc.height = height as usize;
        enc.bit_depth = 8;
        enc.chroma_sampling = ChromaSampling::Cs420;
        enc.time_base = Rational::new(1, fps as u64);
        enc.bitrate = bitrate_kbps as i32;
        enc.low_latency = true;

        let cfg = Config::new().with_encoder_config(enc);
        let ctx: Context<u8> = cfg
            .new_context()
            .map_err(|_| Av1EncodeError::InvalidConfig)?;
        let seq_header = ctx.container_sequence_header();

        Ok(Self {
            ctx,
            width: width as usize,
            height: height as usize,
            seq_header,
            flushed: false,
        })
    }

    pub fn sequence_header(&self) -> &[u8] {
        &self.seq_header
    }

    pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<Av1Packet>, Av1EncodeError> {
        let mut rav1e_frame = self.ctx.new_frame();

        let (y_plane, u_plane, v_plane) = rgba_to_yuv420(&frame.data, self.width, self.height);

        let stride_y = self.width;
        let stride_uv = self.width / 2;

        rav1e_frame.planes[0].copy_from_raw_u8(&y_plane, stride_y, 1);
        rav1e_frame.planes[1].copy_from_raw_u8(&u_plane, stride_uv, 1);
        rav1e_frame.planes[2].copy_from_raw_u8(&v_plane, stride_uv, 1);

        self.ctx
            .send_frame(rav1e_frame)
            .map_err(Av1EncodeError::Encoder)?;

        self.drain_packets()
    }

    pub fn finish(&mut self) -> Result<Vec<Av1Packet>, Av1EncodeError> {
        if !self.flushed {
            self.ctx.flush();
            self.flushed = true;
        }
        self.drain_packets()
    }

    fn drain_packets(&mut self) -> Result<Vec<Av1Packet>, Av1EncodeError> {
        let mut out = Vec::new();

        loop {
            match self.ctx.receive_packet() {
                Ok(packet) => {
                    out.push(Av1Packet {
                        pts: packet.input_frameno,
                        data: packet.data,
                        is_keyframe: matches!(packet.frame_type, FrameType::KEY),
                    });
                }
                Err(EncoderStatus::Encoded) => {}
                Err(EncoderStatus::NeedMoreData) | Err(EncoderStatus::LimitReached) => break,
                Err(e) => return Err(Av1EncodeError::Encoder(e)),
            }
        }

        Ok(out)
    }
}
