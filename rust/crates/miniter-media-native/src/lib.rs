use std::sync::atomic::AtomicBool;

/// true when HW accel. was requested but fell back to software.
pub(crate) static HARDWARE_FALLBACK_OCCURRED: AtomicBool = AtomicBool::new(false);

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
#[cfg(not(target_arch = "wasm32"))]
pub mod subtitle;
pub mod thumbnailer;
#[cfg(target_arch = "wasm32")]
pub mod wasm_export;
pub mod yuv;

pub use audio::{AudioDecodeError, AudioDecoder};
pub use audio_export::{AudioSidecarExportError, write_audio_sidecar_if_present};
pub use encoder::{EncodeError, EncodedVideoOutput, VideoEncodeSession};
pub use frame::RgbaFrame;
pub use probe::{MediaInfo, MediaProbeError};
pub use thumbnailer::clear_session_cache;
