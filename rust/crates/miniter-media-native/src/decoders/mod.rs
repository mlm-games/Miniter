use crate::demux::VideoDecoderBackend;
use std::sync::atomic::Ordering;

pub const H264_FOURCC: u32 = 0x31637661; // "avc1"
pub const H265_FOURCC: u32 = 0x31766568; // "hev1"
pub const AV1_FOURCC: u32 = 0x31305641; // "AV01"
pub const VP8_FOURCC: u32 = 0x30385056; // "VP80"
pub const VP9_FOURCC: u32 = 0x30395056; // "VP90"
pub const AV1_IVF_FOURCC: u32 = 0x31495641; // "AVI1"

pub fn fourcc_to_mime(fourcc: u32) -> Option<&'static str> {
    match fourcc {
        H264_FOURCC => Some("video/avc"),
        H265_FOURCC => Some("video/hevc"),
        AV1_FOURCC | AV1_IVF_FOURCC => Some("video/av01"),
        VP8_FOURCC => Some("video/vp8"),
        VP9_FOURCC => Some("video/vp9"),
        _ => None,
    }
}

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("Demux: {0}")]
    Demux(#[from] crate::demux::DemuxError),
    #[error("No video stream")]
    NoVideoStream,
    #[error("Unsupported codec")]
    UnsupportedCodec,
    #[error("No decoder available for {0}")]
    DecoderNotAvailable(String),
    #[error("Videoson: {0}")]
    Videoson(String),
    #[error("Decoder error: {0}")]
    Other(String),
}

impl From<crate::demux::DecodeBackendError> for DecodeError {
    fn from(e: crate::demux::DecodeBackendError) -> Self {
        DecodeError::Other(e.to_string())
    }
}

#[cfg(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32",
        target_os = "linux"
    )
))]
pub mod baaba;
#[cfg(feature = "av1")]
pub mod rav1d;
#[cfg(feature = "videoson")]
pub mod videoson;

pub mod session;

#[allow(unused_variables)]
pub fn create_backend(
    fourcc: u32,
    width: u32,
    height: u32,
    hardware_acceleration: bool,
    codec_mime: &str,
) -> Option<Box<dyn VideoDecoderBackend>> {
    // HW decoder (baaba) – tried first when flag is on
    #[cfg(all(
        feature = "hw-decoder",
        any(
            all(target_os = "android", target_arch = "aarch64"),
            target_arch = "wasm32",
            target_os = "linux"
        )
    ))]
    if hardware_acceleration {
        let mime = fourcc_to_mime(fourcc).unwrap_or(codec_mime);
        match baaba::BaabaBackend::new(width, height, mime) {
            Ok(dec) => {
                if dec.is_supported(fourcc) {
                    return Some(Box::new(dec));
                }
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
            Err(_) => {
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
        }
    }

    if fourcc == H264_FOURCC {
        #[cfg(feature = "videoson")]
        {
            if let Ok(dec) = videoson::VideosonBackend::new(width, height) {
                return Some(Box::new(dec));
            }
        }
        return None;
    }

    if fourcc == H265_FOURCC {
        return None;
    }

    if fourcc == AV1_FOURCC || fourcc == AV1_IVF_FOURCC {
        #[cfg(feature = "av1")]
        {
            if let Ok(dec) = rav1d::Av1Backend::new(width, height) {
                return Some(Box::new(dec));
            }
        }
        return None;
    }

    None
}
