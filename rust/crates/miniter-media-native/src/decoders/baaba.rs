#[cfg(target_arch = "wasm32")]
use std::collections::VecDeque;
use std::time::Duration;

use crate::decoders::DecodeError;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;
use baabaabaabaabababbababbaa::VideoDecoderInput as _;
#[cfg(not(target_arch = "wasm32"))]
use baabaabaabaabababbababbaa::VideoDecoderOutput as _;
use baabaabaabaabababbababbaa::{
    Dimensions, EncodedVideoPacket, PixelFormat, VideoCodecId, VideoDecoderConfig,
    VideoFrame as BaabaFrame, VideoPlanes,
};

#[cfg(target_os = "linux")]
use baabaabaabaabababbababbaa::platform::linux::{
    CrosCodecsHost as PlatformHost, CrosVideoDecoderInput as PlatformVideoDecoderInput,
    CrosVideoDecoderOutput as PlatformVideoDecoderOutput,
};

#[cfg(target_os = "android")]
use baabaabaabaabababbababbaa::platform::android::{
    AndroidVideoDecoderInput as PlatformVideoDecoderInput,
    AndroidVideoDecoderOutput as PlatformVideoDecoderOutput, MediaCodecHost as PlatformHost,
};

#[cfg(target_arch = "wasm32")]
use baabaabaabaabababbababbaa::platform::wasm::{
    WasmVideoDecoderInput as PlatformVideoDecoderInput,
    WasmVideoDecoderOutput as PlatformVideoDecoderOutput, WebCodecsHost as PlatformHost,
};

#[cfg(not(target_arch = "wasm32"))]
use tokio::runtime::Runtime;

#[cfg(target_arch = "wasm32")]
use wasm_bindgen_futures::spawn_local;

#[cfg(target_arch = "wasm32")]
struct PendingFrame {
    data: Vec<u8>,
    width: u32,
    height: u32,
    format: PixelFormat,
    pts_us: i64,
}

pub struct BaabaBackend {
    input: PlatformVideoDecoderInput,
    output: PlatformVideoDecoderOutput,
    #[cfg(not(target_arch = "wasm32"))]
    rt: Runtime,
    allowed_fourccs: Vec<u32>,
    #[cfg(target_arch = "wasm32")]
    frame_buffer: VecDeque<RgbaFrame>,
    #[cfg(target_arch = "wasm32")]
    copy_rx: tokio::sync::mpsc::UnboundedReceiver<PendingFrame>,
    #[cfg(target_arch = "wasm32")]
    copy_tx: tokio::sync::mpsc::UnboundedSender<PendingFrame>,
}

impl BaabaBackend {
    pub fn new(width: u32, height: u32, mime: &str) -> Result<Self, DecodeError> {
        let config = VideoDecoderConfig {
            codec: VideoCodecId(mime.to_string()),
            resolution: Some(Dimensions::new(width, height)),
            description: None,
            hardware_acceleration: None,
        };
        let host = PlatformHost::new();
        let (input, output) = host
            .create_video_decoder(config)
            .map_err(|e| DecodeError::Other(format!("BaabaError: {:?}", e)))?;

        #[cfg(not(target_arch = "wasm32"))]
        let rt = Runtime::new().map_err(|e| DecodeError::Other(format!("tokio: {e}")))?;

        #[cfg(target_arch = "wasm32")]
        let (copy_tx, copy_rx) = tokio::sync::mpsc::unbounded_channel();

        Ok(Self {
            input,
            output,
            #[cfg(not(target_arch = "wasm32"))]
            rt,
            allowed_fourccs: Vec::new(),
            #[cfg(target_arch = "wasm32")]
            frame_buffer: VecDeque::new(),
            #[cfg(target_arch = "wasm32")]
            copy_rx,
            #[cfg(target_arch = "wasm32")]
            copy_tx,
        })
    }
}

fn convert_baaba_frame(frame: BaabaFrame) -> Result<RgbaFrame, DecodeBackendError> {
    let pts_us = frame.timestamp.as_micros().min(i64::MAX as u128) as i64;
    let w = frame.dimensions.width as usize;
    let h = frame.dimensions.height as usize;

    match frame.planes {
        VideoPlanes::Cpu(data) => match frame.format {
            PixelFormat::Yuv420p => {
                let luma = w * h;
                let chroma = (w / 2) * (h / 2);
                if data.len() >= luma + 2 * chroma {
                    let (y, rest) = data.split_at(luma);
                    let (u, v) = rest.split_at(chroma);
                    let rgba = crate::yuv::yuv420_to_rgba(y, u, v, w, h, w, w / 2, w / 2);
                    Ok(RgbaFrame {
                        width: frame.dimensions.width,
                        height: frame.dimensions.height,
                        data: rgba,
                        pts_us,
                    })
                } else {
                    Err(DecodeBackendError::Other(
                        "Invalid Yuv420p plane data".into(),
                    ))
                }
            }
            PixelFormat::Nv12 => {
                let rgba = crate::yuv::nv12_to_rgba(&data, w, h);
                Ok(RgbaFrame {
                    width: frame.dimensions.width,
                    height: frame.dimensions.height,
                    data: rgba,
                    pts_us,
                })
            }
            _ => Err(DecodeBackendError::Other(format!(
                "Unsupported pixel format: {:?}",
                frame.format
            ))),
        },
        VideoPlanes::Hardware => Err(DecodeBackendError::Other(
            "Hardware frames not supported".into(),
        )),
    }
}

