use libblur::{AnisotropicRadius, BlurImageMut, FastBlurChannels, ThreadingPolicy};
use palette::{LinSrgba, Srgba};

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

/// Unsharp mask sharpen
pub fn sharpen_rgba(buf: &mut [u8], width: usize, height: usize, amount: f32) {
    if amount <= 0.0 {
        return;
    }
    let mut blurred = buf.to_vec();
    blur_rgba(&mut blurred, width, height, 1.0);
    for (i, px) in buf.chunks_exact_mut(4).enumerate() {
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
    for px in buf.chunks_exact_mut(4) {
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

pub fn apply_grayscale(buf: &mut [u8]) {
    for px in buf.chunks_exact_mut(4) {
        let gray = (0.299 * px[0] as f32 + 0.587 * px[1] as f32 + 0.114 * px[2] as f32)
            .round()
            .clamp(0.0, 255.0) as u8;
        px[0] = gray;
        px[1] = gray;
        px[2] = gray;
    }
}

pub fn apply_sepia(buf: &mut [u8]) {
    for px in buf.chunks_exact_mut(4) {
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
    for px in buf.chunks_exact_mut(4) {
        px[3] = ((px[3] as f32) * a).round().clamp(0.0, 255.0) as u8;
    }
}

pub fn premultiply_alpha(buf: &mut [u8]) {
    for px in buf.chunks_exact_mut(4) {
        let a = px[3] as f32 / 255.0;
        px[0] = ((px[0] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[1] = ((px[1] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[2] = ((px[2] as f32) * a).round().clamp(0.0, 255.0) as u8;
        px[3] = 255;
    }
}
