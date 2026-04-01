use serde::{Deserialize, Serialize};
use std::ops::{Add, Sub};

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, Default,
)]
pub struct Timestamp(pub i64);

impl Timestamp {
    pub const ZERO: Self = Self(0);

    pub fn from_micros(us: i64) -> Self {
        Self(us)
    }

    pub fn from_millis(ms: f64) -> Self {
        Self((ms * 1_000.0) as i64)
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

    pub fn as_millis_f64(self) -> f64 {
        self.0 as f64 / 1_000.0
    }

    pub fn clamp_non_negative(self) -> Self {
        Self(self.0.max(0))
    }
}

impl Add<MediaDuration> for Timestamp {
    type Output = Self;
    fn add(self, rhs: MediaDuration) -> Self {
        Self(self.0 + rhs.0)
    }
}

impl Sub for Timestamp {
    type Output = MediaDuration;
    fn sub(self, rhs: Self) -> MediaDuration {
        MediaDuration(self.0 - rhs.0)
    }
}

#[derive(
    Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, Default,
)]
pub struct MediaDuration(pub i64);

impl MediaDuration {
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

    pub fn is_zero(self) -> bool {
        self.0 == 0
    }

    pub fn is_positive(self) -> bool {
        self.0 > 0
    }

    pub fn is_negative(self) -> bool {
        self.0 < 0
    }

    pub fn clamp_non_negative(self) -> Self {
        Self(self.0.max(0))
    }
}

impl Add for MediaDuration {
    type Output = Self;
    fn add(self, rhs: Self) -> Self {
        Self(self.0 + rhs.0)
    }
}

impl Sub for MediaDuration {
    type Output = Self;
    fn sub(self, rhs: Self) -> Self {
        Self(self.0 - rhs.0)
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
