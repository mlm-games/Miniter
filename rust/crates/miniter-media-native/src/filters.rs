use libblur::{AnisotropicRadius, BlurImageMut, FastBlurChannels, ThreadingPolicy};
use palette::{LinSrgba, Srgba};
use std::collections::HashMap;
use std::fs;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::UNIX_EPOCH;

pub fn blur_rgba(buf: &mut [u8], width: usize, height: usize, radius: f32) {
    let r = radius.round().max(1.0) as u32;
    let mut img = BlurImageMut::borrow(
        buf,
        width as u32,
        height as u32,
        FastBlurChannels::Channels4,
    );
    let _ = libblur::stack_blur(&mut img, AnisotropicRadius::new(r), ThreadingPolicy::Single);
}

fn blur_taps(radius: f32) -> usize {
    (radius.max(1.0) * 3.0).round().clamp(4.0, 64.0) as usize
}

fn bilinear(buf: &[u8], w: usize, h: usize, x: f32, y: f32) -> [f32; 4] {
    let cx = x.clamp(0.0, w.saturating_sub(1) as f32);
    let cy = y.clamp(0.0, h.saturating_sub(1) as f32);
    let x0 = cx.floor() as usize;
    let y0 = cy.floor() as usize;
    let x1 = (x0 + 1).min(w - 1);
    let y1 = (y0 + 1).min(h - 1);
    let fx = cx - x0 as f32;
    let fy = cy - y0 as f32;

    let mut out = [0.0f32; 4];
    for (c, slot) in out.iter_mut().enumerate() {
        let p00 = buf[(y0 * w + x0) * 4 + c] as f32;
        let p10 = buf[(y0 * w + x1) * 4 + c] as f32;
        let p01 = buf[(y1 * w + x0) * 4 + c] as f32;
        let p11 = buf[(y1 * w + x1) * 4 + c] as f32;
        let top = p00 + (p10 - p00) * fx;
        let bottom = p01 + (p11 - p01) * fx;
        *slot = top + (bottom - top) * fy;
    }
    out
}

fn accumulate_taps<F>(buf: &mut [u8], w: usize, h: usize, taps: usize, tap: F)
where
    F: Fn(f32, f32, f32) -> (f32, f32),
{
    if w == 0 || h == 0 || buf.len() < w * h * 4 {
        return;
    }
    let src = buf.to_vec();
    let inv = 1.0 / taps.max(1) as f32;

    for y in 0..h {
        for x in 0..w {
            let mut acc = [0.0f32; 4];
            for t in 0..taps {
                let f = if taps > 1 {
                    t as f32 / (taps - 1) as f32
                } else {
                    0.0
                };
                let (sx, sy) = tap(x as f32, y as f32, f);
                let s = bilinear(&src, w, h, sx, sy);
                for c in 0..4 {
                    acc[c] += s[c];
                }
            }
            let p = (y * w + x) * 4;
            for c in 0..4 {
                buf[p + c] = (acc[c] * inv).round().clamp(0.0, 255.0) as u8;
            }
        }
    }
}

fn axis_pass(buf: &mut [u8], src: &[u8], w: usize, h: usize, r: usize, vertical: bool) {
    let len = if vertical { h } else { w };
    let lines = if vertical { w } else { h };
    let n = (2 * r + 1) as u32;

    for line in 0..lines {
        for i in 0..len {
            let mut acc = [0u32; 4];
            for k in 0..(2 * r + 1) {
                let j = (i as isize + k as isize - r as isize).clamp(0, len as isize - 1) as usize;
                let (x, y) = if vertical { (line, j) } else { (j, line) };
                let p = (y * w + x) * 4;
                for c in 0..4 {
                    acc[c] += src[p + c] as u32;
                }
            }
            let (x, y) = if vertical { (line, i) } else { (i, line) };
            let p = (y * w + x) * 4;
            for c in 0..4 {
                buf[p + c] = (acc[c] / n) as u8;
            }
        }
    }
}

pub fn blur_box_rgba(buf: &mut [u8], w: usize, h: usize, radius: f32) {
    if w == 0 || h == 0 || buf.len() < w * h * 4 {
        return;
    }
    let r = radius.round().max(1.0) as usize;
    let src = buf.to_vec();
    axis_pass(buf, &src, w, h, r, false);
    let intermediate = buf.to_vec();
    axis_pass(buf, &intermediate, w, h, r, true);
}

fn gaussian_kernel(sigma: f32) -> Vec<f32> {
    let sigma = sigma.max(0.1);
    let r = (sigma * 3.0).ceil().max(1.0) as usize;
    let mut kernel = Vec::with_capacity(r * 2 + 1);
    let mut sum = 0.0f32;
    for i in 0..=(r * 2) {
        let d = i as f32 - r as f32;
        let v = (-(d * d) / (2.0 * sigma * sigma)).exp();
        kernel.push(v);
        sum += v;
    }
    if sum > 0.0 {
        for v in &mut kernel {
            *v /= sum;
        }
    }
    kernel
}

fn weighted_pass(buf: &mut [u8], src: &[u8], w: usize, h: usize, kernel: &[f32], vertical: bool) {
    let r = kernel.len() / 2;
    let len = if vertical { h } else { w };
    let lines = if vertical { w } else { h };

    for line in 0..lines {
        for i in 0..len {
            let mut acc = [0.0f32; 4];
            for (ki, weight) in kernel.iter().enumerate() {
                let j = (i as isize + ki as isize - r as isize).clamp(0, len as isize - 1) as usize;
                let (x, y) = if vertical { (line, j) } else { (j, line) };
                let p = (y * w + x) * 4;
                for c in 0..4 {
                    acc[c] += src[p + c] as f32 * weight;
                }
            }
            let (x, y) = if vertical { (line, i) } else { (i, line) };
            let p = (y * w + x) * 4;
            for c in 0..4 {
                buf[p + c] = acc[c].round().clamp(0.0, 255.0) as u8;
            }
        }
    }
}

