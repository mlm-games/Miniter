use serde::{Deserialize, Serialize};
use std::ops::{Add, Sub};

macro_rules! impl_micros_common {
    ($ty:ident) => {
        impl $ty {
            pub const ZERO: Self = Self(0);

            pub fn from_micros(us: i64) -> Self {
                Self(us)
            }

            pub fn from_secs(s: f64) -> Self {
                Self((s * 1_000_000.0) as i64)
            }

            pub fn as_micros(self) -> i64 {
                self.0
            }

            pub fn as_secs_f64(self) -> f64 {
                self.0 as f64 / 1_000_000.0
            }

            pub fn clamp_non_negative(self) -> Self {
                Self(self.0.max(0))
            }
        }
    };
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, Default,
)]
pub struct Timestamp(pub i64);

impl_micros_common!(Timestamp);

impl Timestamp {
    pub fn from_millis(ms: f64) -> Self {
        Self((ms * 1_000.0) as i64)
    }

    pub fn as_millis_f64(self) -> f64 {
        self.0 as f64 / 1_000.0
    }
}

impl Add<MediaDuration> for Timestamp {
    type Output = Self;
    fn add(self, rhs: MediaDuration) -> Self {
        Self(self.0.saturating_add(rhs.0))
    }
}

impl Sub for Timestamp {
    type Output = MediaDuration;
    fn sub(self, rhs: Self) -> MediaDuration {
        MediaDuration(self.0.saturating_sub(rhs.0))
    }
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, Default,
)]
pub struct MediaDuration(pub i64);

impl_micros_common!(MediaDuration);

impl MediaDuration {
    pub fn is_zero(self) -> bool {
        self.0 == 0
    }

    pub fn is_positive(self) -> bool {
        self.0 > 0
    }

    pub fn is_negative(self) -> bool {
        self.0 < 0
    }
}

impl Add for MediaDuration {
    type Output = Self;
    fn add(self, rhs: Self) -> Self {
        Self(self.0.saturating_add(rhs.0))
    }
}

impl Sub for MediaDuration {
    type Output = Self;
    fn sub(self, rhs: Self) -> Self {
        Self(self.0.saturating_sub(rhs.0))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct TimeRange {
    pub start: Timestamp,
    pub end: Timestamp,
}

impl TimeRange {
    pub fn new(start: Timestamp, end: Timestamp) -> Self {
        debug_assert!(start <= end, "TimeRange start must be <= end");
        Self { start, end }
    }

    pub fn duration(self) -> MediaDuration {
        self.end - self.start
    }

    pub fn contains(self, t: Timestamp) -> bool {
        t >= self.start && t < self.end
    }

    pub fn overlaps(self, other: Self) -> bool {
        self.start < other.end && other.start < self.end
    }

    pub fn intersection(self, other: Self) -> Option<Self> {
        let s = self.start.max(other.start);
        let e = self.end.min(other.end);
        if s < e {
            Some(Self { start: s, end: e })
        } else {
            None
        }
    }
}

/// Scale integer microseconds by a float ratio (clip `speed`) rounding to
/// nearest instead of truncating. Truncation biases every trim/split by up to
/// 1µs in one direction and accumulates across repeated edits; rounding keeps
/// the error bounded at ±0.5µs per operation.

pub fn scale_us_round(us: i64, ratio: f64) -> i64 {
    if !ratio.is_finite() {
        return us;
    }
    (us as f64 * ratio).round() as i64
}

/// Inverse of [`scale_us_round`]: timeline microseconds per source microsecond.
/// Non-finite or zero ratios pass the value through; callers validate speed.
pub fn unscale_us_round(us: i64, ratio: f64) -> i64 {
    if !ratio.is_finite() || ratio == 0.0 {
        return us;
    }
    (us as f64 / ratio).round() as i64
}

#[cfg(test)]
mod time_scale_tests {
    use super::*;

    #[test]
    fn scale_rounds_instead_of_truncating() {
        assert_eq!(scale_us_round(7, 0.5), 4);
        assert_eq!(scale_us_round(7, 2.0), 14);
        assert_eq!(unscale_us_round(200_000, 3.0), 66_667);
    }

    #[test]
    fn roundtrip_stays_bounded() {
        let mut v = 1_000_000i64;
        for _ in 0..1000 {
            v = unscale_us_round(scale_us_round(v, 1.7), 1.7);
        }
        assert!((v - 1_000_000).abs() <= 1, "drifted to {v}");
    }

    #[test]
    fn non_finite_ratio_passes_through() {
        assert_eq!(scale_us_round(42, f64::NAN), 42);
        assert_eq!(scale_us_round(42, f64::INFINITY), 42);
        assert_eq!(unscale_us_round(42, 0.0), 42);
    }
}
