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
