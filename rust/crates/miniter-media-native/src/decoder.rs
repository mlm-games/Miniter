//! Demux MP4 → extract H.264 NAL units → decode to RGBA frames.

use crate::frame::RgbaFrame;
use mp4::{Mp4Reader, TrackType};
use std::io::{BufReader, Seek};
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum DecodeError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("MP4: {0}")]
    Mp4(#[from] mp4::Error),
    #[error("No video stream")]
    NoVideoStream,
    #[error("Unsupported codec — only H.264 is supported")]
    UnsupportedCodec,
    #[error("Decoder not available: enable videoson or hw-decoder")]
    DecoderNotAvailable,
    #[error("Videoson: {0}")]
    Videoson(String),
    #[error("Decoder error: {0}")]
    Other(String),
}

#[cfg(feature = "videoson")]
mod videoson_decoder {
    use super::*;
    use videoson_codec_h264::H264Decoder;
    use videoson_core::{
        NalFormat, Packet as VideoPacket, VideoCodecParams, VideoDecoder, VideoDecoderOptions,
    };

    pub struct VideosonDecoder(H264Decoder);

    impl VideosonDecoder {
        pub fn new(width: u32, height: u32) -> Result<Self, DecodeError> {
            let params = VideoCodecParams {
                codec: videoson_core::CodecType::H264,
                coded_width: width,
                coded_height: height,
                extradata: Vec::new(),
                nal_format: Some(NalFormat::AnnexB),
            };
            let decoder = H264Decoder::try_new(&params, &VideoDecoderOptions::default())
                .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
            Ok(Self(decoder))
        }

        pub fn decode(
            &mut self,
            annex_b: Vec<u8>,
            pts_us: i64,
        ) -> Result<Option<RgbaFrame>, DecodeError> {
            let packet = VideoPacket {
                track_id: 0,
                pts: Some(pts_us * 1000),
                dts: None,
                duration: None,
                is_sync: false,
                data: annex_b,
            };
            self.0
                .send_packet(&packet)
                .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;

            if let Some(frame) = self
                .0
                .receive_frame()
                .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?
            {
                let (w, h) = (frame.width as usize, frame.height as usize);
                let y_stride = frame.plane_data[0].stride;
                let u_stride = frame.plane_data.get(1).map(|p| p.stride).unwrap_or(w / 2);
                let v_stride = frame.plane_data.get(2).map(|p| p.stride).unwrap_or(w / 2);

                let y_data = match &frame.plane_data[0].data {
                    videoson_core::PlaneData::U8(v) => v.as_slice(),
                    _ => return Err(DecodeError::Videoson("Unsupported bit depth".to_string())),
                };
                let u_data = frame
                    .plane_data
                    .get(1)
                    .and_then(|p| match &p.data {
                        videoson_core::PlaneData::U8(v) => Some(v.as_slice()),
                        _ => None,
                    })
                    .unwrap_or(&[]);
                let v_data = frame
                    .plane_data
                    .get(2)
                    .and_then(|p| match &p.data {
                        videoson_core::PlaneData::U8(v) => Some(v.as_slice()),
                        _ => None,
                    })
                    .unwrap_or(&[]);

                let mut rgba = vec![0u8; w * h * 4];
                for y in 0..h {
                    for x in 0..w {
                        let yi = y * y_stride + x;
                        let ui = y / 2 * u_stride + x / 2;
                        let vi = y / 2 * v_stride + x / 2;
                        let y_val = y_data[yi] as f32;
                        let u_val = u_data.get(ui).copied().unwrap_or(128) as f32 - 128.0;
                        let v_val = v_data.get(vi).copied().unwrap_or(128) as f32 - 128.0;
                        let r = (y_val + 1.402 * v_val).clamp(0.0, 255.0) as u8;
                        let g = (y_val - 0.344 * u_val - 0.714 * v_val).clamp(0.0, 255.0) as u8;
                        let b = (y_val + 1.772 * u_val).clamp(0.0, 255.0) as u8;
                        rgba[(y * w + x) * 4 + 0] = r;
                        rgba[(y * w + x) * 4 + 1] = g;
                        rgba[(y * w + x) * 4 + 2] = b;
                        rgba[(y * w + x) * 4 + 3] = 255;
                    }
                }
                return Ok(Some(RgbaFrame {
                    width: w as u32,
                    height: h as u32,
                    data: rgba,
                    pts_us,
                }));
            }
            Ok(None)
        }

