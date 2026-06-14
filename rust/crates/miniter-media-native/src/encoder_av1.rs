//! Encode RGBA frames → AV1 bitstream via rav1e.

use std::sync::Arc;

use crate::frame::RgbaFrame;
use crate::yuv::rgba_to_yuv420;
use rav1e::prelude::*;

const MIN_DIM: u32 = 16;

#[derive(Debug, thiserror::Error)]
pub enum Av1EncodeError {
    #[error("Invalid dimensions: width and height must be > 0 and even")]
    InvalidDimensions,
    #[error("rav1e config error: {0}")]
    InvalidConfig(#[from] rav1e::InvalidConfig),
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
    enc_width: usize,
    enc_height: usize,
    fps: f64,
    seq_header: Vec<u8>,
    flushed: bool,
    pending_frames: Vec<u64>,
}

impl Av1EncodeSession {
    pub fn new(
        width: u32,
        height: u32,
        fps: f64,
        bitrate_kbps: u32,
    ) -> Result<Self, Av1EncodeError> {
        if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(Av1EncodeError::InvalidDimensions);
        }

        let enc_w = width.max(MIN_DIM) as usize;
        let enc_h = height.max(MIN_DIM) as usize;

        let fps_u32 = fps.round().max(1.0) as u32;
        let mut enc = EncoderConfig::with_speed_preset(10);
        enc.width = enc_w;
        enc.height = enc_h;
        enc.bit_depth = 8;
        enc.chroma_sampling = ChromaSampling::Cs420;
        enc.time_base = Rational::new(1, fps_u32 as u64);
        enc.bitrate = bitrate_kbps as i32;
        enc.min_key_frame_interval = 0;
        enc.max_key_frame_interval = 60;

        let cfg = Config::new().with_encoder_config(enc);
        let ctx: Context<u8> = cfg.new_context()?;
        let seq_header = ctx.container_sequence_header();

        Ok(Self {
            ctx,
            enc_width: enc_w,
            enc_height: enc_h,
            fps,
            seq_header,
            flushed: false,
            pending_frames: Vec::new(),
        })
    }

    pub fn sequence_header(&self) -> &[u8] {
        &self.seq_header
    }

    pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<Av1Packet>, Av1EncodeError> {
        let frame_w = frame.width as usize;
        let frame_h = frame.height as usize;

        let (y_plane, u_plane, v_plane) = if frame_w != self.enc_width || frame_h != self.enc_height
        {
            let stride = self.enc_width * 4;
            let mut padded = vec![0u8; self.enc_width * self.enc_height * 4];
            for row in 0..frame_h.min(self.enc_height) {
                let src = &frame.data[row * frame_w * 4..][..frame_w * 4];
                let dst = &mut padded[row * stride..][..frame_w * 4];
                dst.copy_from_slice(src);
            }
            rgba_to_yuv420(&padded, self.enc_width, self.enc_height)
        } else {
            rgba_to_yuv420(&frame.data, self.enc_width, self.enc_height)
        };

        let mut f = self.ctx.new_frame();
        f.planes[0].copy_from_raw_u8(&y_plane, self.enc_width, 1);
        f.planes[1].copy_from_raw_u8(&u_plane, self.enc_width / 2, 1);
        f.planes[2].copy_from_raw_u8(&v_plane, self.enc_width / 2, 1);

        self.pending_frames.push(frame.pts_us as u64);

        self.ctx
            .send_frame(Arc::new(f))
            .map_err(Av1EncodeError::Encoder)?;

        let mut packets = Vec::new();
        loop {
            match self.ctx.receive_packet() {
                Ok(packet) => {
                    let idx = packet.input_frameno as usize;
                    let pts = if idx < self.pending_frames.len() {
                        self.pending_frames[idx]
                    } else {
                        ((packet.input_frameno as f64 / self.fps) * 1_000_000.0) as u64
                    };
                    packets.push(Av1Packet {
                        pts,
                        data: packet.data,
                        is_keyframe: matches!(packet.frame_type, FrameType::KEY),
                    });
                }
                Err(EncoderStatus::Encoded) => continue,
                Err(EncoderStatus::NeedMoreData)
                | Err(EncoderStatus::LimitReached)
                | Err(EncoderStatus::EnoughData) => break,
                Err(e) => return Err(Av1EncodeError::Encoder(e)),
            }
        }

        Ok(packets)
    }

    pub fn finish(&mut self) -> Result<Vec<Av1Packet>, Av1EncodeError> {
        if !self.flushed {
            self.ctx.flush();
            self.flushed = true;
        }
        let mut packets = Vec::new();
        loop {
            match self.ctx.receive_packet() {
                Ok(packet) => {
                    let idx = packet.input_frameno as usize;
                    let pts = if idx < self.pending_frames.len() {
                        self.pending_frames[idx]
                    } else {
                        ((packet.input_frameno as f64 / self.fps) * 1_000_000.0) as u64
                    };
                    packets.push(Av1Packet {
                        pts,
                        data: packet.data,
                        is_keyframe: matches!(packet.frame_type, FrameType::KEY),
                    });
                }
                Err(EncoderStatus::Encoded) => continue,
                Err(EncoderStatus::NeedMoreData)
                | Err(EncoderStatus::LimitReached)
                | Err(EncoderStatus::EnoughData) => break,
                Err(e) => return Err(Av1EncodeError::Encoder(e)),
            }
        }
        Ok(packets)
    }
}