pub fn blur_gaussian_rgba(buf: &mut [u8], w: usize, h: usize, radius: f32) {
    if w == 0 || h == 0 || buf.len() < w * h * 4 {
        return;
    }
    let kernel = gaussian_kernel(radius / 3.0);
    let src = buf.to_vec();
    weighted_pass(buf, &src, w, h, &kernel, false);
    let intermediate = buf.to_vec();
    weighted_pass(buf, &intermediate, w, h, &kernel, true);
}

pub fn blur_motion_rgba(buf: &mut [u8], w: usize, h: usize, radius: f32, angle_degrees: f32) {
    let taps = blur_taps(radius);
    let reach = radius.max(1.0);
    let angle = angle_degrees.to_radians();
    let (dx, dy) = (angle.cos() * reach, angle.sin() * reach);

    accumulate_taps(buf, w, h, taps, |x, y, t| {
        let o = t - 0.5;
        (x + dx * o * 2.0, y + dy * o * 2.0)
    });
}

pub fn blur_radial_rgba(buf: &mut [u8], w: usize, h: usize, radius: f32) {
    let taps = blur_taps(radius);
    let cx = w as f32 / 2.0;
    let cy = h as f32 / 2.0;
    let sweep = (radius * 2.0).to_radians();

    accumulate_taps(buf, w, h, taps, |x, y, t| {
        let a = (t - 0.5) * sweep;
        let (s, c) = a.sin_cos();
        let px = x - cx;
        let py = y - cy;
        (cx + px * c - py * s, cy + px * s + py * c)
    });
}

pub fn blur_zoom_rgba(buf: &mut [u8], w: usize, h: usize, radius: f32) {
    let taps = blur_taps(radius);
    let cx = w as f32 / 2.0;
    let cy = h as f32 / 2.0;
    let spread = (radius * 0.02).clamp(0.0, 0.9);

    accumulate_taps(buf, w, h, taps, |x, y, t| {
        let k = 1.0 + spread * t;
        (cx + (x - cx) * k, cy + (y - cy) * k)
    });
}

/// Unsharp mask sharpen
pub fn sharpen_rgba(buf: &mut [u8], width: usize, height: usize, amount: f32) {
    if amount <= 0.0 {
        return;
    }
    let mut blurred = buf.to_vec();
    blur_rgba(&mut blurred, width, height, 1.0);
    for (i, px) in buf.as_chunks_mut::<4>().0.iter_mut().enumerate() {
        for c in 0..3 {
            let orig = px[c] as f32;
            let blur = blurred[i * 4 + c] as f32;
            px[c] = (orig + (orig - blur) * amount).round().clamp(0.0, 255.0) as u8;
        }
    }
}

fn apply_in_linear<F>(buf: &mut [u8], f: F)
where
    F: Fn(&mut LinSrgba),
{
    for px in buf.as_chunks_mut::<4>().0 {
        let srgba = Srgba::new(
            px[0] as f32 / 255.0,
            px[1] as f32 / 255.0,
            px[2] as f32 / 255.0,
            px[3] as f32 / 255.0,
        );
        let mut linear: LinSrgba = srgba.into();
        f(&mut linear);
        let result: Srgba = linear.into();
        px[0] = (result.color.red * 255.0).round().clamp(0.0, 255.0) as u8;
        px[1] = (result.color.green * 255.0).round().clamp(0.0, 255.0) as u8;
        px[2] = (result.color.blue * 255.0).round().clamp(0.0, 255.0) as u8;
    }
}

/// Linear space brightness adjust(ion?)
pub fn adjust_brightness(buf: &mut [u8], value: f32) {
    let offset = value / 100.0;
    apply_in_linear(buf, |linear| {
        linear.color.red = (linear.color.red + offset).clamp(0.0, 1.0);
        linear.color.green = (linear.color.green + offset).clamp(0.0, 1.0);
        linear.color.blue = (linear.color.blue + offset).clamp(0.0, 1.0);
    });
}

pub fn adjust_contrast(buf: &mut [u8], factor: f32) {
    apply_in_linear(buf, |linear| {
        linear.color.red = ((linear.color.red - 0.5) * factor + 0.5).clamp(0.0, 1.0);
        linear.color.green = ((linear.color.green - 0.5) * factor + 0.5).clamp(0.0, 1.0);
        linear.color.blue = ((linear.color.blue - 0.5) * factor + 0.5).clamp(0.0, 1.0);
    });
}

pub fn adjust_saturation(buf: &mut [u8], factor: f32) {
    apply_in_linear(buf, |linear| {
        let gray =
            0.299 * linear.color.red + 0.587 * linear.color.green + 0.114 * linear.color.blue;
        linear.color.red = (gray + (linear.color.red - gray) * factor).clamp(0.0, 1.0);
        linear.color.green = (gray + (linear.color.green - gray) * factor).clamp(0.0, 1.0);
        linear.color.blue = (gray + (linear.color.blue - gray) * factor).clamp(0.0, 1.0);
    });
}

