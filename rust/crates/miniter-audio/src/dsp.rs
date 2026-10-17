use miniter_domain::filter::{AudioFilter, EqPreset, ReverbPreset};

#[derive(Debug, Clone)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

fn nyquist_clamped(freq: f32, sample_rate: f32) -> f32 {
    (freq / sample_rate).clamp(1.0e-5, 0.49) * std::f32::consts::TAU
}

impl Biquad {
    fn normalized(b0: f32, b1: f32, b2: f32, a0: f32, a1: f32, a2: f32) -> Self {
        let inv = if a0.abs() > f32::EPSILON {
            1.0 / a0
        } else {
            1.0
        };
        Self {
            b0: b0 * inv,
            b1: b1 * inv,
            b2: b2 * inv,
            a1: a1 * inv,
            a2: a2 * inv,
            z1: 0.0,
            z2: 0.0,
        }
    }

    fn peaking(sample_rate: f32, freq: f32, q: f32, gain_db: f32) -> Self {
        let a = 10f32.powf(gain_db / 40.0);
        let w0 = nyquist_clamped(freq, sample_rate);
        let cos_w0 = w0.cos();
        let alpha = w0.sin() / (2.0 * q.max(0.1));
        Self::normalized(
            1.0 + alpha * a,
            -2.0 * cos_w0,
            1.0 - alpha * a,
            1.0 + alpha / a,
            -2.0 * cos_w0,
            1.0 - alpha / a,
        )
    }

    fn low_shelf(sample_rate: f32, freq: f32, gain_db: f32) -> Self {
        let a = 10f32.powf(gain_db / 40.0);
        let w0 = nyquist_clamped(freq, sample_rate);
        let cos_w0 = w0.cos();
        let alpha = w0.sin() * 0.5 * std::f32::consts::SQRT_2;
        let k = 2.0 * a.sqrt() * alpha;
        Self::normalized(
            a * ((a + 1.0) - (a - 1.0) * cos_w0 + k),
            2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w0),
            a * ((a + 1.0) - (a - 1.0) * cos_w0 - k),
            (a + 1.0) + (a - 1.0) * cos_w0 + k,
            -2.0 * ((a - 1.0) + (a + 1.0) * cos_w0),
            (a + 1.0) + (a - 1.0) * cos_w0 - k,
        )
    }

    fn high_shelf(sample_rate: f32, freq: f32, gain_db: f32) -> Self {
        let a = 10f32.powf(gain_db / 40.0);
        let w0 = nyquist_clamped(freq, sample_rate);
        let cos_w0 = w0.cos();
        let alpha = w0.sin() * 0.5 * std::f32::consts::SQRT_2;
        let k = 2.0 * a.sqrt() * alpha;
        Self::normalized(
            a * ((a + 1.0) + (a - 1.0) * cos_w0 + k),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w0),
            a * ((a + 1.0) + (a - 1.0) * cos_w0 - k),
            (a + 1.0) - (a - 1.0) * cos_w0 + k,
            2.0 * ((a - 1.0) - (a + 1.0) * cos_w0),
            (a + 1.0) - (a - 1.0) * cos_w0 - k,
        )
    }

    fn low_pass(sample_rate: f32, freq: f32, q: f32) -> Self {
        let w0 = nyquist_clamped(freq, sample_rate);
        let cos_w0 = w0.cos();
        let alpha = w0.sin() / (2.0 * q.max(0.1));
        let a = 1.0 - cos_w0;
        Self::normalized(a * 0.5, a, a * 0.5, 1.0 + alpha, -2.0 * cos_w0, 1.0 - alpha)
    }

    fn high_pass(sample_rate: f32, freq: f32, q: f32) -> Self {
        let w0 = nyquist_clamped(freq, sample_rate);
        let cos_w0 = w0.cos();
        let alpha = w0.sin() / (2.0 * q.max(0.1));
        let a = 1.0 + cos_w0;
        Self::normalized(
            a * 0.5,
            -a,
            a * 0.5,
            1.0 + alpha,
            -2.0 * cos_w0,
            1.0 - alpha,
        )
    }

    fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }
}

