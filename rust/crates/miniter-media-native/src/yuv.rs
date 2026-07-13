//! YUV ↔ RGBA pixel format helpers.

use crate::frame::{ChromaSiting, ColorInfo, ColorRange, MatrixCoeffs};

/// Bilinear-chroma coordinate for a given output (col, row).
/// Returns the four nearest chroma-sample indices and fractional weights.
#[derive(Clone, Copy)]
struct ChromaCoord {
    ix0: usize, ix1: usize, fx: f32,
    iy0: usize, iy1: usize, fy: f32,
}

#[inline(always)]
fn chroma_coord(col: usize, row: usize, cw: usize, ch: usize, siting: ChromaSiting) -> ChromaCoord {
    let (cx, cy) = match siting {
        ChromaSiting::Center => (col as f32 / 2.0 - 0.25, row as f32 / 2.0 - 0.25),
        ChromaSiting::Left | ChromaSiting::TopLeft => (col as f32 / 2.0, row as f32 / 2.0),
    };
    let ix0 = (cx.floor() as usize).min(cw.saturating_sub(1));
    let ix1 = (ix0 + 1).min(cw.saturating_sub(1));
    let iy0 = (cy.floor() as usize).min(ch.saturating_sub(1));
    let iy1 = (iy0 + 1).min(ch.saturating_sub(1));
    ChromaCoord { ix0, ix1, fx: cx - cx.floor(), iy0, iy1, fy: cy - cy.floor() }
}

fn get_matrix_coeffs(matrix: MatrixCoeffs, _height: usize) -> (f32, f32, f32, f32, f32) {
    let (kr, kb) = match matrix {
        MatrixCoeffs::Bt601 => (0.299_f32, 0.114_f32),
        MatrixCoeffs::Bt709 => (0.2126_f32, 0.0722_f32),
        MatrixCoeffs::Bt2020Ncl => (0.2627_f32, 0.0593_f32),
        MatrixCoeffs::Identity => return (1.0, 1.0, 0.0, 0.0, 0.0),
    };
    let kg = 1.0 - kr - kb;
    let rv = 2.0 * (1.0 - kr);
    let bu = 2.0 * (1.0 - kb);
    let gu = -2.0 * kb * (1.0 - kb) / kg;
    let gv = -2.0 * kr * (1.0 - kr) / kg;
    (rv, bu, gu, gv, kr + kb)
}

/// Convert a planar I420 (YUV 4:2:0) frame to packed RGBA.
///
/// `y`, `u`, `v` are the three planes; `width`/`height` describe the
/// luma resolution.  The returned vec has `width * height * 4` bytes.
pub fn yuv420_to_rgba(
    y: &[u8],
    u: &[u8],
    v: &[u8],
    width: usize,
    height: usize,
    y_stride: usize,
    u_stride: usize,
    v_stride: usize,
    color_info: ColorInfo,
) -> Vec<u8> {
    let mut rgba = vec![0u8; width * height * 4];
    let (rv_coeff, bu_coeff, gu_coeff, gv_coeff, _) = get_matrix_coeffs(color_info.matrix, height);

    let y_scale = if color_info.range == ColorRange::Limited {
        255.0 / 219.0
    } else {
        1.0
    };
    let y_offset = if color_info.range == ColorRange::Limited {
        16.0
    } else {
        0.0
    };
    let chroma_scale = if color_info.range == ColorRange::Limited {
        255.0 / 224.0
    } else {
        1.0
    };

    let cw = (width + 1) / 2;
    let ch = (height + 1) / 2;

    for row in 0..height {
        for col in 0..width {
            let yi = row * y_stride + col;
            let y_val = (y[yi] as f32 - y_offset) * y_scale;

            let cc = chroma_coord(col, row, cw, ch, color_info.chroma_siting);

            let u00 = u[cc.iy0 * u_stride + cc.ix0] as f32 - 128.0;
            let u10 = u[cc.iy0 * u_stride + cc.ix1] as f32 - 128.0;
            let u01 = u[cc.iy1 * u_stride + cc.ix0] as f32 - 128.0;
            let u11 = u[cc.iy1 * u_stride + cc.ix1] as f32 - 128.0;
            let u_val = lerp(lerp(u00, u10, cc.fx), lerp(u01, u11, cc.fx), cc.fy) * chroma_scale;

            let v00 = v[cc.iy0 * v_stride + cc.ix0] as f32 - 128.0;
            let v10 = v[cc.iy0 * v_stride + cc.ix1] as f32 - 128.0;
            let v01 = v[cc.iy1 * v_stride + cc.ix0] as f32 - 128.0;
            let v11 = v[cc.iy1 * v_stride + cc.ix1] as f32 - 128.0;
            let v_val = lerp(lerp(v00, v10, cc.fx), lerp(v01, v11, cc.fx), cc.fy) * chroma_scale;

            let r = (y_val + rv_coeff * v_val).clamp(0.0, 255.0) as u8;
            let g = (y_val + gu_coeff * u_val + gv_coeff * v_val).clamp(0.0, 255.0) as u8;
            let b = (y_val + bu_coeff * u_val).clamp(0.0, 255.0) as u8;

            let base = (row * width + col) * 4;
            rgba[base] = r;
            rgba[base + 1] = g;
            rgba[base + 2] = b;
            rgba[base + 3] = 255;
        }
    }

    rgba
}