pub fn adjust_hue(buf: &mut [u8], degrees: f32) {
    let angle = (degrees % 360.0) * std::f32::consts::PI / 180.0;
    let cos_a = angle.cos();
    let sin_a = angle.sin();

    apply_in_linear(buf, |linear| {
        let r = linear.color.red;
        let g = linear.color.green;
        let b = linear.color.blue;

        let nr = (0.299 + 0.701 * cos_a + 0.168 * sin_a) * r
            + (0.587 - 0.587 * cos_a + 0.330 * sin_a) * g
            + (0.114 - 0.114 * cos_a - 0.497 * sin_a) * b;
        let ng = (0.299 - 0.299 * cos_a - 0.328 * sin_a) * r
            + (0.587 + 0.413 * cos_a + 0.035 * sin_a) * g
            + (0.114 - 0.114 * cos_a + 0.292 * sin_a) * b;
        let nb = (0.299 - 0.300 * cos_a + 1.250 * sin_a) * r
            + (0.587 - 0.588 * cos_a - 1.050 * sin_a) * g
            + (0.114 + 0.886 * cos_a - 0.203 * sin_a) * b;

        linear.color.red = nr.clamp(0.0, 1.0);
        linear.color.green = ng.clamp(0.0, 1.0);
        linear.color.blue = nb.clamp(0.0, 1.0);
    });
}

#[derive(Debug, Clone, Copy)]
pub struct ColorGrade {
    pub brightness: f32,
    pub contrast: f32,
    pub saturation: f32,
    pub vibrance: f32,
    pub temperature: f32,
    pub tint: f32,
    pub exposure: f32,
    pub gamma: f32,
    pub lift: f32,
    pub gain: f32,
    pub highlights: f32,
    pub shadows: f32,
}

impl ColorGrade {
    fn is_identity(&self) -> bool {
        self.brightness == 0.0
            && self.contrast == 1.0
            && self.saturation == 1.0
            && self.vibrance == 0.0
            && self.temperature == 0.0
            && self.tint == 0.0
            && self.exposure == 0.0
            && self.gamma == 1.0
            && self.lift == 0.0
            && self.gain == 1.0
            && self.highlights == 0.0
            && self.shadows == 0.0
    }
}

pub fn apply_color_grade(buf: &mut [u8], grade: &ColorGrade) {
    if grade.is_identity() {
        return;
    }

    let ev = 2.0f32.powf(grade.exposure);
    let r_gain = ev * (1.0 + 0.3 * grade.temperature);
    let g_gain = ev * (1.0 + 0.3 * grade.tint);
    let b_gain = ev * (1.0 - 0.3 * grade.temperature);
    let inv_gamma = if grade.gamma > 0.0 {
        1.0 / grade.gamma
    } else {
        1.0
    };
    let tone_map = grade.highlights != 0.0 || grade.shadows != 0.0;
    let saturate = grade.saturation != 1.0 || grade.vibrance != 0.0;
    let contrast = grade.contrast;
    let brightness = grade.brightness;

    apply_in_linear(buf, |px| {
        let mut r = px.color.red * r_gain * grade.gain + grade.lift;
        let mut g = px.color.green * g_gain * grade.gain + grade.lift;
        let mut b = px.color.blue * b_gain * grade.gain + grade.lift;

        if tone_map {
            let l = (0.2126 * r + 0.7152 * g + 0.0722 * b).clamp(0.0, 1.0);
            let adjust = grade.highlights * l * l + grade.shadows * (1.0 - l) * (1.0 - l);
            r += adjust;
            g += adjust;
            b += adjust;
        }

        r = ((r + brightness) - 0.5) * contrast + 0.5;
        g = ((g + brightness) - 0.5) * contrast + 0.5;
        b = ((b + brightness) - 0.5) * contrast + 0.5;

        r = r.max(0.0).powf(inv_gamma);
        g = g.max(0.0).powf(inv_gamma);
        b = b.max(0.0).powf(inv_gamma);

        if saturate {
            let l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
            let mx = r.max(g).max(b);
            let mn = r.min(g).min(b);
            let current = if mx > 0.0 { (mx - mn) / mx } else { 0.0 };
            let s = (grade.saturation + grade.vibrance * (1.0 - current)).max(0.0);
            r = l + (r - l) * s;
            g = l + (g - l) * s;
            b = l + (b - l) * s;
        }

        px.color.red = r.clamp(0.0, 1.0);
        px.color.green = g.clamp(0.0, 1.0);
        px.color.blue = b.clamp(0.0, 1.0);
    });
}

pub fn apply_chroma_key(
    buf: &mut [u8],
    key_color: [u8; 3],
    tolerance: f32,
    softness: f32,
    spill: f32,
) {
    fn chroma(r: f32, g: f32, b: f32) -> (f32, f32) {
        let y = 0.299 * r + 0.587 * g + 0.114 * b;
        (0.564 * (b - y), 0.713 * (r - y))
    }

    let (key_cb, key_cr) = chroma(
        key_color[0] as f32 / 255.0,
        key_color[1] as f32 / 255.0,
        key_color[2] as f32 / 255.0,
    );
    let tol = tolerance.clamp(0.0, 1.0);
    let soft = softness.clamp(1.0 / 255.0, 1.0);
    let spill = spill.clamp(0.0, 1.0);

    for px in buf.as_chunks_mut::<4>().0 {
        let r = px[0] as f32 / 255.0;
        let g = px[1] as f32 / 255.0;
        let b = px[2] as f32 / 255.0;
        let (cb, cr) = chroma(r, g, b);
        let distance = ((cb - key_cb).powi(2) + (cr - key_cr).powi(2)).sqrt();
        let matte = ((distance - tol) / soft).clamp(0.0, 1.0);
        if matte >= 1.0 {
            continue;
        }

        let a = px[3] as f32 / 255.0;
        px[3] = (a * matte * 255.0).round().clamp(0.0, 255.0) as u8;

        if spill > 0.0 {
            let luma = 0.299 * r + 0.587 * g + 0.114 * b;
            let s = spill * (1.0 - matte);
            px[0] = ((r + (luma - r) * s) * 255.0).round().clamp(0.0, 255.0) as u8;
            px[1] = ((g + (luma - g) * s) * 255.0).round().clamp(0.0, 255.0) as u8;
            px[2] = ((b + (luma - b) * s) * 255.0).round().clamp(0.0, 255.0) as u8;
        }
    }
}

