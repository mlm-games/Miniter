use std::collections::VecDeque;
use web_time::Duration;

use crate::decoders::DecodeError;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::{ColorInfo, RgbaFrame};

use baabaabaabaabababbababbaa::VideoDecoderInput as _;
#[cfg(not(target_arch = "wasm32"))]
use baabaabaabaabababbababbaa::VideoDecoderOutput as _;
use baabaabaabaabababbababbaa::{
    Dimensions, EncodedVideoPacket, PixelFormat, VideoDecoderConfig, VideoFrame as BaabaFrame,
    VideoPlanes,
};
use bytes::Bytes;

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
    frame_buffer: VecDeque<RgbaFrame>,
    flushed: bool,
    reorder_depth: usize,
    #[cfg(target_arch = "wasm32")]
    copy_rx: tokio::sync::mpsc::UnboundedReceiver<PendingFrame>,
    #[cfg(target_arch = "wasm32")]
    copy_tx: tokio::sync::mpsc::UnboundedSender<PendingFrame>,
}

impl BaabaBackend {
    pub fn new(
        width: u32,
        height: u32,
        mime: &str,
        description: &[u8],
        hardware_acceleration: bool,
    ) -> Result<Self, DecodeError> {
        // WebCodecs contract:
        //   - description present → expects AVC format (length-prefixed NALUs + avcC/hvcC blob)
        //   - description absent  → expects Annex-B (start codes, SPS/PPS in-band on keyframes)
        // The demux always converts to Annex-B and prepends SPS/PPS to the first keyframe,
        // so on WASM we must set description=None to match the Annex-B data being sent.
        let description = if cfg!(target_arch = "wasm32") {
            None
        } else if description.is_empty() {
            None
        } else {
            Some(Bytes::copy_from_slice(description))
        };

        let config = VideoDecoderConfig {
            codec: mime.into(),
            resolution: Some(Dimensions::new(width, height)),
            description,
            hardware_acceleration: Some(hardware_acceleration),
        };
        let host = PlatformHost::new();
        let (input, output) = host
            .create_video_decoder(config)
            .map_err(|e| DecodeError::Other(format!("BaabaError: {:?}", e)))?;

        #[cfg(not(target_arch = "wasm32"))]
        let rt = Runtime::new().map_err(|e| DecodeError::Other(format!("tokio: {e}")))?;

        #[cfg(target_arch = "wasm32")]
        let (copy_tx, copy_rx) = tokio::sync::mpsc::unbounded_channel();

        // On WASM, WebCodecs handles internal reordering; we don't need to buffer.
        // On native, HEVC may need a deeper buffer for B-frame reordering.
        let reorder_depth = if cfg!(target_arch = "wasm32") {
            1
        } else if mime.contains("hevc") || mime.contains("hev1") || mime.contains("265") {
            16
        } else {
            1
        };

        Ok(Self {
            input,
            output,
            #[cfg(not(target_arch = "wasm32"))]
            rt,
            allowed_fourccs: Vec::new(),
            frame_buffer: VecDeque::new(),
            flushed: false,
            reorder_depth,
            #[cfg(target_arch = "wasm32")]
            copy_rx,
            #[cfg(target_arch = "wasm32")]
            copy_tx,
        })
    }

    fn drain_available(&mut self) -> Result<(), DecodeBackendError> {
        #[cfg(not(target_arch = "wasm32"))]
        loop {
            match self.output.try_frame() {
                Ok(Some(frame)) => {
                    self.frame_buffer.push_back(convert_baaba_frame(frame)?);
                }
                Ok(None) => break,
                Err(e) => {
                    return Err(DecodeBackendError::Other(format!("baaba try_frame: {e}")));
                }
            }
        }

        #[cfg(target_arch = "wasm32")]
        drain_raw_frames(
            &mut self.output,
            &self.copy_tx,
            &mut self.copy_rx,
            &mut self.frame_buffer,
        )?;

        Ok(())
    }

    fn pop_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        self.drain_available()?;

        if self.frame_buffer.len() >= self.reorder_depth || self.flushed {
            Ok(self.frame_buffer.pop_front())
        } else {
            Ok(None)
        }
    }
}

