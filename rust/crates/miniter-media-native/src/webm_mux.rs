//! WebM muxer wrapping the `webm` crate (libwebm).

use std::io::{Seek, Write};
use webm::mux::{SegmentBuilder, VideoCodecId, Writer};

#[derive(Debug, thiserror::Error)]
pub enum WebmMuxError {
    #[error("IO: {0}")]
    Io(#[from] std::io::Error),
    #[error("WebM mux: {0}")]
    Webm(#[from] webm::mux::Error),
}

pub struct WebmMuxer<W: Write + Seek> {
    segment: webm::mux::Segment<W>,
    video_track: u64,
    frame_duration_ns: u64,
    current_time_ns: u64,
}

impl<W: Write + Seek + 'static> WebmMuxer<W> {
    pub fn new(
        output: W,
        width: u32,
        height: u32,
        fps: f64,
        codec: VideoCodecId,
    ) -> Result<Self, WebmMuxError> {
        let frame_duration_ns = (1_000_000_000.0 / fps) as u64;

        let writer = Writer::new(output);
        let builder = SegmentBuilder::new(writer)?;

        let (builder, video_track) = builder.add_video_track(width, height, codec, None)?;

        let segment = builder.build();

        Ok(Self {
            segment,
            video_track,
            frame_duration_ns,
            current_time_ns: 0,
        })
    }

    pub fn write_frame(&mut self, data: &[u8], is_keyframe: bool) -> Result<(), WebmMuxError> {
        self.segment
            .add_frame(self.video_track, data, self.current_time_ns, is_keyframe)?;
        self.current_time_ns += self.frame_duration_ns;
        Ok(())
    }

    pub fn finish(self) -> Result<(), WebmMuxError> {
        self.segment.finalize(None).map_err(|(_seg, e)| e)?;
        Ok(())
    }
}