fn mosaic_cells<F>(buf: &mut [u8], width: usize, height: usize, cell_of: F)
where
    F: Fn(usize, usize) -> (i32, i32),
{
    let n = width * height;
    if n == 0 || buf.len() < n * 4 {
        return;
    }

    let mut index: HashMap<(i32, i32), usize> = HashMap::new();
    let mut sums: Vec<[f32; 4]> = Vec::new();
    let mut counts: Vec<u32> = Vec::new();
    let mut owner = vec![0u32; n];

    for y in 0..height {
        for x in 0..width {
            let key = cell_of(x, y);
            let idx = match index.get(&key) {
                Some(i) => *i,
                None => {
                    let i = sums.len();
                    index.insert(key, i);
                    sums.push([0.0; 4]);
                    counts.push(0);
                    i
                }
            };
            let p = (y * width + x) * 4;
            sums[idx][0] += buf[p] as f32;
            sums[idx][1] += buf[p + 1] as f32;
            sums[idx][2] += buf[p + 2] as f32;
            sums[idx][3] += buf[p + 3] as f32;
            counts[idx] += 1;
            owner[y * width + x] = idx as u32;
        }
    }

    for (i, cell) in owner.iter().enumerate() {
        let c = *cell as usize;
        let count = counts[c].max(1) as f32;
        let p = i * 4;
        for ch in 0..4 {
            buf[p + ch] = (sums[c][ch] / count).round().clamp(0.0, 255.0) as u8;
        }
    }
}

fn hex_cell(x: f32, y: f32, r: f32) -> (i32, i32) {
    let cx = (0.577_350_27 * x - (1.0 / 3.0) * y) / r;
    let cz = ((2.0 / 3.0) * y) / r;
    let cy = -cx - cz;
    let rx = cx.round();
    let ry = cy.round();
    let rz = cz.round();
    let dx = (rx - cx).abs();
    let dy = (ry - cy).abs();
    let dz = (rz - cz).abs();
    let (fx, fz) = if dx > dy && dx > dz {
        (-ry - rz, rz)
    } else if dy > dz {
        (rx, rz)
    } else {
        (rx, -rx - ry)
    };
    (fx as i32, fz as i32)
}

pub fn mosaic_square(buf: &mut [u8], width: usize, height: usize, size: f32) {
    let s = size.max(1.0);
    mosaic_cells(buf, width, height, |x, y| {
        ((x as f32 / s) as i32, (y as f32 / s) as i32)
    });
}

pub fn mosaic_hexagon(buf: &mut [u8], width: usize, height: usize, size: f32) {
    let r = size.max(1.0);
    mosaic_cells(buf, width, height, |x, y| hex_cell(x as f32, y as f32, r));
}

pub fn mosaic_triangle(buf: &mut [u8], width: usize, height: usize, size: f32) {
    let s = size.max(1.0);
    mosaic_cells(buf, width, height, |x, y| {
        let cx = (x as f32 / s) as i32;
        let cy = (y as f32 / s) as i32;
        let fx = x as f32 - cx as f32 * s;
        let fy = y as f32 - cy as f32 * s;
        let half = if fx + fy < s { 0 } else { 1 };
        (cx * 2 + half, cy)
    });
}

fn linear_multiply(value: u8, factor: f32) -> u8 {
    let srgb = Srgba::new(value as f32 / 255.0, 0.0, 0.0, 1.0);
    let linear: LinSrgba = srgb.into();
    let scaled = LinSrgba::new(linear.color.red * factor, 0.0, 0.0, 1.0);
    let out: Srgba = scaled.into();
    (out.color.red * 255.0).round().clamp(0.0, 255.0) as u8
}

pub fn apply_vignette(
    buf: &mut [u8],
    width: usize,
    height: usize,
    amount: f32,
    radius: f32,
    softness: f32,
) {
    if width == 0 || height == 0 || buf.len() < width * height * 4 {
        return;
    }
    let amount = amount.clamp(0.0, 1.0);
    if amount <= 0.0 {
        return;
    }

    let cx = (width as f32 / 2.0).max(1.0);
    let cy = (height as f32 / 2.0).max(1.0);
    let radius = radius.clamp(0.0, 1.5);
    let softness = softness.clamp(0.001, 2.0);
    let inv_diagonal = 1.0 / std::f32::consts::SQRT_2;

    for y in 0..height {
        for x in 0..width {
            let dx = (x as f32 + 0.5 - cx) / cx;
            let dy = (y as f32 + 0.5 - cy) / cy;
            let distance = (dx * dx + dy * dy).sqrt() * inv_diagonal;
            let falloff = ((distance - radius) / softness).clamp(0.0, 1.0);
            if falloff <= 0.0 {
                continue;
            }
            let factor = 1.0 - amount * falloff;
            let p = (y * width + x) * 4;
            for c in 0..3 {
                buf[p + c] = linear_multiply(buf[p + c], factor);
            }
        }
    }
}

