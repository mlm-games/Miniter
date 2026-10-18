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

/// CICP codes signalled in the container's Annex-B parameter sets.
///
/// The demuxer stores `avcC`/`hvcC` records as Annex-B config NALs, so the
/// codes are read from the SPS exactly as the decoders read them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct StreamColor {
    pub primaries: u8,
    pub transfer: u8,
    pub matrix: u8,
    pub full_range: bool,
}

/// H.273 code point for "unspecified".
const UNSPECIFIED: u8 = 2;

pub(crate) fn stream_color(mime: &str, annexb: &[u8]) -> Option<StreamColor> {
    match mime {
        "video/avc" => h264_color(annexb),
        "video/hevc" | "video/h265" => h265_color(annexb),
        _ => None,
    }
}

fn h264_color(annexb: &[u8]) -> Option<StreamColor> {
    use nuxodecs::codec::h264::parser::{Nalu, NaluType, Parser};
    let mut parser = Parser::default();
    let mut cursor = std::io::Cursor::new(annexb);
    while let Ok(nalu) = Nalu::next(&mut cursor) {
        if nalu.header.type_ != NaluType::Sps {
            continue;
        }
        let sps = parser.parse_sps(&nalu).ok()?;
        let vui = &sps.vui_parameters;
        if !sps.vui_parameters_present_flag || !vui.video_signal_type_present_flag {
            return None;
        }
        let described = vui.colour_description_present_flag;
        return Some(StreamColor {
            primaries: if described { vui.colour_primaries } else { UNSPECIFIED },
            transfer: if described { vui.transfer_characteristics } else { UNSPECIFIED },
            matrix: if described { vui.matrix_coefficients } else { UNSPECIFIED },
            full_range: vui.video_full_range_flag,
        });
    }
    None
}

fn h265_color(annexb: &[u8]) -> Option<StreamColor> {
    use nuxodecs::codec::h265::parser::{Nalu, NaluType, Parser};
    let mut parser = Parser::default();
    let mut cursor = std::io::Cursor::new(annexb);
    while let Ok(nalu) = Nalu::next(&mut cursor) {
        if nalu.header.type_ != NaluType::SpsNut {
            continue;
        }
        let sps = parser.parse_sps(&nalu).ok()?;
        let vui = &sps.vui_parameters;
        if !sps.vui_parameters_present_flag || !vui.video_signal_type_present_flag {
            return None;
        }
        let described = vui.colour_description_present_flag;
        return Some(StreamColor {
            primaries: if described { vui.colour_primaries as u8 } else { UNSPECIFIED },
            transfer: if described { vui.transfer_characteristics as u8 } else { UNSPECIFIED },
            matrix: if described { vui.matrix_coeffs as u8 } else { UNSPECIFIED },
            full_range: vui.video_full_range_flag,
        });
    }
    None
}

fn color_info_from(color: StreamColor, height: u32) -> crate::frame::ColorInfo {
    let mut info = crate::frame::ColorInfo::infer(height);
    info.range = if color.full_range {
        crate::frame::ColorRange::Full
    } else {
        crate::frame::ColorRange::Limited
    };
    if let Some(matrix) = crate::frame::ColorInfo::matrix_from_cicp(color.matrix) {
        info.matrix = matrix;
    }
    info
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
        target_os = "linux",
        target_os = "macos",
        target_os = "windows"
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

mod hybrid;
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
    let mime = fourcc_to_mime(fourcc).unwrap_or(codec_mime);
    let sw_color =
        stream_color(mime, description).map(|color| color_info_from(color, height));
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
            target_os = "linux",
            target_os = "macos",
            target_os = "windows"
        )
    ))]
    if hardware_acceleration {
        hw_attempted = true;
        match baaba::BaabaBackend::new(width, height, mime, description, hardware_acceleration) {
            Ok(dec) => {
                if dec.is_supported(fourcc) {
                    // A hardware decoder that fails mid-stream continues in software: the
                    // software backend is made on demand and the samples since the last
                    // restart point are replayed through it.
                    let make_sw =
                        move || create_software_backend(fourcc, width, height, sw_color);
                    crate::hw_stats::note_decode_session();
                    return Ok(Box::new(hybrid::HybridBackend::new(
                        Box::new(dec),
                        Box::new(make_sw),
                    )));
                }
                crate::hw_stats::note_decode_declined();
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
            Err(e) => {
                log::warn!("HW decoder init failed, falling back to SW: {e:?}");
                crate::hw_stats::note_decode_declined();
                crate::HARDWARE_FALLBACK_OCCURRED.store(true, Ordering::SeqCst);
            }
        }
    }

    let hw_suffix = hw_suffix(hw_attempted);
    create_software_backend(fourcc, width, height, sw_color)
        .map_err(|e| format!("{e}{hw_suffix}"))
}

/// The software (pure-Rust) decoder for `fourcc`, or why there is none.
fn create_software_backend(
    fourcc: u32,
    width: u32,
    height: u32,
    color: Option<crate::frame::ColorInfo>,
) -> Result<Box<dyn VideoDecoderBackend>, String> {
    // SW decoder fallback (videoson) – tried for every codec with a feature flag.
    if fourcc == H264_FOURCC {
        #[cfg(feature = "videoson")]
        {
            if let Ok(mut dec) = videoson::VideosonBackend::new_h264(width, height) {
                dec.set_fallback_color(color);
                return Ok(Box::new(dec));
            }
        }
        return Err("no H.264 decoder available".into());
    }

    if fourcc == H265_FOURCC {
        #[cfg(any(feature = "videoson", feature = "videoson-h265"))]
        {
            if let Ok(mut dec) = videoson::VideosonBackend::new_h265(width, height) {
                dec.set_fallback_color(color);
                return Ok(Box::new(dec));
            }
        }
        return Err("no HEVC decoder available".into());
    }

    if fourcc == VP8_FOURCC {
        #[cfg(any(feature = "videoson", feature = "videoson-vp8"))]
        {
            if let Ok(mut dec) = videoson::VideosonBackend::new_vp8(width, height) {
                dec.set_fallback_color(color);
                return Ok(Box::new(dec));
            }
        }
        return Err("no VP8 decoder available".into());
    }

    if fourcc == VP9_FOURCC {
        #[cfg(any(feature = "videoson", feature = "videoson-vp9"))]
        {
            if let Ok(mut dec) = videoson::VideosonBackend::new_vp9(width, height) {
                dec.set_fallback_color(color);
                return Ok(Box::new(dec));
            }
        }
        return Err("no VP9 decoder available".into());
    }

    if fourcc == AV1_FOURCC || fourcc == AV1_IVF_FOURCC {
        #[cfg(feature = "av1")]
        {
            if let Ok(mut dec) = videoson::VideosonBackend::new_av1(width, height) {
                dec.set_fallback_color(color);
                return Ok(Box::new(dec));
            }
        }
        return Err("no AV1 decoder available".into());
    }

    Err(format!("unsupported codec 0x{fourcc:08x}"))
}

fn hw_suffix(hw_attempted: bool) -> &'static str {
    let hw_feature = cfg!(feature = "hw-decoder");
    let hw_compiled = hw_feature
        && cfg!(any(
            target_os = "android",
            target_arch = "wasm32",
            target_os = "linux",
            target_os = "macos",
            target_os = "windows"
        ));
    if hw_attempted {
        " (HW decoder attempted)"
    } else if hw_compiled {
        " (HW decoder available but not entered)"
    } else {
        " (HW decoder not compiled)"
    }
}
