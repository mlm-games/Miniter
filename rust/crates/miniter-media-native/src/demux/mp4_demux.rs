//! mp4 crate demuxer.

use super::{DemuxError, DemuxResult, DemuxedSample, Demuxer, VideoContainer};
use crate::decoders::{AV1_FOURCC, H264_FOURCC, H265_FOURCC, VP9_FOURCC};
use std::io::{BufReader, Read, Seek};
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Codec {
    H264,
    H265,
    Vp9,
    Unknown,
}

impl Codec {
    fn fourcc(self) -> u32 {
        match self {
            Codec::H264 => H264_FOURCC,
            Codec::H265 => H265_FOURCC,
            Codec::Vp9 => VP9_FOURCC,
            Codec::Unknown => AV1_FOURCC,
        }
    }

    fn name(self) -> &'static str {
        match self {
            Codec::H264 => "H.264",
            Codec::H265 => "H.265",
            Codec::Vp9 => "VP9",
            Codec::Unknown => "AV1",
        }
    }

    fn needs_annex_b(self) -> bool {
        matches!(self, Codec::H264 | Codec::H265)
    }
}

pub struct Mp4Demuxer<R: Read + Seek> {
    mp4: mp4::Mp4Reader<R>,
    track_id: u32,
    width: u32,
    height: u32,
    timescale: u32,
    total_samples: u32,
    current_sample: u32,
    codec: Codec,
    nalu_length_size: u8,
    sps: Option<Vec<u8>>,
    pps: Option<Vec<u8>>,
    /// AnnexB-formatted codec configuration (VPS/SPS/PPS for HEVC, etc.)
    /// Prepended to the first sample.
    codec_config: Vec<u8>,
    last_pts_us: i64,
}

/// Parse an HEVC hvcC configuration record and return the VPS/SPS/PPS NAL
/// units as a single AnnexB byte buffer.
fn parse_hvcc(data: &[u8]) -> Vec<u8> {
    if data.len() < 23 || data[0] != 1 {
        return Vec::new();
    }
    let num_arrays = data[22] as usize;
    let mut out = Vec::new();
    let mut pos = 23usize;
    for _ in 0..num_arrays {
        if pos >= data.len() {
            break;
        }
        let _nal_type = data[pos] & 0x3F;
        pos += 1;
        if pos + 2 > data.len() {
            break;
        }
        let num_nalus = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
        pos += 2;
        for _ in 0..num_nalus {
            if pos + 2 > data.len() {
                break;
            }
            let len = u16::from_be_bytes([data[pos], data[pos + 1]]) as usize;
            pos += 2;
            if pos + len > data.len() {
                break;
            }
            out.extend_from_slice(&[0x00, 0x00, 0x00, 0x01]);
            out.extend_from_slice(&data[pos..pos + len]);
            pos += len;
        }
    }
    out
}

fn read_hvcc_from_path(path: &Path) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let mut f = std::fs::File::open(path).map_err(DemuxError::Io)?;
    let file_size = f.metadata().map_err(DemuxError::Io)?.len();
    let mut pos = 0u64;

    while pos + 8 <= file_size {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let box_size = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if box_size < 8 {
            break;
        }
        let next_pos = pos + box_size;
        if &hdr[4..8] == b"moov" {
            if let Ok(data) = find_hvcc_in_moov(&mut f, pos, box_size) {
                return Ok(data);
            }
        }
        pos = next_pos;
    }
    Err(DemuxError::Other("hvcc box not found".into()))
}

fn find_hvcc_in_moov(f: &mut std::fs::File, moov_start: u64, moov_size: u64) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let moov_end = moov_start + moov_size;
    let mut pos = moov_start + 8;
    while pos + 8 <= moov_end {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if bs < 8 {
            break;
        }
        let next = pos + bs;
        if &hdr[4..8] == b"trak" {
            if let Ok(data) = find_hvcc_in_trak(f, pos, bs) {
                return Ok(data);
            }
        }
        pos = next;
    }
    Err(DemuxError::Other("hvcc box not found in moov".into()))
}

fn find_hvcc_in_trak(f: &mut std::fs::File, trak_start: u64, trak_size: u64) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let trak_end = trak_start + trak_size;
    let mut pos = trak_start + 8;
    while pos + 8 <= trak_end {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if bs < 8 {
            break;
        }
        let next = pos + bs;
        if &hdr[4..8] == b"mdia" {
            if let Ok(data) = find_hvcc_in_mdia(f, pos, bs) {
                return Ok(data);
            }
        }
        pos = next;
    }
    Err(DemuxError::Other("hvcc box not found in trak".into()))
}

fn find_hvcc_in_mdia(f: &mut std::fs::File, mdia_start: u64, mdia_size: u64) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let mdia_end = mdia_start + mdia_size;
    let mut pos = mdia_start + 8;
    while pos + 8 <= mdia_end {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if bs < 8 {
            break;
        }
        let next = pos + bs;
        if &hdr[4..8] == b"minf" {
            if let Ok(data) = find_hvcc_in_minf(f, pos, bs) {
                return Ok(data);
            }
        }
        pos = next;
    }
    Err(DemuxError::Other("hvcc box not found in mdia".into()))
}

fn find_hvcc_in_minf(f: &mut std::fs::File, minf_start: u64, minf_size: u64) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let minf_end = minf_start + minf_size;
    let mut pos = minf_start + 8;
    while pos + 8 <= minf_end {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if bs < 8 {
            break;
        }
        let next = pos + bs;
        if &hdr[4..8] == b"stbl" {
            if let Ok(data) = find_hvcc_in_stbl(f, pos, bs) {
                return Ok(data);
            }
        }
        pos = next;
    }
    Err(DemuxError::Other("hvcc box not found in minf".into()))
}