        pub fn reset(&mut self) {
            self.0.reset();
        }
    }
}

#[cfg(all(
    feature = "hw-decoder",
    any(
        all(target_os = "android", target_arch = "aarch64"),
        target_arch = "wasm32"
    )
))]
mod baaba_decoder {
    use super::*;
    use baabaabaabaabababbababbaa::{
        Dimensions, EncodedVideoPacket, VideoCodecId, VideoDecoderConfig, VideoFrame as BaabaFrame,
        VideoPlanes,
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

    pub struct BaabaDecoder {
        input: PlatformVideoDecoderInput,
        output: PlatformVideoDecoderOutput,
        #[cfg(not(target_arch = "wasm32"))]
        rt: Runtime,
    }

    impl BaabaDecoder {
        pub fn new(width: u32, height: u32) -> Result<Self, DecodeError> {
            let config = VideoDecoderConfig {
                codec: VideoCodecId("video/avc".to_string()),
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

            Ok(Self {
                input,
                output,
                #[cfg(not(target_arch = "wasm32"))]
                rt,
            })
        }

        pub fn decode(
            &mut self,
            annex_b: Vec<u8>,
            pts_us: i64,
        ) -> Result<Option<RgbaFrame>, DecodeError> {
            use std::time::Duration;

            let packet = EncodedVideoPacket {
                payload: annex_b.into(),
                timestamp: Duration::from_micros(pts_us as u64),
                keyframe: false,
            };
            self.input
                .decode(packet)
                .map_err(|e| DecodeError::Other(format!("decode: {e}")))?;

            #[cfg(not(target_arch = "wasm32"))]
            {
                let frame = self
                    .rt
                    .block_on(self.output.frame())
                    .map_err(|e| DecodeError::Other(format!("frame: {e}")))?;
                return Ok(frame.map(convert_baaba_frame).transpose()?);
            }

            #[cfg(target_arch = "wasm32")]
            {
                Err(DecodeError::Other(
                    "Baaba WebCodecs output is async-only; use wasm async bridge".to_string(),
                ))
            }
        }

        pub fn flush(&mut self) {
            #[cfg(not(target_arch = "wasm32"))]
            {
                let _ = self.rt.block_on(self.input.flush());
            }
        }
    }

    fn convert_baaba_frame(frame: BaabaFrame) -> Result<RgbaFrame, DecodeError> {
        let pts_us = frame.timestamp.as_micros().min(i64::MAX as u128) as i64;

        match frame.planes {
            VideoPlanes::Cpu(data) => {
                let w = frame.dimensions.width as usize;
                let h = frame.dimensions.height as usize;
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
                    Err(DecodeError::Other("Invalid plane data".to_string()))
                }
            }
            VideoPlanes::Hardware => Err(DecodeError::Other(
                "Hardware frames not supported".to_string(),
            )),
        }
    }
}

pub struct VideoDecodeSession<R: std::io::Read + Seek> {
    mp4: Mp4Reader<R>,
    width: u32,
    height: u32,
    timescale: u32,
    nalu_length_size: u8,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    last_pts_us: i64,
    current_sample: u32,
    total_samples: u32,
    video_track_id: u32,
    pending_frame: Option<RgbaFrame>,

