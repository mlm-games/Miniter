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

    /// ITU-T H.273 `matrix_coefficients` to the enum the YUV→RGB path uses.
    ///
    /// `None` for code 2 (unspecified), code 3 (reserved) and anything
    /// unassigned. §E.3.1 leaves those to the caller's own documented
    /// fallback, which here is [`ColorInfo::infer`] — guessing a matrix from
    /// frame height is a guess, and must not be reported as a signal.
    pub fn matrix_from_cicp(code: u8) -> Option<MatrixCoeffs> {
        Some(match code {
            // 0 = identity / GBR.
            0 => MatrixCoeffs::Identity,
            // 1 = BT.709.
            1 => MatrixCoeffs::Bt709,
            // 4 = FCC, 5 = BT.470BG (625), 6 = SMPTE 170M, 7 = SMPTE 240M. All
            // SD-range; H.273 notes 5 and 6 are functionally identical.
            4..=7 => MatrixCoeffs::Bt601,
            // 8 = YCgCo, defined over the BT.709 luma coefficients.
            8 => MatrixCoeffs::Bt709,
            // 9 = BT.2020 non-constant luminance. 12 = chroma-derived NCL and
            // 14 = ICtCp share its coefficients.
            9 | 12 | 14 => MatrixCoeffs::Bt2020Ncl,
            // 10 = BT.2020 constant luminance, 13 = chroma-derived CL.
            10 | 13 => MatrixCoeffs::Bt2020Cl,
            // 11 = SMPTE ST 2085 (PQ), carried on BT.2020 NCL coefficients.
            11 => MatrixCoeffs::Bt2020Ncl,
            _ => return None,
        })
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
    /// ITU-T H.273 matrix_coeffs 9. Kr/Kb only; the luma/chroma derivation is
    /// shared with [`MatrixCoeffs::Bt2020Cl`].
    Bt2020Ncl,
    /// ITU-T H.273 matrix_coeffs 10. Same primaries and coefficients as
    /// [`MatrixCoeffs::Bt2020Ncl`] but constant-luminance, so the G-Y and
    /// B-Y terms depend on Y rather than being constants.
    Bt2020Cl,
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

#[cfg(test)]
mod tests {
    use super::{ColorInfo, ColorRange, MatrixCoeffs};

    /// Every H.273 code a stream can legally signal has to land somewhere
    /// deliberate. Getting this wrong is not cosmetic: the matrix decides the
    /// YUV→RGB coefficients, so a wrong entry renders the stream wrong.
    #[test]
    fn matrix_from_cicp_covers_every_code() {
        assert_eq!(ColorInfo::matrix_from_cicp(0), Some(MatrixCoeffs::Identity));
        assert_eq!(ColorInfo::matrix_from_cicp(1), Some(MatrixCoeffs::Bt709));
        for code in 4..=7 {
            assert_eq!(
                ColorInfo::matrix_from_cicp(code),
                Some(MatrixCoeffs::Bt601),
                "code {code}"
            );
        }
        assert_eq!(ColorInfo::matrix_from_cicp(8), Some(MatrixCoeffs::Bt709));
        assert_eq!(
            ColorInfo::matrix_from_cicp(9),
            Some(MatrixCoeffs::Bt2020Ncl)
        );
        assert_eq!(
            ColorInfo::matrix_from_cicp(10),
            Some(MatrixCoeffs::Bt2020Cl)
        );
        assert_eq!(
            ColorInfo::matrix_from_cicp(11),
            Some(MatrixCoeffs::Bt2020Ncl)
        );
        assert_eq!(
            ColorInfo::matrix_from_cicp(12),
            Some(MatrixCoeffs::Bt2020Ncl)
        );
        assert_eq!(
            ColorInfo::matrix_from_cicp(13),
            Some(MatrixCoeffs::Bt2020Cl)
        );
        assert_eq!(
            ColorInfo::matrix_from_cicp(14),
            Some(MatrixCoeffs::Bt2020Ncl)
        );
    }

    /// Code 2 is "unspecified" and 3 is reserved. Neither may be guessed at —
    /// the caller has to fall back on its own documented terms, and
    /// `matrix_from_cicp` returning a value would hide that.
    #[test]
    fn unspecified_and_reserved_are_not_mapped() {
        for code in [2u8, 3, 15, 31, 255] {
            assert_eq!(
                ColorInfo::matrix_from_cicp(code),
                None,
                "code {code} must stay unmapped"
            );
        }
    }

    #[test]
    fn infer_still_guesses_only_when_nothing_was_signalled() {
        assert_eq!(ColorInfo::infer(1080).matrix, MatrixCoeffs::Bt709);
        assert_eq!(ColorInfo::infer(480).matrix, MatrixCoeffs::Bt601);
        assert_eq!(ColorInfo::infer(480).range, ColorRange::Limited);
    }
}
