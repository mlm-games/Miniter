//! Process-wide hardware video counters: how much decoding ran on the platform
//! decoder, how many streams it declined, and how many fell back mid-stream.

use std::sync::atomic::{AtomicU64, Ordering};

/// Counters so far; they only grow, so subtract two snapshots to measure a span.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HwStats {
    /// Streams whose decoder runs on the platform (hardware) backend.
    pub decode_sessions: u64,
    /// Pictures the platform decoder output.
    pub decode_frames: u64,
    /// Streams that asked for hardware and were given software instead.
    pub decode_declined: u64,
    /// Platform decoders that failed mid-stream and continued in software.
    pub decode_fallbacks: u64,
    /// Sessions created on the platform encoder.
    pub encode_sessions: u64,
    /// Pictures handed to the platform encoder.
    pub encode_frames: u64,
}

static DECODE_SESSIONS: AtomicU64 = AtomicU64::new(0);
static DECODE_FRAMES: AtomicU64 = AtomicU64::new(0);
static DECODE_DECLINED: AtomicU64 = AtomicU64::new(0);
static DECODE_FALLBACKS: AtomicU64 = AtomicU64::new(0);
static ENCODE_SESSIONS: AtomicU64 = AtomicU64::new(0);
static ENCODE_FRAMES: AtomicU64 = AtomicU64::new(0);

pub fn stats() -> HwStats {
    HwStats {
        decode_sessions: DECODE_SESSIONS.load(Ordering::Relaxed),
        decode_frames: DECODE_FRAMES.load(Ordering::Relaxed),
        decode_declined: DECODE_DECLINED.load(Ordering::Relaxed),
        decode_fallbacks: DECODE_FALLBACKS.load(Ordering::Relaxed),
        encode_sessions: ENCODE_SESSIONS.load(Ordering::Relaxed),
        encode_frames: ENCODE_FRAMES.load(Ordering::Relaxed),
    }
}

impl HwStats {
    /// One-line summary for the UI; `None` before anything ran on hardware.
    pub fn summary(&self) -> Option<String> {
        if self.decode_sessions == 0 && self.encode_sessions == 0 && self.decode_declined == 0 {
            return None;
        }
        let mut out = format!(
            "hw decode: {} frames in {} sessions",
            self.decode_frames, self.decode_sessions
        );
        if self.decode_declined > 0 {
            out.push_str(&format!(", {} declined", self.decode_declined));
        }
        if self.decode_fallbacks > 0 {
            out.push_str(&format!(", {} fell back", self.decode_fallbacks));
        }
        if self.encode_sessions > 0 {
            out.push_str(&format!(
                "; hw encode: {} frames in {} sessions",
                self.encode_frames, self.encode_sessions
            ));
        }
        Some(out)
    }
}

pub(crate) fn note_decode_session() {
    DECODE_SESSIONS.fetch_add(1, Ordering::Relaxed);
}

pub(crate) fn note_decode_frame() {
    DECODE_FRAMES.fetch_add(1, Ordering::Relaxed);
}

pub(crate) fn note_decode_declined() {
    DECODE_DECLINED.fetch_add(1, Ordering::Relaxed);
}

pub(crate) fn note_decode_fallback() {
    DECODE_FALLBACKS.fetch_add(1, Ordering::Relaxed);
}

pub(crate) fn note_encode_session() {
    ENCODE_SESSIONS.fetch_add(1, Ordering::Relaxed);
}

pub(crate) fn note_encode_frame() {
    ENCODE_FRAMES.fetch_add(1, Ordering::Relaxed);
}
