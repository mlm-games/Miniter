//! Direct baaba decoder test — checks raw PTS without BaabaBackend's sort.
//!
//! The BaabaBackend::pop_frame() sorts frames by PTS before returning,
//! which masks any PTS mis-assignment in the cros-codecs decoder output.
//! This test uses the raw baaba API to collect frames without sorting.
//!
//! Tests H.265, VP9, and VP8 decoders.

use std::time::Duration;

#[cfg(target_os = "linux")]
use baabaabaabaabababbababbaa::platform::linux::{
    CrosCodecsHost, CrosVideoDecoderInput, CrosVideoDecoderOutput,
};
#[cfg(target_os = "linux")]
use baabaabaabaabababbababbaa::{
    Dimensions, EncodedVideoPacket, VideoDecoderConfig,
};
#[cfg(target_os = "linux")]
use baabaabaabaabababbababbaa::VideoDecoderInput as _;
#[cfg(target_os = "linux")]
use baabaabaabaabababbababbaa::VideoDecoderOutput as _;
use miniter_media_native::decoders::{
    fourcc_to_mime, H265_FOURCC, VP8_FOURCC, VP9_FOURCC,
};
use miniter_media_native::demux::open_demuxer;

#[cfg(target_os = "linux")]
fn collect_raw_frames(
    output: &mut CrosVideoDecoderOutput,
    buf: &mut Vec<(usize, Duration)>,
) -> Result<(), String> {
    loop {
        match output.try_frame() {
            Ok(Some(frame)) => {
                let ts = Duration::from_micros(
                    frame.timestamp.as_micros().min(i64::MAX as u128) as u64,
                );
                buf.push((buf.len(), ts));
            }
            Ok(None) => break,
            Err(e) => return Err(format!("try_frame: {e:?}")),
        }
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn test_raw_pts(path: &str, expected_fourcc: u32, label: &str) {
    let mut demuxer = open_demuxer(std::path::Path::new(path)).expect("open_demuxer");
    let fourcc = demuxer.fourcc();
    assert_eq!(fourcc, expected_fourcc, "expected {label} track");
    let width = demuxer.width();
    let height = demuxer.height();
    let codec_name = demuxer.codec_name().to_string();
    let codec_desc = demuxer.codec_description().to_vec();
    let mime = fourcc_to_mime(fourcc).unwrap_or(&codec_name);

    eprintln!(
        "{label} track: {}x{}, mime={}, description={} bytes",
        width, height, mime, codec_desc.len()
    );

    let host = CrosCodecsHost::new();
    let config = VideoDecoderConfig {
        codec: mime.into(),
        resolution: Some(Dimensions::new(width, height)),
        description: if codec_desc.is_empty() {
            None
        } else {
            Some(codec_desc.into())
        },
        hardware_acceleration: None,
    };
    let (mut input, mut output): (CrosVideoDecoderInput, CrosVideoDecoderOutput) = host
        .create_video_decoder(config)
        .expect("create_video_decoder");

    let rt = tokio::runtime::Runtime::new().expect("tokio rt");

    let mut input_pts: Vec<Duration> = Vec::new();
    let mut raw_output_frames: Vec<(usize, Duration)> = Vec::new();
    let mut during_decode_frames: Vec<(usize, Duration, i64)> = Vec::new();
    let mut packet_idx = 0usize;

    loop {
        let sample = match demuxer.next_sample() {
            Ok(Some(s)) => s,
            Ok(None) => break,
            Err(e) => panic!("demux error: {e:?}"),
        };
        if sample.is_eos {
            break;
        }

        let pts = Duration::from_micros(sample.pts_us.max(0) as u64);
        input_pts.push(pts);

        let packet = EncodedVideoPacket {
            payload: sample.data.to_vec().into(),
            timestamp: pts,
            keyframe: sample.is_sync,
        };

        input.decode(packet).expect("input.decode");

        packet_idx += 1;

        let before = raw_output_frames.len();
        collect_raw_frames(&mut output, &mut raw_output_frames).unwrap();
        for i in before..raw_output_frames.len() {
            during_decode_frames.push((
                raw_output_frames[i].0,
                raw_output_frames[i].1,
                sample.pts_us,
            ));
        }
    }

    eprintln!(
        "Sent {} packets, got {} raw frames during decode",
        packet_idx,
        during_decode_frames.len()
    );

    rt.block_on(input.flush()).expect("flush");

    let before_flush = raw_output_frames.len();
    collect_raw_frames(&mut output, &mut raw_output_frames).unwrap();
    let flushed_frames = raw_output_frames.len() - before_flush;

    eprintln!(
        "Got {} raw frames from flush (total raw: {})",
        flushed_frames,
        raw_output_frames.len()
    );

    let mut input_pts_sorted: Vec<Duration> = input_pts.clone();
    input_pts_sorted.sort();

    let mut output_pts_raw: Vec<Duration> =
        raw_output_frames.iter().map(|(_, pts)| *pts).collect();
    let mut output_pts_sorted = output_pts_raw.clone();
    output_pts_sorted.sort();

    assert_eq!(
        input_pts_sorted.len(),
        output_pts_sorted.len(),
        "Frame count mismatch: {} input packets vs {} raw output frames",
        input_pts_sorted.len(),
        output_pts_sorted.len(),
    );

    let mut mismatches = 0usize;
    let tolerance = Duration::from_micros(500);
    for (i, (in_pts, out_pts)) in input_pts_sorted
        .iter()
        .zip(output_pts_sorted.iter())
        .enumerate()
    {
        let diff = if *in_pts > *out_pts {
            *in_pts - *out_pts
        } else {
            *out_pts - *in_pts
        };
        if diff > tolerance {
            mismatches += 1;
            if mismatches <= 10 {
                eprintln!(
                    "  PTS MISMATCH #{}: frame[{}]: input={:>10}µs output={:>10}µs (Δ={:>6}µs)",
                    mismatches,
                    i,
                    in_pts.as_micros(),
                    out_pts.as_micros(),
                    diff.as_micros(),
                );
            }
        }
    }

    if mismatches > 0 {
        eprintln!(
            "\n❌ PTS MISMATCH AFTER SORT: {mismatches}/{} frames have wrong PTS",
            output_pts_sorted.len(),
        );
    } else {
        eprintln!("✅ Sorted output PTS matches sorted input PTS");
    }

    let mut raw_dips = 0usize;
    for chunk in output_pts_raw.windows(2) {
        if chunk[1] < chunk[0] {
            raw_dips += 1;
        }
    }

    eprintln!(
        "Raw output PTS has {} backward steps (dips) without sorting",
        raw_dips,
    );

    if raw_dips > 0 {
        eprintln!("\n⚠️  Raw PTS has {raw_dips} backward steps! Sorted PTS check hides this.");
        eprintln!("  (The BaabaBackend's pop_frame() sorts, masking this issue)\n");
        eprintln!("  First 20 raw PTS values vs input PTS:");
        for i in 0..20.min(output_pts_raw.len()).min(input_pts.len()) {
            let mark = if i < output_pts_raw.len() && i < input_pts.len() {
                let diff = if output_pts_raw[i] > input_pts[i] {
                    output_pts_raw[i] - input_pts[i]
                } else {
                    input_pts[i] - output_pts_raw[i]
                };
                if diff > tolerance { " ← MISMATCH" } else { "" }
            } else {
                ""
            };
            eprintln!(
                "  [{i:>3}] input={:>10}µs raw_out={:>10}µs{}",
                input_pts[i].as_micros(),
                output_pts_raw.get(i).map(|d| d.as_micros()).unwrap_or(0),
                mark,
            );
        }
    }

    assert!(
        raw_dips == 0,
        "PTS BUG: {label} decoder output has {raw_dips} backward PTS steps in {} raw frames — \
         timestamps follow decode order through the DPB/state instead of POC order. \
         pop_frame() sort masks this but PTS values are attached to wrong frames!",
        output_pts_raw.len(),
    );
}

#[test]
#[cfg_attr(not(target_os = "linux"), ignore)]
fn baaba_h265_raw_pts() {
    test_raw_pts(
        "/home/ymsr/Videos/Big_Buck_Bunny_720_10s_1MB-h265.mp4",
        H265_FOURCC,
        "H.265",
    );
}

#[test]
#[cfg_attr(not(target_os = "linux"), ignore)]
fn baaba_vp9_raw_pts() {
    test_raw_pts(
        "/home/ymsr/Videos/Big_Buck_Bunny_360_10s_1MB.webm",
        VP9_FOURCC,
        "VP9",
    );
}

#[test]
#[cfg_attr(not(target_os = "linux"), ignore)]
#[ignore = "VA driver does not support VP8 on this hardware"]
fn baaba_vp8_raw_pts() {
    test_raw_pts(
        "/home/ymsr/Videos/vp8-720p.webm",
        VP8_FOURCC,
        "VP8",
    );
}
