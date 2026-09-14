use crate::encoder::{EncodedVideoOutput, VideoEncodeSession};
use crate::encoder_av1::Av1EncodeSession;
use crate::encoder_hw::HwEncodeSession;
use crate::frame::{MatrixCoeffs, RgbaFrame};
use std::sync::atomic::Ordering;

/// An encoded video packet produced by an encoder backend.
pub struct EncodedPacket {
    pub data: Vec<u8>,
    pub is_keyframe: bool,
    pub pts_us: u64,
    /// Decode timestamp for B-frame streams (AV1 SW). `None` = "no DTS"
    /// (H.264/HW paths are PTS==DTS in-order): the chunker mux loop uses
    /// `write_sample_at` for those and `write_sample_with_dts_at` otherwise.
    /// AV1 SW always stamps `Some` (see `Av1Packet::dts_us`).
    pub dts_us: Option<u64>,
}

/// Common interface for all video encoder backends (HW and SW).
pub trait EncoderBackend {
    fn name(&self) -> &'static str;

    /// Encode a single frame. May buffer the frame internally and return
    /// zero, one, or multiple completed packets.
    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String>;

    /// Flush any remaining buffered output after all frames have been submitted.
    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String>;

    /// Check for asynchronous errors (e.g., WebCodec error callback).
    fn check_error(&self) -> Option<String>;

    /// Optionally start an async flush (no-op for SW backends).
    fn start_flush(&mut self) {}

    #[cfg(target_arch = "wasm32")]
    fn take_flush_promise(&mut self) -> Option<js_sys::Promise> {
        None
    }
}

pub struct H264SwBackend {
    inner: VideoEncodeSession,
}

impl H264SwBackend {
    pub fn new(
        width: u32,
        height: u32,
        bitrate_kbps: u32,
        fps: f32,
        encode_effort: u8,
    ) -> Result<Self, String> {
        VideoEncodeSession::new(width, height, bitrate_kbps, fps, encode_effort)
            .map(|inner| Self { inner })
            .map_err(|e| format!("H.264 SW encoder init failed: {e}"))
    }
}

impl EncoderBackend for H264SwBackend {
    fn name(&self) -> &'static str {
        "H.264 SW (rusty_h264)"
    }

    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String> {
        match self
            .inner
            .encode_frame(frame)
            .map_err(|e| format!("H.264 encode failed: {e}"))?
        {
            EncodedVideoOutput::Sample {
                bytes,
                is_keyframe,
                pts_us,
            } => Ok(vec![EncodedPacket {
                data: bytes,
                is_keyframe,
                pts_us: pts_us.max(0) as u64,
                dts_us: None,
            }]),
            EncodedVideoOutput::Skipped => Ok(Vec::new()),
        }
    }

    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String> {
        Ok(self
            .inner
            .finish()
            .into_iter()
            .filter_map(|o| match o {
                EncodedVideoOutput::Sample {
                    bytes,
                    is_keyframe,
                    pts_us,
                } if !bytes.is_empty() => Some(EncodedPacket {
                    data: bytes,
                    is_keyframe,
                    pts_us: pts_us.max(0) as u64,
                    dts_us: None,
                }),
                _ => None,
            })
            .collect())
    }

    fn check_error(&self) -> Option<String> {
        None
    }
}

pub struct Av1SwBackend {
    inner: Av1EncodeSession,
}

impl Av1SwBackend {
    pub fn new(
        width: u32,
        height: u32,
        fps: f64,
        bitrate_kbps: u32,
        matrix: MatrixCoeffs,
        speed_preset: u8,
    ) -> Result<Self, String> {
        Av1EncodeSession::new(width, height, fps, bitrate_kbps, matrix, speed_preset)
            .map(|inner| Self { inner })
            .map_err(|e| format!("AV1 SW encoder init failed: {e}"))
    }
}