pub fn apply_posterize(buf: &mut [u8], levels: f32) {
    let steps = levels.round().clamp(2.0, 64.0) - 1.0;
    if steps <= 0.0 {
        return;
    }
    for px in buf.as_chunks_mut::<4>().0 {
        for c in 0..3 {
            let value = px[c] as f32 / 255.0;
            px[c] = ((value * steps).round() / steps * 255.0)
                .round()
                .clamp(0.0, 255.0) as u8;
        }
    }
}

/// Maps the frame's luminance onto a two-colour ramp. Operates on encoded
/// values, like grayscale and sepia, so the ramp the user picks is the ramp the
/// frame shows.
pub fn apply_duotone(buf: &mut [u8], dark: [u8; 3], light: [u8; 3]) {
    for px in buf.as_chunks_mut::<4>().0 {
        let luma = (0.299 * px[0] as f32 + 0.587 * px[1] as f32 + 0.114 * px[2] as f32) / 255.0;
        for c in 0..3 {
            let from = dark[c] as f32;
            let to = light[c] as f32;
            px[c] = (from + (to - from) * luma).round().clamp(0.0, 255.0) as u8;
        }
    }
}

/// Deterministic per-cell luminance noise. A hash rather than an RNG so the
/// preview, the export and any re-render produce identical grain.
fn grain_noise(cell_x: i32, cell_y: i32) -> f32 {
    let mut hash =
        (cell_x as u32).wrapping_mul(0x9E37_79B1) ^ (cell_y as u32).wrapping_mul(0x85EB_CA77);
    hash ^= hash >> 15;
    hash = hash.wrapping_mul(0x2545_F491);
    hash ^= hash >> 13;
    (hash & 0x00FF_FFFF) as f32 / 0x00FF_FFFF as f32
}

pub fn apply_grain(buf: &mut [u8], width: usize, height: usize, amount: f32, size: f32) {
    let amount = amount.clamp(0.0, 1.0);
    if amount <= 0.0 || width == 0 || height == 0 || buf.len() < width * height * 4 {
        return;
    }
    let cell = size.clamp(1.0, 64.0);
    for y in 0..height {
        for x in 0..width {
            let noise = grain_noise(
                (x as f32 / cell).floor() as i32,
                (y as f32 / cell).floor() as i32,
            );
            let offset = (noise - 0.5) * 2.0 * amount * 255.0;
            let p = (y * width + x) * 4;
            for c in 0..3 {
                px_add(&mut buf[p + c], offset);
            }
        }
    }
}

fn px_add(value: &mut u8, offset: f32) {
    *value = (*value as f32 + offset).round().clamp(0.0, 255.0) as u8;
}

pub fn apply_grayscale(buf: &mut [u8]) {
    for px in buf.as_chunks_mut::<4>().0 {
        let gray = (0.299 * px[0] as f32 + 0.587 * px[1] as f32 + 0.114 * px[2] as f32)
            .round()
            .clamp(0.0, 255.0) as u8;
        px[0] = gray;
        px[1] = gray;
        px[2] = gray;
    }
}

pub fn apply_sepia(buf: &mut [u8]) {
    for px in buf.as_chunks_mut::<4>().0 {
        let r = px[0] as f32;
        let g = px[1] as f32;
        let b = px[2] as f32;
        px[0] = (0.393 * r + 0.769 * g + 0.189 * b)
            .round()
            .clamp(0.0, 255.0) as u8;
        px[1] = (0.349 * r + 0.686 * g + 0.168 * b)
            .round()
            .clamp(0.0, 255.0) as u8;
        px[2] = (0.272 * r + 0.534 * g + 0.131 * b)
            .round()
            .clamp(0.0, 255.0) as u8;
    }
}

pub fn scale_alpha(buf: &mut [u8], factor: f32) {
    let a = factor.clamp(0.0, 1.0);
    for px in buf.as_chunks_mut::<4>().0 {
        px[3] = ((px[3] as f32) * a).round().clamp(0.0, 255.0) as u8;
    }
}