fn eq_bands(preset: EqPreset, sample_rate: f32) -> Vec<Biquad> {
    match preset {
        EqPreset::Natural => Vec::new(),
        EqPreset::AmRadio => vec![
            Biquad::high_pass(sample_rate, 400.0, 0.707),
            Biquad::low_pass(sample_rate, 3000.0, 0.707),
            Biquad::peaking(sample_rate, 1800.0, 1.0, 4.0),
        ],
        EqPreset::BassBooster => vec![
            Biquad::low_shelf(sample_rate, 120.0, 6.0),
            Biquad::peaking(sample_rate, 60.0, 0.8, 3.0),
        ],
        EqPreset::BassReducer => vec![Biquad::low_shelf(sample_rate, 120.0, -6.0)],
        EqPreset::BgmMode => vec![
            Biquad::peaking(sample_rate, 2500.0, 0.9, -3.0),
            Biquad::peaking(sample_rate, 300.0, 0.9, -1.5),
        ],
        EqPreset::HipHop => vec![
            Biquad::low_shelf(sample_rate, 90.0, 4.0),
            Biquad::peaking(sample_rate, 2500.0, 1.0, -2.0),
            Biquad::high_shelf(sample_rate, 8000.0, 2.0),
        ],
        EqPreset::Jazz => vec![
            Biquad::low_shelf(sample_rate, 120.0, 2.5),
            Biquad::peaking(sample_rate, 400.0, 1.0, -1.5),
            Biquad::high_shelf(sample_rate, 6000.0, 2.0),
        ],
        EqPreset::Pop => vec![
            Biquad::low_shelf(sample_rate, 100.0, 2.0),
            Biquad::peaking(sample_rate, 1000.0, 1.0, -1.0),
            Biquad::high_shelf(sample_rate, 10000.0, 3.0),
        ],
        EqPreset::RnB => vec![
            Biquad::low_shelf(sample_rate, 80.0, 4.0),
            Biquad::peaking(sample_rate, 3000.0, 1.0, -2.0),
            Biquad::high_shelf(sample_rate, 8000.0, 1.5),
        ],
        EqPreset::Rock => vec![
            Biquad::low_shelf(sample_rate, 100.0, 3.0),
            Biquad::peaking(sample_rate, 800.0, 1.0, -2.0),
            Biquad::peaking(sample_rate, 3000.0, 1.0, 3.0),
            Biquad::high_shelf(sample_rate, 8000.0, 2.0),
        ],
        EqPreset::TrebleBooster => vec![Biquad::high_shelf(sample_rate, 6000.0, 6.0)],
        EqPreset::TrebleReducer => vec![Biquad::high_shelf(sample_rate, 6000.0, -6.0)],
        EqPreset::Voice => vec![
            Biquad::high_pass(sample_rate, 120.0, 0.707),
            Biquad::peaking(sample_rate, 3000.0, 1.0, 3.0),
            Biquad::low_pass(sample_rate, 8000.0, 0.707),
        ],
        _ => Vec::new(),
    }
}

fn apply_eq(samples: &mut [f32], channels: usize, sample_rate: u32, preset: EqPreset) {
    let template = eq_bands(preset, sample_rate as f32);
    if template.is_empty() || channels == 0 {
        return;
    }

    let mut chains: Vec<Vec<Biquad>> = (0..channels).map(|_| template.to_vec()).collect();

    for frame in samples.chunks_mut(channels) {
        for (ch, sample) in frame.iter_mut().enumerate() {
            let mut value = *sample;
            for filter in chains[ch].iter_mut() {
                value = filter.process(value);
            }
            *sample = value;
        }
    }
}

const COMB_TUNING: [usize; 8] = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
const ALLPASS_TUNING: [usize; 4] = [556, 441, 341, 225];
const STEREO_SPREAD: usize = 23;
const FIXED_GAIN: f32 = 0.015;
const SCALE_ROOM: f32 = 0.28;
const OFFSET_ROOM: f32 = 0.7;
const SCALE_DAMP: f32 = 0.4;
const ALLPASS_FEEDBACK: f32 = 0.5;

struct Comb {
    buffer: Vec<f32>,
    index: usize,
    store: f32,
    feedback: f32,
    damp1: f32,
    damp2: f32,
}

impl Comb {
    fn new(len: usize) -> Self {
        Self {
            buffer: vec![0.0; len.max(1)],
            index: 0,
            store: 0.0,
            feedback: 0.5,
            damp1: 0.5,
            damp2: 0.5,
        }
    }

    fn set_damp(&mut self, value: f32) {
        let d = value.clamp(0.0, 1.0);
        self.damp1 = d;
        self.damp2 = 1.0 - d;
    }

    fn process(&mut self, input: f32) -> f32 {
        let output = self.buffer[self.index];
        self.store = output * self.damp2 + self.store * self.damp1;
        self.buffer[self.index] = input + self.store * self.feedback;
        self.index += 1;
        if self.index >= self.buffer.len() {
            self.index = 0;
        }
        output
    }
}

