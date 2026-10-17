use std::sync::atomic::{AtomicBool, Ordering};

/// true when HW accel. was requested but fell back to software.
pub static HARDWARE_FALLBACK_OCCURRED: AtomicBool = AtomicBool::new(false);

pub fn was_hardware_fallback() -> bool {
    HARDWARE_FALLBACK_OCCURRED.load(Ordering::SeqCst)
}

/// The platform's hardware video decoder capability for the UI: a status line and the codecs
/// it decodes. Probed lazily and cached by the platform backend; `None` where no probe exists.
#[cfg(all(
    feature = "hw-decoder",
    any(target_os = "linux", target_os = "macos", target_os = "windows")
))]
pub fn hardware_probe() -> Option<(String, Vec<String>)> {
    #[cfg(target_os = "linux")]
    return baabaabaabaabababbababbaa::platform::linux::probe().ok();
    #[cfg(target_os = "macos")]
    return baabaabaabaabababbababbaa::platform::apple::probe().ok();
    #[cfg(target_os = "windows")]
    return baabaabaabaabababbababbaa::platform::windows::probe().ok();
    #[allow(unreachable_code)]
    None
}

#[cfg(not(all(
    feature = "hw-decoder",
    any(target_os = "linux", target_os = "macos", target_os = "windows")
)))]
pub fn hardware_probe() -> Option<(String, Vec<String>)> {
    None
}

pub mod audio;
pub mod audio_export;
pub mod decoder;
pub mod decoders;
pub mod demux;
pub mod encoder;
pub mod encoder_av1;
pub mod encoder_hw;
#[cfg(not(target_arch = "wasm32"))]
pub mod export;
pub mod export_shared;
pub mod filters;
pub mod frame;
pub mod image_cache;
pub mod mux;
pub mod probe;
pub mod proxy;
#[cfg(not(target_arch = "wasm32"))]
pub mod subtitle;
pub mod subtitles;
pub mod thumbnailer;
#[cfg(target_arch = "wasm32")]
pub mod wasm_export;
pub mod yuv;

pub use audio::{AudioDecodeError, AudioDecoder};
pub use audio_export::{AudioSidecarExportError, write_audio_sidecar_if_present};
pub use encoder::{EncodeError, EncodedVideoOutput, VideoEncodeSession};
pub use frame::{ChromaSiting, ColorInfo, ColorRange, MatrixCoeffs, RgbaFrame};
pub use probe::{MediaInfo, MediaProbeError};
pub use thumbnailer::clear_session_cache;
