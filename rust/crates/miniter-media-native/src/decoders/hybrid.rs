//! A hardware decoder that becomes the software decoder for the same stream when it fails
//! mid-stream, without the caller noticing.
//!
//! Ported in spirit from filmcraft's `HybridDecoder` (MIT OR Apache-2.0): the samples since the
//! last point decoding can restart from (the run start, then every sync sample) are kept; on a
//! failure the software decoder is built, the log replayed through it, and the pictures the
//! hardware already returned are dropped, so the output continues as the software decoder's own
//! sequence. The replay log is bounded; past the bound a failure is reported as an error and the
//! caller falls back the usual way (a new seek re-creates the decoder from software).

use std::collections::{BTreeSet, VecDeque};

use crate::demux::{DecodeBackendError, VideoDecoderBackend};

/// Replay log bounds: samples and bytes since the last restart point.
const LOG_MAX_SAMPLES: usize = 600;
const LOG_MAX_BYTES: usize = 256 << 20;
/// Presentation times remembered as returned (older ones are forgotten first).
const EMITTED_MAX: usize = 4096;

type MakeSoftware = Box<dyn Fn() -> Result<Box<dyn VideoDecoderBackend>, String>>;

pub struct HybridBackend {
    hw: Option<Box<dyn VideoDecoderBackend>>,
    sw: Option<Box<dyn VideoDecoderBackend>>,
    make_sw: Option<MakeSoftware>,
    /// Samples (data, pts, sync) since the last restart point; `None` once the bound was passed.
    log: Option<VecDeque<(Vec<u8>, i64, bool)>>,
    log_bytes: usize,
    /// Presentation times of the pictures returned so far, to drop the hardware's from the replay.
    emitted: BTreeSet<i64>,
    /// The fallback happened past the replay bound: skip samples until the next restart point.
    sw_waiting_for_sync: bool,
}

impl HybridBackend {
    pub fn new(hw: Box<dyn VideoDecoderBackend>, make_sw: MakeSoftware) -> Self {
        Self {
            hw: Some(hw),
            sw: None,
            make_sw: Some(make_sw),
            log: Some(VecDeque::new()),
            log_bytes: 0,
            emitted: BTreeSet::new(),
            sw_waiting_for_sync: false,
        }
    }

    /// Keep `data` for replay. A sync sample is a restart point, so older samples are dropped.
    fn record(&mut self, data: &[u8], pts_us: i64, is_sync: bool) {
        if is_sync {
            self.log = Some(VecDeque::new());
            self.log_bytes = 0;
        }
        let Some(log) = self.log.as_mut() else {
            return;
        };
        self.log_bytes += data.len();
        if log.len() >= LOG_MAX_SAMPLES || self.log_bytes > LOG_MAX_BYTES {
            self.log = None;
            self.log_bytes = 0;
            return;
        }
        log.push_back((data.to_vec(), pts_us, is_sync));
    }

    fn note_emitted(&mut self, frame: &crate::RgbaFrame) {
        if self.emitted.len() >= EMITTED_MAX
            && let Some(&oldest) = self.emitted.iter().next()
        {
            self.emitted.remove(&oldest);
        }
        self.emitted.insert(frame.pts_us);
    }

    /// Replace the hardware decoder with a software one and replay the log through it,
    /// returning the newest picture the hardware had not returned yet.
    fn fall_back(
        &mut self,
        error: DecodeBackendError,
    ) -> Result<Option<crate::RgbaFrame>, DecodeBackendError> {
        let Some(make) = self.make_sw.take() else {
            return Err(error);
        };
        let mut sw = make().map_err(|e| {
            DecodeBackendError::Other(format!("software decoder after a hardware failure: {e}"))
        })?;
        let replayable = self.log.is_some();
        if replayable {
            log::warn!("hardware decoder failed mid-stream ({error}); continuing in software");
        } else {
            // Past the replay bound the samples needed to reconstruct the stream are gone:
            // report the failure once, and take over at the next restart point.
            log::warn!(
                "hardware decoder failed mid-stream ({error}) past the replay log bound; \
                 continuing in software from the next sync sample"
            );
        }
        let mut newest = None;
        if let Some(log) = self.log.take() {
            for (data, pts_us, is_sync) in log {
                match sw.decode_frame(&data, pts_us, is_sync) {
                    Ok(Some(frame)) => {
                        if !self.emitted.contains(&frame.pts_us) {
                            newest = Some(frame);
                        }
                    }
                    Ok(None) => {}
                    Err(e) => {
                        log::warn!("software replay failed: {e}");
                        break;
                    }
                }
            }
        }
        self.hw = None;
        self.sw = Some(sw);
        self.sw_waiting_for_sync = !replayable;
        if replayable { Ok(newest) } else { Err(error) }
    }
}

