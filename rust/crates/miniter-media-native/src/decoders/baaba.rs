use std::collections::VecDeque;
use std::time::Duration;

use crate::decoders::DecodeError;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;
use baabaabaabaabababbababbaa::{
    Dimensions, EncodedVideoPacket, PixelFormat, VideoCodecId, VideoDecoderConfig,
    VideoFrame as BaabaFrame, VideoPlanes,
};
use baabaabaabaabababbababbaa::VideoDecoderInput as _;
#[cfg(not(target_arch = "wasm32"))]
use baabaabaabaabababbababbaa::VideoDecoderOutput as _;

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
use std::sync::LazyLock;
#[cfg(target_arch = "wasm32")]
static WASM_RT: LazyLock<tokio::runtime::Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_current_thread()
        .build()
        .expect("WASM tokio runtime for baaba decoder")
});

pub struct BaabaBackend {
    input: PlatformVideoDecoderInput,
    output: PlatformVideoDecoderOutput,
    #[cfg(not(target_arch = "wasm32"))]
    rt: Runtime,
    allowed_fourccs: Vec<u32>,
    #[cfg(target_arch = "wasm32")]
    frame_buffer: VecDeque<RgbaFrame>,
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
        let rt =
            Runtime::new().map_err(|e| DecodeError::Other(format!("tokio: {e}")))?;

        Ok(Self {
            input,
            output,
            #[cfg(not(target_arch = "wasm32"))]
            rt,
            allowed_fourccs: Vec::new(),
            #[cfg(target_arch = "wasm32")]
            frame_buffer: VecDeque::new(),
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
                    let rgba =
                        crate::yuv::yuv420_to_rgba(y, u, v, w, h, w, w / 2, w / 2);
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
fn drain_frames(
    output: &mut PlatformVideoDecoderOutput,
    buffer: &mut VecDeque<RgbaFrame>,
) -> Result<(), DecodeBackendError> {
    while let Some(baaba_frame) = output
        .try_frame()
        .map_err(|e| DecodeBackendError::Other(format!("baaba try_frame: {e}")))?
    {
        buffer.push_back(convert_baaba_frame(baaba_frame)?);
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
        _is_sync: bool,
    ) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        let packet = EncodedVideoPacket {
            payload: data.to_vec().into(),
            timestamp: Duration::from_micros(pts_us as u64),
            keyframe: false,
        };
        self.input
            .decode(packet)
            .map_err(|e| DecodeBackendError::Other(format!("baaba decode: {e}")))?;

        #[cfg(not(target_arch = "wasm32"))]
        {
            let frame = self
                .rt
                .block_on(self.output.frame())
                .map_err(|e| DecodeBackendError::Other(format!("baaba frame: {e}")))?;
            return Ok(frame.map(convert_baaba_frame).transpose()?);
        }

        #[cfg(target_arch = "wasm32")]
        {
            drain_frames(&mut self.output, &mut self.frame_buffer)?;
            Ok(self.frame_buffer.pop_front())
        }
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        #[cfg(not(target_arch = "wasm32"))]
        {
            let _ = self.rt.block_on(self.input.flush());
        }

        #[cfg(target_arch = "wasm32")]
        {
            let _ = WASM_RT
                .block_on(self.input.flush())
                .map_err(|e| DecodeBackendError::Other(format!("baaba flush: {e}")))?;
            drain_frames(&mut self.output, &mut self.frame_buffer)?;
        }

        Ok(self.frame_buffer.pop_front())
    }

    fn reset(&mut self) {
        // no-op; baaba doesn't expose reset
    }
}