impl EncoderBackend for Av1SwBackend {
    fn name(&self) -> &'static str {
        "AV1 SW (rav1e)"
    }

    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String> {
        let packets = self
            .inner
            .encode_frame(frame)
            .map_err(|e| format!("AV1 encode failed: {e}"))?;
        Ok(packets
            .into_iter()
            .map(|p| EncodedPacket {
                data: p.data,
                is_keyframe: p.is_keyframe,
                pts_us: p.pts,
                dts_us: Some(p.dts_us),
            })
            .collect())
    }

    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String> {
        let packets = self
            .inner
            .finish()
            .map_err(|e| format!("AV1 finalize failed: {e}"))?;
        Ok(packets
            .into_iter()
            .map(|p| EncodedPacket {
                data: p.data,
                is_keyframe: p.is_keyframe,
                pts_us: p.pts,
                dts_us: Some(p.dts_us),
            })
            .collect())
    }

    fn check_error(&self) -> Option<String> {
        None
    }
}

#[cfg(feature = "hw-decoder")]
pub struct H264HwBackend {
    inner: HwEncodeSession,
}

#[cfg(feature = "hw-decoder")]
impl H264HwBackend {
    pub fn new(
        width: u32,
        height: u32,
        bitrate_kbps: u32,
        fps: f32,
        matrix: MatrixCoeffs,
    ) -> Result<Self, String> {
        HwEncodeSession::new(
            width,
            height,
            crate::export_shared::video_bitrate_bps(bitrate_kbps),
            fps,
            "video/avc",
            matrix,
        )
        .map(|inner| Self { inner })
        .map_err(|e| format!("HW H.264 encoder init failed: {e}"))
    }
}

#[cfg(feature = "hw-decoder")]
impl EncoderBackend for H264HwBackend {
    fn name(&self) -> &'static str {
        "H.264 HW (WebCodecs)"
    }

    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String> {
        self.inner
            .submit_frame(frame)
            .map_err(|e| format!("H.264 HW submit failed: {e}"))?;
        let completed = self
            .inner
            .drain_completed()
            .map_err(|e| format!("H.264 HW drain failed: {e}"))?;
        Ok(completed
            .into_iter()
            .filter_map(|f| {
                if f.bytes.is_empty() {
                    None
                } else {
                    Some(EncodedPacket {
                        data: f.bytes,
                        is_keyframe: f.is_keyframe,
                        pts_us: f.pts_us,
                        dts_us: None,
                    })
                }
            })
            .collect())
    }

    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String> {
        let completed = self
            .inner
            .drain_completed()
            .map_err(|e| format!("HW encoder final drain: {e}"))?;
        Ok(completed
            .into_iter()
            .filter_map(|f| {
                if f.bytes.is_empty() {
                    None
                } else {
                    Some(EncodedPacket {
                        data: f.bytes,
                        is_keyframe: f.is_keyframe,
                        pts_us: f.pts_us,
                        dts_us: None,
                    })
                }
            })
            .collect())
    }

    fn check_error(&self) -> Option<String> {
        self.inner.check_error().map(|e| format!("{e:?}"))
    }

    fn start_flush(&mut self) {
        self.inner.start_flush();
    }

    #[cfg(target_arch = "wasm32")]
    fn take_flush_promise(&mut self) -> Option<js_sys::Promise> {
        self.inner.take_flush_promise()
    }
}

#[cfg(feature = "hw-decoder")]
pub struct Av1HwBackend {
    inner: HwEncodeSession,
}

#[cfg(feature = "hw-decoder")]
impl Av1HwBackend {
    pub fn new(
        width: u32,
        height: u32,
        bitrate_kbps: u32,
        fps: f32,
        matrix: MatrixCoeffs,
    ) -> Result<Self, String> {
        HwEncodeSession::new(
            width,
            height,
            crate::export_shared::video_bitrate_bps(bitrate_kbps),
            fps,
            "video/av01",
            matrix,
        )
        .map(|inner| Self { inner })
        .map_err(|e| format!("HW AV1 encoder init failed: {e}"))
    }
}

