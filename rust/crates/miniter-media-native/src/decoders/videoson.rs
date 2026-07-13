use crate::decoders::DecodeError;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::{ColorInfo, RgbaFrame};
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
    /// Buffer of recent PTS values used to derive frame duration from the
    /// minimum positive delta between sorted PTS values (display order).
    pts_buffer: Vec<i64>,
    /// Used to derive the true frame_duration_us for POC-based PTS correction.
    min_pts_delta_us: i64,
    /// Number of non-zero PTS values seen.  Once >= 2 the minimum delta
    /// gives a reliable frame duration and POC correction is activated.
    non_zero_pts_seen: u64,
    /// Cached duration derived from min_pts_delta_us.
    frame_duration_us: u64,
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
            pts_buffer: Vec::new(),
            min_pts_delta_us: i64::MAX,
            non_zero_pts_seen: 0,
            frame_duration_us: 0,
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

            let color_info = ColorInfo::infer(h as u32);

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
                    let y_stride = frame.plane_data[0].stride;
                    let uv_stride = frame.plane_data[1].stride;
                    crate::yuv::nv12_to_rgba_separate(
                        y, uv, w, h, y_stride, uv_stride, color_info,
                    )
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
                        y_data, u_data, v_data, w, h, y_stride, u_stride, v_stride, color_info,
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
                color_info,
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
        // We use minimum delta rather because µs-precision PTS values
        // have truncation noise (e.g. 33333.333 becomes 33333)
        if pts_us > 0 {
            self.pts_buffer.push(pts_us);
            self.non_zero_pts_seen += 1;
        }
        if self.non_zero_pts_seen >= 2 && self.pts_buffer.len() >= 2 {
            let mut sorted = self.pts_buffer.clone();
            sorted.sort_unstable();
            let mut min_delta = i64::MAX;
            for pair in sorted.windows(2) {
                let d = pair[1] - pair[0];
                if d > 0 && d < min_delta {
                    min_delta = d;
                }
            }
            if min_delta < self.min_pts_delta_us {
                self.min_pts_delta_us = min_delta;
                let new_fd = min_delta as u64;
                if new_fd != self.frame_duration_us {
                    self.frame_duration_us = new_fd;
                    self.inner.set_frame_duration_micros(new_fd);
                }
            }
        }

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
        let _ = self.inner.reset();
        self.queued.clear();
        self.eos_sent = false;
        self.pts_buffer.clear();
        self.min_pts_delta_us = i64::MAX;
        self.non_zero_pts_seen = 0;
        self.frame_duration_us = 0;
    }
}
