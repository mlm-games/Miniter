/// Color-space metadata for YUV->RGB conversion.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ColorInfo {
    pub matrix: MatrixCoeffs,
    pub range: ColorRange,
    pub chroma_siting: ChromaSiting,
}

impl ColorInfo {
    /// Infer sensible defaults from frame height.
    /// ≥720p -> BT.709, Limited, Center;  <720p -> BT.601, Limited, Center.
    pub fn infer(height: u32) -> Self {
        Self {
            matrix: if height >= 720 {
                MatrixCoeffs::Bt709
            } else {
                MatrixCoeffs::Bt601
            },
            range: ColorRange::Limited,
            chroma_siting: ChromaSiting::Center,
        }
    }
}

impl Default for ColorInfo {
    fn default() -> Self {
        Self {
            matrix: MatrixCoeffs::Bt709,
            range: ColorRange::Limited,
            chroma_siting: ChromaSiting::Center,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum MatrixCoeffs {
    Bt601,
    Bt709,
    Bt2020Ncl,
    Identity,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ColorRange {
    Limited,
    Full,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ChromaSiting {
    Left,
    Center,
    TopLeft,
}

/// An RGBA bitmap frame decoded from video.
#[derive(Debug, Clone)]
pub struct RgbaFrame {
    pub width: u32,
    pub height: u32,
    /// Packed RGBA, `width * height * 4` bytes.
    pub data: Vec<u8>,
    /// Presentation timestamp in microseconds.
    pub pts_us: i64,
    /// Color-space metadata used during YUV->RGB conversion.
    pub color_info: ColorInfo,
}

impl RgbaFrame {
    /// Create a new `RgbaFrame`, validating the buffer size.
    /// Returns `None` if `data.len() != width * height * 4`.
    pub fn new(width: u32, height: u32, data: Vec<u8>, pts_us: i64) -> Option<Self> {
        if data.len() != (width as usize) * (height as usize) * 4 {
            return None;
        }
        Some(Self {
            width,
            height,
            data,
            pts_us,
            color_info: ColorInfo::default(),
        })
    }

    /// Create a new `RgbaFrame` without validation (internal use).
    pub(crate) fn new_unchecked(width: u32, height: u32, data: Vec<u8>, pts_us: i64) -> Self {
        Self {
            width,
            height,
            data,
            pts_us,
            color_info: ColorInfo::default(),
        }
    }
}