#[cfg(feature = "hw-decoder")]
impl EncoderBackend for Av1HwBackend {
    fn name(&self) -> &'static str {
        "AV1 HW (WebCodecs)"
    }

    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String> {
        self.inner
            .submit_frame(frame)
            .map_err(|e| format!("AV1 HW submit failed: {e}"))?;
        let completed = self
            .inner
            .drain_completed()
            .map_err(|e| format!("AV1 HW drain failed: {e}"))?;
        Ok(completed
            .into_iter()
            .filter_map(|f| {
                if f.bytes.is_empty() {
                    None
                } else {
                    Some(EncodedPacket {
                        data: f.bytes,
                        is_keyframe: f.is_keyframe,
                        pts_us: f.pts_us,
                        dts_us: None,
                    })
                }
            })
            .collect())
    }

    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String> {
        let completed = self
            .inner
            .drain_completed()
            .map_err(|e| format!("HW AV1 final drain: {e}"))?;
        Ok(completed
            .into_iter()
            .filter_map(|f| {
                if f.bytes.is_empty() {
                    None
                } else {
                    Some(EncodedPacket {
                        data: f.bytes,
                        is_keyframe: f.is_keyframe,
                        pts_us: f.pts_us,
                        dts_us: None,
                    })
                }
            })
            .collect())
    }

    fn check_error(&self) -> Option<String> {
        self.inner.check_error().map(|e| format!("{e:?}"))
    }

    fn start_flush(&mut self) {
        self.inner.start_flush();
    }

    #[cfg(target_arch = "wasm32")]
    fn take_flush_promise(&mut self) -> Option<js_sys::Promise> {
        self.inner.take_flush_promise()
    }
}

/// Create an encoder backend, trying HW first if `hw_requested` is true.
/// Returns `Ok(Box<dyn EncoderBackend>)` or an error if no encoder can be created.
pub fn create_encoder_backend(
    format: miniter_domain::export::ExportFormat,
    width: u32,
    height: u32,
    bitrate_kbps: u32,
    fps: f64,
    hw_requested: bool,
    matrix: MatrixCoeffs,
    encode_effort: u8,
) -> Result<Box<dyn EncoderBackend>, String> {
    match format {
        miniter_domain::export::ExportFormat::Mp4
        | miniter_domain::export::ExportFormat::H264Mkv
        | miniter_domain::export::ExportFormat::Mov => {
            if hw_requested {
                #[cfg(feature = "hw-decoder")]
                match H264HwBackend::new(width, height, bitrate_kbps, fps as f32, matrix) {
                    Ok(enc) => return Ok(Box::new(enc)),
                    Err(e) => {
                        log::warn!("HW H.264 encoder init failed, falling back to SW: {e}");
                        crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
                    }
                }
                #[cfg(not(feature = "hw-decoder"))]
                { /* TODO: print no HW decoder feature, fall through to SW */ }
            }
            H264SwBackend::new(width, height, bitrate_kbps, fps as f32, encode_effort)
                .map(|enc| Box::new(enc) as Box<dyn EncoderBackend>)
        }
        miniter_domain::export::ExportFormat::Av1Mp4
        | miniter_domain::export::ExportFormat::Av1Ivf
        | miniter_domain::export::ExportFormat::Av1Mkv
        | miniter_domain::export::ExportFormat::Av1WebM => {
            if hw_requested {
                #[cfg(feature = "hw-decoder")]
                match Av1HwBackend::new(width, height, bitrate_kbps, fps as f32, matrix) {
                    Ok(enc) => return Ok(Box::new(enc)),
                    Err(e) => {
                        log::warn!("HW AV1 encoder init failed, falling back to SW: {e}");
                        crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
                    }
                }
                #[cfg(not(feature = "hw-decoder"))]
                { /* TODO: print no HW decoder feature, fall through to SW */ }
            }
            Av1SwBackend::new(width, height, fps, bitrate_kbps, matrix, encode_effort)
                .map(|enc| Box::new(enc) as Box<dyn EncoderBackend>)
        }
        _ => Err("Unsupported export format".to_string()),
    }
}
