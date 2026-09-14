//! Encode RGBA frames -> AV1 bitstream via rav1e.

use std::sync::Arc;

use crate::frame::{MatrixCoeffs, RgbaFrame};
use crate::yuv::rgba_to_yuv420;
use rav1e::prelude::*;

const MIN_DIM: u32 = 16;

/// Keyframe interval for seeking/thumbnailing: one keyframe ≈ every 2 s of
/// video, clamped to [30, 240] frames.
///
/// Equal min/max forces a deterministic grid. rav1e's defaults otherwise emit
/// only sparse scene-cut keyframes (e.g. 3 in 300 frames), and OS file
/// managers (Nautilus via ffmpegthumbnailer, …) fail to seek such files —
/// falling back to frame 0, which is legitimately black in fade-from-black
/// projects — so exports show no thumbnail. See `tests/export_thumbnail_regression.rs`.
pub(crate) fn key_frame_interval(fps: f64) -> u64 {
    if !fps.is_finite() || fps <= 0.0 {
        return 60;
    }
    (2.0 * fps).round().clamp(30.0, 240.0) as u64
}

fn rav1e_color_description(matrix: MatrixCoeffs) -> ColorDescription {
    let (primaries, transfer, matrix_coefficients) = match matrix {
        MatrixCoeffs::Bt601 => (
            ColorPrimaries::BT601,
            TransferCharacteristics::BT601,
            MatrixCoefficients::BT601,
        ),
        MatrixCoeffs::Bt709 => (
            ColorPrimaries::BT709,
            TransferCharacteristics::BT709,
            MatrixCoefficients::BT709,
        ),
        MatrixCoeffs::Bt2020Ncl => (
            ColorPrimaries::BT2020,
            TransferCharacteristics::BT2020_10Bit,
            MatrixCoefficients::BT2020NCL,
        ),
        MatrixCoeffs::Identity => (
            ColorPrimaries::BT709,
            TransferCharacteristics::SRGB,
            MatrixCoefficients::Identity,
        ),
    };
    ColorDescription {
        color_primaries: primaries,
        transfer_characteristics: transfer,
        matrix_coefficients,
    }
}

#[derive(Debug, thiserror::Error)]
pub enum Av1EncodeError {
    #[error("Invalid dimensions: width and height must be even and >= 16")]
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
    matrix: MatrixCoeffs,
    fps: f64,
    seq_header: Vec<u8>,
    flushed: bool,
    /// PTS for submitted frames in display order. Consumed as rav1e emits
    /// packets (`packet_with_pts` drains through `input_frameno`), so the
    /// queue stays bounded by in-flight frames instead of growing with the
    /// whole export.
    pending_frames: Vec<u64>,
}

impl Av1EncodeSession {
    pub fn new(
        width: u32,
        height: u32,
        fps: f64,
        bitrate_kbps: u32,
        matrix: MatrixCoeffs,
        speed_preset: u8,
    ) -> Result<Self, Av1EncodeError> {
        if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(Av1EncodeError::InvalidDimensions);
        }
        if width < MIN_DIM || height < MIN_DIM {
            return Err(Av1EncodeError::InvalidDimensions);
        }

        let enc_w = width as usize;
        let enc_h = height as usize;

        let (fps_num, fps_den) = crate::export_shared::fps_to_rational(fps);
        let preset = speed_preset.min(10);
        let mut enc = EncoderConfig::with_speed_preset(preset);
        enc.width = enc_w;
        enc.height = enc_h;
        enc.bit_depth = 8;
        enc.chroma_sampling = ChromaSampling::Cs420;
        enc.time_base = Rational::new(fps_den as u64, fps_num as u64);
        enc.bitrate = bitrate_kbps.saturating_mul(1000).min(i32::MAX as u32) as i32;
        let keyint = key_frame_interval(fps);
        enc.min_key_frame_interval = keyint;
        enc.max_key_frame_interval = keyint;
        enc.color_description = Some(rav1e_color_description(matrix));
        enc.pixel_range = PixelRange::Full;

        let cfg = Config::new().with_encoder_config(enc);
        let ctx: Context<u8> = cfg.new_context()?;
        let seq_header = ctx.container_sequence_header();

