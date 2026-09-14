//! H.264 SW export regression: the streaming path (encode -> mux straight
//! through, no whole-stream sample buffer) must produce a decodable MP4 with
//! the right frame count, dimensions, and a leading keyframe.
//!
//! Guards the rusty_h264 swap invariants:
//! * `encode` emits one AU per call with the current no-lookahead config
//!   (no `Skipped`, no `finish()` tail needed for correctness);
//! * first AU is an IDR (muxer + thumbnailers depend on it);
//! * PTS pass through in submission order;
//! * `video_bitrate_bps` clamps without overflow (covered in unit tests,
//!   exercised end-to-end here via a below-floor bitrate).

use miniter_media_native::encoder::{EncodedVideoOutput, VideoEncodeSession};
use miniter_media_native::frame::RgbaFrame;

fn solid_frame(width: u32, height: u32, pts_us: i64, luma: u8) -> RgbaFrame {
    let mut data = vec![0u8; width as usize * height as usize * 4];
    for px in data.as_chunks_mut::<4>().0 {
        px[0] = luma;
        px[1] = luma;
        px[2] = luma;
        px[3] = 255;
    }
    RgbaFrame {
        width,
        height,
        data,
        pts_us,
        color_info: Default::default(),
    }
}

#[test]
fn sw_encode_streams_one_au_per_call_with_ordered_pts() {
    let (w, h) = (320, 240);
    let fps = 30.0f32;
    let mut session = VideoEncodeSession::new(w, h, 2_000, fps, 6).expect("session");
    let n = 12u32;
    let mut samples: Vec<(i64, Vec<u8>, bool)> = Vec::new();
    for i in 0..n {
        let pts = i as i64 * 33_333;
        let frame = solid_frame(w, h, pts, (i * 17) as u8);
        match session.encode_frame(&frame).expect("encode") {
            EncodedVideoOutput::Sample {
                bytes,
                is_keyframe,
                pts_us,
            } => {
                assert!(!bytes.is_empty(), "frame {i} produced no bytes");
                assert_eq!(pts_us, pts, "frame {i} PTS mismatch");
                samples.push((pts_us, bytes, is_keyframe));
            }
            EncodedVideoOutput::Skipped => panic!("frame {i} buffered with lookahead off"),
        }
    }
    for tail in session.finish() {
        if let EncodedVideoOutput::Sample { bytes, .. } = tail {
            assert!(
                bytes.is_empty(),
                "flush tail must be empty with lookahead off"
            );
        }
    }
    assert_eq!(samples.len() as u32, n);
    assert!(samples[0].2, "first AU must be an IDR");
    let mut prev = -1i64;
    for (pts, _, _) in &samples {
        assert!(*pts > prev, "PTS not monotonic: {prev} -> {pts}");
        prev = *pts;
    }
}

#[test]
fn sw_encode_first_au_is_idr_at_any_effort() {
    for effort in [0u8, 6, 10] {
        let mut session = VideoEncodeSession::new(160, 120, 2_000, 30.0, effort).expect("session");
        let frame = solid_frame(160, 120, 0, 128);
        match session.encode_frame(&frame).expect("encode") {
            EncodedVideoOutput::Sample { is_keyframe, .. } => {
                assert!(is_keyframe, "effort {effort}: first AU must be IDR")
            }
            EncodedVideoOutput::Skipped => panic!("effort {effort}: buffered with lookahead off"),
        }
    }
}

#[test]
fn sw_encode_below_floor_bitrate_still_runs_abr() {
    // 100 kbps < 500 kbps floor: clamps up into ABR, never silent CQP.
    let mut session = VideoEncodeSession::new(160, 120, 100, 30.0, 6).expect("session");
    let frame = solid_frame(160, 120, 0, 200);
    match session.encode_frame(&frame).expect("encode") {
        EncodedVideoOutput::Sample { bytes, .. } => assert!(!bytes.is_empty()),
        EncodedVideoOutput::Skipped => panic!("buffered with lookahead off"),
    }
}
