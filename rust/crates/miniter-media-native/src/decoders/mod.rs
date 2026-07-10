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
    description: &[u8],
) -> Result<Box<dyn VideoDecoderBackend>, String> {
    // HW decoder (baaba) – used for codecs with NO SW fallback (VP8, VP9, H265).
    let mut hw_attempted = false;
    #[cfg(all(
        feature = "hw-decoder",
        any(
            all(target_os = "android", target_arch = "aarch64"),
            target_arch = "wasm32",
            target_os = "linux"
        )
    ))]
    if hardware_acceleration
        && (fourcc == H265_FOURCC || fourcc == VP8_FOURCC || fourcc == VP9_FOURCC)
    {
        hw_attempted = true;
        let mime = fourcc_to_mime(fourcc).unwrap_or(codec_mime);
        match baaba::BaabaBackend::new(width, height, mime, description) {
            Ok(dec) => {
                if dec.is_supported(fourcc) {
                    return Ok(Box::new(dec));
                }
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
            Err(e) => {
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
                return Err(format!("HW decoder init failed: {e:?}"));
            }
        }
    }

    if fourcc == H264_FOURCC {
        #[cfg(feature = "videoson")]
        {
            if let Ok(dec) = videoson::VideosonBackend::new(width, height) {
                return Ok(Box::new(dec));
            }
        }
        return Err("no H.264 decoder available".into());
    }

    let hw_feature = cfg!(feature = "hw-decoder");
    let hw_compiled = hw_feature && cfg!(any(target_os = "android", target_arch = "wasm32", target_os = "linux"));
    let hw_suffix = if hw_attempted {
        " (HW decoder attempted)"
    } else if hw_compiled {
        " (HW decoder available but not entered)"
    } else {
        " (HW decoder not compiled)"
    };
    if fourcc == H265_FOURCC {
        Err(format!("no HEVC decoder available{hw_suffix}"))
    } else if fourcc == VP8_FOURCC || fourcc == VP9_FOURCC {
        Err(format!("no {codec_mime} decoder available{hw_suffix}"))
    } else if fourcc == AV1_FOURCC || fourcc == AV1_IVF_FOURCC {
        #[cfg(feature = "av1")]
        {
            if let Ok(dec) = rav1d::Av1Backend::new(width, height) {
                return Ok(Box::new(dec));
            }
        }
        Err("no AV1 decoder available".into())
    } else {
        Err(format!("unsupported codec 0x{fourcc:08x}"))
    }
}