/// Convert a packed NV12 frame to RGBA.
///
/// NV12 layout: Y plane (width*height bytes) followed by interleaved U/V
/// plane (width*height/2 bytes). Chroma is subsampled 2x in both directions.
pub fn nv12_to_rgba(
    data: &[u8],
    width: usize,
    height: usize,
    color_info: ColorInfo,
) -> Vec<u8> {
    nv12_to_rgba_impl(
        data,
        data.get(width * height..).unwrap_or(&[]),
        width,
        height,
        width,
        width,
        color_info,
    )
}

/// Convert NV12 with separate Y/UV planes and explicit strides to RGBA.
pub fn nv12_to_rgba_separate(
    y: &[u8],
    uv: &[u8],
    width: usize,
    height: usize,
    y_stride: usize,
    uv_stride: usize,
    color_info: ColorInfo,
) -> Vec<u8> {
    nv12_to_rgba_impl(y, uv, width, height, y_stride, uv_stride, color_info)
}

fn nv12_to_rgba_impl(
    y: &[u8],
    uv: &[u8],
    width: usize,
    height: usize,
    y_stride: usize,
    uv_stride: usize,
    color_info: ColorInfo,
) -> Vec<u8> {
    let mut rgba = vec![0u8; width * height * 4];
    let (rv_coeff, bu_coeff, gu_coeff, gv_coeff, _) = get_matrix_coeffs(color_info.matrix, height);

    let y_scale = if color_info.range == ColorRange::Limited {
        255.0 / 219.0
    } else {
        1.0
    };
    let y_offset = if color_info.range == ColorRange::Limited {
        16.0
    } else {
        0.0
    };
    let chroma_scale = if color_info.range == ColorRange::Limited {
        255.0 / 224.0
    } else {
        1.0
    };

    let cw = (width + 1) / 2;
    let ch = (height + 1) / 2;

    for row in 0..height {
        for col in 0..width {
            let yi = row * y_stride + col;
            let y_val = (y[yi] as f32 - y_offset) * y_scale;

            let cc = chroma_coord(col, row, cw, ch, color_info.chroma_siting);

            let u00 = uv[cc.iy0 * uv_stride + cc.ix0 * 2] as f32 - 128.0;
            let v00 = uv[cc.iy0 * uv_stride + cc.ix0 * 2 + 1] as f32 - 128.0;
            let u10 = uv[cc.iy0 * uv_stride + cc.ix1 * 2] as f32 - 128.0;
            let v10 = uv[cc.iy0 * uv_stride + cc.ix1 * 2 + 1] as f32 - 128.0;
            let u01 = uv[cc.iy1 * uv_stride + cc.ix0 * 2] as f32 - 128.0;
            let v01 = uv[cc.iy1 * uv_stride + cc.ix0 * 2 + 1] as f32 - 128.0;
            let u11 = uv[cc.iy1 * uv_stride + cc.ix1 * 2] as f32 - 128.0;
            let v11 = uv[cc.iy1 * uv_stride + cc.ix1 * 2 + 1] as f32 - 128.0;

            let u_val = lerp(lerp(u00, u10, cc.fx), lerp(u01, u11, cc.fx), cc.fy) * chroma_scale;
            let v_val = lerp(lerp(v00, v10, cc.fx), lerp(v01, v11, cc.fx), cc.fy) * chroma_scale;

            let r = (y_val + rv_coeff * v_val).clamp(0.0, 255.0) as u8;
            let g = (y_val + gu_coeff * u_val + gv_coeff * v_val).clamp(0.0, 255.0) as u8;
            let b = (y_val + bu_coeff * u_val).clamp(0.0, 255.0) as u8;

            let base = (row * width + col) * 4;
            rgba[base] = r;
            rgba[base + 1] = g;
            rgba[base + 2] = b;
            rgba[base + 3] = 255;
        }
    }

    rgba
}

#[inline(always)]
fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// Convert packed RGBA pixels to planar I420 (YUV 4:2:0).
///
/// Returns `(y_plane, u_plane, v_plane)`.
pub fn rgba_to_yuv420(rgba: &[u8], width: usize, height: usize) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
    let cw = width / 2;
    let ch = height / 2;
    let mut y_plane = vec![0u8; width * height];
    let mut u_plane = vec![0u8; cw * ch];
    let mut v_plane = vec![0u8; cw * ch];

    for row in 0..height {
        for col in 0..width {
            let base = (row * width + col) * 4;
            let r = rgba[base] as f32;
            let g = rgba[base + 1] as f32;
            let b = rgba[base + 2] as f32;

            let yy = (0.299 * r + 0.587 * g + 0.114 * b).clamp(0.0, 255.0) as u8;
            y_plane[row * width + col] = yy;

            if row % 2 == 0 && col % 2 == 0 {
                let u = (-0.168736 * r - 0.331264 * g + 0.5 * b + 128.0).clamp(0.0, 255.0) as u8;
                let v = (0.5 * r - 0.418688 * g - 0.081312 * b + 128.0).clamp(0.0, 255.0) as u8;
                let ci = (row / 2) * cw + col / 2;
                u_plane[ci] = u;
                v_plane[ci] = v;
            }
        }
    }

    (y_plane, u_plane, v_plane)
}
