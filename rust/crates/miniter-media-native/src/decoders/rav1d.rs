use std::sync::OnceLock;

use videoson::{
    CodecRegistry, CodecType, Packet as VideoPacket, VideoCodecParams, VideoDecoder,
    VideoDecoderOptions, codec_rav1d::Rav1dSafeDecoder,
};

use crate::decoders::{AV1_FOURCC, AV1_IVF_FOURCC, DecodeError};
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;

fn get_registry() -> &'static CodecRegistry {
    static REGISTRY: OnceLock<CodecRegistry> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut registry = CodecRegistry::new();
        registry.register_video_decoder::<Rav1dSafeDecoder>();
        registry
    })
}

pub struct Av1Backend {
    inner: Box<dyn VideoDecoder>,
    width: u32,
    height: u32,
}

impl Av1Backend {
    pub fn new(width: u32, height: u32) -> Result<Self, DecodeError> {
        let params = VideoCodecParams {
            codec: CodecType::AV1,
            coded_width: width,
            coded_height: height,
            extradata: Vec::new(),
            nal_format: None,
        };
        let inner = get_registry()
            .make_video_decoder(CodecType::AV1, &params, &VideoDecoderOptions::default())
            .ok_or(DecodeError::DecoderNotAvailable("AV1".into()))?
            .map_err(|e| DecodeError::Videoson(format!("{:?}", e)))?;
        Ok(Self {
            inner,
            width,
            height,
        })
    }
}

impl VideoDecoderBackend for Av1Backend {
    fn name(&self) -> &'static str {
        "videoson-codec-rav1d (AV1)"
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        fourcc == AV1_FOURCC || fourcc == AV1_IVF_FOURCC
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
            .map_err(|e| DecodeBackendError::Other(format!("send: {:?}", e)))?;

        match self.inner.receive_frame() {
            Ok(Some(frame)) => {
                let rgba = frame_to_rgba(&frame)?;
                Ok(Some(RgbaFrame { pts_us, ..rgba }))
            }
            Ok(None) => Ok(None),
            Err(e) => Err(DecodeBackendError::Other(format!("recv: {:?}", e))),
        }
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        self.inner
            .send_eos()
            .map_err(|e| DecodeBackendError::Other(format!("eos: {:?}", e)))?;
        let mut last = None;
        loop {
            match self.inner.receive_frame() {
                Ok(Some(frame)) => last = Some(frame_to_rgba(&frame)?),
                Ok(None) => break,
                Err(e) => return Err(DecodeBackendError::Other(format!("recv: {:?}", e))),
            }
        }
        Ok(last)
    }

    fn reset(&mut self) {
        self.inner.reset();
    }
}

fn frame_to_rgba(frame: &videoson::VideoFrame) -> Result<RgbaFrame, DecodeBackendError> {
    let w = frame.width as usize;
    let h = frame.height as usize;
    let y_stride = frame.plane_data[0].stride;
    let u_stride = frame.plane_data.get(1).map(|p| p.stride).unwrap_or(w / 2);
    let v_stride = frame.plane_data.get(2).map(|p| p.stride).unwrap_or(w / 2);

    let y_data = match &frame.plane_data[0].data {
        videoson::PlaneData::U8(v) => v.as_slice(),
        _ => return Err(DecodeBackendError::Other("unsupported bit depth".into())),
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
            rgba[(y * w + x) * 4 + 0] = r;
            rgba[(y * w + x) * 4 + 1] = g;
            rgba[(y * w + x) * 4 + 2] = b;
            rgba[(y * w + x) * 4 + 3] = 255;
        }
    }

    Ok(RgbaFrame {
        width: frame.width,
        height: frame.height,
        data: rgba,
        pts_us: 0,
    })
}