    #[cfg(feature = "videoson")]
    videoson: Option<videoson_decoder::VideosonDecoder>,
    #[cfg(all(
        feature = "hw-decoder",
        any(
            all(target_os = "android", target_arch = "aarch64"),
            target_arch = "wasm32"
        )
    ))]
    baaba: Option<baaba_decoder::BaabaDecoder>,
}

impl VideoDecodeSession<BufReader<std::fs::File>> {
    pub fn open(path: &Path) -> Result<Self, DecodeError> {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        let reader = BufReader::new(file);
        Self::from_reader(reader, size)
    }
}

impl<R: std::io::Read + Seek> VideoDecodeSession<R> {
    pub fn from_reader(reader: R, size: u64) -> Result<Self, DecodeError> {
        let mp4 = Mp4Reader::read_header(reader, size)?;

        let video_track = mp4
            .tracks()
            .values()
            .find(|t| matches!(t.track_type(), Ok(TrackType::Video)))
            .ok_or(DecodeError::NoVideoStream)?;

        let media_type = video_track
            .media_type()
            .map_err(|_| DecodeError::UnsupportedCodec)?;

        if !matches!(media_type, mp4::MediaType::H264) {
            return Err(DecodeError::UnsupportedCodec);
        }

        let video_track_id = video_track.track_id();
        let total_samples = video_track.sample_count();
        let width = video_track.width() as u32;
        let height = video_track.height() as u32;
        let timescale = video_track.timescale();

        let (nalu_length_size, sps, pps) =
            if let Some(ref avc1) = video_track.trak.mdia.minf.stbl.stsd.avc1 {
                (
                    (avc1.avcc.length_size_minus_one & 0x03) + 1,
                    avc1.avcc
                        .sequence_parameter_sets
                        .first()
                        .map(|nal| nal.bytes.clone()),
                    avc1.avcc
                        .picture_parameter_sets
                        .first()
                        .map(|nal| nal.bytes.clone()),
                )
            } else {
                (4, None, None)
            };

        #[cfg(feature = "videoson")]
        let videoson = Some(videoson_decoder::VideosonDecoder::new(width, height)?);
        #[cfg(all(
            feature = "hw-decoder",
            any(
                all(target_os = "android", target_arch = "aarch64"),
                target_arch = "wasm32"
            )
        ))]
        let baaba = baaba_decoder::BaabaDecoder::new(width, height).ok();

        Ok(Self {
            mp4,
            width,
            height,
            timescale,
            nalu_length_size,
            sps,
            pps,
            last_pts_us: -1,
            current_sample: 1,
            total_samples,
            video_track_id,
            pending_frame: None,
            #[cfg(feature = "videoson")]
            videoson,
            #[cfg(all(
                feature = "hw-decoder",
                any(
                    all(target_os = "android", target_arch = "aarch64"),
                    target_arch = "wasm32"
                )
            ))]
            baaba,
        })
    }

    pub fn width(&self) -> u32 {
        self.width
    }
    pub fn height(&self) -> u32 {
        self.height
    }
    pub fn timescale(&self) -> u32 {
        self.timescale
    }
    pub fn total_samples(&self) -> u32 {
        self.total_samples
    }

    pub fn next_frame(&mut self) -> Result<Option<RgbaFrame>, DecodeError> {
        if let Some(frame) = self.pending_frame.take() {
            return Ok(Some(frame));
        }

        while self.current_sample <= self.total_samples {
            let sample = self
                .mp4
                .read_sample(self.video_track_id, self.current_sample)?
                .ok_or_else(|| DecodeError::Other("Failed to read sample".to_string()))?;

            let pts = if self.timescale > 0 {
                ((sample.start_time as f64 / self.timescale as f64) * 1_000_000.0).round() as i64
            } else {
                0
            };

            if pts <= self.last_pts_us {
                self.last_pts_us = pts.saturating_add(1);
            } else {
                self.last_pts_us = pts;
            }

            self.current_sample += 1;

            let annex_b = mp4_nalu_to_annex_b(
                &sample.bytes,
                self.nalu_length_size,
                self.sps.as_deref(),
                self.pps.as_deref(),
            );

            #[cfg(all(
                feature = "hw-decoder",
                any(
                    all(target_os = "android", target_arch = "aarch64"),
                    target_arch = "wasm32"
                )
            ))]
            {
                if let Some(ref mut dec) = self.baaba {
                    match dec.decode(annex_b.clone(), self.last_pts_us) {
                        Ok(Some(frame)) => return Ok(Some(frame)),
                        Ok(None) => continue,
                        Err(e) => {
                            log::warn!("Baaba decoder failed, falling back to videoson: {}", e);
                            self.baaba = None;
                            #[cfg(feature = "videoson")]
                            {
                                if let Some(ref mut vdec) = self.videoson {
                                    if let Some(frame) = vdec.decode(annex_b, self.last_pts_us)? {
                                        return Ok(Some(frame));
                                    }
                                    continue;
                                }
                            }
                            return Err(e);
                        }
                    }
                }
            }

            #[cfg(feature = "videoson")]
            {
                if let Some(ref mut dec) = self.videoson {
                    if let Some(frame) = dec.decode(annex_b, self.last_pts_us)? {
                        return Ok(Some(frame));
                    }
                    continue;
                }
            }

            return Err(DecodeError::DecoderNotAvailable);
        }

        Ok(None)
    }

    pub fn reset(&mut self) -> Result<(), DecodeError> {
        self.current_sample = 1;
        self.last_pts_us = -1;
        self.pending_frame = None;

        #[cfg(feature = "videoson")]
        if let Some(ref mut dec) = self.videoson {
            dec.reset();
        }

        #[cfg(all(
            feature = "hw-decoder",
            any(
                all(target_os = "android", target_arch = "aarch64"),
                target_arch = "wasm32"
            )
        ))]
        if let Some(ref mut dec) = self.baaba {
            dec.flush();
        }

        Ok(())
    }

    pub fn seek_to_sample(&mut self, sample_id: u32) -> Result<(), DecodeError> {
        self.current_sample = sample_id.max(1);
        self.last_pts_us = -1;
        self.pending_frame = None;
        self.reset()
    }
}

