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
#[cfg(any(
    feature = "videoson",
    feature = "videoson-h265",
    feature = "videoson-vp8",
    feature = "videoson-vp9",
    feature = "av1"
))]
pub mod videoson;

pub mod session;

#[allow(unused_variables)]
pub fn create_backend(
    fourcc: u32,
    width: u32,
    height: u32,
    hardware_acceleration: bool,
    codec_mime: &str,
    description: &[u8],
) -> Result<Box<dyn VideoDecoderBackend>, String> {
    let mut hw_attempted = false;

    // HW decoder (baaba) – tried first for codecs where available.
    // On WASM, WebCodecs configure() may return Ok even when the codec is
    // not truly supported; the decoder then fails asynchronously (NotSupportedError
    // via on_error) during the first JS event loop yield.  Skip HW for codecs
    // where SW is reliable on WASM.
    #[cfg(all(
        feature = "hw-decoder",
        any(
            all(target_os = "android", target_arch = "aarch64"),
            target_arch = "wasm32",
            target_os = "linux"
        )
    ))]
    if hardware_acceleration {
        hw_attempted = true;
        let mime = fourcc_to_mime(fourcc).unwrap_or(codec_mime);
        match baaba::BaabaBackend::new(width, height, mime, description, hardware_acceleration) {
            Ok(dec) => {
                if dec.is_supported(fourcc) {
                    return Ok(Box::new(dec));
                }
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
            Err(e) => {
                log::warn!("HW decoder init failed, falling back to SW: {e:?}");
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
        }
    }

    // SW decoder fallback (videoson) – tried for every codec with a feature flag.
    if fourcc == H264_FOURCC {
        #[cfg(feature = "videoson")]
        {
            if let Ok(dec) = videoson::VideosonBackend::new_h264(width, height) {
                return Ok(Box::new(dec));
            }
        }
        return Err("no H.264 decoder available".into());
    }

    if fourcc == H265_FOURCC {
        #[cfg(any(feature = "videoson", feature = "videoson-h265"))]
        {
            if let Ok(dec) = videoson::VideosonBackend::new_h265(width, height) {
                return Ok(Box::new(dec));
            }
        }
        let hw_suffix = hw_suffix(hw_attempted);
        return Err(format!("no HEVC decoder available{hw_suffix}"));
    }

    if fourcc == VP8_FOURCC {
        #[cfg(any(feature = "videoson", feature = "videoson-vp8"))]
        {
            if let Ok(dec) = videoson::VideosonBackend::new_vp8(width, height) {
                return Ok(Box::new(dec));
            }
        }
        let hw_suffix = hw_suffix(hw_attempted);
        return Err(format!("no VP8 decoder available{hw_suffix}"));
    }

    if fourcc == VP9_FOURCC {
        #[cfg(any(feature = "videoson", feature = "videoson-vp9"))]
        {
            if let Ok(dec) = videoson::VideosonBackend::new_vp9(width, height) {
                return Ok(Box::new(dec));
            }
        }
        let hw_suffix = hw_suffix(hw_attempted);
        return Err(format!("no VP9 decoder available{hw_suffix}"));
    }

    if fourcc == AV1_FOURCC || fourcc == AV1_IVF_FOURCC {
        #[cfg(feature = "av1")]
        {
            if let Ok(dec) = videoson::VideosonBackend::new_av1(width, height) {
                return Ok(Box::new(dec));
            }
        }
        let hw_suffix = hw_suffix(hw_attempted);
        return Err(format!("no AV1 decoder available{hw_suffix}"));
    }

    Err(format!("unsupported codec 0x{fourcc:08x}"))
}

fn hw_suffix(hw_attempted: bool) -> &'static str {
    let hw_feature = cfg!(feature = "hw-decoder");
    let hw_compiled = hw_feature
        && cfg!(any(
            target_os = "android",
            target_arch = "wasm32",
            target_os = "linux"
        ));
    if hw_attempted {
        " (HW decoder attempted)"
    } else if hw_compiled {
        " (HW decoder available but not entered)"
    } else {
        " (HW decoder not compiled)"
    }
}
