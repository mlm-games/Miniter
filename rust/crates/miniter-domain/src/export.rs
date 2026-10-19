use crate::time::{TimeRange, Timestamp};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct ExportRange {
    #[serde(default)]
    pub start: Option<Timestamp>,
    #[serde(default)]
    pub end: Option<Timestamp>,
}

impl ExportRange {
    pub fn resolve(self, content_end: Timestamp) -> TimeRange {
        let content_end = content_end.clamp_non_negative();
        let start = self
            .start
            .unwrap_or(Timestamp::ZERO)
            .clamp_non_negative()
            .min(content_end);
        let end = self
            .end
            .unwrap_or(content_end)
            .clamp_non_negative()
            .min(content_end)
            .max(start);
        TimeRange::new(start, end)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[non_exhaustive]
pub enum ExportFormat {
    Mp4,
    Mov,
    Av1Ivf,
    #[default]
    Av1Mp4,
    Av1Mkv,
    Av1WebM,
    H264Mkv,
    Opus,
    Flac,
    Gif,
}

impl ExportFormat {
    pub fn extension(self) -> &'static str {
        match self {
            Self::Mp4 => "mp4",
            Self::Av1Ivf => "ivf",
            Self::Av1Mp4 => "mp4",
            Self::Av1Mkv => "mkv",
            Self::Av1WebM => "webm",
            Self::H264Mkv => "mkv",
            Self::Mov => "mov",
            Self::Opus => "opus",
            Self::Flac => "flac",
            Self::Gif => "gif",
        }
    }

    pub fn mime(self) -> &'static str {
        match self {
            Self::Mp4 => "video/mp4",
            Self::Av1Ivf => "video/ivf",
            Self::Av1Mp4 => "video/mp4",
            Self::Av1Mkv => "video/x-matroska",
            Self::Av1WebM => "video/webm",
            Self::H264Mkv => "video/x-matroska",
            Self::Mov => "video/quicktime",
            Self::Opus => "audio/ogg",
            Self::Flac => "audio/flac",
            Self::Gif => "image/gif",
        }
    }

    /// Whether this format muxes through Matroska (`build_mkv`).
    pub fn is_matroska_family(self) -> bool {
        matches!(self, Self::Av1Mkv | Self::Av1WebM | Self::H264Mkv)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum ExportResolution {
    Source,
    Sd480,
    Hd720,
    Hd1080,
    Uhd4k,
    Custom { width: u32, height: u32 },
}

impl ExportResolution {
    pub fn dimensions(self) -> (u32, u32) {
        match self {
            Self::Source => (0, 0),
            Self::Sd480 => (854, 480),
            Self::Hd720 => (1280, 720),
            Self::Hd1080 => (1920, 1080),
            Self::Uhd4k => (3840, 2160),
            Self::Custom { width, height } => (width, height),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum SubtitleMode {
    #[default]
    Hard,
    Soft,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[non_exhaustive]
pub enum LoudnessNormalization {
    #[default]
    Off,
    Minus14Lufs,
    Minus16Lufs,
    Minus23Lufs,
}

impl LoudnessNormalization {
    pub fn target_lufs(self) -> Option<f32> {
        match self {
            Self::Off => None,
            Self::Minus14Lufs => Some(-14.0),
            Self::Minus16Lufs => Some(-16.0),
            Self::Minus23Lufs => Some(-23.0),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportProfile {
    #[serde(default)]
    pub format: ExportFormat,
    pub resolution: ExportResolution,
    pub fps: f64,
    pub video_bitrate_kbps: u32,
    pub audio_bitrate_kbps: u32,
    pub audio_sample_rate: u32,
    pub output_path: String,
    #[serde(default)]
    pub subtitle_mode: SubtitleMode,
    #[serde(default = "default_hardware_acceleration")]
    pub hardware_acceleration: bool,
    /// Encode effort 0 (slowest/best) .. 10 (fastest), honored by every
    /// software backend: rav1e speed preset for AV1, rusty_h264 Preset
    /// bucket for H.264. HW encoders ignore it (platform-controlled).
    #[serde(default = "default_encode_effort")]
    pub encode_effort: u8,
    #[serde(default)]
    pub loudness_normalization: LoudnessNormalization,
    #[serde(default = "default_limiter_ceiling_db")]
    pub limiter_ceiling_db: f32,
}

fn default_hardware_acceleration() -> bool {
    true
}

fn default_encode_effort() -> u8 {
    6
}

fn default_limiter_ceiling_db() -> f32 {
    -1.0
}

impl Default for ExportProfile {
    fn default() -> Self {
        Self {
            format: ExportFormat::Av1Mp4,
            resolution: ExportResolution::Source,
            fps: 30.0,
            video_bitrate_kbps: 8_000,
            audio_bitrate_kbps: 192,
            audio_sample_rate: 48_000,
            output_path: String::new(),
            subtitle_mode: SubtitleMode::Soft,
            hardware_acceleration: true,
            encode_effort: default_encode_effort(),
            loudness_normalization: LoudnessNormalization::Off,
            limiter_ceiling_db: default_limiter_ceiling_db(),
        }
    }
}
