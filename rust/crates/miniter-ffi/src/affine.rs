use miniter_domain::filter::VideoFilter;

#[inline]
fn clamp01(x: f32) -> f32 {
    if x.is_finite() { x.clamp(0.0, 1.0) } else { 0.0 }
}

#[inline]
fn safe_zoom(z: f32) -> f32 {
    if !z.is_finite() { 1.0 } else { z.clamp(0.05, 50.0) }
}

#[inline]
fn lerp_f32(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

#[inline]
fn get_px(src: &[u8], stride: usize, x: i32, y: i32) -> (u8, u8, u8, u8) {
    if x < 0 || y < 0 { return (0, 0, 0, 0); }
    let ix = x as usize;
    let iy = y as usize;
    if ix >= stride / 4 || iy >= src.len() / stride { return (0, 0, 0, 0); }
    let i = iy * stride + ix * 4;
    (src[i], src[i + 1], src[i + 2], src[i + 3])
}

#[inline]
fn sample_bilinear(src: &[u8], stride: usize, width: usize, height: usize, fx: f32, fy: f32) -> (u8, u8, u8, u8) {
    let x0 = fx.floor() as i32;
    let y0 = fy.floor() as i32;
    let x1 = x0 + 1;
    let y1 = y0 + 1;
    let tx = fx - x0 as f32;
    let ty = fy - y0 as f32;

    let p00 = get_px(src, stride, x0, y0);
    let p10 = get_px(src, stride, x1, y0);
    let p01 = get_px(src, stride, x0, y1);
    let p11 = get_px(src, stride, x1, y1);

    let r0 = lerp_f32(p00.0 as f32, p10.0 as f32, tx);
    let g0 = lerp_f32(p00.1 as f32, p10.1 as f32, tx);
    let b0 = lerp_f32(p00.2 as f32, p10.2 as f32, tx);
    let a0 = lerp_f32(p00.3 as f32, p10.3 as f32, tx);

    let r1 = lerp_f32(p01.0 as f32, p11.0 as f32, tx);
    let g1 = lerp_f32(p01.1 as f32, p11.1 as f32, tx);
    let b1 = lerp_f32(p01.2 as f32, p11.2 as f32, tx);
    let a1 = lerp_f32(p01.3 as f32, p11.3 as f32, tx);

    let r = lerp_f32(r0, r1, ty).round() as u8;
    let g = lerp_f32(g0, g1, ty).round() as u8;
    let b = lerp_f32(b0, b1, ty).round() as u8;
    let a = lerp_f32(a0, a1, ty).round() as u8;

    (r, g, b, a)
}

pub fn apply_affine_transforms(pixels: &mut Vec<u8>, width: usize, height: usize, filters: &[VideoFilter]) {
    if width == 0 || height == 0 || pixels.len() != width * height * 4 { return; }

    let mut crop_l = 0.0f32; let mut crop_t = 0.0f32;
    let mut crop_r = 0.0f32; let mut crop_b = 0.0f32;
    let mut zoom = 1.0f32; let mut rotate_deg = 0.0f32;
    let mut flip_h = false; let mut flip_v = false;
    let mut translate_x = 0.0f32; let mut translate_y = 0.0f32;

    for f in filters {
        match f {
            VideoFilter::Crop { left, top, right, bottom } => {
                crop_l = clamp01(*left); crop_t = clamp01(*top);
                crop_r = clamp01(*right); crop_b = clamp01(*bottom);
            }
            VideoFilter::Rotate { degrees } => { if degrees.is_finite() { rotate_deg += *degrees; } }
            VideoFilter::Flip { horizontal, vertical } => { flip_h ^= *horizontal; flip_v ^= *vertical; }
            VideoFilter::Transform { scale, translate_x: tx, translate_y: ty } => {
                zoom = safe_zoom(zoom * scale);
                translate_x = tx;
                translate_y = ty;
            }
            _ => {}
        }
    }

    let crop_id = (crop_l + crop_r).abs() < 1e-6 && (crop_t + crop_b).abs() < 1e-6;
    let rot_id = rotate_deg.abs() < 0.5;
    let zoom_id = (zoom - 1.0).abs() < 1e-6;
    let flip_id = !flip_h && !flip_v;

    if crop_id && rot_id && zoom_id && flip_id { return; }

    let roi_w = 1.0 - crop_l - crop_r;
    let roi_h = 1.0 - crop_t - crop_b;
    let rotate_rad = rotate_deg.to_radians();
    let (sin_r, cos_r) = rotate_rad.sin_cos();

    let src = std::mem::take(pixels);
    let mut dst = vec![0u8; src.len()];
    let stride = width * 4;
    let wf = width as f32;
    let hf = height as f32;
    let cx = 0.5;
    let cy = 0.5;

    for yd in 0..height {
        for xd in 0..width {
            let mut u = (xd as f32 + 0.5) / wf;
            let mut v = (yd as f32 + 0.5) / hf;

            let px = (u - cx) * wf;
            let py = (v - cy) * hf;
            let rx = px * cos_r + py * sin_r;
            let ry = -px * sin_r + py * cos_r;
            u = rx / wf + cx;
            v = ry / hf + cy;

            u = u - 0.5 - translate_x;
            v = v - 0.5 - translate_y;
            u = (u + 0.5) * zoom;
            v = (v + 0.5) * zoom;

            u = crop_l + u * roi_w;
            v = crop_t + v * roi_h;
            if flip_h { u = 1.0 - u; }
            if flip_v { v = 1.0 - v; }

            let sx = u * wf - 0.5;
            let sy = v * hf - 0.5;
            let (r, g, b, a) = sample_bilinear(&src, stride, width, height, sx, sy);
            let di = yd * stride + xd * 4;
            dst[di] = r; dst[di+1] = g; dst[di+2] = b; dst[di+3] = a;
        }
    }
    *pixels = dst;
}
