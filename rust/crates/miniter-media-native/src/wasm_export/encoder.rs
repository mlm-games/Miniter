use crate::encoder::{EncodedVideoOutput, VideoEncodeSession};
use crate::encoder_av1::Av1EncodeSession;
use crate::encoder_hw::HwEncodeSession;
use crate::frame::RgbaFrame;
use std::sync::atomic::Ordering;

/// An encoded video packet produced by an encoder backend.
pub struct EncodedPacket {
    pub data: Vec<u8>,
    pub is_keyframe: bool,
    pub pts_us: u64,
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
}

pub struct H264SwBackend {
    inner: VideoEncodeSession,
}

impl H264SwBackend {
    pub fn new(width: u32, height: u32, bitrate_bps: u32, fps: f32) -> Result<Self, String> {
        VideoEncodeSession::new(width, height, bitrate_bps, fps)
            .map(|inner| Self { inner })
            .map_err(|e| format!("H.264 SW encoder init failed: {e}"))
    }
}

impl EncoderBackend for H264SwBackend {
    fn name(&self) -> &'static str {
        "H.264 SW (videoson)"
    }

    fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<Vec<EncodedPacket>, String> {
        match self
            .inner
            .encode_frame(frame)
            .map_err(|e| format!("H.264 encode failed: {e}"))?
        {
            EncodedVideoOutput::Sample { bytes, is_keyframe } => Ok(vec![EncodedPacket {
                data: bytes,
                is_keyframe,
                pts_us: frame.pts_us.max(0) as u64,
            }]),
            EncodedVideoOutput::Skipped => Err("H.264 encoder skipped frame".to_string()),
        }
    }

    fn finish(&mut self) -> Result<Vec<EncodedPacket>, String> {
        Ok(Vec::new())
    }

    fn check_error(&self) -> Option<String> {
        None
    }
}

pub struct Av1SwBackend {
    inner: Av1EncodeSession,
}

impl Av1SwBackend {
    pub fn new(width: u32, height: u32, fps: f64, bitrate_kbps: u32) -> Result<Self, String> {
        Av1EncodeSession::new(width, height, fps, bitrate_kbps)
            .map(|inner| Self { inner })
            .map_err(|e| format!("AV1 SW encoder init failed: {e}"))
    }
}

impl EncoderBackend for Av1SwBackend {
    fn name(&self) -> &'static str {
        "AV1 SW (videoson)"
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
    pub fn new(width: u32, height: u32, bitrate_bps: u32, fps: f32) -> Result<Self, String> {
        HwEncodeSession::new(width, height, bitrate_bps, fps, "video/avc")
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
                    })
                }
            })
            .collect())
    }

    fn check_error(&self) -> Option<String> {
        self.inner.check_error().map(|e| format!("{e:?}"))
    }
}

#[cfg(feature = "hw-decoder")]
pub struct Av1HwBackend {
    inner: HwEncodeSession,
}

#[cfg(feature = "hw-decoder")]
impl Av1HwBackend {
    pub fn new(width: u32, height: u32, bitrate_bps: u32, fps: f32) -> Result<Self, String> {
        HwEncodeSession::new(width, height, bitrate_bps, fps, "video/av01")
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
                    })
                }
            })
            .collect())
    }

    fn check_error(&self) -> Option<String> {
        self.inner.check_error().map(|e| format!("{e:?}"))
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
) -> Result<Box<dyn EncoderBackend>, String> {
    match format {
        miniter_domain::export::ExportFormat::Mp4 | miniter_domain::export::ExportFormat::Mov => {
            let bitrate_bps = bitrate_kbps.max(500) * 1000;
            if hw_requested {
                #[cfg(feature = "hw-decoder")]
                match H264HwBackend::new(width, height, bitrate_bps, fps as f32) {
                    Ok(enc) => return Ok(Box::new(enc)),
                    Err(e) => {
                        log::warn!("HW H.264 encoder init failed, falling back to SW: {e}");
                        crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
                    }
                }
                #[cfg(not(feature = "hw-decoder"))]
                { /* TODO: print no HW decoder feature, fall through to SW */ }
            }
            H264SwBackend::new(width, height, bitrate_bps, fps as f32)
                .map(|enc| Box::new(enc) as Box<dyn EncoderBackend>)
        }
        miniter_domain::export::ExportFormat::Av1Mp4
        | miniter_domain::export::ExportFormat::Av1Ivf
        | miniter_domain::export::ExportFormat::Av1Mkv
        | miniter_domain::export::ExportFormat::Av1WebM => {
            if hw_requested {
                #[cfg(feature = "hw-decoder")]
                match Av1HwBackend::new(width, height, bitrate_kbps.max(500) * 1000, fps as f32) {
                    Ok(enc) => return Ok(Box::new(enc)),
                    Err(e) => {
                        log::warn!("HW AV1 encoder init failed, falling back to SW: {e}");
                        crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
                    }
                }
                #[cfg(not(feature = "hw-decoder"))]
                { /* TODO: print no HW decoder feature, fall through to SW */ }
            }
            Av1SwBackend::new(width, height, fps, bitrate_kbps.max(500))
                .map(|enc| Box::new(enc) as Box<dyn EncoderBackend>)
        }
        _ => Err("Unsupported export format".to_string()),
    }
}