fn convert_baaba_frame(frame: BaabaFrame) -> Result<RgbaFrame, DecodeBackendError> {
    let pts_us = frame.timestamp.as_micros().min(i64::MAX as u128) as i64;
    let w = frame.dimensions.width as usize;
    let h = frame.dimensions.height as usize;
    let color_info = ColorInfo::infer(frame.dimensions.height);

    match frame.planes {
        VideoPlanes::Cpu(data) => match frame.format {
            PixelFormat::Yuv420p => {
                let luma = w * h;
                let chroma = (w / 2) * (h / 2);
                if data.len() >= luma + 2 * chroma {
                    let (y, rest) = data.split_at(luma);
                    let (u, v) = rest.split_at(chroma);
                    let rgba = crate::yuv::yuv420_to_rgba(y, u, v, w, h, w, w / 2, w / 2, color_info);
                    Ok(RgbaFrame {
                        width: frame.dimensions.width,
                        height: frame.dimensions.height,
                        data: rgba,
                        pts_us,
                        color_info,
                    })
                } else {
                    Err(DecodeBackendError::Other(
                        "Invalid Yuv420p plane data".into(),
                    ))
                }
            }
            PixelFormat::Nv12 => {
                let expected = w * h * 3 / 2;
                if data.len() < expected {
                    log::warn!("NV12 plane data too small: got {} expected {}", data.len(), expected);
                }
                let rgba = crate::yuv::nv12_to_rgba(&data, w, h, color_info);
                Ok(RgbaFrame {
                    width: frame.dimensions.width,
                    height: frame.dimensions.height,
                    data: rgba,
                    pts_us,
                    color_info,
                })
            }
            PixelFormat::Rgba8 => {
                let expected = w * h * 4;
                if data.len() < expected {
                    return Err(DecodeBackendError::Other(format!(
                        "Rgba8 data too small: got {} expected {}", data.len(), expected
                    )));
                }
                Ok(RgbaFrame {
                    width: frame.dimensions.width,
                    height: frame.dimensions.height,
                    data: data[..expected].to_vec(),
                    pts_us,
                    color_info,
                })
            }
            PixelFormat::Bgra8 => {
                let expected = w * h * 4;
                if data.len() < expected {
                    return Err(DecodeBackendError::Other(format!(
                        "Bgra8 data too small: got {} expected {}", data.len(), expected
                    )));
                }
                let mut rgba = Vec::with_capacity(expected);
                for bgra in data[..expected].chunks_exact(4) {
                    rgba.push(bgra[2]); // R
                    rgba.push(bgra[1]); // G
                    rgba.push(bgra[0]); // B
                    rgba.push(bgra[3]); // A
                }
                Ok(RgbaFrame {
                    width: frame.dimensions.width,
                    height: frame.dimensions.height,
                    data: rgba,
                    pts_us,
                    color_info,
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
        VideoPixelFormat::Rgba | VideoPixelFormat::Rgbx => PixelFormat::Rgba8,
        VideoPixelFormat::Bgra | VideoPixelFormat::Bgrx => PixelFormat::Bgra8,
        _ => {
            log::warn!("unhandled WebCodecs pixel format {:?}, defaulting to Nv12", fmt);
            PixelFormat::Nv12
        },
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
        let (width, height) = raw_frame
            .visible_rect()
            .map(|r| (r.width() as u32, r.height() as u32))
            .unwrap_or_else(|| (raw_frame.display_width(), raw_frame.display_height()));
        let width = width.max(1);
        let height = height.max(1);
        let ts = raw_frame.timestamp();
        let fmt = raw_frame
            .format()
            .map(wc_pixel_format_to_baaba)
            .unwrap_or_else(|| {
                log::warn!("drain_raw_frames: frame format() returned None, defaulting to Nv12");
                PixelFormat::Nv12
            });
        let pts_us = ts.as_micros().min(i64::MAX as u128) as i64;

        let tx = copy_tx.clone();
        spawn_local(async move {
            match raw_frame.copy_to_cpu().await {
                Ok(data) => {
                    let _ = tx.send(PendingFrame {
                        data,
                        width,
                        height,
                        format: fmt,
                        pts_us,
                    });
                }
                Err(e) => {
                    log::error!("copy_to_cpu failed: {e:?}");
                }
            }
        });
    }

    while let Ok(pending) = copy_rx.try_recv() {
        let expected_size = match pending.format {
            PixelFormat::Yuv420p => (pending.width * pending.height * 3 / 2) as usize,
            PixelFormat::Nv12 => (pending.width * pending.height * 3 / 2) as usize,
            PixelFormat::Rgba8 | PixelFormat::Bgra8 => (pending.width * pending.height * 4) as usize,
            _ => pending.data.len(),
        };
        if pending.data.len() != expected_size {
            log::warn!(
                "copy_rx: size mismatch for {}x{} fmt={:?}: got {} expected {}",
                pending.width, pending.height, pending.format,
                pending.data.len(), expected_size,
            );
        }
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
        let pts_clamped = pts_us.max(0) as u64;

        if let Some(frame) = self.pop_frame()? {
            let packet = EncodedVideoPacket {
                payload: data.to_vec().into(),
                timestamp: Duration::from_micros(pts_clamped),
                keyframe: is_sync,
            };
            self.input
                .decode(packet)
                .map_err(|e| DecodeBackendError::Other(format!("baaba decode: {e}")))?;
            return Ok(Some(frame));
        }

        let packet = EncodedVideoPacket {
            payload: data.to_vec().into(),
            timestamp: Duration::from_micros(pts_clamped),
            keyframe: is_sync,
        };
        self.input
            .decode(packet)
            .map_err(|e| DecodeBackendError::Other(format!("baaba decode: {e}")))?;

        self.pop_frame()
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        #[cfg(target_arch = "wasm32")]
        {
            self.drain_available()?;
            return Ok(self.frame_buffer.pop_front());
        }

        #[cfg(not(target_arch = "wasm32"))]
        {
            if self.flushed {
                return Ok(self.frame_buffer.pop_front());
            }
            self.flushed = true;
            self.rt
                .block_on(self.input.flush())
                .map_err(|e| DecodeBackendError::Other(format!("baaba flush error: {e:?}")))?;
            self.drain_available()?;
            Ok(self.frame_buffer.pop_front())
        }
    }

    fn reset(&mut self) {
        self.frame_buffer.clear();
        self.flushed = false;

        #[cfg(not(target_arch = "wasm32"))]
        {
            if let Err(e) = self.rt.block_on(self.input.flush()) {
                log::warn!("BaabaBackend::reset flush error: {e:?}");
            }
            loop {
                match self.output.try_frame() {
                    Ok(Some(_)) => {}
                    Ok(None) => break,
                    Err(e) => {
                        log::warn!("BaabaBackend::reset drain error: {e}");
                        break;
                    }
                }
            }
        }

        #[cfg(target_arch = "wasm32")]
        {
            while self.copy_rx.try_recv().is_ok() {}
        }
    }
}
