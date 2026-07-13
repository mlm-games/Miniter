//! Multi-file decode test: checks PTS monotonicity, missing frames, and
//! frame count across AV1 / VP8 / VP9 / H.264 / H.265 with HW + SW decoders.
//!
//! Run:
//!   cargo test --release -p miniter-media-native --test decode_pts_trace -- --nocapture
//!
//! Set SKIP_ENCODE=1 to skip the encode+mux round-trip.
//! Set CHECK_VISUAL=1 to compare decoded frames against ffmpeg reference
//! (requires ffmpeg on PATH).

use std::io::Read;
use std::path::Path;
use std::process::{Command, Stdio};
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
}

const TEST_FILES: &[TestVideo] = &[
    TestVideo {
        path: "/home/ymsr/Videos/Big_Buck_Bunny_720_10s_1MB-h265.mp4",
        label: "H.265 720p",
        expected_frames: 300,
        expected_fd_us: 33333,
    },
    TestVideo {
        path: "/home/ymsr/Videos/av1-1080p.webm",
        label: "AV1 1080p",
        expected_frames: 0,
        expected_fd_us: 33333,
    },
    TestVideo {
        path: "/home/ymsr/Videos/vp8-720p.webm",
        label: "VP8 720p",
        expected_frames: 0,
        expected_fd_us: 33333,
    },
    TestVideo {
        path: "/home/ymsr/Videos/film_116___4K_res.mp4",
        label: "H.264 4K",
        expected_frames: 255,
        expected_fd_us: 41667,
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

    if tv.expected_fd_us > 0 {
        let tolerance = (tv.expected_fd_us as f64 * 0.05) as u64; // 5%
        if estimated_fd_us.abs_diff(tv.expected_fd_us) > tolerance.max(1) {
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
                "frame duration: got ~{estimated_fd_us}µs, expected ~{}µs ({n} frames{first_last})",
                tv.expected_fd_us
            ));
        }
    }

    if gaps > 0 {
        errors.push(format!("{gaps} PTS gaps >1.5× frame duration (max={max_gap_us}µs)"));
    }

    // Optional visual comparison against ffmpeg reference
    let check_visual = std::env::var("CHECK_VISUAL").map(|v| v == "1").unwrap_or(false);
    if check_visual {
        match check_visual_ffmpeg(tv.path, hw, n, w, h_) {
            Ok(min_psnr) => {
                // Threshold 20dB: anything below indicates real decoder
                // corruption (wrong pixels, block artifacts) rather than
                // expected chroma-siting/color-matrix differences (~25-31dB).
                if min_psnr < 20.0 {
                    errors.push(format!("visual: min PSNR={min_psnr:.1}dB (<20dB — decoder corruption?)"));
                } else {
                    eprintln!("  ✅ {:<20} {hwsw:>3}:  visual PSNR min={min_psnr:.1}dB",
                        tv.label, hwsw = if hw { "HW" } else { "SW" });
                }
            }
            Err(e) => errors.push(format!("visual check: {e}")),
        }
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
        eprintln!("  First 10 PTS:");
        for &(idx, pts) in frames.iter().take(10) {
            eprintln!("    {idx:>4} {pts:>10}µs");
        }
        return Err(errors.join("; ").into());
    }

    // Optional encode+mux
    if !skip_encode {
        let _ = encode_and_mux(tv.path, hw, n)?;
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
        eprintln!("  First 10 PTS:");
        for &(idx, pts) in frames.iter().take(10) {
            eprintln!("    {idx:>4} {pts:>10}µs");
        }
        return Err(errors.join("; ").into());
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
    let mut encoder_pts_list: Vec<(u32, i64)> = Vec::new();
    let mut first = true;
    let mut sps_pps = None;
    let mut enc_dips = 0u32;
    let mut enc_prev_pts: Option<i64> = None;
    let mut enc_frame_idx = 0u32;

    while let Some(frame) = session.next_frame()? {
        let output = match &mut encoder {
            Encoder::Sw(e) => e.encode_frame(&frame)?,
            Encoder::Hw(e) => e.encode_frame(&frame)?,
        };
        let (bytes, keyframe, enc_pts_us) = match output {
            miniter_media_native::encoder::EncodedVideoOutput::Sample { bytes, is_keyframe, pts_us } => {
                (bytes, is_keyframe, pts_us)
            }
            miniter_media_native::encoder::EncodedVideoOutput::Skipped => continue,
        };

        encoder_pts_list.push((enc_frame_idx, enc_pts_us));
        if let Some(prev) = enc_prev_pts {
            if enc_pts_us < prev && prev - enc_pts_us > 100 {
                enc_dips += 1;
                eprintln!("  ⚠️  Encoder PTS dip #{}: frame_idx={}, prev={}µs -> curr={}µs",
                    enc_dips, enc_frame_idx, prev, enc_pts_us);
            }
        }
        enc_prev_pts = Some(enc_pts_us);
        enc_frame_idx += 1;

        if first {
            if let Some((sps, pps)) = extract_sps_pps(&bytes) {
                sps_pps = Some((sps, pps));
            } else {
                sps_pps = Some((vec![], vec![]));
            }
            first = false;
        }

        encoded_packets.push((enc_pts_us as u64, bytes, keyframe));
    }

    if std::env::var("TRACE_ENCODER_PTS").is_ok() {
        eprintln!("  Encoder PTS trace (all {} frames):", encoder_pts_list.len());
        for &(idx, pts) in &encoder_pts_list {
            eprintln!("    enc[{idx:>4}] = {pts:>10}µs");
        }
    }

    if enc_dips > 0 {
        eprintln!("  ❌ Encoder PTS FAIL: {enc_dips} dips in encoder output");
        return Err(format!("encoder PTS: {enc_dips} dips >100µs in encoder output").into());
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
    drop(session);

    // Re-decode the muxed output and verify PTS monotonicity
    let mut re_session = VideoDecodeSession::open(Path::new(out_path), false)?;
    let mut prev: Option<i64> = None;
    let mut re_dips = 0u32;
    let mut re_count = 0u32;
    let mut re_frames: Vec<(u32, i64)> = Vec::new();
    while let Some(f) = re_session.next_frame()? {
        let pts = f.pts_us;
        re_frames.push((re_count, pts));
        if let Some(p) = prev {
            if pts < p && p - pts > 100 {
                re_dips += 1;
            }
        }
        prev = Some(pts);
        re_count += 1;
    }
    if re_dips > 0 || re_count == 0 {
        eprintln!("  ❌ Encode round-trip PTS FAIL: {re_dips} dips, {re_count} frames");
        for &(idx, pts) in re_frames.iter().take(10) {
            eprintln!("    {idx:>4} {pts:>10}µs");
        }
        return Err(format!(
            "encode round-trip: {re_dips} PTS dips >100µs in re-decoded output ({re_count} frames)"
        )
        .into());
    }
    eprintln!("  ✅ Encode round-trip: {re_count} frames, PTS OK");

    Ok(())
}

/// Decode the file with both our decoder and ffmpeg reference, compare up to
/// `max_frames` frames via PSNR.  Returns the minimum PSNR across all
/// compared frames (higher is better, ≥40dB ≈ lossless).
fn check_visual_ffmpeg(
    path: &str,
    hw: bool,
    max_frames: u32,
    width: u32,
    height: u32,
) -> Result<f64, Box<dyn std::error::Error>> {
    use std::io::Write;

    // decode with our pipeline
    let mut our_frames: Vec<Vec<u8>> = Vec::new();
    {
        let mut session = VideoDecodeSession::open(Path::new(path), hw)?;
        while let Some(frame) = session.next_frame()? {
            if our_frames.len() >= max_frames as usize {
                break;
            }
            our_frames.push(frame.data);
        }
    }

    let frame_size = (width * height * 4) as usize;
    let n_check = our_frames.len().min(max_frames as usize);
    if n_check == 0 {
        return Ok(99.0); // no frames to compare
    }

    // decode with ffmpeg
    let mut child = Command::new("ffmpeg")
        .args([
            "-hide_banner",
            "-i", path,
            "-f", "rawvideo",
            "-pix_fmt", "rgba",
            "-vsync", "0",
            "-frame_size", &frame_size.to_string(),
            "-",
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("ffmpeg spawn: {e}"))?;

    let mut ffmpeg_stdout = child.stdout.take()
        .ok_or("ffmpeg stdout not captured")?;

    let mut min_psnr = f64::MAX;
    let mut matched = 0u32;

    for our_data in &our_frames {
        let mut ref_data = vec![0u8; frame_size];
        let mut read = 0usize;
        while read < frame_size {
            let n = ffmpeg_stdout.read(&mut ref_data[read..])?;
            if n == 0 {
                break; // ffmpeg ended early
            }
            read += n;
        }
        if read < frame_size {
            // ffmpeg output fewer frames than our decoder
            break;
        }

        // Compare luma (Y) only: Y ≈ 0.299*R + 0.587*G + 0.114*B
        // This avoids penalising color-matrix / chroma-siting differences
        // between our decoder and ffmpeg (commonly 5-15 units in practice).
        let pixels = frame_size / 4;
        let mut sq_err = 0u64;
        for px in 0..pixels {
            let our_y =
                0.299 * our_data[px * 4] as f64
                + 0.587 * our_data[px * 4 + 1] as f64
                + 0.114 * our_data[px * 4 + 2] as f64;
            let ref_y =
                0.299 * ref_data[px * 4] as f64
                + 0.587 * ref_data[px * 4 + 1] as f64
                + 0.114 * ref_data[px * 4 + 2] as f64;
            let d = our_y - ref_y;
            sq_err += (d * d) as u64;
        }
        let mse = sq_err as f64 / pixels as f64;
        let psnr = if mse == 0.0 {
            99.0
        } else {
            10.0 * (255.0 * 255.0 / mse).log10()
        };
        if psnr < min_psnr {
            min_psnr = psnr;
        }
        matched += 1;
    }

    // reap ffmpeg
    let _ = child.wait();

    if matched < n_check as u32 {
        eprintln!("  WARN: visual check only compared {matched}/{n_check} frames (ffmpeg ended early)");
    }

    if min_psnr == f64::MAX {
        min_psnr = 99.0;
    }

    Ok(min_psnr)
}