fn mp4_nalu_to_annex_b(
    data: &[u8],
    nalu_length_size: u8,
    sps: Option<&[u8]>,
    pps: Option<&[u8]>,
) -> Vec<u8> {
    let start_code: &[u8] = &[0x00, 0x00, 0x00, 0x01];
    let mut output = Vec::with_capacity(data.len() + 128);
    let mut saw_sps = false;
    let mut saw_pps = false;
    let mut offset = 0;
    let len_size = nalu_length_size.clamp(1, 4) as usize;

    while offset + len_size <= data.len() {
        let mut nalu_len = 0usize;
        for _ in 0..len_size {
            nalu_len = (nalu_len << 8) | data[offset] as usize;
            offset += 1;
        }

        if nalu_len == 0 || offset + nalu_len > data.len() {
            continue;
        }

        let nalu = &data[offset..offset + nalu_len];
        if let Some(&header) = nalu.first() {
            match header & 0x1F {
                7 => saw_sps = true,
                8 => saw_pps = true,
                _ => {}
            }
        }

        output.extend_from_slice(start_code);
        output.extend_from_slice(nalu);
        offset += nalu_len;
    }

    if output.is_empty() {
        return data.to_vec();
    }

    if !saw_sps || !saw_pps {
        let mut prefixed = Vec::new();
        if !saw_sps {
            if let Some(sps_nal) = sps {
                prefixed.extend_from_slice(start_code);
                prefixed.extend_from_slice(sps_nal);
            }
        }
        if !saw_pps {
            if let Some(pps_nal) = pps {
                prefixed.extend_from_slice(start_code);
                prefixed.extend_from_slice(pps_nal);
            }
        }
        prefixed.extend_from_slice(&output);
        prefixed
    } else {
        output
    }
}
