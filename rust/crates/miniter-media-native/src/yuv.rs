//! YUV ↔ RGBA pixel format helpers.

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
) -> Vec<u8> {
    let mut rgba = vec![0u8; width * height * 4];

    for row in 0..height {
        for col in 0..width {
            let yi = row * y_stride + col;
            let ui = (row / 2) * u_stride + col / 2;
            let vi = (row / 2) * v_stride + col / 2;

            let yy = y[yi] as f32;
            let uu = u[ui] as f32 - 128.0;
            let vv = v[vi] as f32 - 128.0;

            let r = (yy + 1.402 * vv).clamp(0.0, 255.0) as u8;
            let g = (yy - 0.344136 * uu - 0.714136 * vv).clamp(0.0, 255.0) as u8;
            let b = (yy + 1.772 * uu).clamp(0.0, 255.0) as u8;

            let base = (row * width + col) * 4;
            rgba[base] = r;
            rgba[base + 1] = g;
            rgba[base + 2] = b;
            rgba[base + 3] = 255;
        }
    }

    rgba
}

/// Convert packed RGBA pixels to planar I420 (YUV 4:2:0).
///
/// Returns `(y_plane, u_plane, v_plane)`.
pub fn rgba_to_yuv420(rgba: &[u8], width: usize, height: usize) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
    let mut y_plane = vec![0u8; width * height];
    let cw = width / 2;
    let ch = height / 2;
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
