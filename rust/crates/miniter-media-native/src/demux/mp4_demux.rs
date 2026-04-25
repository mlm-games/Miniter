//! mp4 crate demuxer.

use super::{DemuxError, DemuxResult, DemuxedSample, Demuxer, VideoContainer};
use std::io::{BufReader, Read, Seek};

const H264_FOURCC: u32 = 0x31637661;

pub struct Mp4Demuxer<R: Read + Seek> {
    mp4: mp4::Mp4Reader<R>,
    track_id: u32,
    width: u32,
    height: u32,
    timescale: u32,
    total_samples: u32,
    current_sample: u32,
    nalu_length_size: u8,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    last_pts_us: i64,
}

impl<R: Read + Seek> Mp4Demuxer<R> {
    pub fn from_reader(reader: R, size: u64) -> DemuxResult<Self> {
        let mp4 = mp4::Mp4Reader::read_header(reader, size)
            .map_err(|e| DemuxError::Other(e.to_string()))?;
        let track = mp4
            .tracks()
            .values()
            .find(|t| matches!(t.track_type(), Ok(mp4::TrackType::Video)))
            .ok_or(DemuxError::NoVideoTrack)?;

        let media_type = track
            .media_type()
            .map_err(|_| DemuxError::Other("Unknown media type".into()))?;

        let track_id = track.track_id();
        let total_samples = track.sample_count();
        let width = track.width() as u32;
        let height = track.height() as u32;
        let timescale = track.timescale();

        let (nalu_length_size, sps, pps) =
            if let Some(ref avc1) = track.trak.mdia.minf.stbl.stsd.avc1 {
                (
                    (avc1.avcc.length_size_minus_one & 0x03) + 1,
                    avc1.avcc
                        .sequence_parameter_sets
                        .first()
                        .map(|n| n.bytes.clone()),
                    avc1.avcc
                        .picture_parameter_sets
                        .first()
                        .map(|n| n.bytes.clone()),
                )
            } else {
                (4, None, None)
            };

        Ok(Self {
            mp4,
            track_id,
            width,
            height,
            timescale,
            total_samples,
            current_sample: 1,
            nalu_length_size,
            sps,
            pps,
            last_pts_us: -1,
        })
    }

    pub fn codec_fourcc(&self) -> u32 {
        H264_FOURCC
    }

    fn to_annex_b(&self, data: &[u8]) -> Vec<u8> {
        let start_code: &[u8] = &[0x00, 0x00, 0x00, 0x01];
        let mut output = Vec::with_capacity(data.len() + 128);
        let len_size = self.nalu_length_size.clamp(1, 4) as usize;
        let mut offset = 0;
        let mut saw_sps = false;
        let mut saw_pps = false;

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
                if let Some(sps_nal) = self.sps.as_deref() {
                    prefixed.extend_from_slice(start_code);
                    prefixed.extend_from_slice(sps_nal);
                }
            }
            if !saw_pps {
                if let Some(pps_nal) = self.pps.as_deref() {
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
}

impl<R: Read + Seek + Send> Demuxer for Mp4Demuxer<R> {
    fn container(&self) -> VideoContainer {
        VideoContainer::Mp4
    }

    fn width(&self) -> u32 {
        self.width
    }

    fn height(&self) -> u32 {
        self.height
    }

    fn timescale(&self) -> u32 {
        self.timescale
    }

    fn total_samples(&self) -> u32 {
        self.total_samples
    }

    fn codec_name(&self) -> &'static str {
        "H.264"
    }

    fn fourcc(&self) -> u32 {
        H264_FOURCC
    }

    fn format_name(&self) -> &'static str {
        "MP4"
    }

    fn next_sample(&mut self) -> DemuxResult<Option<DemuxedSample>> {
        if self.current_sample > self.total_samples {
            return Ok(Some(DemuxedSample::eos()));
        }

        let sample = self
            .mp4
            .read_sample(self.track_id, self.current_sample)
            .map_err(|e| DemuxError::Other(e.to_string()))?
            .ok_or_else(|| DemuxError::Other("Failed to read sample".into()))?;

        let pts = if self.timescale > 0 {
            let dts = sample.start_time as i64;
            let pts_raw = dts + sample.rendering_offset as i64;
            (pts_raw * 1_000_000) / (self.timescale as i64)
        } else {
            0
        };

        self.last_pts_us = pts;
        self.current_sample += 1;

        let data = self.to_annex_b(&sample.bytes);
        Ok(Some(DemuxedSample::new(data, pts, sample.is_sync)))
    }

    fn seek_to_sample(&mut self, sample_id: u32) -> DemuxResult<()> {
        self.current_sample = sample_id.max(1);
        self.last_pts_us = -1;
        Ok(())
    }

    fn reset(&mut self) -> DemuxResult<()> {
        self.current_sample = 1;
        self.last_pts_us = -1;
        Ok(())
    }
}

impl Mp4Demuxer<BufReader<std::fs::File>> {
    pub fn open(path: &std::path::Path) -> DemuxResult<Self> {
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        Self::from_reader(BufReader::new(file), size)
    }
}
