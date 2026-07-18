use crate::encoder::{EncodeError, EncodedVideoOutput};
use crate::frame::RgbaFrame;
#[cfg(not(target_arch = "wasm32"))]
use crate::yuv::rgba_to_yuv420;
#[cfg(target_arch = "wasm32")]
use crate::demux::symphonia_demux::{avcc_to_annexb, parse_avcc};
#[cfg(target_arch = "wasm32")]
use crate::export_shared::has_annexb_start_code;
use web_time::Duration;

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
        AvcBitstreamFormat, Dimensions, VideoEncoderConfig, VideoEncoderInput, VideoFrame,
        VideoPlanes,
    };
    use baabaabaabaabababbababbaa::traits::VideoEncoderOutput;

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
        CrosCodecsHost, CrosVideoEncoderInput, CrosVideoEncoderOutput,
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
        #[cfg(target_arch = "wasm32")]
        is_h264: bool,
        #[cfg(target_arch = "wasm32")]
        sps_pps_prepended: bool,
        #[cfg(target_arch = "wasm32")]
        flush_promise: Option<js_sys::Promise>,
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
                codec: mime.into(),
                dimensions: Dimensions::new(width, height),
                bitrate: Some(bitrate_bps),
                framerate: Some(fps as f64),
                hardware_acceleration: None,
                latency_optimized: Some(false),
                level: None,
                avc_bitstream_format: Some(AvcBitstreamFormat::AnnexB),
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

            let is_h264 = mime.contains("avc") || mime.contains("h264");
            Ok(Self {
                input,
                output,
                #[cfg(not(target_arch = "wasm32"))]
                rt,
                width,
                height,
                frame_index: 0,
                #[cfg(target_arch = "wasm32")]
                is_h264,
                #[cfg(target_arch = "wasm32")]
                sps_pps_prepended: false,
                #[cfg(target_arch = "wasm32")]
                flush_promise: None,
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
        /// Converts AVCC (length-prefixed) format to Annex-B (start codes) for H.264,
        /// since the WebCodecs encoder outputs AVCC but the downstream pipeline expects Annex-B.
        /// Also prepends SPS/PPS from the decoder config to the first frame as a fallback
        /// in case the keyframe payload does not include them.
        #[cfg(target_arch = "wasm32")]
        pub fn drain_completed(&mut self) -> Result<Vec<HwEncodedFrame>, EncodeError> {
            let mut frames = Vec::new();
            while let Some(pkt) = self
                .output
                .try_packet()
                .map_err(|e| EncodeError::LessAvc(format!("HwEncoder try_packet: {e:?}")))?
            {
                let mut bytes = if self.is_h264 && !has_annexb_start_code(&pkt.payload) {
                    avcc_to_annexb(&pkt.payload)
                } else {
                    pkt.payload.to_vec()
                };

                // HACK for WASM: Prepend SPS/PPS from encoder's decoder config to the first frame
                // (converted from AVCC extradata to Annex-B). This ensures downstream
                // extract_sps_pps() always finds them, even if the encoder omitted them
                // from the keyframe payload.
                if self.is_h264 && !self.sps_pps_prepended {
                    if let Some(cfg) = self.output.decoder_config() {
                        if let Some(desc) = &cfg.description {
                            let annexb = parse_avcc(desc);
                            if !annexb.is_empty() {
                                bytes.splice(0..0, annexb);
                            }
                        }
                    }
                    self.sps_pps_prepended = true;
                }

                let pts = pkt.timestamp.as_micros() as u64;
                log::warn!(
                    "DRAIN_COMPLETED: pts_us={} key={} bytes_len={}",
                    pts, pkt.keyframe, bytes.len(),
                );
                frames.push(HwEncodedFrame {
                    bytes,
                    is_keyframe: pkt.keyframe,
                    pts_us: pts,
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
            let is_first = idx == 0;

            #[cfg(target_arch = "wasm32")]
            {
                self.submit_frame(frame)?;
                let mut completed = self.drain_completed()?;
                match completed.first_mut() {
                    Some(f) => Ok(EncodedVideoOutput::Sample {
                        bytes: std::mem::take(&mut f.bytes),
                        is_keyframe: is_first || f.is_keyframe,
                        pts_us: f.pts_us as i64,
                    }),
                    None => Err(EncodeError::SkippedFrame { frame_index: idx }),
                }
            }

            #[cfg(not(target_arch = "wasm32"))]
            {
                let video_frame = self.build_video_frame(frame);
                use baabaabaabaabababbababbaa::VideoEncoderInput;
                self.input
                    .encode(video_frame, None)
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
                            is_keyframe: is_first || pkt.keyframe,
                            pts_us: pkt.timestamp.as_micros() as i64,
                        })
                    }
                    None => Err(EncodeError::SkippedFrame { frame_index: idx }),
                }
            }
        }

        /// Start an async flush on the WebCodecs encoder.
        #[cfg(target_arch = "wasm32")]
        pub fn start_flush(&mut self) {
            self.flush_promise = Some(self.input.start_flush());
        }

        /// Take the flush completion promise (if any) for external awaiting.
        #[cfg(target_arch = "wasm32")]
        pub fn take_flush_promise(&mut self) -> Option<js_sys::Promise> {
            self.flush_promise.take()
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
