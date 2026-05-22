/// An RGBA bitmap frame decoded from video.
#[derive(Debug, Clone)]
pub struct RgbaFrame {
    pub width: u32,
    pub height: u32,
    /// Packed RGBA, `width * height * 4` bytes.
    pub data: Vec<u8>,
    /// Presentation timestamp in microseconds.
    pub pts_us: i64,
}

impl RgbaFrame {
    /// Create a new `RgbaFrame`, validating the buffer size.
    /// Returns `None` if `data.len() != width * height * 4`.
    pub fn new(width: u32, height: u32, data: Vec<u8>, pts_us: i64) -> Option<Self> {
        if data.len() != (width as usize) * (height as usize) * 4 {
            return None;
        }
        Some(Self { width, height, data, pts_us })
    }

    /// Create a new `RgbaFrame` without validation (internal use).
    pub(crate) fn new_unchecked(width: u32, height: u32, data: Vec<u8>, pts_us: i64) -> Self {
        Self { width, height, data, pts_us }
    }
}