pub fn premultiply_alpha(buf: &mut [u8]) {
    for px in buf.as_chunks_mut::<4>().0 {
        let a = px[3] as f32 / 255.0;
        px[0] = ((px[0] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[1] = ((px[1] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[2] = ((px[2] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[3] = 255;
    }
}

const MAX_LUT_1D_SIZE: usize = 65_536;
const MAX_LUT_3D_SIZE: usize = 256;
const LUT_VALUE_TOLERANCE: f32 = 1e-3;
const LUT_CACHE_CAPACITY: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LutKind {
    OneD(usize),
    ThreeD(usize),
}

#[derive(Debug, Clone)]
pub struct CubeLut {
    kind: LutKind,
    domain_min: [f32; 3],
    domain_max: [f32; 3],
    entries: Vec<[f32; 3]>,
}

#[derive(Debug, thiserror::Error)]
pub enum LutError {
    #[error("missing LUT_1D_SIZE or LUT_3D_SIZE")]
    MissingSize,
    #[error("conflicting LUT size declarations")]
    ConflictingSize,
    #[error("unsupported LUT size {0}")]
    UnsupportedSize(String),
    #[error("line {line}: {reason}")]
    InvalidLine { line: usize, reason: String },
    #[error("LUT declares {expected} entries but contains {found}")]
    EntryCount { expected: usize, found: usize },
    #[error("invalid domain: {0}")]
    InvalidDomain(&'static str),
}

impl CubeLut {
    pub fn parse(text: &str) -> Result<Self, LutError> {
        let mut kind: Option<LutKind> = None;
        let mut domain_min = [0.0f32; 3];
        let mut domain_max = [1.0f32; 3];
        let mut entries: Vec<[f32; 3]> = Vec::new();

        for (index, raw) in text.lines().enumerate() {
            let line = index + 1;
            let content = raw.split('#').next().unwrap_or_default().trim();
            if content.is_empty() {
                continue;
            }

            let mut parts = content.split_whitespace();
            let first = parts.next().unwrap_or_default();
            let rest: Vec<&str> = parts.collect();

            if looks_numeric(first) {
                entries.push(parse_lut_entry(first, &rest, line)?);
                continue;
            }

            match first.to_ascii_uppercase().as_str() {
                "LUT_1D_SIZE" => {
                    let size = parse_lut_size(&rest, MAX_LUT_1D_SIZE, line)?;
                    if kind.replace(LutKind::OneD(size)).is_some() {
                        return Err(LutError::ConflictingSize);
                    }
                }
                "LUT_3D_SIZE" => {
                    let size = parse_lut_size(&rest, MAX_LUT_3D_SIZE, line)?;
                    if kind.replace(LutKind::ThreeD(size)).is_some() {
                        return Err(LutError::ConflictingSize);
                    }
                }
                "DOMAIN_MIN" => domain_min = parse_lut_domain(&rest, line)?,
                "DOMAIN_MAX" => domain_max = parse_lut_domain(&rest, line)?,
                _ => {}
            }
        }

        let kind = kind.ok_or(LutError::MissingSize)?;
        for channel in 0..3 {
            if !(domain_max[channel] > domain_min[channel]) {
                return Err(LutError::InvalidDomain("maximum must exceed minimum"));
            }
        }
        let expected = match kind {
            LutKind::OneD(size) => size,
            LutKind::ThreeD(size) => size * size * size,
        };
        if entries.len() != expected {
            return Err(LutError::EntryCount {
                expected,
                found: entries.len(),
            });
        }

        Ok(Self {
            kind,
            domain_min,
            domain_max,
            entries,
        })
    }
}

fn looks_numeric(token: &str) -> bool {
    token.starts_with(|c: char| c.is_ascii_digit() || c == '-' || c == '+' || c == '.')
}

fn parse_lut_number(token: &str) -> Option<f32> {
    token.parse::<f32>().ok().filter(|value| value.is_finite())
}

fn parse_lut_entry(first: &str, rest: &[&str], line: usize) -> Result<[f32; 3], LutError> {
    let invalid = |reason: &str| LutError::InvalidLine {
        line,
        reason: reason.to_string(),
    };
    if rest.len() != 2 {
        return Err(invalid("expected three values"));
    }

    let mut entry = [0.0f32; 3];
    for (slot, token) in entry.iter_mut().zip([first, rest[0], rest[1]]) {
        let value = parse_lut_number(token).ok_or_else(|| invalid("value is not finite"))?;
        if value < -LUT_VALUE_TOLERANCE || value > 1.0 + LUT_VALUE_TOLERANCE {
            return Err(invalid("value is outside the 0..1 range"));
        }
        *slot = value.clamp(0.0, 1.0);
    }
    Ok(entry)
}

fn parse_lut_size(values: &[&str], max: usize, line: usize) -> Result<usize, LutError> {
    let parsed = match values {
        [value] => value.parse::<usize>().ok(),
        _ => None,
    };
    match parsed {
        Some(size) if (2..=max).contains(&size) => Ok(size),
        Some(size) => Err(LutError::UnsupportedSize(size.to_string())),
        None => Err(LutError::InvalidLine {
            line,
            reason: "expected one LUT size".to_string(),
        }),
    }
}

fn parse_lut_domain(values: &[&str], line: usize) -> Result<[f32; 3], LutError> {
    if values.len() != 3 {
        return Err(LutError::InvalidLine {
            line,
            reason: "expected three domain values".to_string(),
        });
    }

    let mut domain = [0.0f32; 3];
    for (slot, token) in domain.iter_mut().zip(values) {
        *slot = parse_lut_number(token).ok_or_else(|| LutError::InvalidLine {
            line,
            reason: "value is not finite".to_string(),
        })?;
    }
    Ok(domain)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct FileStamp {
    len: u64,
    modified_ns: Option<u128>,
}

struct CachedLut {
    stamp: Option<FileStamp>,
    lut: Option<Arc<CubeLut>>,
}

fn lut_cache() -> &'static Mutex<HashMap<String, CachedLut>> {
    static CACHE: OnceLock<Mutex<HashMap<String, CachedLut>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn memory_luts() -> &'static Mutex<HashMap<String, Arc<CubeLut>>> {
    static MEMORY: OnceLock<Mutex<HashMap<String, Arc<CubeLut>>>> = OnceLock::new();
    MEMORY.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Registers a LUT supplied in memory rather than on disk. The web build has no
/// filesystem for the exporter to read, so its host hands the bytes over here
/// under the same path the project stores.
pub fn register_cube_lut_bytes(path: &str, bytes: &[u8]) {
    let parsed = std::str::from_utf8(bytes)
        .ok()
        .and_then(|text| CubeLut::parse(text).ok())
        .map(Arc::new);
    if let Ok(mut luts) = memory_luts().lock() {
        match parsed {
            Some(lut) => {
                luts.insert(path.to_string(), lut);
            }
            None => {
                luts.remove(path);
            }
        }
    }
}

fn file_stamp(path: &str) -> Option<FileStamp> {
    let meta = fs::metadata(path).ok()?;
    let modified_ns = meta
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|since| since.as_nanos());
    Some(FileStamp {
        len: meta.len(),
        modified_ns,
    })
}

fn load_cube_lut(path: &str) -> Option<Arc<CubeLut>> {
    if let Ok(luts) = memory_luts().lock() {
        if let Some(lut) = luts.get(path) {
            return Some(lut.clone());
        }
    }

    let stamp = file_stamp(path);
    if let Ok(cache) = lut_cache().lock() {
        if let Some(cached) = cache.get(path) {
            if cached.stamp == stamp {
                return cached.lut.clone();
            }
        }
    }

    let lut = read_cube_lut(path, stamp);
    if let Ok(mut cache) = lut_cache().lock() {
        if cache.len() >= LUT_CACHE_CAPACITY && !cache.contains_key(path) {
            cache.clear();
        }
        cache.insert(
            path.to_string(),
            CachedLut {
                stamp,
                lut: lut.clone(),
            },
        );
    }
    lut
}

fn read_cube_lut(path: &str, stamp: Option<FileStamp>) -> Option<Arc<CubeLut>> {
    if stamp.is_none() {
        log::warn!("LUT file {path} is not readable, skipping the filter");
        return None;
    }
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) => {
            log::warn!("LUT file {path} could not be read: {error}");
            return None;
        }
    };
    match CubeLut::parse(&text) {
        Ok(lut) => Some(Arc::new(lut)),
        Err(error) => {
            log::warn!("LUT file {path} could not be parsed: {error}");
            None
        }
    }
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

fn lut_axis(value: f32, min: f32, max: f32, size: usize) -> f32 {
    let span = max - min;
    let normalized = if span > 0.0 {
        (value - min) / span
    } else {
        value
    };
    let last = (size - 1) as f32;
    (normalized * last).clamp(0.0, last)
}

fn sample_cube_lut_1d(lut: &CubeLut, size: usize, value: [f32; 3]) -> [f32; 3] {
    let mut out = [0.0f32; 3];
    for channel in 0..3 {
        let axis = lut_axis(
            value[channel],
            lut.domain_min[channel],
            lut.domain_max[channel],
            size,
        );
        let low = axis.floor() as usize;
        let high = (low + 1).min(size - 1);
        out[channel] = lerp(
            lut.entries[low][channel],
            lut.entries[high][channel],
            axis - low as f32,
        );
    }
    out
}

fn sample_cube_lut_3d(lut: &CubeLut, size: usize, value: [f32; 3]) -> [f32; 3] {
    let mut axis = [0.0f32; 3];
    for channel in 0..3 {
        axis[channel] = lut_axis(
            value[channel],
            lut.domain_min[channel],
            lut.domain_max[channel],
            size,
        );
    }

    let r0 = axis[0].floor() as usize;
    let g0 = axis[1].floor() as usize;
    let b0 = axis[2].floor() as usize;
    let r1 = (r0 + 1).min(size - 1);
    let g1 = (g0 + 1).min(size - 1);
    let b1 = (b0 + 1).min(size - 1);
    let tr = axis[0] - r0 as f32;
    let tg = axis[1] - g0 as f32;
    let tb = axis[2] - b0 as f32;

    let at = |r: usize, g: usize, b: usize| lut.entries[r + g * size + b * size * size];
    let c000 = at(r0, g0, b0);
    let c100 = at(r1, g0, b0);
    let c010 = at(r0, g1, b0);
    let c110 = at(r1, g1, b0);
    let c001 = at(r0, g0, b1);
    let c101 = at(r1, g0, b1);
    let c011 = at(r0, g1, b1);
    let c111 = at(r1, g1, b1);

    let mut out = [0.0f32; 3];
    for channel in 0..3 {
        let c00 = lerp(c000[channel], c100[channel], tr);
        let c10 = lerp(c010[channel], c110[channel], tr);
        let c01 = lerp(c001[channel], c101[channel], tr);
        let c11 = lerp(c011[channel], c111[channel], tr);
        out[channel] = lerp(lerp(c00, c10, tg), lerp(c01, c11, tg), tb);
    }
    out
}

fn apply_sampled_cube_lut<F>(buf: &mut [u8], strength: f32, sample: F)
where
    F: Fn([f32; 3]) -> [f32; 3],
{
    for px in buf.as_chunks_mut::<4>().0 {
        let original = [
            px[0] as f32 / 255.0,
            px[1] as f32 / 255.0,
            px[2] as f32 / 255.0,
        ];
        let mapped = sample(original);
        for channel in 0..3 {
            let value = original[channel] + (mapped[channel] - original[channel]) * strength;
            px[channel] = (value * 255.0).round().clamp(0.0, 255.0) as u8;
        }
    }
}

pub fn apply_cube_lut(buf: &mut [u8], lut: &CubeLut, strength: f32) {
    let strength = strength.clamp(0.0, 1.0);
    if strength <= 0.0 {
        return;
    }
    match lut.kind {
        LutKind::OneD(size) => {
            apply_sampled_cube_lut(buf, strength, |value| sample_cube_lut_1d(lut, size, value));
        }
        LutKind::ThreeD(size) => {
            apply_sampled_cube_lut(buf, strength, |value| sample_cube_lut_3d(lut, size, value));
        }
    }
}

pub fn apply_cube_lut_file(buf: &mut [u8], path: &str, strength: f32) {
    if path.is_empty() || strength <= 0.0 {
        return;
    }
    let Some(lut) = load_cube_lut(path) else {
        return;
    };
    apply_cube_lut(buf, &lut, strength);
}

#[cfg(test)]
mod lut_tests {
    use super::*;

    const IDENTITY_3D: &str = "LUT_3D_SIZE 2\n\
        0 0 0\n1 0 0\n0 1 0\n1 1 0\n\
        0 0 1\n1 0 1\n0 1 1\n1 1 1\n";

    const INVERT_3D: &str = "LUT_3D_SIZE 2\n\
        1 1 1\n0 1 1\n1 0 1\n0 0 1\n\
        1 1 0\n0 1 0\n1 0 0\n0 0 0\n";

    fn pixel(r: u8, g: u8, b: u8) -> Vec<u8> {
        vec![r, g, b, 255]
    }

    #[test]
    fn identity_lut_leaves_primaries_unchanged() {
        let lut = CubeLut::parse(IDENTITY_3D).expect("identity parses");
        for (r, g, b) in [
            (255u8, 0u8, 0u8),
            (0, 255, 0),
            (0, 0, 255),
            (255, 255, 255),
            (0, 0, 0),
            (255, 128, 7),
        ] {
            let mut buf = pixel(r, g, b);
            apply_cube_lut(&mut buf, &lut, 1.0);
            assert_eq!(buf, pixel(r, g, b), "identity changed {r},{g},{b}");
        }
    }

    #[test]
    fn strength_mixes_between_original_and_mapped() {
        let lut = CubeLut::parse(INVERT_3D).expect("invert parses");

        let mut full = pixel(255, 0, 0);
        apply_cube_lut(&mut full, &lut, 1.0);
        assert_eq!(full, pixel(0, 255, 255));

        let mut none = pixel(255, 0, 0);
        apply_cube_lut(&mut none, &lut, 0.0);
        assert_eq!(none, pixel(255, 0, 0));

        let mut half = pixel(255, 0, 0);
        apply_cube_lut(&mut half, &lut, 0.5);
        assert_eq!(half, pixel(128, 128, 128));
    }

    #[test]
    fn parses_noisy_but_valid_text() {
        let text = "# a comment\r\nTITLE \"sample\"\n\n  LUT_3D_SIZE\t2  \n\
            0 0 0 # inline\n1 0 0\n0 1 0\n1 1 0\n\
            0 0 1\n1 0 1\n0 1 1\n1 1 1\n\r\n";
        assert!(CubeLut::parse(text).is_ok());
    }

    #[test]
    fn parses_1d_and_applies_per_channel() {
        let text = "LUT_1D_SIZE 2\n0 0 0\n1 1 1\n";
        let lut = CubeLut::parse(text).expect("1D parses");
        let mut buf = pixel(10, 200, 90);
        apply_cube_lut(&mut buf, &lut, 1.0);
        assert_eq!(buf, pixel(10, 200, 90));
    }

    #[test]
    fn honours_domain_bounds() {
        let text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n\
            0 0 0\n1 0 0\n0 1 0\n1 1 0\n\
            0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
        let lut = CubeLut::parse(text).expect("domain parses");
        let mut buf = pixel(255, 255, 255);
        apply_cube_lut(&mut buf, &lut, 1.0);
        assert_eq!(buf, pixel(128, 128, 128));

        let mut lower = pixel(0, 0, 0);
        apply_cube_lut(&mut lower, &lut, 1.0);
        assert_eq!(lower, pixel(0, 0, 0));
    }

    #[test]
    fn rejects_malformed_input_without_panicking() {
        let cases = [
            "",
            "TITLE \"no size\"\n0 0 0\n",
            "LUT_1D_SIZE 2\nLUT_3D_SIZE 2\n0 0 0\n",
            "LUT_3D_SIZE 1\n0 0 0\n",
            "LUT_3D_SIZE 2\n0 0 0\n1 0\n",
            "LUT_3D_SIZE 2\n0 0 0\n1 0 nan\n",
            "LUT_3D_SIZE 2\n0 0 0\n1 0 2\n",
            "LUT_3D_SIZE 2\n0 0 0\n",
            "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n0 0 0\n",
            "LUT_3D_SIZE 2\nDOMAIN_MIN 1 0 0\nDOMAIN_MAX 0 1 1\n",
        ];
        for text in cases {
            assert!(
                CubeLut::parse(text).is_err(),
                "expected a clean error for {text:?}"
            );
        }
    }

    #[test]
    fn rejects_entries_outside_the_declared_count() {
        let short = CubeLut::parse("LUT_1D_SIZE 4\n0 0 0\n1 1 1\n");
        assert!(matches!(short, Err(LutError::EntryCount { .. })));

        let long = CubeLut::parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n0.5 0.5 0.5\n");
        assert!(matches!(long, Err(LutError::EntryCount { .. })));
    }

    #[test]
    fn registered_bytes_are_used_instead_of_disk() {
        let path = "miniter-lut-test-in-memory.cube";
        register_cube_lut_bytes(path, INVERT_3D.as_bytes());
        let mut buf = pixel(255, 0, 0);
        apply_cube_lut_file(&mut buf, path, 1.0);
        assert_eq!(buf, pixel(0, 255, 255));

        register_cube_lut_bytes(path, b"not a lut");
        let mut after = pixel(255, 0, 0);
        apply_cube_lut_file(&mut after, path, 1.0);
        assert_eq!(after, pixel(255, 0, 0));
    }
}