impl VideoDecoderBackend for HybridBackend {
    fn name(&self) -> &'static str {
        "hardware + software fallback"
    }

    fn is_supported(&self, fourcc: u32) -> bool {
        if let Some(hw) = &self.hw {
            return hw.is_supported(fourcc);
        }
        self.sw.as_ref().is_some_and(|sw| sw.is_supported(fourcc))
    }

    fn decode_frame(
        &mut self,
        data: &[u8],
        pts_us: i64,
        is_sync: bool,
    ) -> Result<Option<crate::RgbaFrame>, DecodeBackendError> {
        if self.sw.is_some() {
            if self.sw_waiting_for_sync {
                if !is_sync {
                    return Ok(None);
                }
                self.sw_waiting_for_sync = false;
            }
            let Some(sw) = self.sw.as_mut() else {
                return Ok(None);
            };
            return sw.decode_frame(data, pts_us, is_sync);
        }
        self.record(data, pts_us, is_sync);
        let Some(hw) = self.hw.as_mut() else {
            return Ok(None);
        };
        match hw.decode_frame(data, pts_us, is_sync) {
            Ok(frame) => {
                if let Some(f) = &frame {
                    self.note_emitted(f);
                    crate::hw_stats::note_decode_frame();
                }
                Ok(frame)
            }
            Err(e) => {
                crate::hw_stats::note_decode_fallback();
                self.fall_back(e)
            }
        }
    }

    fn finish(&mut self) -> Result<Option<crate::RgbaFrame>, DecodeBackendError> {
        if self.sw_waiting_for_sync {
            return Ok(None);
        }
        if let Some(sw) = self.sw.as_mut() {
            return sw.finish();
        }
        let Some(hw) = self.hw.as_mut() else {
            return Ok(None);
        };
        match hw.finish() {
            Ok(frame) => {
                if let Some(f) = &frame {
                    self.note_emitted(f);
                    crate::hw_stats::note_decode_frame();
                }
                Ok(frame)
            }
            Err(e) => {
                let replayed = self.fall_back(e)?;
                let tail = self.sw.as_mut().and_then(|sw| sw.finish().ok().flatten());
                Ok(tail.or(replayed))
            }
        }
    }

    fn reset(&mut self) {
        if let Some(hw) = self.hw.as_mut() {
            hw.reset();
        }
        if let Some(sw) = self.sw.as_mut() {
            sw.reset();
        }
        self.log = Some(VecDeque::new());
        self.log_bytes = 0;
        self.emitted.clear();
        // A software fallback stays in place: a seek does not bring the hardware back, and the
        // caller creates a fresh backend (which tries hardware again) when it wants one.
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::RgbaFrame;

    struct FakeBackend {
        fail_after: Option<u32>,
        fed: u32,
        width: u32,
        height: u32,
    }

    impl FakeBackend {
        fn new(fail_after: Option<u32>) -> Self {
            Self {
                fail_after,
                fed: 0,
                width: 4,
                height: 4,
            }
        }
    }

    impl VideoDecoderBackend for FakeBackend {
        fn name(&self) -> &'static str {
            "fake"
        }
        fn is_supported(&self, _fourcc: u32) -> bool {
            true
        }
        fn decode_frame(
            &mut self,
            _data: &[u8],
            pts_us: i64,
            _is_sync: bool,
        ) -> Result<Option<RgbaFrame>, DecodeBackendError> {
            self.fed += 1;
            if self.fail_after.is_some_and(|n| self.fed > n) {
                return Err(DecodeBackendError::Other("lost device".into()));
            }
            Ok(Some(RgbaFrame {
                width: self.width,
                height: self.height,
                data: vec![0x40; 4 * 4 * 4],
                pts_us,
                color_info: Default::default(),
            }))
        }
        fn finish(&mut self) -> Result<Option<RgbaFrame>, DecodeBackendError> {
            Ok(None)
        }
        fn reset(&mut self) {
            self.fed = 0;
        }
    }

    fn hybrid(fail_after: u32) -> HybridBackend {
        HybridBackend::new(
            Box::new(FakeBackend::new(Some(fail_after))),
            Box::new(|| Ok(Box::new(FakeBackend::new(None)) as Box<dyn VideoDecoderBackend>)),
        )
    }

    #[test]
    fn a_mid_stream_failure_continues_in_software_from_the_last_sync_sample() {
        let mut dec = hybrid(3);
        // a run: sync at 0, then 10, 20, 30 — the fourth decode fails
        assert!(dec.decode_frame(b"a", 0, true).unwrap().is_some());
        assert!(dec.decode_frame(b"b", 10, false).unwrap().is_some());
        assert!(dec.decode_frame(b"c", 20, false).unwrap().is_some());
        // failure on sample 3: the replay (samples 0..=3) must return its picture, and must not
        // return the pictures 0..2 a second time.
        let frame = dec.decode_frame(b"d", 30, false).unwrap().unwrap();
        assert_eq!(frame.pts_us, 30);
        // the software decoder now serves the stream
        assert_eq!(
            dec.decode_frame(b"e", 40, false).unwrap().unwrap().pts_us,
            40
        );
    }

    #[test]
    fn the_log_starts_at_the_latest_restart_point() {
        let mut dec = hybrid(1);
        assert!(dec.decode_frame(b"a", 0, true).unwrap().is_some());
        assert!(dec.decode_frame(b"b", 10, false).unwrap().is_some());
        // a second sync sample drops the samples before it
        assert!(dec.decode_frame(b"c", 20, true).unwrap().is_some());
        // fails on the sample after the sync: the replay must start at pts 20, not 0
        let frame = dec.decode_frame(b"d", 30, false).unwrap().unwrap();
        assert_eq!(frame.pts_us, 30);
    }

    #[test]
    fn a_failure_past_the_log_bound_is_reported_once_and_resumes_at_the_next_sync() {
        let mut dec = hybrid(LOG_MAX_SAMPLES as u32 + 1);
        assert!(dec.decode_frame(b"a", 0, true).unwrap().is_some());
        for i in 1..=LOG_MAX_SAMPLES as i64 {
            let _ = dec.decode_frame(b"b", i, false).unwrap();
        }
        // the log is past its bound, so the failure is reported
        let err = dec.decode_frame(b"c", 1000, false);
        assert!(err.is_err());
        // non-sync samples are skipped until a restart point
        assert!(dec.decode_frame(b"d", 1010, false).unwrap().is_none());
        // the next sync sample resumes decoding in software
        assert_eq!(
            dec.decode_frame(b"e", 1020, true).unwrap().unwrap().pts_us,
            1020
        );
    }
}
