use crate::demux::VideoDecoderBackend;

// ---------------------------------------------------------------------------
// Canonical fourcc constants (LE u32)
// ---------------------------------------------------------------------------
pub const H264_FOURCC: u32 = 0x31637661; // "avc1"
pub const H265_FOURCC: u32 = 0x68657631; // "hev1"
pub const AV1_FOURCC: u32 = 0x31305641; // "av01"
pub const VP8_FOURCC: u32 = 0x31475661; // "vp80"
pub const VP9_FOURCC: u32 = 0x31475639; // "vp90"
pub const AV1_IVF_FOURCC: u32 = 0x31495641; // "AVI1" (IVF container legacy)

// ---------------------------------------------------------------------------
// Error types
// ---------------------------------------------------------------------------
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
    #[error("AV1: {0}")]
    Av1(String),
    #[error("Decoder error: {0}")]
    Other(String),
}

impl From<crate::demux::DecodeBackendError> for DecodeError {
    fn from(e: crate::demux::DecodeBackendError) -> Self {
        DecodeError::Other(e.to_string())
    }
}

// ---------------------------------------------------------------------------
// Module declarations (feature-gated)
// ---------------------------------------------------------------------------
#[cfg(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32"
    )
))]
pub mod baaba;
#[cfg(feature = "av1")]
pub mod rav1d;
#[cfg(feature = "videoson")]
pub mod videoson;

pub mod session;

// ---------------------------------------------------------------------------
// Factory: pick a backend by fourcc
// ---------------------------------------------------------------------------
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
            target_arch = "wasm32"
        )
    ))]
    if hardware_acceleration {
        if let Ok(dec) = baaba::BaabaBackend::new(width, height, codec_mime) {
            if dec.is_supported(fourcc) {
                return Some(Box::new(dec));
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
            if let Ok(dec) = rav1d::Av1Backend::new() {
                return Some(Box::new(dec));
            }
        }
        return None;
    }

    None
}
