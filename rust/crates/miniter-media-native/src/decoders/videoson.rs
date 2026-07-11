use crate::decoders::DecodeError;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;
use std::collections::VecDeque;
use videoson::{
    CodecType, NalFormat, PixelFormat, Packet as VideoPacket, VideoCodecParams, VideoDecoder,
    VideoDecoderOptions, VideoOutputFormat,
};

pub struct VideosonBackend {
    inner: Box<dyn VideoDecoder>,
    queued: VecDeque<RgbaFrame>,
    eos_sent: bool,
    backend_name: &'static str,
    allowed_fourccs: Vec<u32>,
}

impl VideosonBackend {
    fn new_impl(
        decoder: Box<dyn VideoDecoder>,
        backend_name: &'static str,
        allowed_fourccs: Vec<u32>,
    ) -> Self {
        Self {
            inner: decoder,
            queued: VecDeque::new(),
            eos_sent: false,
            backend_name,
            allowed_fourccs,
        }
    }

    #[cfg(feature = "videoson")]
    pub fn new_h264(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: CodecType::H264,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: Some(NalFormat::AnnexB),
        };
        let opts = VideoDecoderOptions {
            output_format: VideoOutputFormat::Nv12,
            ..Default::default()
        };
        let decoder = videoson::codec_h264::H264Decoder::try_new(&params, &opts)
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self::new_impl(
            Box::new(decoder),
            "videoson (H.264)",
            vec![crate::decoders::H264_FOURCC],
        ))
    }

    #[cfg(any(feature = "videoson", feature = "videoson-h265"))]
    pub fn new_h265(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: CodecType::H265,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: Some(NalFormat::AnnexB),
        };
        let opts = VideoDecoderOptions {
            output_format: VideoOutputFormat::Nv12,
            ..Default::default()
        };
        let decoder = videoson::codec_h265::H265Decoder::try_new(&params, &opts)
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self::new_impl(
            Box::new(decoder),
            "videoson (H.265)",
            vec![crate::decoders::H265_FOURCC],
        ))
    }

    #[cfg(any(feature = "videoson", feature = "videoson-vp8"))]
    pub fn new_vp8(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: CodecType::VP8,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: None,
        };
        let opts = VideoDecoderOptions {
            output_format: VideoOutputFormat::Nv12,
            ..Default::default()
        };
        let decoder = videoson::codec_vp8::Vp8Decoder::try_new(&params, &opts)
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self::new_impl(
            Box::new(decoder),
            "videoson (VP8)",
            vec![crate::decoders::VP8_FOURCC],
        ))
    }

    #[cfg(any(feature = "videoson", feature = "videoson-vp9"))]
    pub fn new_vp9(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: CodecType::VP9,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: None,
        };
        let opts = VideoDecoderOptions {
            output_format: VideoOutputFormat::Nv12,
            ..Default::default()
        };
        let decoder = videoson::codec_vp9::Vp9Decoder::try_new(&params, &opts)
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self::new_impl(
            Box::new(decoder),
            "videoson (VP9)",
            vec![crate::decoders::VP9_FOURCC],
        ))
    }

    #[cfg(feature = "av1")]
    pub fn new_av1(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: CodecType::AV1,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: None,
        };
        let opts = VideoDecoderOptions {
            output_format: VideoOutputFormat::Nv12,
            ..Default::default()
        };
        let decoder = videoson::codec_rav1d::Rav1dSafeDecoder::try_new(&params, &opts)
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self::new_impl(
            Box::new(decoder),
            "videoson (AV1)",
            vec![crate::decoders::AV1_FOURCC, crate::decoders::AV1_IVF_FOURCC],
        ))
    }

    fn drain_ready_frames(&mut self, fallback_pts_us: i64) -> Result<(), DecodeBackendError> {
        while let Some(frame) = self
            .inner
            .receive_frame()
            .map_err(|e| DecodeBackendError::Other(format!("videoson recv: {:?}", e)))?
        {
            let (w, h) = (frame.width as usize, frame.height as usize);

            let rgba = match frame.pixfmt {
                PixelFormat::Nv12 => {
                    let y = match &frame.plane_data[0].data {
                        videoson::PlaneData::U8(v) => v.as_slice(),
                        _ => {
                            return Err(DecodeBackendError::Other(
                                "videoson: unsupported bit depth".into(),
                            ));
                        }
                    };
                    let uv = match &frame.plane_data[1].data {
                        videoson::PlaneData::U8(v) => v.as_slice(),
                        _ => {
                            return Err(DecodeBackendError::Other(
                                "videoson: unsupported bit depth".into(),
                            ));
                        }
                    };
                    let uv_stride = frame.plane_data[1].stride;
                    let mut rgba = vec![0u8; w * h * 4];
                    for row in 0..h {
                        for col in 0..w {
                            let yi = row * frame.plane_data[0].stride + col;
                            let uvi = (row / 2) * uv_stride + (col / 2) * 2;
                            let yy = y[yi] as f32;
                            let uu = uv.get(uvi).copied().unwrap_or(128) as f32 - 128.0;
                            let vv = uv.get(uvi + 1).copied().unwrap_or(128) as f32 - 128.0;
                            let base = (row * w + col) * 4;
                            rgba[base] = (yy + 1.402 * vv).clamp(0.0, 255.0) as u8;
                            rgba[base + 1] =
                                (yy - 0.344136 * uu - 0.714136 * vv).clamp(0.0, 255.0) as u8;
                            rgba[base + 2] = (yy + 1.772 * uu).clamp(0.0, 255.0) as u8;
                            rgba[base + 3] = 255;
                        }
                    }
                    rgba
                }
                PixelFormat::Yuv420 => {
                    let y_stride = frame.plane_data[0].stride;
                    let u_stride = frame.plane_data.get(1).map(|p| p.stride).unwrap_or(w / 2);
                    let v_stride = frame.plane_data.get(2).map(|p| p.stride).unwrap_or(w / 2);
                    let y_data = match &frame.plane_data[0].data {
                        videoson::PlaneData::U8(v) => v.as_slice(),
                        _ => {
                            return Err(DecodeBackendError::Other(
                                "videoson: unsupported bit depth".into(),
                            ));
                        }
                    };
                    let u_data = frame
                        .plane_data
                        .get(1)
                        .and_then(|p| match &p.data {
                            videoson::PlaneData::U8(v) => Some(v.as_slice()),
                            _ => None,
                        })
                        .unwrap_or(&[]);
                    let v_data = frame
                        .plane_data
                        .get(2)
                        .and_then(|p| match &p.data {
                            videoson::PlaneData::U8(v) => Some(v.as_slice()),
                            _ => None,
                        })
                        .unwrap_or(&[]);
                    crate::yuv::yuv420_to_rgba(
                        y_data, u_data, v_data, w, h, y_stride, u_stride, v_stride,
                    )
                }
                PixelFormat::Gray => {
                    let y_data = match &frame.plane_data[0].data {
                        videoson::PlaneData::U8(v) => v.as_slice(),
                        _ => {
                            return Err(DecodeBackendError::Other(
                                "videoson: unsupported bit depth".into(),
                            ));
                        }
                    };
                    let y_stride = frame.plane_data[0].stride;
                    let mut rgba = vec![0u8; w * h * 4];
                    for row in 0..h {
                        for col in 0..w {
                            let v = y_data[row * y_stride + col];
                            let base = (row * w + col) * 4;
                            rgba[base] = v;
                            rgba[base + 1] = v;
                            rgba[base + 2] = v;
                            rgba[base + 3] = 255;
                        }
                    }
                    rgba
                }
                _ => {
                    return Err(DecodeBackendError::Other(
                        "unsupported pixel format".into(),
                    ));
                }
            };

            let pts_from_frame = frame.pts.map(|p| p / 1000).unwrap_or(fallback_pts_us);

            self.queued.push_back(RgbaFrame {
                width: w as u32,
                height: h as u32,
                data: rgba,
                pts_us: if frame.pts.is_some() {
                    pts_from_frame
                } else {
                    fallback_pts_us
                },
            });
        }
        Ok(())
    }
}

impl VideoDecoderBackend for VideosonBackend {
    fn name(&self) -> &'static str {
        self.backend_name
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        self.allowed_fourccs.contains(&fourcc)
    }

    fn decode_frame(
        &mut self,
        data: &[u8],
        pts_us: i64,
        is_sync: bool,
    ) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        let packet = VideoPacket {
            track_id: 0,
            pts: Some(pts_us * 1000),
            dts: None,
            duration: None,
            is_sync,
            data: data.to_vec(),
        };
        self.inner
            .send_packet(&packet)
            .map_err(|e| DecodeBackendError::Other(format!("videoson send: {:?}", e)))?;

        self.drain_ready_frames(pts_us)?;
        Ok(self.queued.pop_front())
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        if !self.eos_sent {
            self.inner
                .send_eos()
                .map_err(|e| DecodeBackendError::Other(format!("videoson eos: {:?}", e)))?;
            self.eos_sent = true;
        }
        self.drain_ready_frames(0)?;
        Ok(self.queued.pop_front())
    }

    fn reset(&mut self) {
        self.inner.reset();
        self.queued.clear();
        self.eos_sent = false;
    }
}