fn find_hvcc_in_stbl(f: &mut std::fs::File, stbl_start: u64, stbl_size: u64) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let stbl_end = stbl_start + stbl_size;
    let mut pos = stbl_start + 8;
    while pos + 8 <= stbl_end {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if bs < 8 {
            break;
        }
        let next = pos + bs;
        if &hdr[4..8] == b"stsd" {
            if let Ok(data) = find_hvcc_in_stsd(f, pos, bs) {
                return Ok(data);
            }
        }
        pos = next;
    }
    Err(DemuxError::Other("hvcc box not found in stbl".into()))
}

fn find_hvcc_in_stsd(f: &mut std::fs::File, stsd_start: u64, stsd_size: u64) -> DemuxResult<Vec<u8>> {
    use std::io::Read;
    let stsd_end = stsd_start + stsd_size;
    let mut pos = stsd_start + 8 + 8; // skip stsd header (version + entry_count)
    while pos + 8 <= stsd_end {
        f.seek(std::io::SeekFrom::Start(pos)).map_err(DemuxError::Io)?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
        let bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
        if bs < 8 {
            break;
        }
        let next = pos + bs;
        let bt = &hdr[4..8];
        if bt == b"hev1" || bt == b"hvc1" {
            let entry_end = next;
            // VisualSampleEntry fields after 16-byte box+SampleEntry header:
            // pre_defined1(2)+reserved1(2)+pre_defined2(12)+width(2)+height(2)
            // +horizresolution(4)+vertresolution(4)+reserved2(4)+frame_count(2)
            // +compressorname(32)+depth(2)+pre_defined3(2) = 70 bytes
            const HEADER_SKIP: u64 = 8 + 8 + 70;
            let mut p = pos + HEADER_SKIP;
            while p + 8 <= entry_end {
                f.seek(std::io::SeekFrom::Start(p)).map_err(DemuxError::Io)?;
                f.read_exact(&mut hdr).map_err(DemuxError::Io)?;
                let child_bs = u32::from_be_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]) as u64;
                if child_bs < 8 {
                    break;
                }
                let child_next = p + child_bs;
                if &hdr[4..8] == b"hvcC" {
                    let payload_len = child_bs - 8;
                    let mut buf = vec![0u8; payload_len as usize];
                    f.seek(std::io::SeekFrom::Start(p + 8)).map_err(DemuxError::Io)?;
                    f.read_exact(&mut buf).map_err(DemuxError::Io)?;
                    return Ok(buf);
                }
                p = child_next;
            }
        }
        pos = next;
    }
    Err(DemuxError::Other("hvcc box not found in stsd".into()))
}

impl<R: Read + Seek> Mp4Demuxer<R> {
    pub fn from_reader(reader: R, size: u64) -> DemuxResult<Self> {
        Self::from_reader_hvcc(reader, size, Vec::new())
    }

    pub fn from_reader_hvcc(reader: R, size: u64, hvcc_data: Vec<u8>) -> DemuxResult<Self> {
        let mp4 = mp4::Mp4Reader::read_header(reader, size)
            .map_err(|e| DemuxError::Other(e.to_string()))?;
        let track = mp4
            .tracks()
            .values()
            .find(|t| matches!(t.track_type(), Ok(mp4::TrackType::Video)))
            .ok_or(DemuxError::NoVideoTrack)?;

        let track_id = track.track_id();
        let total_samples = track.sample_count();
        let width = track.width() as u32;
        let height = track.height() as u32;
        let timescale = track.timescale();

        let codec = if track.trak.mdia.minf.stbl.stsd.avc1.is_some() {
            Codec::H264
        } else if track.trak.mdia.minf.stbl.stsd.hev1.is_some() {
            Codec::H265
        } else if track.trak.mdia.minf.stbl.stsd.vp09.is_some() {
            Codec::Vp9
        } else {
            return Err(DemuxError::Other(format!(
                "Unsupported sample entry in video track"
            )));
        };

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

        let codec_config = if codec == Codec::H265 {
            parse_hvcc(&hvcc_data)
        } else {
            Vec::new()
        };

        Ok(Self {
            mp4,
            track_id,
            width,
            height,
            timescale,
            total_samples,
            current_sample: 1,
            codec,
            nalu_length_size,
            sps,
            pps,
            codec_config,
            last_pts_us: -1,
        })
    }

    pub fn codec_fourcc(&self) -> u32 {
        self.codec.fourcc()
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
            if !saw_sps && let Some(sps_nal) = self.sps.as_deref() {
                prefixed.extend_from_slice(start_code);
                prefixed.extend_from_slice(sps_nal);
            }
            if !saw_pps && let Some(pps_nal) = self.pps.as_deref() {
                prefixed.extend_from_slice(start_code);
                prefixed.extend_from_slice(pps_nal);
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
        self.codec.name()
    }

    fn fourcc(&self) -> u32 {
        self.codec_fourcc()
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

        let mut data = if self.codec.needs_annex_b() {
            self.to_annex_b(&sample.bytes)
        } else {
            sample.bytes.to_vec()
        };

        // Prepend codec config (VPS/SPS/PPS for HEVC) to the first sample.
        if !self.codec_config.is_empty() {
            let mut prefixed = std::mem::take(&mut self.codec_config);
            prefixed.extend_from_slice(&data);
            data = prefixed;
        }

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
        let hvcc_data = read_hvcc_from_path(path).unwrap_or_default();
        let file = std::fs::File::open(path)?;
        let size = file.metadata()?.len();
        Self::from_reader_hvcc(BufReader::new(file), size, hvcc_data)
    }
}