struct Allpass {
    buffer: Vec<f32>,
    index: usize,
    feedback: f32,
}

impl Allpass {
    fn new(len: usize) -> Self {
        Self {
            buffer: vec![0.0; len.max(1)],
            index: 0,
            feedback: ALLPASS_FEEDBACK,
        }
    }

    fn process(&mut self, input: f32) -> f32 {
        let buffered = self.buffer[self.index];
        let output = -input + buffered;
        self.buffer[self.index] = input + buffered * self.feedback;
        self.index += 1;
        if self.index >= self.buffer.len() {
            self.index = 0;
        }
        output
    }
}

fn reverb_params(preset: ReverbPreset) -> (f32, f32, f32) {
    match preset {
        ReverbPreset::Bathroom => (0.30, 0.85, 0.22),
        ReverbPreset::Cathedral => (0.92, 0.25, 0.45),
        ReverbPreset::Cave => (0.88, 0.55, 0.40),
        ReverbPreset::Arena => (0.85, 0.35, 0.36),
        ReverbPreset::Church => (0.80, 0.30, 0.34),
        ReverbPreset::ConcertHall => (0.88, 0.28, 0.40),
        ReverbPreset::EchoRoom => (0.86, 0.15, 0.36),
        ReverbPreset::HallDelay => (0.90, 0.22, 0.42),
        ReverbPreset::SpringReverb => (0.62, 0.12, 0.30),
        ReverbPreset::Studio => (0.45, 0.60, 0.20),
        _ => (0.0, 0.0, 0.0),
    }
}

fn apply_reverb(samples: &mut [f32], channels: usize, sample_rate: u32, preset: ReverbPreset) {
    if channels == 0 || samples.is_empty() {
        return;
    }
    let (room, damp, wet) = reverb_params(preset);
    if wet <= 0.0 {
        return;
    }

    let scale = sample_rate as f32 / 44100.0;
    let feedback = room * SCALE_ROOM + OFFSET_ROOM;
    let damp1 = damp * SCALE_DAMP;

    let mut combs: Vec<Vec<Comb>> = Vec::with_capacity(channels);
    let mut allpasses: Vec<Vec<Allpass>> = Vec::with_capacity(channels);
    for ch in 0..channels {
        let spread = if ch % 2 == 1 { STEREO_SPREAD } else { 0 };
        let mut channel_combs = Vec::with_capacity(COMB_TUNING.len());
        for &tuning in COMB_TUNING.iter() {
            let len = ((tuning + spread) as f32 * scale).round().max(1.0) as usize;
            let mut comb = Comb::new(len);
            comb.feedback = feedback;
            comb.set_damp(damp1);
            channel_combs.push(comb);
        }
        let channel_allpasses = ALLPASS_TUNING
            .iter()
            .map(|&tuning| {
                let len = ((tuning + spread) as f32 * scale).round().max(1.0) as usize;
                Allpass::new(len)
            })
            .collect();
        combs.push(channel_combs);
        allpasses.push(channel_allpasses);
    }

    for frame in samples.chunks_mut(channels) {
        let taps = frame.len().min(2);
        let mut mono = 0.0f32;
        for sample in frame.iter().take(taps) {
            mono += *sample;
        }
        if taps > 0 {
            mono /= taps as f32;
        }
        let input = mono * FIXED_GAIN;

        for ch in 0..frame.len() {
            let mut wet_sum = 0.0f32;
            for comb in combs[ch].iter_mut() {
                wet_sum += comb.process(input);
            }
            let mut tail = wet_sum;
            for allpass in allpasses[ch].iter_mut() {
                tail = allpass.process(tail);
            }
            frame[ch] += tail * wet;
        }
    }
}

pub fn has_clip_dsp(filters: &[AudioFilter]) -> bool {
    filters.iter().any(|filter| {
        matches!(
            filter,
            AudioFilter::Equalizer { .. } | AudioFilter::Reverb { .. }
        )
    })
}

pub fn apply_clip_dsp(
    samples: &mut [f32],
    channels: usize,
    sample_rate: u32,
    filters: &[AudioFilter],
) {
    for filter in filters {
        match filter {
            AudioFilter::Equalizer { preset } => apply_eq(samples, channels, sample_rate, *preset),
            AudioFilter::Reverb { preset } => apply_reverb(samples, channels, sample_rate, *preset),
            _ => {}
        }
    }
}
