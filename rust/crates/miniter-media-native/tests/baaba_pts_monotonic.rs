//! Smoke test for BaabaBackend integration.
//!
//! NOTE: `BaabaBackend::pop_frame()` **sorts frames by PTS** before returning
//! them, which masks the cros-codecs DPB PTS bug (timestamps follow decode
//! order through the DPB instead of POC order).
//!
//! This test therefore cannot detect PTS mis-assignment — it only checks that
//! the basic decode pipeline produces the expected number of frames.
//!
//! The real PTS correctness check is `baaba_raw_pts`, which uses the raw
//! baaba decoder API directly (bypassing `pop_frame()`'s sort).

use std::time::Duration;

use miniter_media_native::decoders::baaba::BaabaBackend;
use miniter_media_native::decoders::{fourcc_to_mime, H265_FOURCC};
use miniter_media_native::demux::{open_demuxer, VideoDecoderBackend};

fn pts_dbg(pts: Duration) -> String {
    format!("{:.6}s", pts.as_secs_f64())
}

#[test]
fn baaba_h265_pts_monotonic() {
    let path = "/home/ymsr/Videos/Big_Buck_Bunny_720_10s_1MB-h265.mp4";

    let mut demuxer = open_demuxer(std::path::Path::new(path)).expect("open_demuxer");
    let fourcc = demuxer.fourcc();
    assert_eq!(fourcc, H265_FOURCC, "expected H.265 track");
    let width = demuxer.width();
    let height = demuxer.height();
    let codec_name = demuxer.codec_name().to_string();
    let codec_desc = demuxer.codec_description().to_vec();
    let mime = fourcc_to_mime(fourcc).unwrap_or(&codec_name);

    eprintln!(
        "H.265 track: {}x{}, mime={}, description={} bytes",
        width, height, mime, codec_desc.len()
    );

    let mut decoder =
        BaabaBackend::new(width, height, mime, &codec_desc).expect("create BaabaBackend");

    let mut output_frames: Vec<Duration> = Vec::new();
    let mut packet_count: usize = 0;

    loop {
        let sample = match demuxer.next_sample() {
            Ok(Some(s)) => s,
            Ok(None) => break,
            Err(e) => panic!("demux error: {e:?}"),
        };

        if sample.is_eos {
            break;
        }

        packet_count += 1;

        while let Some(frame) = decoder
            .decode_frame(&sample.data, sample.pts_us, sample.is_sync)
            .expect("decode_frame")
        {
            let pts = Duration::from_micros(frame.pts_us.max(0) as u64);
            output_frames.push(pts);
        }
    }

    while let Some(frame) = decoder.finish().expect("finish") {
        let pts = Duration::from_micros(frame.pts_us.max(0) as u64);
        output_frames.push(pts);
    }

    eprintln!(
        "Sent {} packets, got {} total output frames",
        packet_count,
        output_frames.len(),
    );

    // Basic sanity: we should get roughly the same number of frames as packets.
    assert!(
        output_frames.len() >= packet_count.saturating_sub(5),
        "Too few output frames: got {} from {} packets",
        output_frames.len(),
        packet_count,
    );

    // Check that the output PTS values form a reasonable set within expected duration.
    let min_pts = output_frames.iter().min().unwrap();
    let max_pts = output_frames.iter().max().unwrap();
    let span = *max_pts - *min_pts;
    eprintln!(
        "Output frame range: {} – {} (span ≈ {} s)",
        pts_dbg(*min_pts),
        pts_dbg(*max_pts),
        span.as_secs_f64(),
    );

    // The clip is ~10s at 30fps — we expect the span to be at least 9s.
    assert!(
        span >= Duration::from_secs(9),
        "Output PTS range too narrow: {:.2}s (expected ~10s)",
        span.as_secs_f64(),
    );
}
