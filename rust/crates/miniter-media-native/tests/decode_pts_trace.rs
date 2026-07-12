//! Decode a video file using HW or SW decoder, print PTS for every frame,
//! verify monotonicity (no dips), and optionally re-encode + mux.
//!
//! Environment variables:
//!   VIDEO=<path>           – input file (default: /../../../Videos/Big_Buck_Bunny_720_10s_1MB-h265.mp4)
//!   HW=0                  – force software decoder (default: use HW if available)
//!   ENCODE=1              – also encode and mux to /tmp/pts_test_out.mp4

use std::path::Path;
use std::time::Instant;

use miniter_media_native::decoders::session::VideoDecodeSession;

#[test]
fn decode_pts_trace() {
    let path = std::env::var("VIDEO").unwrap_or_else(|_| {
        "/../../../Videos/Big_Buck_Bunny_720_10s_1MB-h265.mp4".into()
    });
    let hw = std::env::var("HW")
        .map(|v| v != "0")
        .unwrap_or(true);
    let do_encode = std::env::var("ENCODE")
        .map(|v| v == "1")
        .unwrap_or(false);

    run(&path, hw, do_encode).unwrap();
}

fn run(path: &str, hw: bool, do_encode: bool) -> Result<(), Box<dyn std::error::Error>> {
    eprintln!("=== PTS Trace ===");
    eprintln!("  input:  {path}");
    eprintln!("  hw:     {hw}");
    eprintln!("  encode: {do_encode}");

    let mut session = VideoDecodeSession::open(Path::new(path), hw)?;
    let codec = session.codec_name().to_string();
    let container = session.container_name().to_string();
    let w = session.width();
    let h = session.height();
    let total = session.total_samples();
    eprintln!("  codec:  {codec}");
    eprintln!("  cont:   {container}");
    eprintln!("  res:    {w}x{h}");
    eprintln!("  samples:{total}");

    let start = Instant::now();
    let mut frames: Vec<(u32, i64)> = Vec::new();
    let mut prev_pts: Option<i64> = None;
    let mut dips_100us = 0u32;
    let mut raw_dips = 0u32;
    let mut frame_idx = 0u32;

    loop {
        match session.next_frame()? {
            Some(frame) => {
                let pts_us = frame.pts_us;
                frames.push((frame_idx, pts_us));

                if let Some(prev) = prev_pts {
                    if pts_us < prev {
                        raw_dips += 1;
                        if prev - pts_us > 100 {
                            dips_100us += 1;
                            eprintln!(
                                "  DIP #{dips_100us}: frame[{frame_idx}] pts={pts_us:>8}μs  prev={prev:>8}μs  Δ={:+}μs",
                                pts_us - prev,
                            );
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
    let n = frames.len();

    eprintln!("\n=== Results ===");
    eprintln!("  decoded: {n} frames in {elapsed:.1?}");
    eprintln!("  raw PTS dips:     {raw_dips}");
    eprintln!("  dips >100μs:      {dips_100us}");

    // Print first 30 frames
    let print_n = frames.len().min(30);
    eprintln!("\nFirst {print_n} frame PTS values:");
    for &(idx, pts_us) in frames.iter().take(print_n) {
        eprintln!("  frame[{idx:>3}]: pts={pts_us:>8}μs  ({:.6}s)", pts_us as f64 / 1_000_000.0);
    }

    // Print last 10 frames
    if frames.len() > 30 {
        eprintln!("\nLast 10 frame PTS values:");
        for &(idx, pts_us) in frames.iter().rev().take(10).rev() {
            eprintln!("  frame[{idx:>3}]: pts={pts_us:>8}μs  ({:.6}s)", pts_us as f64 / 1_000_000.0);
        }
    }

    // Print all frames that are out of order (non-monotonic)
    let issues: Vec<&(u32, i64)> = frames
        .windows(2)
        .filter(|w| w[1].1 < w[0].1)
        .map(|w| &w[1])
        .collect();
    if !issues.is_empty() {
        eprintln!("\nAll non-monotonic frame positions ({} total):", issues.len());
        for &&(idx, pts_us) in issues.iter().take(20) {
            eprintln!("  frame[{idx:>3}]: pts={pts_us:>8}μs");
        }
        if issues.len() > 20 {
            eprintln!("  ... and {} more", issues.len() - 20);
        }
    }

    // Print ALL frames (complete trace)
    eprintln!("\n=== Full PTS trace ===");
    for &(idx, pts_us) in &frames {
        eprintln!("{idx:>4} {pts_us:>10}", idx = idx, pts_us = pts_us);
    }

    assert!(
        dips_100us == 0,
        "Found {dips_100us} PTS dips >100μs (raw: {raw_dips}) — PTS is NOT monotonic"
    );

    // Optional: encode + mux the decoded frames to an output file
    if do_encode && !frames.is_empty() {
        encode_and_mux(path, hw, n as u32)?;
    }

    Ok(())
}

fn encode_and_mux(
    input_path: &str,
    hw: bool,
    _expected_frames: u32,
) -> Result<(), Box<dyn std::error::Error>> {
    use std::fs::File;
    use std::io::BufWriter;

    use miniter_media_native::decoders::session::VideoDecodeSession;
    use miniter_media_native::encoder::VideoEncodeSession;
    use miniter_media_native::encoder_hw::HwEncodeSession;
    use miniter_media_native::frame::RgbaFrame;
    use miniter_media_native::mux::{extract_sps_pps, Mp4Muxer};
    use miniter_media_native::mux::{ContainerFormat, VideoTrackCodecOut};

    let out_path = "/tmp/pts_test_out.mp4";
    eprintln!("\n=== Encode + Mux ===");
    eprintln!("  output: {out_path}");

    let mut session = VideoDecodeSession::open(Path::new(input_path), hw)?;
    let w = session.width();
    let h = session.height();
    let fps = 30.0;
    let bitrate = 2_000;

    // Try HW encoder, fall back to SW
    let hw_enc = HwEncodeSession::new(w, h, bitrate * 1000, fps as f32, "video/avc");
    enum Encoder {
        Sw(VideoEncodeSession),
        Hw(HwEncodeSession),
    }
    let mut encoder = match hw_enc {
        Ok(e) => Encoder::Hw(e),
        Err(_) => Encoder::Sw(VideoEncodeSession::new(w, h, bitrate * 1000, fps as f32)?),
    };

    // Decode all frames & encode
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
                eprintln!("  WARN: could not extract SPS/PPS");
                sps_pps = Some((vec![], vec![]));
            }
            first = false;
        }

        encoded_packets.push((frame.pts_us as u64, bytes, keyframe));
        eprintln!("  encoded frame pts={}μs keyframe={keyframe}", frame.pts_us);
    }

    // Flush encoder by sending an empty frame
    if let Some(dummy) = RgbaFrame::new(w, h, vec![0u8; (w * h * 4) as usize], 0) {
        let _ = match &mut encoder {
            Encoder::Sw(e) => e.encode_frame(&dummy),
            Encoder::Hw(e) => e.encode_frame(&dummy),
        };
    }

    // Mux to MP4
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
        eprintln!("\n✅ Muxed {} packets to {out_path}", encoded_packets.len());
    }

    Ok(())
}
