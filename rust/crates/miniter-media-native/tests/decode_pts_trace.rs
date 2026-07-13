//! Multi-file decode test: checks PTS monotonicity, missing frames, and
//! frame count across AV1 / VP8 / VP9 / H.264 / H.265 with HW + SW decoders.
//!
//! Run:
//!   cargo test --release -p miniter-media-native --test decode_pts_trace -- --nocapture
//!
//! Set SKIP_ENCODE=1 to skip the encode+mux round-trip.

use std::path::Path;
use std::time::Instant;

use miniter_media_native::decoders::session::VideoDecodeSession;

/// A descriptor for a test video file.
struct TestVideo {
    path: &'static str,
    label: &'static str,
    /// Expected number of video frames (0 = unknown).
    expected_frames: u32,
    /// Expected frame duration in µs (0 = unknown).
    expected_fd_us: u64,
    /// Skip frame-duration check when using SW decoder (e.g. VP9 batch
    /// decoder drops PTS).
    skip_fd_check_sw: bool,
}

const TEST_FILES: &[TestVideo] = &[
    TestVideo {
        path: "/home/ymsr/Videos/Big_Buck_Bunny_720_10s_1MB-h265.mp4",
        label: "H.265 720p",
        expected_frames: 300,
        expected_fd_us: 33333,
        skip_fd_check_sw: false,
    },
    TestVideo {
        path: "/home/ymsr/Videos/av1-1080p.webm",
        label: "AV1 1080p",
        expected_frames: 0,    // ffprobe can't count frames
        expected_fd_us: 33333, // 30fps
        skip_fd_check_sw: false,
    },
    TestVideo {
        path: "/home/ymsr/Videos/vp8-720p.webm",
        label: "VP8 720p",
        expected_frames: 0,    // ffprobe can't count frames
        expected_fd_us: 33333, // 30fps
        skip_fd_check_sw: false,
    },
    // TestVideo {
    //     path: "/home/ymsr/Videos/Big_Buck_Bunny_360_10s_1MB.webm",
    //     label: "VP9 360p",
    //     expected_frames: 0,
    //     expected_fd_us: 33333, // 30fps
    //     // NOTE: SW VP9 uses batch decode that drops PTS — skip fd check.
    //     skip_fd_check_sw: true,
    // },
    TestVideo {
        path: "/home/ymsr/Videos/film_116___4K_res.mp4",
        label: "H.264 4K",
        expected_frames: 255,
        expected_fd_us: 41667, // 24000/1001 ≈ 23.976fps → 41667µs
        skip_fd_check_sw: false,
    },
];

#[test]
fn decode_multi_codec_pts_trace() {
    let skip_encode = std::env::var("SKIP_ENCODE")
        .map(|v| v == "1")
        .unwrap_or(false);

    for tv in TEST_FILES {
        // Try HW decoder first, then SW
        let hw_result = test_one(tv, true, skip_encode);
        match hw_result {
            Ok(n) => eprintln!("  ✅ {:<20} HW:  {n:>3} frames, PTS OK", tv.label),
            Err(e) => eprintln!("  ⚠️  {:<20} HW:  FAILED — {e}", tv.label),
        }

        let sw_result = test_one(tv, false, skip_encode);
        match sw_result {
            Ok(n) => eprintln!("  ✅ {:<20} SW:  {n:>3} frames, PTS OK", tv.label),
            Err(e) => eprintln!("  ❌ {:<20} SW:  FAILED — {e}", tv.label),
        }
    }
}