#[cfg(target_arch = "wasm32")]
fn wc_pixel_format_to_baaba(fmt: web_sys::VideoPixelFormat) -> PixelFormat {
    use web_sys::VideoPixelFormat;
    match fmt {
        VideoPixelFormat::I420 | VideoPixelFormat::I420a => PixelFormat::Yuv420p,
        VideoPixelFormat::Nv12 => PixelFormat::Nv12,
        VideoPixelFormat::Rgba => PixelFormat::Rgba8,
        VideoPixelFormat::Bgra => PixelFormat::Bgra8,
        _ => PixelFormat::Nv12,
    }
}

/// Collect raw frames from the WebCodecs output channel, schedule async
/// copy-to-CPU via `spawn_local`, and check for previously completed copies.
#[cfg(target_arch = "wasm32")]
fn drain_raw_frames(
    output: &mut PlatformVideoDecoderOutput,
    copy_tx: &tokio::sync::mpsc::UnboundedSender<PendingFrame>,
    copy_rx: &mut tokio::sync::mpsc::UnboundedReceiver<PendingFrame>,
    frame_buffer: &mut VecDeque<RgbaFrame>,
) -> Result<(), DecodeBackendError> {
    while let Some(raw_frame) = output
        .try_frame_raw()
        .map_err(|e| DecodeBackendError::Other(format!("baaba try_frame_raw: {e}")))?
    {
        let dims = raw_frame.dimensions();
        let ts = raw_frame.timestamp();
        let fmt = raw_frame
            .format()
            .map(wc_pixel_format_to_baaba)
            .unwrap_or(PixelFormat::Nv12);
        let pts_us = ts.as_micros().min(i64::MAX as u128) as i64;
        let width = dims.width;
        let height = dims.height;

        let tx = copy_tx.clone();
        spawn_local(async move {
            if let Ok(data) = raw_frame.copy_to_cpu().await {
                let _ = tx.send(PendingFrame {
                    data,
                    width,
                    height,
                    format: fmt,
                    pts_us,
                });
            }
        });
    }

    while let Ok(pending) = copy_rx.try_recv() {
        let baaba_frame = BaabaFrame {
            dimensions: Dimensions::new(pending.width, pending.height),
            format: pending.format,
            timestamp: Duration::from_micros(pending.pts_us as u64),
            planes: VideoPlanes::Cpu(pending.data),
        };
        frame_buffer.push_back(convert_baaba_frame(baaba_frame)?);
    }

    Ok(())
}

impl VideoDecoderBackend for BaabaBackend {
    fn name(&self) -> &'static str {
        "baaba (HW)"
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        if self.allowed_fourccs.is_empty() {
            true
        } else {
            self.allowed_fourccs.contains(&fourcc)
        }
    }

    fn decode_frame(
        &mut self,
        data: &[u8],
        pts_us: i64,
        is_sync: bool,
    ) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        #[cfg(not(target_arch = "wasm32"))]
        {
            // Drain stale frames from previous packets before sending a new one.
            while let Ok(Some(stale)) = self.output.try_frame() {
                // discard stale frames
                let _ = stale;
            }
        }

        let packet = EncodedVideoPacket {
            payload: data.to_vec().into(),
            timestamp: Duration::from_micros(pts_us as u64),
            keyframe: is_sync,
        };
        self.input
            .decode(packet)
            .map_err(|e| DecodeBackendError::Other(format!("baaba decode: {e}")))?;

        #[cfg(not(target_arch = "wasm32"))]
        {
            match self.output.try_frame() {
                Ok(Some(frame)) => {
                    let mut result = convert_baaba_frame(frame)?;
                    result.pts_us = pts_us;
                    return Ok(Some(result));
                }
                Ok(None) => {
                    return Ok(None);
                }
                Err(e) => {
                    return Err(DecodeBackendError::Other(format!("baaba try_frame: {e}")));
                }
            }
        }

        #[cfg(target_arch = "wasm32")]
        {
            drain_raw_frames(
                &mut self.output,
                &self.copy_tx,
                &mut self.copy_rx,
                &mut self.frame_buffer,
            )?;
            Ok(self.frame_buffer.pop_front())
        }
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        #[cfg(not(target_arch = "wasm32"))]
        {
            let _ = self.rt.block_on(self.input.flush());
            let mut last = None;
            while let Ok(Some(frame)) = self.output.try_frame() {
                last = Some(convert_baaba_frame(frame)?);
            }
            Ok(last)
        }

        #[cfg(target_arch = "wasm32")]
        {
            drain_raw_frames(
                &mut self.output,
                &self.copy_tx,
                &mut self.copy_rx,
                &mut self.frame_buffer,
            )?;
            Ok(self.frame_buffer.pop_front())
        }
    }

    fn reset(&mut self) {
        // no-op; baaba doesn't expose reset
    }
}
