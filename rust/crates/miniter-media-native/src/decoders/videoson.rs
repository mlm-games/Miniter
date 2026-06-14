use crate::decoders::DecodeError;
use crate::decoders::H264_FOURCC;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;
use std::collections::VecDeque;
use videoson::{
    NalFormat, Packet as VideoPacket, VideoCodecParams, VideoDecoder, VideoDecoderOptions,
    codec_h264::H264Decoder,
};

pub struct VideosonBackend {
    inner: H264Decoder,
    queued: VecDeque<RgbaFrame>,
    eos_sent: bool,
    width: u32,
    height: u32,
}

impl VideosonBackend {
    pub fn new(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: videoson::CodecType::H264,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: Some(NalFormat::AnnexB),
        };
        let inner = H264Decoder::try_new(&params, &VideoDecoderOptions::default())
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self {
            inner,
            queued: VecDeque::new(),
            eos_sent: false,
            width,
            height,
        })
    }

    fn drain_ready_frames(&mut self, fallback_pts_us: i64) -> Result<(), DecodeBackendError> {
        while let Some(frame) = self
            .inner
            .receive_frame()
            .map_err(|e| DecodeBackendError::Other(format!("videoson recv: {:?}", e)))?
        {
            let (w, h) = (frame.width as usize, frame.height as usize);
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

            let mut rgba = vec![0u8; w * h * 4];
            for y in 0..h {
                for x in 0..w {
                    let yi = y * y_stride + x;
                    let ui = y / 2 * u_stride + x / 2;
                    let vi = y / 2 * v_stride + x / 2;
                    let yy = y_data[yi] as f32;
                    let uu = u_data.get(ui).copied().unwrap_or(128) as f32 - 128.0;
                    let vv = v_data.get(vi).copied().unwrap_or(128) as f32 - 128.0;
                    let r = (yy + 1.402 * vv).clamp(0.0, 255.0) as u8;
                    let g = (yy - 0.344 * uu - 0.714 * vv).clamp(0.0, 255.0) as u8;
                    let b = (yy + 1.772 * uu).clamp(0.0, 255.0) as u8;
                    rgba[((y * w + x) * 4)] = r;
                    rgba[(y * w + x) * 4 + 1] = g;
                    rgba[(y * w + x) * 4 + 2] = b;
                    rgba[(y * w + x) * 4 + 3] = 255;
                }
            }
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
        "videoson (H.264)"
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        fourcc == H264_FOURCC
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