fn test_one(
    tv: &TestVideo,
    hw: bool,
    skip_encode: bool,
) -> Result<u32, Box<dyn std::error::Error>> {
    let mut session = VideoDecodeSession::open(Path::new(tv.path), hw)?;
    let codec = session.codec_name().to_string();
    let container = session.container_name().to_string();
    let w = session.width();
    let h_ = session.height();

    let start = Instant::now();
    let mut frames: Vec<(u32, i64)> = Vec::new();
    let mut prev_pts: Option<i64> = None;
    let mut dips_100us = 0u32;
    let mut gaps = 0u32;
    let mut max_gap_us: i64 = 0;
    let mut frame_idx = 0u32;

    loop {
        match session.next_frame()? {
            Some(frame) => {
                let pts_us = frame.pts_us;
                frames.push((frame_idx, pts_us));

                if let Some(prev) = prev_pts {
                    if pts_us < prev {
                        if prev - pts_us > 100 {
                            dips_100us += 1;
                        }
                    } else {
                        let gap = pts_us - prev;
                        if gap > 0 && tv.expected_fd_us > 0 {
                            let threshold = (tv.expected_fd_us as f64 * 1.5) as i64;
                            if gap > threshold {
                                gaps += 1;
                                if gap > max_gap_us {
                                    max_gap_us = gap;
                                }
                            }
                        }
                    }
                }
                prev_pts = Some(pts_us);
                frame_idx += 1;
            }
            None => break,
        }
    }

    let elapsed = start.elapsed();
    let n_usize = frames.len();
    let n = n_usize as u32;

    // Detect frame duration from PTS
    let estimated_fd_us = if n >= 2 {
        let last = frames.last().unwrap().1;
        let first = frames.first().unwrap().1;
        let duration = last - first;
        if n > 1 && duration > 0 {
            (duration as f64 / (n - 1) as f64) as u64
        } else {
            0
        }
    } else {
        0
    };

    // Build failure message
    let mut errors: Vec<String> = Vec::new();

    if dips_100us > 0 {
        errors.push(format!("{dips_100us} PTS dips >100µs"));
    }

    if tv.expected_frames > 0 && n != tv.expected_frames {
        errors.push(format!(
            "frame count: got {n}, expected {}",
            tv.expected_frames
        ));
    }

    let expected_fd = if !hw && tv.skip_fd_check_sw { 0 } else { tv.expected_fd_us };
    if expected_fd > 0 {
        let tolerance = (expected_fd as f64 * 0.05) as u64; // 5%
        if estimated_fd_us.abs_diff(expected_fd) > tolerance.max(1) {
            let first_last = if n >= 1 {
                format!(
                    "; first={}µs last={}µs",
                    frames.first().map(|f| f.1).unwrap_or(0),
                    frames.last().map(|f| f.1).unwrap_or(0),
                )
            } else {
                String::new()
            };
            errors.push(format!(
                "frame duration: got ~{estimated_fd_us}µs, expected ~{expected_fd}µs ({n} frames{first_last})",
            ));
        }
    }

    if gaps > 0 {
        errors.push(format!("{gaps} PTS gaps >1.5× frame duration (max={max_gap_us}µs)"));
    }

    if !errors.is_empty() {
        let msg = errors.join("; ");
        eprintln!(
            "  {label}: {n} frames in {elapsed:.1?}, est_fd={estimated_fd_us}µs, codec={codec} {cont} {w}x{h_} — {msg}",
            label = tv.label,
            n = n,
            elapsed = elapsed,
            estimated_fd_us = estimated_fd_us,
            codec = codec,
            cont = container,
            w = w,
            h_ = h_,
            msg = msg,
        );
        // Print first 10 PTS for debugging
        eprintln!("  First 10 PTS:");
        for &(idx, pts) in frames.iter().take(10) {
            eprintln!("    {idx:>4} {pts:>10}µs");
        }
        return Err(errors.join("; ").into());
    }

    // Optional encode+mux
    if !skip_encode {
        // re-open for encode (session is consumed)
        let _ = encode_and_mux(tv.path, hw, n)?;
    }

    Ok(n)
}

fn encode_and_mux(
    input_path: &str,
    hw: bool,
    _n: u32,
) -> Result<(), Box<dyn std::error::Error>> {
    use std::fs::File;
    use std::io::BufWriter;

    use miniter_media_native::decoders::session::VideoDecodeSession;
    use miniter_media_native::encoder::VideoEncodeSession;
    use miniter_media_native::encoder_hw::HwEncodeSession;
    use miniter_media_native::frame::RgbaFrame;
    use miniter_media_native::mux::{extract_sps_pps, ContainerFormat, Mp4Muxer, VideoTrackCodecOut};

    let out_path = "/tmp/pts_test_encode.mp4";
    let mut session = VideoDecodeSession::open(Path::new(input_path), hw)?;
    let w = session.width();
    let h = session.height();
    let fps = 30.0;
    let bitrate = 2_000;

    let hw_enc = HwEncodeSession::new(w, h, bitrate * 1000, fps as f32, "video/avc");
    enum Encoder {
        Sw(VideoEncodeSession),
        Hw(HwEncodeSession),
    }
    let mut encoder = match hw_enc {
        Ok(e) => Encoder::Hw(e),
        Err(_) => Encoder::Sw(VideoEncodeSession::new(w, h, bitrate * 1000, fps as f32)?),
    };

    let mut encoded_packets: Vec<(u64, Vec<u8>, bool)> = Vec::new();
    let mut first = true;
    let mut sps_pps = None;

    while let Some(frame) = session.next_frame()? {
        let output = match &mut encoder {
            Encoder::Sw(e) => e.encode_frame(&frame)?,
            Encoder::Hw(e) => e.encode_frame(&frame)?,
        };
        let (bytes, keyframe) = match output {
            miniter_media_native::encoder::EncodedVideoOutput::Sample { bytes, is_keyframe } => {
                (bytes, is_keyframe)
            }
            miniter_media_native::encoder::EncodedVideoOutput::Skipped => continue,
        };

        if first {
            if let Some((sps, pps)) = extract_sps_pps(&bytes) {
                sps_pps = Some((sps, pps));
            } else {
                sps_pps = Some((vec![], vec![]));
            }
            first = false;
        }

        encoded_packets.push((frame.pts_us as u64, bytes, keyframe));
    }

    if let Some(dummy) = RgbaFrame::new(w, h, vec![0u8; (w * h * 4) as usize], 0) {
        let _ = match &mut encoder {
            Encoder::Sw(e) => e.encode_frame(&dummy),
            Encoder::Hw(e) => e.encode_frame(&dummy),
        };
    }

    if let Some((sps, pps)) = sps_pps {
        let file = File::create(out_path)?;
        let writer = BufWriter::new(file);
        let mut muxer = Mp4Muxer::new(
            writer,
            w,
            h,
            fps,
            &sps,
            &pps,
            ContainerFormat::Mp4,
            None,
            None,
            VideoTrackCodecOut::H264,
        )?;

        for (pts_us, data, keyframe) in &encoded_packets {
            muxer.write_sample_at(*pts_us, data, *keyframe)?;
        }
        muxer.finish()?;
        eprintln!("  📦 Muxed {} packets to {out_path}", encoded_packets.len());
    }

    Ok(())
}
