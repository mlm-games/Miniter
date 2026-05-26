use crate::decoders::DecodeError;
use crate::decoders::H265_FOURCC;
use crate::demux::{DecodeBackendError, VideoDecoderBackend};
use crate::frame::RgbaFrame;
use oxideav_core::{CodecId, CodecParameters, Frame, Packet, PixelFormat, TimeBase};

pub struct OxideAvBackend {
    decoder: Box<dyn oxideav_codec::Decoder + Send>,
    width: u32,
    height: u32,
}

impl OxideAvBackend {
    pub fn new(width: u32, height: u32) -> Result<Self, DecodeError> {
        let mut reg = oxideav_codec::CodecRegistry::new();
        oxideav_h265::register(&mut reg);

        let mut params = CodecParameters::video(CodecId::new("h265"));
        params.width = Some(width);
        params.height = Some(height);

        let decoder = reg
            .make_decoder(&params)
            .map_err(|e| DecodeError::OxideAv(format!("Failed to create decoder: {e}")))?;

        Ok(Self {
            decoder,
            width,
            height,
        })
    }
}

impl VideoDecoderBackend for OxideAvBackend {
    fn name(&self) -> &'static str {
        "oxideav (H.265)"
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        fourcc == H265_FOURCC
    }

    fn decode_frame(
        &mut self,
        data: &[u8],
        pts_us: i64,
        _is_sync: bool,
    ) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        let timebase = TimeBase::new(1, 90_000);
        let mut pkt = Packet::new(0, timebase, data.to_vec());
        pkt.pts = Some(pts_us);

        self.decoder
            .send_packet(&pkt)
            .map_err(|e| DecodeBackendError::Other(format!("oxideav send: {e}")))?;

        match self.decoder.receive_frame() {
            Ok(Frame::Video(vf)) => {
                let (w, h) = (vf.width as usize, vf.height as usize);
                let rgba = match vf.format {
                    PixelFormat::Yuv420P => {
                        if vf.planes.len() >= 3 {
                            let y_plane = &vf.planes[0];
                            let u_plane = &vf.planes[1];
                            let v_plane = &vf.planes[2];
                            let y = &y_plane.data;
                            let u = &u_plane.data;
                            let v = &v_plane.data;
                            crate::yuv::yuv420_to_rgba(
                                y,
                                u,
                                v,
                                w,
                                h,
                                y_plane.stride,
                                u_plane.stride,
                                v_plane.stride,
                            )
                        } else {
                            return Err(DecodeBackendError::Other(
                                "oxideav: invalid plane count".into(),
                            ));
                        }
                    }
                    other => {
                        return Err(DecodeBackendError::Other(format!(
                            "oxideav: unsupported pixel format {other:?}"
                        )));
                    }
                };
                Ok(Some(RgbaFrame {
                    width: vf.width as u32,
                    height: vf.height as u32,
                    data: rgba,
                    pts_us,
                }))
            }
            Ok(_) => Err(DecodeBackendError::Other(
                "oxideav: expected video frame".into(),
            )),
            Err(e) => {
                let err_str = format!("{e}");
                if err_str.contains("again") || err_str.contains("would block") {
                    Ok(None)
                } else {
                    Err(DecodeBackendError::Other(format!("oxideav recv: {e}")))
                }
            }
        }
    }

    fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
        self.decoder
            .flush()
            .map_err(|e| DecodeBackendError::Other(format!("oxideav flush: {e}")))?;
        Ok(None)
    }

    fn reset(&mut self) {
        let _ = self.decoder.reset();
    }
}
