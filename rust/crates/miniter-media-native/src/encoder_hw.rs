use crate::encoder::{EncodeError, EncodedVideoOutput};
use crate::frame::RgbaFrame;
#[cfg(not(target_arch = "wasm32"))]
use crate::yuv::rgba_to_yuv420;
use std::time::Duration;

#[cfg(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32",
        target_os = "linux"
    )
))]
mod hw {
    use super::*;
    use baabaabaabaabababbababbaa::{
        Dimensions, VideoCodecId, VideoEncoderConfig, VideoEncoderInput, VideoFrame, VideoPlanes,
    };

    #[cfg(target_os = "android")]
    use baabaabaabaabababbababbaa::platform::android::{
        AndroidVideoEncoderInput, AndroidVideoEncoderOutput, MediaCodecHost,
    };
    #[cfg(target_arch = "wasm32")]
    use baabaabaabaabababbababbaa::platform::wasm::{
        WasmVideoEncoderInput, WasmVideoEncoderOutput, WebCodecsHost,
    };

    #[cfg(target_os = "linux")]
    use baabaabaabaabababbababbaa::platform::linux::{
        CrosVideoEncoderInput, CrosVideoEncoderOutput, CrosCodecsHost,
    };

    #[cfg(not(target_arch = "wasm32"))]
    use tokio::runtime::Runtime;

    #[cfg(target_os = "android")]
    type PlatformInput = AndroidVideoEncoderInput;
    #[cfg(target_os = "android")]
    type PlatformOutput = AndroidVideoEncoderOutput;
    #[cfg(target_os = "linux")]
    type PlatformInput = CrosVideoEncoderInput;
    #[cfg(target_os = "linux")]
    type PlatformOutput = CrosVideoEncoderOutput;
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

    #[cfg(target_arch = "wasm32")]
    pub struct HwEncodedFrame {
        pub bytes: Vec<u8>,
        pub is_keyframe: bool,
        pub pts_us: u64,
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
            #[cfg(target_os = "linux")]
            let host = CrosCodecsHost::new();

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

        #[cfg(target_arch = "wasm32")]
        fn build_video_frame(&self, frame: &RgbaFrame) -> VideoFrame {
            VideoFrame {
                dimensions: Dimensions::new(self.width, self.height),
                format: baabaabaabaabababbababbaa::PixelFormat::Rgba8,
                timestamp: Duration::from_micros(frame.pts_us as u64),
                planes: VideoPlanes::Cpu(frame.data.clone()),
            }
        }

        #[cfg(not(target_arch = "wasm32"))]
        fn build_video_frame(&self, frame: &RgbaFrame) -> VideoFrame {
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
            VideoFrame {
                dimensions: Dimensions::new(self.width, self.height),
                format: baabaabaabaabababbababbaa::PixelFormat::Nv12,
                timestamp: Duration::from_micros(frame.pts_us as u64),
                planes: VideoPlanes::Cpu(nv12),
            }
        }

        /// Output is collected later via `drain_completed()` (after JS yields).
        #[cfg(target_arch = "wasm32")]
        pub fn submit_frame(&mut self, frame: &RgbaFrame) -> Result<(), EncodeError> {
            if let Some(err) = self.output.check_error() {
                return Err(EncodeError::LessAvc(format!(
                    "HwEncoder error callback: {err:?}"
                )));
            }
            let video_frame = self.build_video_frame(frame);
            self.input
                .encode(video_frame, Some(true))
                .map_err(|e| EncodeError::LessAvc(format!("HwEncoder encode: {e:?}")))?;
            Ok(())
        }

        /// Must be called after JS has yielded (microtasks fired) to get output.
        /// Returns frames with correct pts from the encoded packet timestamps.
        #[cfg(target_arch = "wasm32")]
        pub fn drain_completed(&mut self) -> Result<Vec<HwEncodedFrame>, EncodeError> {
            let mut frames = Vec::new();
            while let Some(pkt) = self
                .output
                .try_packet()
                .map_err(|e| EncodeError::LessAvc(format!("HwEncoder try_packet: {e:?}")))?
            {
                frames.push(HwEncodedFrame {
                    bytes: pkt.payload.to_vec(),
                    is_keyframe: pkt.keyframe,
                    pts_us: pkt.timestamp.as_micros() as u64,
                });
            }
            Ok(frames)
        }

        /// On non-WASM: submit + block on output (works because tokio multi-thread).
        /// On WASM: submit + drain completed (for backwards compat with non-chunked path).
        /// Chunked export should prefer `submit_frame` + `drain_completed` instead.
        pub fn encode_frame(
            &mut self,
            frame: &RgbaFrame,
        ) -> Result<EncodedVideoOutput, EncodeError> {
            let idx = self.frame_index;
            self.frame_index += 1;

            #[cfg(target_arch = "wasm32")]
            {
                self.submit_frame(frame)?;
                let mut completed = self.drain_completed()?;
                match completed.first_mut() {
                    Some(f) => Ok(EncodedVideoOutput::Sample {
                        bytes: std::mem::take(&mut f.bytes),
                        is_keyframe: f.is_keyframe,
                    }),
                    None => Err(EncodeError::SkippedFrame { frame_index: idx }),
                }
            }

            #[cfg(not(target_arch = "wasm32"))]
            {
                let video_frame = self.build_video_frame(frame);
                use baabaabaabaabababbababbaa::VideoEncoderInput;
                self.input
                    .encode(video_frame, Some(true))
                    .map_err(|e| EncodeError::LessAvc(format!("HwEncoder encode: {e:?}")))?;

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
        }

        /// Check if the encoder's error callback has fired (async init failure).
        #[cfg(target_arch = "wasm32")]
        pub fn check_error(&self) -> Option<crate::encoder::EncodeError> {
            self.output.check_error().map(|e| {
                crate::encoder::EncodeError::LessAvc(format!("HW enc error callback: {e:?}"))
            })
        }

        pub fn width(&self) -> u32 {
            self.width
        }
        pub fn height(&self) -> u32 {
            self.height
        }
    }
}

#[cfg(not(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32",
        target_os = "linux"
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
            Err(EncodeError::LessAvc(
                "HW encoder not available on this platform".into(),
            ))
        }

        pub fn encode_frame(
            &mut self,
            _frame: &RgbaFrame,
        ) -> Result<EncodedVideoOutput, EncodeError> {
            Err(EncodeError::LessAvc("HW encoder not available".into()))
        }

        pub fn width(&self) -> u32 {
            0
        }
        pub fn height(&self) -> u32 {
            0
        }
    }
}

pub use hw::HwEncodeSession;