        Ok(Self {
            ctx,
            enc_width: enc_w,
            enc_height: enc_h,
            matrix,
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
        let matrix = self.matrix;

        let (y_plane, u_plane, v_plane) = if frame_w != self.enc_width || frame_h != self.enc_height
        {
            let mut padded = vec![16u8; self.enc_width * self.enc_height];
            let mut padded_u = vec![128u8; self.enc_width / 2 * self.enc_height / 2];
            let mut padded_v = vec![128u8; self.enc_width / 2 * self.enc_height / 2];
            let (y_src, u_src, v_src) = rgba_to_yuv420(&frame.data, frame_w, frame_h, matrix);
            let copy_w = frame_w.min(self.enc_width);
            let copy_h = frame_h.min(self.enc_height);
            let off_x = self.enc_width.saturating_sub(frame_w) / 2;
            let off_y = self.enc_height.saturating_sub(frame_h) / 2;
            for row in 0..copy_h {
                let src = &y_src[row * frame_w..row * frame_w + copy_w];
                let dst = &mut padded[(row + off_y) * self.enc_width + off_x..][..copy_w];
                dst.copy_from_slice(src);
            }
            let cw_src = frame_w / 2;
            let cw_dst = self.enc_width / 2;
            let ch = copy_h / 2;
            let off_x_c = off_x / 2;
            let off_y_c = off_y / 2;
            for row in 0..ch {
                let s = &u_src[row * cw_src..row * cw_src + copy_w / 2];
                let d = &mut padded_u[(row + off_y_c) * cw_dst + off_x_c..][..copy_w / 2];
                d.copy_from_slice(s);
                let s = &v_src[row * cw_src..row * cw_src + copy_w / 2];
                let d = &mut padded_v[(row + off_y_c) * cw_dst + off_x_c..][..copy_w / 2];
                d.copy_from_slice(s);
            }
            (padded, padded_u, padded_v)
        } else {
            rgba_to_yuv420(&frame.data, self.enc_width, self.enc_height, matrix)
        };

        let mut f = self.ctx.new_frame();
        f.planes[0].copy_from_raw_u8(&y_plane, self.enc_width, 1);
        f.planes[1].copy_from_raw_u8(&u_plane, self.enc_width / 2, 1);
        f.planes[2].copy_from_raw_u8(&v_plane, self.enc_width / 2, 1);

        self.pending_frames.push(frame.pts_us.max(0) as u64);

        self.ctx
            .send_frame(Arc::new(f))
            .map_err(Av1EncodeError::Encoder)?;

        let mut packets = Vec::new();
        loop {
            match self.ctx.receive_packet() {
                Ok(packet) => {
                    let idx = packet.input_frameno as usize;
                    let pts = if idx < self.pending_frames.len() {
                        self.pending_frames.drain(..=idx).next_back().unwrap_or(0)
                    } else {
                        ((packet.input_frameno as f64 / self.fps) * 1_000_000.0).max(0.0) as u64
                    };
                    packets.push(Av1Packet {
                        pts,
                        data: packet.data,
                        is_keyframe: packet.frame_type.all_intra(),
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

    /// Attach the submit-time PTS for `packet.input_frameno`, consuming the
    /// corresponding queue entry. Shared by `encode_frame` and `finish`.
    /// `input_frameno` is a dense 0-based index over accepted frames
    /// (`send_frame` never skips), so drain through it: entries before `idx`
    /// belong to already-returned packets and the head IS this packet.
    /// Queue length also stays bounded (≤ in-flight frames) instead of
    /// growing with the whole export.
    fn packet_with_pts(&mut self, packet: Packet<u8>) -> Av1Packet {
        let idx = packet.input_frameno as usize;
        let pts = if idx < self.pending_frames.len() {
            self.pending_frames.drain(..=idx).next_back().unwrap_or(0)
        } else {
            ((packet.input_frameno as f64 / self.fps) * 1_000_000.0).max(0.0) as u64
        };
        Av1Packet {
            pts,
            data: packet.data,
            is_keyframe: packet.frame_type.all_intra(),
        }
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
                    packets.push(self.packet_with_pts(packet));
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

#[cfg(test)]
mod tests {
    use super::key_frame_interval;

    #[test]
    fn keyframe_grid_is_two_seconds_clamped() {
        assert_eq!(key_frame_interval(30.0), 60);
        assert_eq!(key_frame_interval(60.0), 120);
        assert_eq!(key_frame_interval(25.0), 50);
        // Degenerate input falls back to a sane default instead of
        // disabling periodic keyframes (which breaks seeking/thumbnails).
        assert_eq!(key_frame_interval(0.0), 60);
        assert_eq!(key_frame_interval(f64::NAN), 60);
        assert_eq!(key_frame_interval(-5.0), 60);
    }

    #[test]
    fn keyframe_grid_is_bounded() {
        // Very low fps must not collapse to all-intra; very high fps must
        // not starve keyframes beyond a seekable horizon.
        assert_eq!(key_frame_interval(5.0), 30);
        assert_eq!(key_frame_interval(240.0), 240);
        assert_eq!(key_frame_interval(1000.0), 240);
    }
}
