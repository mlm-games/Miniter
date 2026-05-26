use crate::encoder::{EncodeError, EncodedVideoOutput};
use crate::frame::RgbaFrame;
use crate::yuv::rgba_to_yuv420;
use std::time::Duration;

#[cfg(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32"
    )
))]
mod hw {
    use super::*;
    use baabaabaabaabababbababbaa::{
        Dimensions, VideoCodecId, VideoEncoderConfig, VideoFrame, VideoPlanes,
    };

    #[cfg(target_os = "android")]
    use baabaabaabaabababbababbaa::platform::android::{
        AndroidVideoEncoderInput, AndroidVideoEncoderOutput, MediaCodecHost,
    };
    #[cfg(target_arch = "wasm32")]
    use baabaabaabaabababbababbaa::platform::wasm::{
        WasmVideoEncoderInput, WasmVideoEncoderOutput, WebCodecsHost,
    };

    #[cfg(not(target_arch = "wasm32"))]
    use tokio::runtime::Runtime;

    #[cfg(target_os = "android")]
    type PlatformInput = AndroidVideoEncoderInput;
    #[cfg(target_os = "android")]
    type PlatformOutput = AndroidVideoEncoderOutput;
    #[cfg(target_arch = "wasm32")]
    type PlatformInput = WasmVideoEncoderInput;
    #[cfg(target_arch = "wasm32")]
    type PlatformOutput = WasmVideoEncoderOutput;

    pub struct HwEncodeSession {
        input: PlatformInput,
        output: PlatformOutput,
        #[cfg(not(target_arch = "wasm32"))]
        rt: Runtime,
        width: u32,
        height: u32,
        frame_index: u32,
    }

    impl HwEncodeSession {
        pub fn new(
            width: u32,
            height: u32,
            bitrate_bps: u32,
            fps: f32,
            mime: &str,
        ) -> Result<Self, EncodeError> {
            let config = VideoEncoderConfig {
                codec: VideoCodecId(mime.to_string()),
                dimensions: Dimensions::new(width, height),
                bitrate: Some(bitrate_bps),
                framerate: Some(fps as f64),
                hardware_acceleration: None,
                latency_optimized: None,
                level: None,
            };

            #[cfg(target_os = "android")]
            let host = MediaCodecHost::new();
            #[cfg(target_arch = "wasm32")]
            let host = WebCodecsHost::new();

            let (input, output) = host
                .create_video_encoder(config)
                .map_err(|e| EncodeError::LessAvc(format!("HwEncoder init: {e:?}")))?;

            #[cfg(not(target_arch = "wasm32"))]
            let rt = Runtime::new().map_err(|e| EncodeError::LessAvc(format!("tokio: {e}")))?;

            Ok(Self {
                input,
                output,
                #[cfg(not(target_arch = "wasm32"))]
                rt,
                width,
                height,
                frame_index: 0,
            })
        }

        pub fn encode_frame(&mut self, frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
            let idx = self.frame_index;
            self.frame_index += 1;

            let (y_plane, u_plane, v_plane) =
                rgba_to_yuv420(&frame.data, self.width as usize, self.height as usize);
            let luma = (self.width * self.height) as usize;
            let chroma = ((self.width / 2) * (self.height / 2)) as usize;
            let mut nv12 = Vec::with_capacity(luma + chroma * 2);
            nv12.extend_from_slice(&y_plane);
            for i in 0..chroma {
                nv12.push(u_plane[i]);
                nv12.push(v_plane[i]);
            }

            let video_frame = VideoFrame {
                dimensions: Dimensions::new(self.width, self.height),
                format: baabaabaabaabababbababbaa::PixelFormat::Nv12,
                timestamp: Duration::from_micros(frame.pts_us as u64),
                planes: VideoPlanes::Cpu(nv12),
            };

            use baabaabaabaabababbababbaa::VideoEncoderInput;
            self.input
                .encode(video_frame, Some(true))
                .map_err(|e| EncodeError::LessAvc(format!("HwEncoder encode: {e:?}")))?;

            #[cfg(not(target_arch = "wasm32"))]
            {
                use baabaabaabaabababbababbaa::VideoEncoderOutput;
                let pkt = self
                    .rt
                    .block_on(self.output.packet())
                    .map_err(|e| EncodeError::LessAvc(format!("HwEncoder packet: {e:?}")))?;

                match pkt {
                    Some(pkt) => {
                        let bytes = pkt.payload.to_vec();
                        if bytes.is_empty() {
                            return Err(EncodeError::EmptyFrame { frame_index: idx });
                        }
                        Ok(EncodedVideoOutput::Sample {
                            bytes,
                            is_keyframe: pkt.keyframe,
                        })
                    }
                    None => Err(EncodeError::SkippedFrame { frame_index: idx }),
                }
            }

            #[cfg(target_arch = "wasm32")]
            Err(EncodeError::LessAvc("Wasm encoder not supported".into()))
        }

        pub fn width(&self) -> u32 { self.width }
        pub fn height(&self) -> u32 { self.height }
    }
}

#[cfg(not(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32"
    )
)))]
mod hw {
    use crate::encoder::{EncodeError, EncodedVideoOutput};
    use crate::frame::RgbaFrame;

    pub struct HwEncodeSession;

    impl HwEncodeSession {
        pub fn new(
            _width: u32,
            _height: u32,
            _bitrate_bps: u32,
            _fps: f32,
            _mime: &str,
        ) -> Result<Self, EncodeError> {
            Err(EncodeError::LessAvc("HW encoder not available on this platform".into()))
        }

        pub fn encode_frame(&mut self, _frame: &RgbaFrame) -> Result<EncodedVideoOutput, EncodeError> {
            Err(EncodeError::LessAvc("HW encoder not available".into()))
        }

        pub fn width(&self) -> u32 { 0 }
        pub fn height(&self) -> u32 { 0 }
    }
}

pub use hw::HwEncodeSession;
