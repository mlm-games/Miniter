use crate::decoders::{AV1_FOURCC, AV1_IVF_FOURCC};
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;
use rav1d_safe::{Decoder, Frame, Planes, Planes8};

pub struct Av1Backend {
    decoder: Decoder,
}

impl Av1Backend {
    pub fn new() -> Result<Self, DecodeError> {
        let decoder = Decoder::new()
            .map_err(|e| DecodeError::Av1(format!("Failed to create decoder: {e}")))?;
        Ok(Self { decoder })
    }

    fn frame_to_rgba(frame: &Frame) -> Result<RgbaFrame, DecodeBackendError> {
        let (w, h) = (frame.width() as usize, frame.height() as usize);

        let rgba = match frame.planes() {
            Planes::Depth8(planes) => Self::planes8_to_rgba(&planes, w, h),
            Planes::Depth16(_) => {
                return Err(DecodeBackendError::Other(
                    "AV1 16-bit depth not supported".into(),
                ));
            }
        };

        Ok(RgbaFrame {
            width: frame.width() as u32,
            height: frame.height() as u32,
            data: rgba,
            pts_us: 0,
        })
    }

    fn planes8_to_rgba(planes: &Planes8, w: usize, h: usize) -> Vec<u8> {
        let y_plane = planes.y();
        let u_plane = planes.u().unwrap_or_else(|| planes.y());
        let v_plane = planes.v().unwrap_or_else(|| planes.y());

        let mut rgba = vec![0u8; w * h * 4];

        for row in 0..h {
            let y_row = y_plane.row(row);
            let u_row = u_plane.row(row / 2);
            let v_row = v_plane.row(row / 2);

            for col in 0..w {
                let yy = y_row[col] as f32;
                let uu = u_row[col / 2] as f32 - 128.0;
                let vv = v_row[col / 2] as f32 - 128.0;

                let r = (yy + 1.402 * vv).clamp(0.0, 255.0) as u8;
                let g = (yy - 0.344136 * uu - 0.714136 * vv).clamp(0.0, 255.0) as u8;
                let b = (yy + 1.772 * uu).clamp(0.0, 255.0) as u8;

                let base = (row * w + col) * 4;
                rgba[base] = r;
                rgba[base + 1] = g;
                rgba[base + 2] = b;
                rgba[base + 3] = 255;
            }
        }

        rgba
    }
}

use crate::decoders::DecodeError;

impl VideoDecoderBackend for Av1Backend {
    fn name(&self) -> &'static str {
        "rav1d-safe (AV1)"
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        fourcc == AV1_FOURCC || fourcc == AV1_IVF_FOURCC
    }

    fn decode_frame(
        &mut self,
        data: &[u8],
        pts_us: i64,
        _is_sync: bool,
    ) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        match self.decoder.decode(data) {
            Ok(Some(frame)) => {
                let rgba = Self::frame_to_rgba(&frame)?;
                Ok(Some(RgbaFrame { pts_us, ..rgba }))
            }
            Ok(None) => Ok(None),
            Err(rav1d_safe::Error::NeedMoreData) => Ok(None),
            Err(e) => Err(DecodeBackendError::Other(format!("rav1d: {e}"))),
        }
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        match self.decoder.flush() {
            Ok(frames) if !frames.is_empty() => {
                let rgba = Self::frame_to_rgba(&frames[0])?;
                Ok(Some(rgba))
            }
            Ok(_) => Ok(None),
            Err(e) => Err(DecodeBackendError::Other(format!("rav1d flush: {e}"))),
        }
    }

    fn reset(&mut self) {
        // rav1d-safe decoder does not expose a reset; recreate.
        if let Ok(dec) = Decoder::new() {
            self.decoder = dec;
        }
    }
}
